package com.orangechuice.lightstick.device

import com.orangechuice.lightstick.device.profiles.TwiceProfile
import com.orangechuice.lightstick.device.profiles.TwiceProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwiceProtocolTest {

    private val protocol = TwiceProtocol()

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

    private fun encodeHex(r: Int, g: Int, b: Int, brightness: Int = 255) =
        protocol.encode(LightState(r, g, b, brightness)).hex()

    // The colour bytes below are the ones confirmed on hardware; the trailing
    // brightness byte is 0x0A rather than 0x01, which lit the stick but left it
    // dim. Only the brightness byte differs from the verified payloads.

    @Test
    fun `red matches the colour bytes verified on hardware`() {
        assertEquals("FFE604FF00000AFF", encodeHex(255, 0, 0))
    }

    @Test
    fun `green matches the colour bytes verified on hardware`() {
        assertEquals("FFE60400FF000AFF", encodeHex(0, 255, 0))
    }

    @Test
    fun `blue matches expected blue payload`() {
        assertEquals("FFE6040000FF0AFF", encodeHex(0, 0, 255))
    }

    @Test
    fun `black encodes as all-zero channels`() {
        assertEquals("FFE6040000000AFF", encodeHex(0, 0, 0))
    }

    @Test
    fun `brightness scales rgb rather than emitting a separate command`() {
        // Half brightness halves every channel and still yields one packet, which
        // is what keeps encode() a single-packet interface on this device.
        assertEquals("FFE6048000000AFF", encodeHex(255, 0, 0, 128))
    }

    @Test
    fun `every packet is framed by FF and carries its payload length`() {
        val packet = protocol.encode(LightState(1, 2, 3))
        assertEquals(0xFF.toByte(), packet.first())
        assertEquals(0xFF.toByte(), packet.last())
        // Byte 2 is LEN, counting payload bytes only: R, G, B and the brightness.
        assertEquals(4, packet[2].toInt())
        assertEquals(8, packet.size)
    }

    @Test
    fun `handshake replays the required opening sequence`() {
        val packets = protocol.handshake().map { it.hex() }
        // Control commands are ignored by the firmware until these have been sent,
        // so an empty handshake here would be a silent, total failure on device.
        assertEquals(
            listOf(
                "FF1600FF",
                "FF1800FF0000",
                "FFC600FF",
                "FFC800FF",
                "FFCA00FF",
                "FF15020001FF",
                "FF13010AFF",
            ),
            packets,
        )
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `recognises the battery reply and keeps the raw frame`() {
        val frame = bytes(0xFF, 0x16, 0x02, 0x11, 0x02)
        val status = protocol.parseStatus(frame)
        assertNotNull(status)
        assertArrayEquals(frame, status?.raw)
    }

    @Test
    fun `recognises the alternate B2 battery opcode`() {
        assertNotNull(protocol.parseStatus(bytes(0xFF, 0xB2, 0x02, 0x40, 0x02)))
    }

    @Test
    fun `does not report a battery percentage`() {
        // Byte 3 is not a percentage: a stick with fresh cells reported 17, then 12
        // an hour later. Reporting it would show a confidently wrong number, so the
        // level is withheld until the scale is decoded. See TwiceProtocol.parseStatus.
        assertNull(protocol.parseStatus(bytes(0xFF, 0x16, 0x02, 0x11, 0x02))?.batteryPercent)
        assertNull(protocol.parseStatus(bytes(0xFF, 0x16, 0x02, 0x0C, 0x03))?.batteryPercent)
    }

    @Test
    fun `ignores replies that are not battery frames`() {
        // Firmware and interactive-data replies arrive on the same characteristic.
        // Reading byte 3 blindly would report firmware 0x01 as a 1% battery.
        assertNull(protocol.parseStatus(bytes(0xFF, 0xB4, 0x02, 0x01, 0x03, 0x46)))
        assertNull(protocol.parseStatus(bytes(0xFF, 0xC6, 0x0F, 0x01, 0xFF, 0xFF)))
    }

    @Test
    fun `ignores truncated and unframed payloads`() {
        assertNull(protocol.parseStatus(bytes(0xFF, 0x16, 0x02)))
        assertNull(protocol.parseStatus(bytes(0x00, 0x16, 0x02, 0x11, 0x02)))
        assertNull(protocol.parseStatus(ByteArray(0)))
    }

    @Test
    fun `status query asks for battery and matches the handshake packet`() {
        // Same bytes the handshake sends; the point of re-sending is timing, not
        // content, so if these ever diverge one of them is wrong.
        assertEquals("FF1600FF", protocol.statusQuery()?.hex())
        assertEquals(protocol.handshake().first().hex(), protocol.statusQuery()?.hex())
    }

    @Test
    fun `twice profile matches advertised names`() {
        assertTrue(TwiceProfile.advertisedNamePattern.containsMatchIn("TWICE LIGHTSTICK"))
        assertTrue(TwiceProfile.advertisedNamePattern.containsMatchIn("TWICE LIGHT STICK"))
        assertNotNull(DeviceRegistry.findProfileForName("TWICE LIGHTSTICK"))
        assertEquals("twice", DeviceRegistry.findProfileForName("TWICE LIGHTSTICK")?.id)
    }

    @Test
    fun `twice profile does not collide with the fanlight profiles`() {
        // The registry returns the first pattern that matches, so a TWICE stick
        // must not be claimed by IVE's or XG's prefix.
        assertEquals("katseye", DeviceRegistry.findProfileForName("KATSEYE OLS 1234")?.id)
        assertEquals("ive", DeviceRegistry.findProfileForName("IVE LIGHT STICK")?.id)
        assertEquals("xg", DeviceRegistry.findProfileForName("XG LIGHT STICK1")?.id)
    }
}
