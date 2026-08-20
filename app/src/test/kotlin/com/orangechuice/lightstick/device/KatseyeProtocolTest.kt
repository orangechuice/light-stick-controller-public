package com.orangechuice.lightstick.device

import com.orangechuice.lightstick.device.profiles.KatseyeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payloads here are verified against hardware — red hand-checked in nRF Connect.
 *
 * This firmware drops malformed packets with no error and no visible change, so
 * every arithmetic bug in [KatseyeProtocol.encode] looks exactly like "the stick
 * is not responding". These tests are what keep that class of bug out of the
 * BLE stack, where it costs hours instead of minutes.
 */
class KatseyeProtocolTest {

    private val protocol = KatseyeProtocol()

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

    private fun encodeHex(r: Int, g: Int, b: Int, brightness: Int = 255) =
        protocol.encode(LightState(r, g, b, brightness)).hex()

    @Test
    fun `red matches the hardware-verified payload`() {
        assertEquals("01FF00FF00000000FF", encodeHex(255, 0, 0))
    }

    @Test
    fun `green matches the captured payload`() {
        assertEquals("01FF0000FF000000FF", encodeHex(0, 255, 0))
    }

    @Test
    fun `blue matches the captured payload`() {
        assertEquals("01FF000000FF0000FF", encodeHex(0, 0, 255))
    }

    @Test
    fun `white matches the captured payload`() {
        assertEquals("01FF00FFFFFF0000FD", encodeHex(255, 255, 255))
    }

    @Test
    fun `black matches the captured payload`() {
        assertEquals("01FF00000000000000", encodeHex(0, 0, 0))
    }

    @Test
    fun `handshake is empty`() {
        assertTrue(protocol.handshake().isEmpty())
    }

    @Test
    fun `brightness folds into RGB`() {
        val packet = protocol.encode(LightState(255, 0, 0, brightness = 128))

        // Half brightness halves the red channel. Asserted as a range rather than
        // a single byte so the test pins the behaviour, not the rounding rule.
        assertTrue(
            "expected red ~128, got ${packet[3].toInt() and 0xFF}",
            (packet[3].toInt() and 0xFF) in 127..128,
        )
        assertEquals(0, packet[4].toInt())
        assertEquals(0, packet[5].toInt())
        assertChecksumConsistent(packet)
    }

    @Test
    fun `brightness zero is indistinguishable from black`() {
        assertEquals(encodeHex(0, 0, 0), encodeHex(255, 255, 255, brightness = 0))
    }

    @Test
    fun `brightness 255 leaves colour untouched`() {
        assertEquals(encodeHex(200, 100, 50), encodeHex(200, 100, 50, brightness = 255))
    }

    @Test
    fun `every packet is exactly nine bytes with a consistent checksum`() {
        for (r in 0..255 step 17) {
            for (g in 0..255 step 17) {
                for (b in 0..255 step 17) {
                    for (brightness in intArrayOf(0, 1, 64, 128, 254, 255)) {
                        val packet = protocol.encode(LightState(r, g, b, brightness))
                        assertEquals(
                            "wrong length for ($r,$g,$b)@$brightness: ${packet.hex()}",
                            9,
                            packet.size,
                        )
                        assertChecksumConsistent(packet)
                    }
                }
            }
        }
    }

    @Test
    fun `header and trailer bytes are constant`() {
        val packet = protocol.encode(LightState(12, 34, 56))
        assertEquals(0x01, packet[0].toInt() and 0xFF)
        assertEquals(0xFF, packet[1].toInt() and 0xFF)
        assertEquals(0x00, packet[2].toInt() and 0xFF)
        assertEquals(0x00, packet[6].toInt() and 0xFF)
        assertEquals(0x00, packet[7].toInt() and 0xFF)
    }

    @Test
    fun `checksum is the sum of preceding bytes mod 256`() {
        // Worked by hand:
        // 0x01 + 0xFF + 0xFF + 0xFF + 0xFF = 0x3FD -> 0xFD
        assertEquals(0xFD.toByte(), KatseyeProtocol.checksum(byteArrayOf(0x01, -1, 0x00, -1, -1, -1, 0x00, 0x00)))
    }

    private fun assertChecksumConsistent(packet: ByteArray) {
        val expected = KatseyeProtocol.checksum(packet.copyOfRange(0, 8))
        assertEquals(
            "bad checksum in ${packet.hex()}",
            expected.toInt() and 0xFF,
            packet[8].toInt() and 0xFF,
        )
    }
}
