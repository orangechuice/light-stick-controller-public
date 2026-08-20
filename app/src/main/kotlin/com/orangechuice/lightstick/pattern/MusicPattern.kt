package com.orangechuice.lightstick.pattern

import com.orangechuice.lightstick.audio.AudioAnalysis
import com.orangechuice.lightstick.device.LightState
import kotlin.math.exp

/**
 * The ways a light can answer music. See `plan.md` 3.3.
 *
 * Declaration order is the order of the chips on screen, and it is grouped by
 * [usesLevels] on purpose: the sensitivity control appears for the second pair
 * and not the first, so the two beat-driven modes sit together rather than
 * having the control blink in and out as you step along the row.
 *
 * [usesColor] cuts across that grouping rather than along it, so the colour
 * controls *do* blink in and out as you step along. No order satisfies both, and
 * the sensitivity grouping was here first; the screen instead keeps the colour
 * section below these chips so the blinking never moves the chip you are
 * tapping. Within each [usesLevels] group the modes are ordered so the ones that
 * source their own colour sit together, which is as close as this gets.
 */
enum class MusicMode(
    val label: String,
    /**
     * Driven by continuous band levels rather than by discrete beats. The single
     * fact that decides which controls apply: everything about sensitivity and
     * normalisation lands on the levels, and the beat detector — which works off
     * raw bass against its own rolling threshold — is untouched by all of it.
     */
    val usesLevels: Boolean,
    /**
     * Reads the hue and saturation of [MusicPattern.baseColor]. False for the
     * two modes that source their own colour — Palette from its fixed list,
     * Spectrum from the bands themselves — which is why [MusicPattern.restingColor]
     * takes care not to reintroduce the dependency through the silent glow.
     *
     * Brightness is not covered by this: every mode scales [MusicPattern.baseColor]'s
     * brightness, so that slider always applies.
     */
    val usesColor: Boolean,
) {
    PULSE("Pulse on beat", usesLevels = false, usesColor = true),
    STROBE("Strobe", usesLevels = false, usesColor = true),
    FLIP("Flip", usesLevels = false, usesColor = true),
    PALETTE("Palette", usesLevels = false, usesColor = false),
    RANDOM("Random", usesLevels = false, usesColor = false),
    LOUDNESS("Loudness", usesLevels = true, usesColor = true),
    BASS("Bass only", usesLevels = true, usesColor = true),
    SPECTRUM("Spectrum", usesLevels = true, usesColor = false),
}

/**
 * Music-reactive light, as **just another [PatternSource]** — the handoff
 * `plan-phase2.md` promised. It publishes into the same player and the same
 * write gate as Breathe or Rainbow, and needs no new machinery for the rate
 * mismatch: audio arrives every ~23 ms, the gate consumes at 15 ms and conflates.
 *
 * Two clocks meet here and they are deliberately kept apart. Analysis arrives
 * from the mic thread via [submit]; [tick] runs on the player's loop. Nothing is
 * shared but a few volatile fields, so neither side ever waits on the other —
 * a stalled mic leaves the light on its last state rather than freezing playback.
 *
 * Its settings are `var`s rather than constructor values because the instance
 * outlives them: dragging the sensitivity slider must retune the pattern that is
 * playing, not swap in a fresh one that has forgotten where the beat was.
 */
class MusicPattern(
    mode: MusicMode = MusicMode.PULSE,
    baseColor: LightState = LightState(255, 0, 160),
    sensitivity: Float = 0.5f,
    autoSensitivity: Boolean = true,
    private val palette: List<LightState> = DEFAULT_PALETTE,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : PatternSource {

    @Volatile var mode: MusicMode = mode
    @Volatile var baseColor: LightState = baseColor

    /** 0..1 from the UI. Ignored entirely while [autoSensitivity] is on. */
    @Volatile var sensitivity: Float = sensitivity

    /**
     * Leave the analyser's levels alone rather than applying [sensitivity].
     *
     * Pairs with `AudioAnalyzer.autoRange`, and the two must be set together.
     * That flag makes the analyser's output already span the full 0..1 for the
     * music actually playing, so a multiplier on top of it has nothing left to
     * correct and only clips the top off.
     */
    @Volatile var autoSensitivity: Boolean = autoSensitivity

    @Volatile private var latest: AudioAnalysis = AudioAnalysis.SILENT
    @Volatile private var lastBeatAt: Long = Long.MIN_VALUE
    @Volatile private var paletteIndex: Int = 0

    /**
     * Beats since this instance started, for [MusicMode.FLIP]'s alternation.
     *
     * Not derived from [paletteIndex]'s parity, which would be the obvious reuse
     * and is wrong: that index wraps at the palette size, and an odd-length
     * palette makes its parity stutter — five entries give 0,1,0,1,0,**0**,1 and
     * the flip stalls for a beat every time the palette comes round.
     */
    @Volatile private var beatCount: Long = 0

    /** Current hue for [MusicMode.RANDOM]. Advanced by [GOLDEN_ANGLE] per beat. */
    @Volatile private var scatterHue: Float = 0f

    /** Called from the audio thread, once per frame. Never blocks. */
    fun submit(analysis: AudioAnalysis) {
        latest = analysis
        if (analysis.beat) {
            lastBeatAt = nowMs()
            beatCount++
            paletteIndex = (paletteIndex + 1) % palette.size
            scatterHue = (scatterHue + GOLDEN_ANGLE) % 360f
        }
    }

    /**
     * [timeMs] is ignored: this pattern is a function of what the mic just heard,
     * not of how long it has been playing. Beat decay runs off [nowMs] because
     * beats are stamped on the audio thread against that same clock.
     */
    override fun tick(timeMs: Long): LightState {
        val analysis = latest

        // Resting state. A silent room settles to a dim glow rather than going
        // black — "nothing to hear" and "the app has died" should not look alike.
        if (analysis.silent) return restingColor().copy(brightness = restingBrightness())

        val gain = gain()
        val decay = beatDecay()

        return when (mode) {
            MusicMode.PULSE -> baseColor.scaledTo(decay)

            // Hard edges, unlike Pulse: full for a fixed window and then nothing,
            // with no tail in between. Driven off the elapsed time rather than off
            // a threshold on [decay] so the lit window is a duration that can be
            // stated and tuned, not a by-product of the decay constant.
            MusicMode.STROBE -> baseColor.scaledTo(if (strobing()) 1f else 0f)

            // No brightness modulation at all: the two colours are a half-turn
            // apart, so the change itself is what reads as the beat. Adding a
            // pulse on top only muddies the switch.
            MusicMode.FLIP -> flipColor()

            // A step per beat, with enough pulse left in it to show the beat
            // itself on a run of identical-looking palette entries.
            MusicMode.PALETTE -> palette[paletteIndex]
                .copy(brightness = baseColor.brightness)
                .scaledTo(STEP_FLOOR + (1f - STEP_FLOOR) * decay)

            MusicMode.RANDOM -> scatterColor()
                .scaledTo(STEP_FLOOR + (1f - STEP_FLOOR) * decay)

            MusicMode.LOUDNESS -> baseColor.scaledTo((analysis.level * gain).coerceIn(0f, 1f))

            // Loudness against the one band a crowd does not drown out. Screaming
            // and clapping land in mid and treble; the kick has the low end more
            // or less to itself even in a packed arena.
            MusicMode.BASS -> baseColor.scaledTo((analysis.bass * gain).coerceIn(0f, 1f))

            MusicMode.SPECTRUM -> LightState.of(
                r = (analysis.bass * gain * 255f).toInt(),
                g = (analysis.mid * gain * 255f).toInt(),
                b = (analysis.treble * gain * 255f).toInt(),
                brightness = baseColor.brightness,
            )
        }
    }

    /**
     * Milliseconds since the last beat, or null if none has landed yet.
     *
     * The null is load-bearing and is the same trap `BeatDetector` documents:
     * subtracting the [Long.MIN_VALUE] sentinel overflows to a large *negative*
     * number, so every "has it been long enough" test downstream silently answers
     * yes forever. Clamped at zero so a clock that steps backwards reads as "just
     * now" rather than as the distant future.
     */
    private fun sinceBeatMs(): Long? {
        val at = lastBeatAt
        if (at == Long.MIN_VALUE) return null
        return (nowMs() - at).coerceAtLeast(0L)
    }

    /** 1 on the beat, falling away over [BEAT_DECAY_MS]. */
    private fun beatDecay(): Float {
        val since = sinceBeatMs() ?: return 0f
        return exp(-since / BEAT_DECAY_MS).toFloat().coerceIn(0f, 1f)
    }

    /** Whether the strobe's lit window is still open. */
    private fun strobing(): Boolean = (sinceBeatMs() ?: Long.MAX_VALUE) < STROBE_MS

    /**
     * [baseColor] on even beats, its opposite on odd ones.
     *
     * Rotating the hue rather than taking a second colour from the user is what
     * lets this mode exist without a second picker on screen: one hue slider
     * defines both halves, and they cannot be set to a pair that does not
     * contrast. Note that a fully desaturated base has no opposite — see
     * [LightState.hueRotated] — so white flips to white and the light simply
     * holds steady instead of doing something surprising.
     */
    private fun flipColor(): LightState =
        if (beatCount % 2L == 0L) baseColor else baseColor.hueRotated(180f)

    /**
     * The current [MusicMode.RANDOM] colour, at full saturation.
     *
     * The hue walks by [GOLDEN_ANGLE] per beat rather than being drawn at random,
     * which looks the same on a light and is better in every other way: successive
     * hues are guaranteed far apart instead of merely likely to be, and there is
     * no generator to seed — two instances fed the same beats agree, which is what
     * makes the mode testable at all.
     */
    private fun scatterColor(): LightState =
        LightState.fromHsv(scatterHue, 1f, 1f, baseColor.brightness)

    /**
     * Maps the 0..1 slider onto a useful multiplier: 0.4× at one end, 3× at the
     * other. Note that the midpoint is 1.7×, not 1× — with the floor left where
     * it is, a band's level rides well below full scale (it is divided by a 90th
     * percentile, and most frames are not in the top tenth), so unity gain here
     * means a light that never quite reaches the brightness that was asked for.
     * The boost is doing real work, which is also why it has to go away when the
     * analyser starts stretching that range itself.
     */
    private fun gain(): Float =
        if (autoSensitivity) 1f else 0.4f + sensitivity.coerceIn(0f, 1f) * 2.6f

    /**
     * What the dim glow is made of when there is nothing to hear.
     *
     * The modes that source their own colour have to rest on their own colour
     * too. Resting every mode on [baseColor] would have been the one place a
     * hue set on screen still reached Palette and Spectrum — and with the hue
     * control now hidden for exactly those two, that surviving thread would
     * leave a visible colour with nothing left to adjust it.
     *
     * Palette rests where it stopped, which is the colour that was already lit
     * when the music died. Spectrum has no such colour to hold — its palette is
     * the music — so it rests neutral rather than inventing a hue.
     */
    private fun restingColor(): LightState = when (mode) {
        MusicMode.PALETTE -> palette[paletteIndex]
        MusicMode.RANDOM -> scatterColor()
        MusicMode.SPECTRUM -> RESTING_NEUTRAL
        // Flip rests on the base rather than on whichever half it stopped on:
        // both halves are derived from that one hue, so there is no information
        // in the parity worth holding on to through a silence.
        MusicMode.PULSE, MusicMode.STROBE, MusicMode.FLIP,
        MusicMode.LOUDNESS, MusicMode.BASS,
        -> baseColor
    }

    private fun restingBrightness(): Int =
        (baseColor.brightness * RESTING_FRACTION).toInt().coerceIn(0, 255)

    private fun LightState.scaledTo(fraction: Float): LightState =
        copy(brightness = (brightness * fraction.coerceIn(0f, 1f)).toInt().coerceIn(0, 255))

    companion object {
        const val BEAT_DECAY_MS = 180.0

        /**
         * How long [MusicMode.STROBE] stays lit after a beat.
         *
         * Comfortably above the 15 ms write interval, so the lit window survives
         * the gate's conflation instead of landing between two writes and being
         * dropped, and short enough to still read as a flash rather than a pulse.
         */
        const val STROBE_MS = 70L

        /**
         * Floor under the stepping modes' beat pulse — Palette and Random. They
         * change colour on the beat and use the pulse only as reinforcement, so
         * unlike Pulse they must never reach black: a missed beat should look like
         * a colour that held, not like the light went out.
         */
        private const val STEP_FLOOR = 0.55f

        /**
         * Hue advance per beat for [MusicMode.RANDOM], in degrees. The golden
         * angle: successive values stay far apart and the sequence takes a long
         * time to visibly repeat, which is exactly what "random colours" needs to
         * look like without an actual generator.
         */
        private const val GOLDEN_ANGLE = 137.508f

        private const val RESTING_FRACTION = 0.12f

        /** Spectrum's resting glow. Dimmed by [RESTING_FRACTION], so not glaring. */
        private val RESTING_NEUTRAL = LightState(255, 255, 255)

        val DEFAULT_PALETTE = listOf(
            LightState(255, 0, 160),
            LightState(0, 160, 255),
            LightState(255, 200, 0),
            LightState(120, 0, 255),
            LightState(0, 230, 120),
        )
    }
}
