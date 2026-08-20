package com.orangechuice.lightstick.pattern

import com.orangechuice.lightstick.device.LightState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternTest {

    @Test
    fun `solid ignores time`() {
        val pattern = SolidPattern(LightState(10, 20, 30))
        assertEquals(pattern.tick(0), pattern.tick(999_999))
    }

    /**
     * The property that matters: a pattern's period must not drift with elapsed
     * time. Same check a hardware rate test performs against a real stick, and
     * for the same reason — on a silently-failing device, a cycle stretching past
     * its nominal period is the only readable evidence that writes are dropping.
     */
    @Test
    fun `rainbow holds a true fixed period`() {
        val period = 5_000L
        val pattern = RainbowPattern(periodMs = period)

        for (cycle in 0..500L) {
            for (offset in longArrayOf(0, 1, 1234, 2500, 4999)) {
                assertEquals(
                    "drifted at cycle $cycle offset $offset",
                    pattern.tick(offset),
                    pattern.tick(cycle * period + offset),
                )
            }
        }
    }

    @Test
    fun `rainbow sweeps the whole hue circle`() {
        val pattern = RainbowPattern(periodMs = 3_000L)
        val seen = (0 until 3_000 step 25).map { pattern.tick(it.toLong()) }.toSet()
        assertTrue("expected a wide spread of colours, got ${seen.size}", seen.size > 50)

        // Start of the cycle is red; a third of the way round is green.
        assertEquals(LightState(255, 0, 0), pattern.tick(0))
        assertEquals(LightState(0, 255, 0), pattern.tick(1_000))
    }

    @Test
    fun `breathing starts dark peaks mid-cycle and returns`() {
        val pattern = BreathingPattern(LightState(255, 255, 255), periodMs = 2_000L)

        assertEquals(0, pattern.tick(0).brightness)
        assertEquals(255, pattern.tick(1_000).brightness)
        assertEquals(0, pattern.tick(2_000).brightness)
        assertTrue(pattern.tick(500).brightness in 100..155)
    }

    @Test
    fun `breathing is periodic`() {
        val pattern = BreathingPattern(LightState(0, 128, 255), periodMs = 1_500L)
        assertEquals(pattern.tick(400), pattern.tick(400 + 1_500 * 77))
    }

    @Test
    fun `strobe respects its duty cycle`() {
        val color = LightState(255, 255, 255)
        val pattern = StrobePattern(color, periodMs = 100L, dutyCycle = 0.3)

        assertEquals(color, pattern.tick(0))
        assertEquals(color, pattern.tick(29))
        assertEquals(LightState.OFF, pattern.tick(30))
        assertEquals(LightState.OFF, pattern.tick(99))
        assertEquals(color, pattern.tick(100))
    }

    @Test
    fun `keyframe timeline hits its keyframes exactly`() {
        val a = LightState(255, 0, 0)
        val b = LightState(0, 0, 255)
        val pattern = KeyframePattern(
            keyframes = listOf(Keyframe(0L, a), Keyframe(1_000L, b)),
            durationMs = 2_000L,
        )

        assertEquals(a, pattern.tick(0))
        assertEquals(b, pattern.tick(1_000))
    }

    @Test
    fun `keyframe timeline interpolates between frames`() {
        val pattern = KeyframePattern(
            keyframes = listOf(
                Keyframe(0L, LightState(0, 0, 0)),
                Keyframe(1_000L, LightState(200, 100, 50)),
            ),
            durationMs = 1_000L,
        )

        val mid = pattern.tick(500)
        assertEquals(100, mid.r)
        assertEquals(50, mid.g)
        assertEquals(25, mid.b)
    }

    @Test
    fun `keyframe timeline loops back to the first frame`() {
        val first = LightState(255, 0, 0)
        val pattern = KeyframePattern(
            keyframes = listOf(Keyframe(0L, first), Keyframe(1_000L, LightState(0, 0, 255))),
            durationMs = 2_000L,
        )

        assertEquals(first, pattern.tick(2_000))
        assertEquals(pattern.tick(300), pattern.tick(2_300))
        // Between the last keyframe and the wrap point it is still moving.
        assertNotEquals(pattern.tick(1_000), pattern.tick(1_500))
    }

    @Test
    fun `phase is always within one cycle`() {
        for (t in longArrayOf(-10_000, -1, 0, 1, 999, 1_000, 123_456)) {
            val phase = phaseOf(t, 1_000L)
            assertTrue("phase $phase out of range for t=$t", phase >= 0.0 && phase < 1.0)
        }
    }
}
