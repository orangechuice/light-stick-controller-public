package com.orangechuice.lightstick.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightStateTest {

    @Test
    fun `of clamps out-of-range components`() {
        assertEquals(LightState(255, 0, 255), LightState.of(300, -20, 999))
    }

    @Test
    fun `hsv primaries`() {
        assertEquals(LightState(255, 0, 0), LightState.fromHsv(0f, 1f, 1f))
        assertEquals(LightState(0, 255, 0), LightState.fromHsv(120f, 1f, 1f))
        assertEquals(LightState(0, 0, 255), LightState.fromHsv(240f, 1f, 1f))
    }

    @Test
    fun `hsv wraps hue`() {
        assertEquals(LightState.fromHsv(10f, 1f, 1f), LightState.fromHsv(370f, 1f, 1f))
        assertEquals(LightState.fromHsv(350f, 1f, 1f), LightState.fromHsv(-10f, 1f, 1f))
    }

    @Test
    fun `hsv zero saturation is white`() {
        val state = LightState.fromHsv(200f, 0f, 1f)
        assertEquals(state.r, state.g)
        assertEquals(state.g, state.b)
        assertTrue(state.r >= 254)
    }

    @Test
    fun `a half turn of hue lands on the opposite primary`() {
        // Allow a unit of rounding: the round trip through HSV is not exact.
        fun assertClose(expected: LightState, actual: LightState) {
            assertTrue(
                "expected about $expected, got $actual",
                kotlin.math.abs(expected.r - actual.r) <= 1 &&
                    kotlin.math.abs(expected.g - actual.g) <= 1 &&
                    kotlin.math.abs(expected.b - actual.b) <= 1,
            )
        }

        assertClose(LightState(0, 255, 255), LightState(255, 0, 0).hueRotated(180f))
        assertClose(LightState(255, 0, 255), LightState(0, 255, 0).hueRotated(180f))
        assertClose(LightState(255, 255, 0), LightState(0, 0, 255).hueRotated(180f))
    }

    @Test
    fun `rotating hue keeps brightness`() {
        val rotated = LightState(255, 0, 160, brightness = 90).hueRotated(180f)
        assertEquals(90, rotated.brightness)
    }

    /**
     * The property Flip depends on: an "opposite" that never darkens the light.
     * Channel inversion, the other obvious implementation, maps white to black.
     */
    @Test
    fun `grey has no hue to rotate and comes back unchanged`() {
        listOf(
            LightState(255, 255, 255),
            LightState(120, 120, 120),
            LightState(0, 0, 0),
        ).forEach { grey ->
            assertEquals(grey, grey.hueRotated(180f))
        }
    }
}
