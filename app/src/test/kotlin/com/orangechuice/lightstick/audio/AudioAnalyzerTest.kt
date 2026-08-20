package com.orangechuice.lightstick.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The tuning checklist from `plan.md` 3.5, run against synthetic material.
 *
 * Real music is the final judge, but these pin the failures that are obvious in
 * hindsight and invisible in the moment: a band that responds to the wrong
 * frequencies, silence that flickers, and a detector that fires twice per kick.
 */
class AudioAnalyzerTest {

    private val sampleRate = 44_100
    private val frameSize = 1024
    private val frameMs = frameSize * 1000L / sampleRate

    @Test
    fun `fft finds the bin a sine actually lives in`() {
        val size = 1024
        val fft = Fft(size)
        val bin = 64
        val re = FloatArray(size) { sin(2.0 * PI * bin * it / size).toFloat() }
        val im = FloatArray(size)

        fft.transform(re, im)

        val peak = (1 until size / 2).maxBy { sqrt(re[it] * re[it] + im[it] * im[it]) }
        assertEquals(bin, peak)
    }

    @Test
    fun `fft of a constant is all DC`() {
        val size = 256
        val fft = Fft(size)
        val re = FloatArray(size) { 1f }
        val im = FloatArray(size)

        fft.transform(re, im)

        assertEquals(size.toFloat(), re[0], 1e-2f)
        for (k in 1 until size) {
            assertTrue("bin $k should be empty", sqrt(re[k] * re[k] + im[k] * im[k]) < 1e-2f)
        }
    }

    /** Bass→R, mid→G, treble→B only means anything if the bands are separated. */
    @Test
    fun `each band responds to its own frequency and not its neighbours`() {
        assertDominant(60f) { it.bass }
        assertDominant(800f) { it.mid }
        assertDominant(5_000f) { it.treble }
    }

    private fun assertDominant(hz: Float, band: (AudioAnalysis) -> Float) {
        val analyzer = AudioAnalyzer(sampleRate, frameSize)
        var last = AudioAnalysis.SILENT
        // Long enough for the envelopes and the rolling gain to settle.
        repeat(200) { i ->
            last = analyzer.process(tone(hz, amplitude = 0.4f, index = i), i * frameMs)
        }

        val chosen = band(last)
        val others = listOf(last.bass, last.mid, last.treble).filter { it != chosen }
        assertTrue(
            "${hz}Hz gave bass=${last.bass} mid=${last.mid} treble=${last.treble}",
            others.all { chosen > it * 2f },
        )
    }

    /** The ballad-intro case: near-silence must settle, not flail. */
    @Test
    fun `silence reports silent and never fires a beat`() {
        val analyzer = AudioAnalyzer(sampleRate, frameSize)
        val random = Random(7)

        repeat(300) { i ->
            // Not a flat zero — a real mic floor, which is what a naive
            // ratio-based detector happily turns into a light show.
            val frame = ShortArray(frameSize) { (random.nextInt(-12, 13)).toShort() }
            val analysis = analyzer.process(frame, i * frameMs)
            assertTrue("frame $i should read as silent", analysis.silent)
            assertTrue("frame $i fired a beat on noise", !analysis.beat)

            // Adaptive gain divides each band by its own recent peak, so left to
            // itself it divides noise by noise and reports full scale. The meter
            // then reads "nothing to hear" next to three bars pinned at 100%.
            assertEquals("frame $i showed bass on noise", 0f, analysis.bass, 0f)
            assertEquals("frame $i showed mid on noise", 0f, analysis.mid, 0f)
            assertEquals("frame $i showed treble on noise", 0f, analysis.treble, 0f)
            assertEquals("frame $i showed level on noise", 0f, analysis.level, 0f)
        }
    }

    /**
     * Silence must not poison the reference. Coming back from a quiet passage,
     * the gain should still be scaled to the music that was playing before it —
     * not to the room tone that filled the gap.
     */
    @Test
    fun `a quiet passage does not retune the gain to room noise`() {
        val analyzer = AudioAnalyzer(sampleRate, frameSize)
        val random = Random(11)

        fun playMusic(frames: Int, from: Int) = (0 until frames).map { i ->
            analyzer.process(tone(200f, 0.3f, from + i), (from + i) * frameMs)
        }.last()

        val before = playMusic(250, 0)
        // Four seconds of near-silence, then the same music at the same level.
        repeat(180) { i ->
            val frame = ShortArray(frameSize) { random.nextInt(-12, 13).toShort() }
            analyzer.process(frame, (250 + i) * frameMs)
        }
        val after = playMusic(20, 430)

        assertTrue(
            "level went from ${before.level} to ${after.level} across a silent gap",
            abs(before.level - after.level) < 0.25f,
        )
    }

    /** Four-on-the-floor at 120bpm: one beat per kick, none in between. */
    @Test
    fun `beats track a steady kick without double triggering`() {
        val analyzer = AudioAnalyzer(sampleRate, frameSize)
        val beatIntervalMs = 500L
        val totalFrames = 400
        val settleFrames = 120

        val beatTimes = mutableListOf<Long>()
        for (i in 0 until totalFrames) {
            val timeMs = i * frameMs
            val sincePulse = timeMs % beatIntervalMs
            // 60Hz kick lasting ~70ms, over a quiet sustained pad.
            val frame = ShortArray(frameSize) { n ->
                val t = (i * frameSize + n).toDouble() / sampleRate
                val kick = if (sincePulse < 70) 0.6 * sin(2.0 * PI * 60.0 * t) else 0.0
                val pad = 0.05 * sin(2.0 * PI * 900.0 * t)
                ((kick + pad) * 32_000).toInt().toShort()
            }
            if (analyzer.process(frame, timeMs).beat && i >= settleFrames) beatTimes += timeMs
        }

        val expected = (totalFrames * frameMs - settleFrames * frameMs) / beatIntervalMs
        assertTrue(
            "got ${beatTimes.size} beats, expected about $expected: $beatTimes",
            abs(beatTimes.size - expected) <= 2,
        )
        // No double-trigger: consecutive beats stay at least a refractory apart.
        beatTimes.zipWithNext { a, b ->
            assertTrue("beats $a and $b are too close", b - a >= 200)
        }
    }

    /**
     * The levels a phone mic actually delivers, not the levels a synthesised
     * full-scale tone does.
     *
     * Measured on a razr 2025 with music playing in the room: broadband rms
     * 0.008–0.04, bass band around 0.0005, treble around 0.03 — a signal roughly
     * twenty times quieter than anything the other tests here feed in. Every
     * absolute threshold in the analyser was originally set well above this, so
     * the mode ran, reported "Listening", and did nothing. Tones alone would
     * never have caught it; this test is the field measurement written down.
     */
    @Test
    fun `phone-level input is loud enough to register and to find the beat`() {
        val analyzer = AudioAnalyzer(sampleRate, frameSize)
        val beatIntervalMs = 500L
        val settleFrames = 120
        val totalFrames = 400

        val beatTimes = mutableListOf<Long>()
        var last = AudioAnalysis.SILENT
        var peakBass = 0f

        for (i in 0 until totalFrames) {
            val timeMs = i * frameMs
            val sincePulse = timeMs % beatIntervalMs
            val frame = ShortArray(frameSize) { n ->
                val t = (i * frameSize + n).toDouble() / sampleRate
                // Amplitudes chosen so the bands land on the measured values:
                // bass at barely over 1% of a treble-dominated broadband level.
                val kick = if (sincePulse < 70) 0.0012 * sin(2.0 * PI * 60.0 * t) else 0.0
                val treble = 0.06 * sin(2.0 * PI * 4_000.0 * t)
                ((kick + treble) * 32_000).toInt().toShort()
            }
            last = analyzer.process(frame, timeMs)
            if (i >= settleFrames) {
                peakBass = maxOf(peakBass, last.bass)
                if (last.beat) beatTimes += timeMs
            }
        }

        assertTrue("a phone-level mix read as silence", !last.silent)
        assertTrue("bass never registered: peak was $peakBass", peakBass > 0.5f)

        val expected = (totalFrames - settleFrames) * frameMs / beatIntervalMs
        assertTrue(
            "got ${beatTimes.size} beats at phone levels, expected about $expected",
            abs(beatTimes.size - expected) <= 2,
        )
    }

    /**
     * The loud-room failure, and the one thing adaptive gain cannot rescue.
     *
     * Everything else about level is handled by normalising against a rolling
     * percentile — a festival needs no different thresholds than a bedroom. But
     * once the capture path rails, the peaks are gone before the analyser ever
     * sees them, so this has to be reported rather than corrected.
     */
    @Test
    fun `a railing input is reported as clipping`() {
        val analyzer = AudioAnalyzer(sampleRate, frameSize)

        // Loud but intact: a sine at 80% of full scale must not raise the flag.
        var loud = AudioAnalysis.SILENT
        repeat(60) { i -> loud = analyzer.process(tone(200f, 0.8f, i), i * frameMs) }
        assertTrue("headroom left, but reported as clipping", !loud.clipping)

        // The same tone driven well past the rail, so its peaks flatten.
        var railed = AudioAnalysis.SILENT
        repeat(60) { i ->
            val frame = ShortArray(frameSize) { n ->
                val t = (i * frameSize + n).toDouble() / sampleRate
                (sin(2.0 * PI * 200.0 * t) * 3.0 * 32_767)
                    .coerceIn(-32_768.0, 32_767.0)
                    .toInt()
                    .toShort()
            }
            railed = analyzer.process(frame, (60 + i) * frameMs)
        }
        assertTrue("a flat-topped signal was not reported as clipping", railed.clipping)
    }

    /**
     * What [AudioAnalyzer.autoRange] is for, on the material that makes the case.
     *
     * Dividing by a rolling ceiling fixes where the loud end lands and leaves the
     * quiet end wherever the music put it. Modern masters are compressed hard
     * enough that the quiet end is not very quiet, so the whole show plays out
     * across the top of the range and the light looks like it is barely moving.
     * Tracking the bottom too is the only thing that widens that span rather than
     * sliding it around.
     *
     * The material is a tone that never drops below 60% of its own peak — flat
     * enough that a ceiling-only gain has almost nothing to show.
     */
    @Test
    fun `auto range widens a compressed signal that ceiling-only gain flattens`() {
        fun span(autoRange: Boolean): Float {
            val analyzer = AudioAnalyzer(sampleRate, frameSize, autoRange = autoRange)
            var low = 1f
            var high = 0f
            repeat(400) { i ->
                // Slow swell between 0.45 and 0.75, a 1.7:1 range.
                val amplitude = 0.6f + 0.15f * sin(2.0 * PI * i / 40.0).toFloat()
                val out = analyzer.process(tone(200f, amplitude, i), i * frameMs)
                // Only once the rolling references have a full history to work
                // from; before that both modes are still learning the material.
                if (i > 250) {
                    low = minOf(low, out.level)
                    high = maxOf(high, out.level)
                }
            }
            return high - low
        }

        val ceilingOnly = span(autoRange = false)
        val expanded = span(autoRange = true)
        assertTrue(
            "auto range should open the signal up, not just shift it: " +
                "ceiling-only spanned $ceilingOnly, auto spanned $expanded",
            expanded > ceilingOnly * 1.5f,
        )
    }

    /** Adaptive gain is the whole reason there is no manual level control. */
    @Test
    fun `the same music reads the same quiet or loud`() {
        fun settledLevel(amplitude: Float): Float {
            val analyzer = AudioAnalyzer(sampleRate, frameSize)
            var last = AudioAnalysis.SILENT
            repeat(300) { i -> last = analyzer.process(tone(200f, amplitude, i), i * frameMs) }
            return last.level
        }

        val quiet = settledLevel(0.05f)
        val loud = settledLevel(0.8f)
        assertTrue("quiet=$quiet loud=$loud", abs(quiet - loud) < 0.2f)
    }

    /**
     * The reason overlap exists, stated as a measurement rather than a claim.
     *
     * Without it nothing can be reported until the window a sound fell into has
     * closed, so a kick that lands just after a window boundary waits out the
     * whole window before the analyser has an opinion about it. Hopping four
     * times per window looks four times as often at the same 1024-point
     * resolution.
     *
     * Measured here on this material: 20 ms at no overlap, 8 ms at two hops per
     * window, 2 ms at four. The margin asserted is a small fraction of that,
     * because the number itself depends on where the kick happens to fall
     * relative to a window boundary — what must not regress is the ordering.
     */
    @Test
    fun `overlap reports a kick sooner than a full window does`() {
        val kickAtMs = 2_000L

        fun detectionLatency(hopSize: Int): Long {
            val analyzer = AudioAnalyzer(sampleRate, frameSize, hopSize)
            var sample = 0L
            // Run past the kick, but stop at the first beat after it: with a
            // 200 ms refractory there is exactly one to find.
            while (sample * 1000 / sampleRate < kickAtMs + 400) {
                val hop = ShortArray(hopSize) { n ->
                    val at = sample + n
                    val t = at.toDouble() / sampleRate
                    val sinceKick = at * 1000.0 / sampleRate - kickAtMs
                    // A quiet sustained pad throughout, so the room is never
                    // silent and the gain has something to settle against.
                    val pad = 0.05 * sin(2.0 * PI * 900.0 * t)
                    val kick =
                        if (sinceKick in 0.0..70.0) 0.6 * sin(2.0 * PI * 60.0 * t) else 0.0
                    ((pad + kick) * 32_000).roundToInt().coerceIn(-32_768, 32_767).toShort()
                }
                sample += hopSize
                // Stamped at the end of the hop: that is the earliest moment
                // this audio could possibly have been acted on.
                val timeMs = sample * 1000 / sampleRate
                if (analyzer.process(hop, timeMs).beat && timeMs >= kickAtMs) {
                    return timeMs - kickAtMs
                }
            }
            fail("no beat detected after the kick at hop $hopSize")
            error("unreachable")
        }

        val whole = detectionLatency(frameSize)
        val overlapped = detectionLatency(frameSize / 4)

        assertTrue(
            "overlapped hop found the kick after ${overlapped}ms, " +
                "whole window after ${whole}ms — overlap bought nothing",
            overlapped + 5 <= whole,
        )
    }

    /**
     * Overlap must not move any band edge. It changes how often the same
     * transform runs, and the frequency resolution is the thing being protected
     * by hopping rather than by shrinking the window.
     */
    @Test
    fun `overlapped analysis keeps the bands separated`() {
        for (hz in listOf(60f, 800f, 5_000f)) {
            val hopSize = frameSize / 4
            val analyzer = AudioAnalyzer(sampleRate, frameSize, hopSize)
            var last = AudioAnalysis.SILENT
            var sample = 0L
            // The same wall-clock settling time as the non-overlapped tests.
            repeat(200 * 4) {
                val hop = ShortArray(hopSize) { n ->
                    val t = (sample + n).toDouble() / sampleRate
                    (sin(2.0 * PI * hz * t) * 0.4 * 32_000).roundToInt().toShort()
                }
                sample += hopSize
                last = analyzer.process(hop, sample * 1000 / sampleRate)
            }

            val chosen = when (hz) {
                60f -> last.bass
                800f -> last.mid
                else -> last.treble
            }
            val others = listOf(last.bass, last.mid, last.treble).filter { it != chosen }
            assertTrue(
                "${hz}Hz overlapped gave bass=${last.bass} mid=${last.mid} treble=${last.treble}",
                others.all { chosen > it * 2f },
            )
        }
    }

    /**
     * The adaptive gain samples once per window regardless of overlap, so a
     * settled level must land in the same place either way. If it did not, the
     * five seconds of history the reference is built from would silently become
     * one and a quarter.
     */
    @Test
    fun `overlap does not change where the adaptive gain settles`() {
        fun settledLevel(hopSize: Int): Float {
            val analyzer = AudioAnalyzer(sampleRate, frameSize, hopSize)
            var last = AudioAnalysis.SILENT
            var sample = 0L
            repeat(300 * (frameSize / hopSize)) {
                val hop = ShortArray(hopSize) { n ->
                    val t = (sample + n).toDouble() / sampleRate
                    (sin(2.0 * PI * 200.0 * t) * 0.3 * 32_000).roundToInt().toShort()
                }
                sample += hopSize
                last = analyzer.process(hop, sample * 1000 / sampleRate)
            }
            return last.level
        }

        val whole = settledLevel(frameSize)
        val overlapped = settledLevel(frameSize / 4)
        assertEquals(whole, overlapped, 0.1f)
    }

    private fun tone(hz: Float, amplitude: Float, index: Int) = ShortArray(frameSize) { n ->
        val t = (index * frameSize + n).toDouble() / sampleRate
        (sin(2.0 * PI * hz * t) * amplitude * 32_000).roundToInt().toShort()
    }
}
