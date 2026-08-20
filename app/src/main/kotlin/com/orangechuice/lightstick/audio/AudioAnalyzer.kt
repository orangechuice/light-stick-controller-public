package com.orangechuice.lightstick.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * What one audio frame says about the music, in terms a pattern can use.
 *
 * All levels are adaptively normalised to roughly 0..1, so the same numbers mean
 * the same thing in a quiet room and at concert volume.
 */
data class AudioAnalysis(
    val bass: Float = 0f,
    val mid: Float = 0f,
    val treble: Float = 0f,
    /** Overall loudness envelope, same normalisation as the bands. */
    val level: Float = 0f,
    /** True on exactly the frame a kick was detected. */
    val beat: Boolean = false,
    /** Nothing audible — patterns should rest rather than chase noise. */
    val silent: Boolean = true,
    /**
     * The input is railing against full scale. Everything downstream is still
     * computed, but it is built on flattened peaks: the envelope saturates, the
     * beat detector loses the transients it discriminates on, and the light sits
     * at constant full brightness. Loud rooms fail this way, and nothing after
     * capture can undo it.
     */
    val clipping: Boolean = false,
) {
    companion object {
        val SILENT = AudioAnalysis()
    }
}

/**
 * Mic frames in, [AudioAnalysis] out. Pure and device-free: feed it samples from
 * anywhere and it behaves identically, which is what makes the tuning in
 * `plan.md` 3.5 testable without standing in front of a speaker.
 *
 * ```
 * hop → sliding window → Hann → FFT → band energies → envelope → gain → beat
 * ```
 *
 * Four choices carry most of the perceived quality:
 *
 *  - **Envelope followers** (fast attack, slow decay). Raw per-frame energy is
 *    visual noise; the envelope is what the eye reads as a pulse.
 *  - **Adaptive gain** against a rolling 90th percentile of the last ~5 s, so no
 *    sensitivity slider is needed to cross from a quiet room to a loud one.
 *    Optionally against the rolling 10th percentile too — see [autoRange].
 *  - **Beat detection on the raw bass amplitude**, not the enveloped one — the
 *    envelope deliberately blunts exactly the transient a kick detector needs.
 *  - **Overlapping windows** ([hopSize] < [windowSize]), which is what decides
 *    how quickly any of the above can react. See [hopSize].
 */
class AudioAnalyzer(
    private val sampleRate: Int = 44_100,
    private val windowSize: Int = 1024,
    /**
     * Samples between analyses. Equal to [windowSize] means no overlap: each
     * sample is looked at exactly once, and nothing can be reported until the
     * window it fell in has closed — 23 ms of pure latency at 1024/44.1 kHz.
     *
     * Shrinking [windowSize] instead would be the obvious fix and is the wrong
     * one: at 512 the bin width doubles to 86 Hz and `binRange(20f, 150f)`
     * collapses from three bins to one, which is most of the kick drum. Hopping
     * by a fraction of the window keeps the full frequency resolution and just
     * looks more often. The cost is one FFT per hop instead of per window,
     * which at 1024 points and a few hundred hops a second is not a real cost.
     *
     * Note that this does not buy the whole difference back. The Hann window
     * tapers to zero at both ends, so a transient that has only just entered
     * the window is weighted near zero — a kick lands about two hops before it
     * is fully visible, not one.
     */
    private val hopSize: Int = windowSize,
    autoRange: Boolean = true,
) {
    /**
     * Whether normalisation adapts its *floor* as well as its ceiling.
     *
     * Off, a band is divided by its recent 90th percentile: the loud end of the
     * range lands at 1, and the quiet end lands wherever it happens to. Dense,
     * heavily-compressed music never gets quiet, so in practice the whole show
     * plays out between 0.6 and 1 and the light looks like it is barely moving.
     *
     * On, the recent 10th percentile is subtracted first, so the range that is
     * actually being used is stretched across the full 0..1. This is the only
     * thing here that adds contrast rather than just scaling it — a downstream
     * multiplier can brighten that 0.6..1 window but cannot widen it.
     *
     * Volatile because the UI toggles it while capture is running, on a
     * different thread from [process]. Only the mapping changes; both references
     * are tracked either way, so flipping it takes effect on the next hop with
     * no re-learning pause.
     */
    @Volatile
    var autoRange: Boolean = autoRange

    init {
        require(hopSize in 1..windowSize) { "hop $hopSize must fit in window $windowSize" }
        require(windowSize % hopSize == 0) {
            "window $windowSize must be a whole number of hops of $hopSize"
        }
    }

    private val fft = Fft(windowSize)

    /** Hann window: without it, spectral leakage smears bass across every band. */
    private val window = FloatArray(windowSize) {
        (0.5 - 0.5 * cos(2.0 * PI * it / (windowSize - 1))).toFloat()
    }

    private val re = FloatArray(windowSize)
    private val im = FloatArray(windowSize)

    /**
     * The most recent [windowSize] samples, as a circular buffer. [ringNext] is
     * both the next slot to write and — once wrapped — the oldest sample, which
     * is what makes reading out in chronological order a single walk.
     */
    private val ring = FloatArray(windowSize)
    private var ringNext = 0

    private val hopMs = hopSize * 1000f / sampleRate
    private val windowMs = windowSize * 1000f / sampleRate

    /** Bins → amplitude in roughly full-scale units, so thresholds are absolute. */
    private val magnitudeScale = 2f / windowSize

    // Bin 0 is DC and is always excluded — a mic's DC offset is not bass.
    //
    // The bands do not tile: there is a deliberate guard gap above bass and above
    // mid. At 43 Hz per bin a 60 Hz kick sits at bin 1.4, and with bands butted
    // together the mid band starts only 2.6 bins away — close enough that window
    // sidelobe leakage from the kick alone drove the mid band to full scale, so a
    // bass-only tone lit the green channel as brightly as the red one. Leakage
    // falls off steeply with distance, so moving the edge out two bins cuts it by
    // roughly five times. The cost is 150–250 Hz and 2–2.5 kHz going unwatched,
    // which for three colour channels is a much better trade than three channels
    // that all say the same thing.
    private val bassBins = binRange(20f, 150f)
    private val midBins = binRange(250f, 2_000f)
    private val trebleBins = binRange(2_500f, 8_000f)

    // Time constants, not frame counts, so overlap changes how often the
    // analyser looks without changing how anything feels.
    private val bassEnvelope = Envelope(ATTACK_MS, BASS_DECAY_MS, hopMs)
    private val midEnvelope = Envelope(ATTACK_MS, DECAY_MS, hopMs)
    private val trebleEnvelope = Envelope(ATTACK_MS, DECAY_MS, hopMs)
    private val levelEnvelope = Envelope(ATTACK_MS, DECAY_MS, hopMs)

    /**
     * Hops between adaptive-gain updates — one window's worth, whatever the
     * overlap. The gain reference is a percentile of the last five seconds, so
     * it gains nothing from being recomputed on every overlapping hop, and
     * recomputing it there would multiply both the history length and the sort
     * that maintains it by the overlap factor. Sampling once per window keeps
     * both exactly where they were before overlap existed.
     */
    private val gainStride = windowSize / hopSize
    private var hopsSinceGainUpdate = 0

    private val normFrames = (NORM_WINDOW_MS / windowMs).toInt().coerceAtLeast(8)
    private val bassGain = RollingPercentile(normFrames)
    private val midGain = RollingPercentile(normFrames)
    private val trebleGain = RollingPercentile(normFrames)
    private val levelGain = RollingPercentile(normFrames)

    // Per hop, unlike the gain above: reacting sooner to a kick is the entire
    // point of overlapping, and the running sum makes this O(1) either way.
    private val beats = BeatDetector(
        historyFrames = (BEAT_WINDOW_MS / hopMs).toInt().coerceAtLeast(8),
    )

    /**
     * @param hop [hopSize] mono 16-bit samples, contiguous with the previous
     *   call. Analysis runs over these plus the preceding [windowSize] − [hopSize]
     *   samples, which this class retains; the caller only ever supplies new audio.
     * @param timeMs a monotonic-enough clock; only differences matter, and only
     *   for the beat refractory period.
     */
    fun process(hop: ShortArray, timeMs: Long): AudioAnalysis {
        require(hop.size == hopSize) { "expected $hopSize samples, got ${hop.size}" }

        for (sample in hop) {
            ring[ringNext] = sample.toInt() / 32_768f
            ringNext++
            if (ringNext == windowSize) ringNext = 0
        }

        var sumSquares = 0.0
        var railed = 0
        var read = ringNext
        for (i in 0 until windowSize) {
            val sample = ring[read]
            read++
            if (read == windowSize) read = 0

            if (sample >= CLIP_LEVEL || sample <= -CLIP_LEVEL) railed++
            sumSquares += (sample * sample).toDouble()
            re[i] = sample * window[i]
            im[i] = 0f
        }
        val rms = sqrt(sumSquares / windowSize).toFloat()

        // A stray sample at full scale is normal; a run of them is a flat top.
        val clipping = railed > windowSize * CLIP_FRACTION

        fft.transform(re, im)

        val bassRaw = amplitude(bassBins)
        val midRaw = amplitude(midBins)
        val trebleRaw = amplitude(trebleBins)

        val broadband = levelEnvelope.update(rms)

        // Gated on the envelope rather than this frame's rms. Instantaneous rms
        // dips into the floor in the gaps between notes, which would strobe the
        // whole mode off and back on several times a bar.
        val silent = broadband < SILENCE_RMS

        // Envelopes track even through silence so they decay rather than holding
        // a stale peak, but nothing below feeds the adaptive gain: five seconds
        // of room tone in the percentile history is exactly what teaches it to
        // treat hiss as a reference level.
        val bassEnv = bassEnvelope.update(bassRaw)
        val midEnv = midEnvelope.update(midRaw)
        val trebleEnv = trebleEnvelope.update(trebleRaw)

        // Adaptive gain divides a band by its own recent peak, so in a quiet room
        // it divides noise by noise and reports a full-scale light show. The
        // silence gate is the only thing standing between "nothing to hear" and
        // meters pinned at 100%, so it returns nothing rather than falling
        // through to a normalisation that has no signal to work with.
        if (silent) return AudioAnalysis.SILENT

        // What it takes for a band to count as present rather than as spill from
        // a louder neighbour. Mostly relative, because leakage scales with the
        // signal while an absolute threshold does not: the same fixed number
        // cannot both admit a phone's 0.0005 bass and reject a full-scale tone's
        // leakage into the next band along. [ABSOLUTE_FLOOR] only covers the
        // case where there is no broadband signal to be a fraction of.
        val bandFloor = maxOf(ABSOLUTE_FLOOR, RELATIVE_FLOOR * broadband)

        // Only every [gainStride]th hop contributes to the reference; every hop
        // is measured against it.
        if (hopsSinceGainUpdate == 0) {
            bassGain.observe(bassEnv)
            midGain.observe(midEnv)
            trebleGain.observe(trebleEnv)
            levelGain.observe(broadband)
        }
        hopsSinceGainUpdate = (hopsSinceGainUpdate + 1) % gainStride

        // Read once: a flip between two of these lines would scale one band
        // against a floor its neighbours never saw, which on Spectrum is a
        // visible colour lurch for exactly one frame.
        val ranged = autoRange

        return AudioAnalysis(
            bass = bassGain.scale(bassEnv, bandFloor, ranged),
            mid = midGain.scale(midEnv, bandFloor, ranged),
            treble = trebleGain.scale(trebleEnv, bandFloor, ranged),
            level = levelGain.scale(broadband, ABSOLUTE_FLOOR, ranged),
            // Raw, not enveloped: the envelope deliberately blunts the transient.
            beat = beats.update(bassRaw, timeMs, bandFloor),
            silent = false,
            clipping = clipping,
        )
    }

    /** Total amplitude across a band: `sqrt(Σ power)`, so wide bands aren't diluted. */
    private fun amplitude(bins: IntRange): Float {
        var power = 0.0
        for (k in bins) {
            val real = re[k] * magnitudeScale
            val imag = im[k] * magnitudeScale
            power += (real * real + imag * imag).toDouble()
        }
        return sqrt(power).toFloat()
    }

    private fun binRange(lowHz: Float, highHz: Float): IntRange {
        val nyquistBin = windowSize / 2
        val from = ceil(lowHz * windowSize / sampleRate).toInt().coerceIn(1, nyquistBin)
        val to = ceil(highHz * windowSize / sampleRate).toInt().coerceIn(1, nyquistBin)
        return from until maxOf(to, from + 1)
    }

    private companion object {
        const val ATTACK_MS = 10f
        const val DECAY_MS = 300f

        /** Shorter than the others: a kick that lingers reads as mush, not a pulse. */
        const val BASS_DECAY_MS = 220f

        const val NORM_WINDOW_MS = 5_000f
        const val BEAT_WINDOW_MS = 1_000f

        /**
         * Counts as railed, as a fraction of full scale. Just below the rail
         * rather than at it, because a limiter in the capture path tops out a
         * hair under it — 32,500 of a 16-bit sample's 32,768.
         */
        const val CLIP_LEVEL = 32_500f / 32_768f

        /** Fraction of a window that must be railed before it reads as clipping. */
        const val CLIP_FRACTION = 0.002f

        /**
         * A band must reach this fraction of the broadband envelope to count as
         * real. Scale-invariant, so it holds at any mic gain.
         *
         * Kept low deliberately. Bass off a phone mic measured as little as 1.25%
         * of broadband against a treble-heavy mix, so a floor set anywhere near
         * "a few percent" deletes the kick drum — which is most of the point. The
         * guard gaps between bands, not this number, are what handle leakage.
         */
        const val RELATIVE_FLOOR = 0.01f

        /** Backstop for when there is no broadband signal to take a fraction of. */
        const val ABSOLUTE_FLOOR = 0.0002f

        /**
         * Below this the room is quiet; chasing it would just amplify hiss.
         *
         * Measured end to end on a razr 2025 on the `CAMCORDER` source: a quiet
         * room with an air conditioner running reads 0.0001–0.0005, and music
         * from computer speakers reads 0.020–0.178. The two are a hundred times
         * apart, so this sits comfortably in the gap rather than being balanced
         * on a knife edge — roughly 3× above the loudest silence and 13× below
         * the quietest music.
         *
         * An earlier attempt learned this threshold from the room instead, on
         * the strength of a "quiet room" measurement of 0.006 that turned out to
         * have a passing truck in it. With the real numbers there is nothing for
         * an adaptive gate to buy: it only added flicker, because a floor that
         * latches onto the single quietest frame sits below the room's own
         * variation.
         */
        const val SILENCE_RMS = 0.0015f
    }
}

/**
 * One-pole follower with separate attack and decay time constants.
 *
 * Coefficients are derived from the update period, so changing the hop changes
 * how often the follower moves but not how fast it rises or falls in wall time.
 */
internal class Envelope(attackMs: Float, decayMs: Float, updateMs: Float) {
    private val attack = coefficient(attackMs, updateMs)
    private val decay = coefficient(decayMs, updateMs)
    private var value = 0f

    fun update(input: Float): Float {
        val coefficient = if (input > value) attack else decay
        value += (input - value) * coefficient
        return value
    }

    private companion object {
        fun coefficient(tauMs: Float, updateMs: Float): Float =
            if (tauMs <= 0f) 1f else (1.0 - exp(-updateMs / tauMs.toDouble())).toFloat()
    }
}

/**
 * Adaptive gain: rescale against rolling percentiles of recent values.
 *
 * Percentile rather than max because one door slam should not dim the next five
 * seconds of the show. The floor argument to [scale] is what stops a silent room
 * from being amplified to full brightness — without it the normalisation happily
 * turns hiss into a light show.
 *
 * The floor is supplied per call rather than fixed here, because the right value
 * depends on how hot the mic runs — a razr 2025 on a voice-tuned source delivers
 * band amplitudes around 0.0005 (bass) to 0.03 (treble) with music playing,
 * while a synthesised full-scale tone is twenty times that. An earlier fixed
 * 0.012 sat *above* the bass band entirely, which pinned bass near zero no
 * matter how loud the room was: adaptive gain cannot adapt through a floor it
 * never clears. See [AudioAnalyzer.RELATIVE_FLOOR].
 *
 * Both the [HIGH] and [LOW] references are maintained unconditionally, even
 * though [scale] may be asked to use only the high one. They come out of a
 * single sort of a history that has to be sorted anyway, so the second one is
 * free, and tracking it always is what lets [AudioAnalyzer.autoRange] be flipped
 * mid-song without a five-second gap while the floor learns itself.
 *
 * Building the reference and using it are separate calls because they run at
 * different rates once windows overlap: every hop is scaled, but only one hop
 * per window is worth remembering. See [AudioAnalyzer.gainStride].
 */
internal class RollingPercentile(
    private val capacity: Int,
) {
    private val ring = FloatArray(capacity)
    private val scratch = FloatArray(capacity)
    private var count = 0
    private var next = 0
    private var high = 0f
    private var low = 0f

    /** Fold one measurement into the rolling references. */
    fun observe(value: Float) {
        ring[next] = value
        next = (next + 1) % capacity
        if (count < capacity) count++

        System.arraycopy(ring, 0, scratch, 0, count)
        java.util.Arrays.sort(scratch, 0, count)
        high = scratch[percentileIndex(HIGH)]
        low = scratch[percentileIndex(LOW)]
    }

    /**
     * Map a value onto 0..1 against the references [observe] has built.
     *
     * With [expandRange] the recent [LOW] is subtracted before dividing, so the
     * span the signal actually occupies fills the output instead of only its top
     * end. [floor] does double duty: it is the smallest usable denominator
     * either way, which is also what keeps a steady tone — where high and low
     * converge — from being expanded into pure amplified noise.
     */
    fun scale(value: Float, floor: Float, expandRange: Boolean = false): Float {
        if (!expandRange) return (value / maxOf(high, floor)).coerceIn(0f, 1f)
        val base = low * FLOOR_STRENGTH
        val span = maxOf(high - base, floor)
        return ((value - base) / span).coerceIn(0f, 1f)
    }

    private fun percentileIndex(percentile: Float): Int =
        ((count - 1) * percentile).toInt().coerceIn(0, count - 1)

    private companion object {
        const val HIGH = 0.9f

        /**
         * Deliberately not the symmetric 0.1's mirror of "quietest tenth". Music
         * spends a lot of a bar near its own floor — the gaps between notes are
         * short and shallow — so a lower percentile latches onto those gaps and
         * the expansion goes with them, which reads as pumping. A quarter is far
         * enough up to sit in the body of the signal and stay still.
         */
        const val LOW = 0.25f

        /**
         * How much of [LOW] is actually subtracted. Subtracting all of it would
         * put the zero point at a level a quarter of all frames fall below, and
         * everything under it clamps — a quarter of the show at hard black,
         * which reads as flicker rather than as contrast. Backing the zero point
         * off below the body of the signal keeps nearly all of the range
         * expansion while making full black something the music has to earn.
         */
        const val FLOOR_STRENGTH = 0.8f
    }
}

/**
 * Kick detector: bass amplitude above `rollingMean × threshold`, with a
 * refractory period so one kick cannot fire twice.
 *
 * The floor matters as much as the ratio — against near-silence any ratio test
 * fires constantly, which is exactly the ballad-intro failure in `plan.md` 3.5.
 * But it has to sit *below* real kicks or it silently disables the whole mode:
 * measured bass amplitude on a phone mic is around 0.0005, and an earlier fixed
 * floor of 0.01 was twenty times that, so no kick could ever cross it and
 * Pulse-on-beat never fired once. It is passed in for the same reason the band
 * floors are: the level depends on the mic, the ratio does not.
 */
internal class BeatDetector(
    historyFrames: Int,
    private val threshold: Float = 1.35f,
    private val refractoryMs: Long = 200L,
) {
    private val history = FloatArray(historyFrames)
    private var count = 0
    private var next = 0
    private var sum = 0f

    /** Null, not a sentinel: `timeMs - Long.MIN_VALUE` overflows negative, which
     *  silently makes the refractory check false forever — no beat, ever. */
    private var lastBeatMs: Long? = null

    fun update(energy: Float, timeMs: Long, floor: Float): Boolean {
        val mean = if (count == 0) 0f else sum / count
        val warm = count >= history.size / 2

        val loud = energy > mean * threshold && energy > floor
        val ready = lastBeatMs?.let { timeMs - it >= refractoryMs } ?: true
        val beat = warm && loud && ready
        if (beat) lastBeatMs = timeMs

        // Add after deciding: the frame under test must not dilute its own mean.
        if (count == history.size) sum -= history[next]
        history[next] = energy
        sum += energy
        next = (next + 1) % history.size
        if (count < history.size) count++

        return beat
    }
}
