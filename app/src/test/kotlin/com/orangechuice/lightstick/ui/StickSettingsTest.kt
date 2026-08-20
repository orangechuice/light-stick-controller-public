package com.orangechuice.lightstick.ui

import com.orangechuice.lightstick.pattern.MusicMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StickSettingsTest {

    private val settings = StickSettings(
        hue = 212.5f,
        saturation = 0.75f,
        brightness = 190,
        pattern = PatternChoice.MUSIC,
        musicMode = MusicMode.SPECTRUM,
    )

    @Test
    fun `round trips every field`() {
        assertEquals(settings, StickSettings.decode(settings.encode()))
    }

    @Test
    fun `defaults round trip`() {
        assertEquals(StickSettings.DEFAULT, StickSettings.decode(StickSettings.DEFAULT.encode()))
    }

    @Test
    fun `unreadable records decode to null`() {
        assertNull(StickSettings.decode(null))
        assertNull(StickSettings.decode(""))
        assertNull(StickSettings.decode("1|0.0|1.0"))
        assertNull(StickSettings.decode("2|0.0|1.0|255|MANUAL|PULSE"))
    }

    @Test
    fun `unknown enum names fall back without losing the rest`() {
        val decoded = StickSettings.decode("1|212.5|0.75|190|KALEIDOSCOPE|SPECTRUM")
        assertEquals(settings.copy(pattern = StickSettings.DEFAULT.pattern), decoded)
    }

    @Test
    fun `out-of-range values are brought back into range`() {
        val decoded = StickSettings.decode("1|-10.0|1.5|9999|MANUAL|PULSE")
        assertEquals(350f, decoded!!.hue, 0.001f)
        assertEquals(1f, decoded.saturation, 0.001f)
        assertEquals(255, decoded.brightness)
    }
}
