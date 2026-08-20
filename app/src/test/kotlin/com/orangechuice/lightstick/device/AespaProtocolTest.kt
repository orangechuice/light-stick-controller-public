package com.orangechuice.lightstick.device

import com.orangechuice.lightstick.device.profiles.AespaProfile
import com.orangechuice.lightstick.device.profiles.AespaProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These vectors are derived from the frame format rather than from observed
 * traffic — this stick's colour command has no counterpart to record. Hardware
 * is the real gate; these lock the encoder against regression once it passes.
 */
class AespaProtocolTest {

    private val protocol = AespaProtocol()

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

    private fun encodeHex(r: Int, g: Int, b: Int, brightness: Int = 255) =
        protocol.encode(LightState(r, g, b, brightness)).hex()

    @Test
    fun `red matches expected payload`() {
        assertEquals("01010B0000FF000000000A", encodeHex(255, 0, 0))
    }

    @Test
    fun `green matches expected payload`() {
        assertEquals("01010B000000FF0000000A", encodeHex(0, 255, 0))
    }

    @Test
    fun `blue matches expected payload`() {
        assertEquals("01010B00000000FF00000A", encodeHex(0, 0, 255))
    }

    @Test
    fun `white matches expected payload`() {
        assertEquals("01010B0000FFFFFF000008", encodeHex(255, 255, 255))
    }

    @Test
    fun `off matches black payload`() {
        assertEquals("01010B000000000000000B", encodeHex(0, 0, 0))
    }

    /** No hardware brightness field exists, so brightness scales RGB. */
    @Test
    fun `half brightness scales rgb`() {
        assertEquals("01010B000080000000008B", encodeHex(255, 0, 0, brightness = 128))
    }

    @Test
    fun `length byte counts header and checksum`() {
        val packet = protocol.encode(LightState(1, 2, 3, 255))
        assertEquals(11, packet.size)
        assertEquals(11, packet[2].toInt())
    }

    /** Bytes 0 and 1 are outside the sum — the Fanlight family includes them. */
    @Test
    fun `checksum covers length command and payload only`() {
        val packet = protocol.encode(LightState(255, 0, 0, 255))
        val expected = packet.drop(2).dropLast(1).sumOf { it.toInt() and 0xFF } and 0xFF
        assertEquals(expected.toByte(), packet.last())
    }

    @Test
    fun `battery query matches expected payload`() {
        assertEquals("01010650075D", protocol.statusQuery().hex())
    }

    /** The real frame observed on hardware, byte 4 = 0x45 = 69%. */
    @Test
    fun `battery response decodes to a percentage`() {
        assertEquals(69, protocol.parseStatus(OBSERVED_BATTERY_FRAME)?.batteryPercent)
    }

    @Test
    fun `battery response keeps the raw frame`() {
        assertArrayEquals(OBSERVED_BATTERY_FRAME, protocol.parseStatus(OBSERVED_BATTERY_FRAME)?.raw)
    }

    /**
     * Byte 4 is a percentage. Reading bytes 5-6 as millivolts is the plausible
     * alternative and is wrong here: it yields 1110, which clamps to a 10% floor
     * and would report a healthy stick as nearly flat.
     */
    @Test
    fun `battery is not decoded as millivolts`() {
        assertNotEquals(10, protocol.parseStatus(OBSERVED_BATTERY_FRAME)?.batteryPercent)
    }

    /** Nothing outside 0..100 is reported as a percentage. */
    @Test
    fun `implausible battery byte reports no percentage`() {
        val bogus = byteArrayOf(0x01, 0x01, 0x09, 0x07, 0xF0.toByte(), 0x00, 0x00, 0x00, 0x00)
        val status = protocol.parseStatus(bogus)
        assertNotNull(status)
        assertNull(status?.batteryPercent)
    }

    /** Replies to other queries share the characteristic and must not be read as battery. */
    @Test
    fun `non battery response reports no percentage`() {
        val lighting = byteArrayOf(0x01, 0x01, 0x08, 0x53, 0x00, 0x00, 0x00, 0x00)
        val status = protocol.parseStatus(lighting)
        assertNotNull(status)
        assertNull(status?.batteryPercent)
    }

    @Test
    fun `short frames are rejected`() {
        assertNull(protocol.parseStatus(byteArrayOf(0x01, 0x01, 0x06, 0x07)))
    }

    @Test
    fun `aespa profile matches bonded name`() {
        assertTrue(AespaProfile.advertisedNamePattern.containsMatchIn("aespa_OFL_V2"))
        assertTrue(AespaProfile.advertisedNamePattern.containsMatchIn("SME-aespa-OFL01"))
        assertEquals("aespa", DeviceRegistry.findProfileForName("aespa_OFL_V2")?.id)
    }

    /** The other four profiles must not swallow an aespa stick, or vice versa. */
    @Test
    fun `aespa name does not collide with other profiles`() {
        assertEquals("katseye", DeviceRegistry.findProfileForName("KATSEYE OLS")?.id)
        assertEquals("xg", DeviceRegistry.findProfileForName("XG LIGHT STICK1")?.id)
        assertEquals("ive", DeviceRegistry.findProfileForName("IVE OLS 2")?.id)
        assertEquals("twice", DeviceRegistry.findProfileForName("TWICE LIGHTSTICK")?.id)
    }

    private companion object {
        /** Observed on hardware from `aespa_OFL_V2`; checksum 0x5C verifies. */
        val OBSERVED_BATTERY_FRAME = byteArrayOf(
            0x01, 0x01, 0x09, 0x07, 0x45, 0x04, 0x56, 0xAD.toByte(), 0x5C,
        )
    }
}
