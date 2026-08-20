package com.orangechuice.lightstick.device

import com.orangechuice.lightstick.device.profiles.IveProfile
import com.orangechuice.lightstick.device.profiles.IveProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IveProtocolTest {

    private val protocol = IveProtocol()

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

    private fun encodeHex(r: Int, g: Int, b: Int, brightness: Int = 255) =
        protocol.encode(LightState(r, g, b, brightness)).hex()

    @Test
    fun `red matches expected payload`() {
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
    fun `ive profile matches advertised names`() {
        assertTrue(IveProfile.advertisedNamePattern.containsMatchIn("IVE"))
        assertTrue(IveProfile.advertisedNamePattern.containsMatchIn("IVE LIGHT STICK"))
        assertNotNull(DeviceRegistry.findProfileForName("IVE LIGHT STICK"))
        assertEquals("ive", DeviceRegistry.findProfileForName("IVE LIGHT STICK")?.id)
    }
}
