package com.orangechuice.lightstick.device

import com.orangechuice.lightstick.device.profiles.XgProfile
import com.orangechuice.lightstick.device.profiles.XgProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XgProtocolTest {

    private val protocol = XgProtocol()

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

    private fun encodeHex(r: Int, g: Int, b: Int, brightness: Int = 255) =
        protocol.encode(LightState(r, g, b, brightness)).hex()

    @Test
    fun `red matches the hardware-verified payload`() {
        assertEquals("01FF00FF00000000FF", encodeHex(255, 0, 0))
    }

    @Test
    fun `green matches expected green payload`() {
        assertEquals("01FF0000FF000000FF", encodeHex(0, 255, 0))
    }

    @Test
    fun `blue matches expected blue payload`() {
        assertEquals("01FF000000FF0000FF", encodeHex(0, 0, 255))
    }

    @Test
    fun `off matches black payload`() {
        assertEquals("01FF00000000000000", encodeHex(0, 0, 0))
    }

    @Test
    fun `xg profile matches advertised name`() {
        assertTrue(XgProfile.advertisedNamePattern.containsMatchIn("XG LIGHT STICK1"))
        assertNotNull(DeviceRegistry.findProfileForName("XG LIGHT STICK1"))
        assertEquals("xg", DeviceRegistry.findProfileForName("XG LIGHT STICK1")?.id)
    }
}
