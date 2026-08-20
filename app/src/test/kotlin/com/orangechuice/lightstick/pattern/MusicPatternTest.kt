package com.orangechuice.lightstick.pattern

import com.orangechuice.lightstick.audio.AudioAnalysis
import com.orangechuice.lightstick.device.LightState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPatternTest {

    private var now = 0L
    private val base = LightState(255, 0, 160, brightness = 200)

    /**
     * Manual by default, because that is the mode in which the pattern's own
     * gain exists at all. Under auto the analyser has already done the scaling
     * and this class is a pass-through, so testing brightness maths there would
     * only be testing 1×.
     */
    private fun pattern(mode: MusicMode) = MusicPattern(
        mode = mode,
        baseColor = base,
        sensitivity = 0.5f,
        autoSensitivity = false,
        nowMs = { now },
    )

    private fun hearing(
        bass: Float = 0f,
        mid: Float = 0f,
        treble: Float = 0f,
        level: Float = 0f,
        beat: Boolean = false,
    ) = AudioAnalysis(bass, mid, treble, level, beat, silent = false)

    @Test
    fun `a beat snaps to full and decays between`() {
        val pattern = pattern(MusicMode.PULSE)
        pattern.submit(hearing(bass = 1f, beat = true))

        val onBeat = pattern.tick(0)
        assertEquals(base.brightness, onBeat.brightness)

        now += MusicPattern.BEAT_DECAY_MS.toLong()
        val after = pattern.tick(0)
        assertTrue("decayed to ${after.brightness}", after.brightness < onBeat.brightness / 2)
    }

    /**
     * The pattern is driven by the mic, not by the clock. A player tick that
     * arrives late must not change the light — otherwise a hitch in the write
     * loop would show up as a visible glitch.
     */
    @Test
    fun `output does not depend on elapsed play time`() {
        val pattern = pattern(MusicMode.LOUDNESS)
        pattern.submit(hearing(level = 0.5f))
        assertEquals(pattern.tick(0), pattern.tick(9_999_999))
    }

    @Test
    fun `spectrum maps bass to red mid to green treble to blue`() {
        val pattern = pattern(MusicMode.SPECTRUM)

        pattern.submit(hearing(bass = 1f))
        pattern.tick(0).let { assertTrue("$it", it.r > it.g && it.r > it.b) }

        pattern.submit(hearing(mid = 1f))
        pattern.tick(0).let { assertTrue("$it", it.g > it.r && it.g > it.b) }

        pattern.submit(hearing(treble = 1f))
        pattern.tick(0).let { assertTrue("$it", it.b > it.r && it.b > it.g) }
    }

    @Test
    fun `palette advances one step per beat and holds between`() {
        val pattern = pattern(MusicMode.PALETTE)

        pattern.submit(hearing(bass = 1f, beat = true))
        val first = pattern.tick(0)

        pattern.submit(hearing(bass = 0.8f))
        val held = pattern.tick(0)
        assertEquals(first.r to first.g to first.b, held.r to held.g to held.b)

        pattern.submit(hearing(bass = 1f, beat = true))
        val second = pattern.tick(0)
        assertTrue(
            "palette did not advance: $first then $second",
            first.r != second.r || first.g != second.g || first.b != second.b,
        )
    }

    /**
     * Strobe's whole point is that it has no tail. Pulse is already down to a
     * third of full by the time this checks, so a test that only looked at the
     * end state would pass against either one.
     */
    @Test
    fun `strobe holds full then cuts to black with nothing in between`() {
        val pattern = pattern(MusicMode.STROBE)
        pattern.submit(hearing(bass = 1f, beat = true))

        assertEquals(base.brightness, pattern.tick(0).brightness)

        now += MusicPattern.STROBE_MS - 1
        assertEquals("still inside the lit window", base.brightness, pattern.tick(0).brightness)

        now += 2
        assertEquals("past the lit window", 0, pattern.tick(0).brightness)
    }

    /** Before the first beat there is no window to be inside of. */
    @Test
    fun `strobe stays dark until the first beat`() {
        val pattern = pattern(MusicMode.STROBE)
        pattern.submit(hearing(bass = 1f))
        assertEquals(0, pattern.tick(0).brightness)
    }

    @Test
    fun `flip alternates between two hues and returns to the first`() {
        val pattern = pattern(MusicMode.FLIP)
        val lit = (1..3).map {
            pattern.submit(hearing(bass = 1f, beat = true))
            pattern.tick(0)
        }

        assertTrue("flip did not change colour: ${lit[0]} then ${lit[1]}", lit[0] != lit[1])
        assertEquals("flip did not come back round", lit[0], lit[2])
    }

    /** Constant brightness is the mode: the colour change alone carries the beat. */
    @Test
    fun `flip does not modulate brightness`() {
        val pattern = pattern(MusicMode.FLIP)
        pattern.submit(hearing(bass = 1f, beat = true))
        val onBeat = pattern.tick(0)

        now += MusicPattern.BEAT_DECAY_MS.toLong() * 4
        assertEquals(base.brightness, onBeat.brightness)
        assertEquals(base.brightness, pattern.tick(0).brightness)
    }

    @Test
    fun `random takes a well separated hue on each of several beats`() {
        val pattern = pattern(MusicMode.RANDOM)
        val lit = (1..6).map {
            pattern.submit(hearing(bass = 1f, beat = true))
            now += 250
            pattern.tick(0)
        }

        assertEquals("hues repeated within six beats", lit.size, lit.distinct().size)
        lit.zipWithNext().forEach { (a, b) ->
            // Compared as colours rather than as hue angles: a light that reads as
            // a new colour is the actual requirement, and channel distance is what
            // the eye is doing. A half-turn apart is ~255; adjacent hues are ~0.
            val distance = kotlin.math.abs(a.r - b.r) +
                kotlin.math.abs(a.g - b.g) + kotlin.math.abs(a.b - b.b)
            assertTrue("$a and $b are too close together (distance $distance)", distance > 120)
        }
    }

    @Test
    fun `bass ignores mid and treble`() {
        val pattern = pattern(MusicMode.BASS)

        pattern.submit(hearing(bass = 1f, level = 1f))
        val onBass = pattern.tick(0)

        pattern.submit(hearing(mid = 1f, treble = 1f, level = 1f))
        val offBass = pattern.tick(0)

        assertTrue(
            "bass $onBass should outshine mid/treble $offBass",
            onBass.brightness > offBass.brightness,
        )
        assertEquals("mid and treble reached the light", 0, offBass.brightness)
    }

    /** Silence settles to a dim resting glow — not black, and not flickering. */
    @Test
    fun `silence rests at a steady dim colour in every mode`() {
        MusicMode.entries.forEach { mode ->
            val pattern = pattern(mode)
            pattern.submit(AudioAnalysis.SILENT)

            val first = pattern.tick(0)
            now += 1_000
            val later = pattern.tick(5_000)

            assertEquals("$mode should hold still while silent", first, later)
            assertTrue("$mode went dark", first.brightness > 0)
            assertTrue("$mode is not dim", first.brightness < base.brightness / 2)
        }
    }

    @Test
    fun `sensitivity scales the response`() {
        val quiet = pattern(MusicMode.LOUDNESS).apply { sensitivity = 0f }
        val loud = pattern(MusicMode.LOUDNESS).apply { sensitivity = 1f }
        quiet.submit(hearing(level = 0.3f))
        loud.submit(hearing(level = 0.3f))

        assertTrue(quiet.tick(0).brightness < loud.tick(0).brightness)
    }

    /**
     * Auto has to ignore the slider outright rather than merely default it. The
     * slider position survives a trip through auto and back, so a stale 3× left
     * over from a previous song would otherwise quietly clip the analyser's
     * already-full-range output flat.
     */
    @Test
    fun `auto ignores the slider at either extreme`() {
        val levels = hearing(level = 0.4f)
        val brightnesses = listOf(0f, 0.5f, 1f).map { position ->
            pattern(MusicMode.LOUDNESS)
                .apply { sensitivity = position; autoSensitivity = true }
                .also { it.submit(levels) }
                .tick(0).brightness
        }

        assertEquals(1, brightnesses.distinct().size)
        assertEquals((base.brightness * 0.4f).toInt(), brightnesses.first())
    }

    /** The one fact the UI uses to decide whether to offer the control at all. */
    @Test
    fun `only the level-driven modes are marked as such`() {
        assertEquals(
            listOf(MusicMode.LOUDNESS, MusicMode.BASS, MusicMode.SPECTRUM),
            MusicMode.entries.filter { it.usesLevels },
        )
    }

    /** Same role for the colour controls that [MusicMode.usesLevels] plays for sensitivity. */
    @Test
    fun `only the modes that tint the base colour are marked as using it`() {
        assertEquals(
            listOf(
                MusicMode.PULSE,
                MusicMode.STROBE,
                MusicMode.FLIP,
                MusicMode.LOUDNESS,
                MusicMode.BASS,
            ),
            MusicMode.entries.filter { it.usesColor },
        )
    }

    /**
     * The sensitivity control is offered per group, so a mode landing in the wrong
     * one gets a slider that does nothing or loses one that it needs. Declaration
     * order is also the chip order, so this pins the grouping the screen relies on.
     */
    @Test
    fun `the level-driven modes are contiguous in declaration order`() {
        val flags = MusicMode.entries.map { it.usesLevels }
        assertEquals(
            "usesLevels must switch exactly once down the row: $flags",
            1,
            flags.zipWithNext().count { (a, b) -> a != b },
        )
    }

    /**
     * The hue control is hidden on the modes below, so nothing they put on the
     * light may depend on it — including the resting glow, which is the one
     * place that dependency survived. A mode that fails this leaves a visible
     * colour with no control left to change it.
     */
    @Test
    fun `a mode that hides the colour control ignores hue everywhere`() {
        MusicMode.entries.filterNot { it.usesColor }.forEach { mode ->
            fun litBy(color: LightState): List<LightState> {
                val pattern = MusicPattern(
                    mode = mode,
                    baseColor = color,
                    autoSensitivity = false,
                    nowMs = { now },
                )
                return listOf(
                    AudioAnalysis.SILENT,
                    hearing(bass = 1f, mid = 0.4f, treble = 0.7f, level = 0.8f, beat = true),
                    hearing(bass = 0.2f, mid = 0.9f, treble = 0.1f, level = 0.3f),
                ).map { pattern.submit(it); pattern.tick(0) }
            }

            // Same brightness, opposite hues: only the hue may not show through.
            assertEquals(
                "$mode still reads the base hue",
                litBy(LightState(255, 0, 160, brightness = 200)),
                litBy(LightState(0, 255, 95, brightness = 200)),
            )
        }
    }

    /** Brightness is never hidden, so it must still land on every mode. */
    @Test
    fun `the resting glow tracks brightness in every mode`() {
        MusicMode.entries.forEach { mode ->
            fun restAt(brightness: Int) = MusicPattern(
                mode = mode,
                baseColor = base.copy(brightness = brightness),
                nowMs = { now },
            ).also { it.submit(AudioAnalysis.SILENT) }.tick(0).brightness

            assertTrue("$mode ignores brightness at rest", restAt(200) > restAt(60))
        }
    }
}
