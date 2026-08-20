package com.orangechuice.lightstick.device.profiles

import com.orangechuice.lightstick.device.DeviceProfile
import com.orangechuice.lightstick.device.DeviceStatus
import com.orangechuice.lightstick.device.LightState
import com.orangechuice.lightstick.device.LightstickProtocol
import com.orangechuice.lightstick.device.WriteType
import java.util.UUID

/**
 * IVE Lightstick — Fanlight (KR) platform, Telink chipset.
 *
 * Wire format, written to `2b19` as Write Without Response:
 *
 * ```
 * 01 FF 00 [R] [G] [B] 00 00 [CHK]     // exactly 9 bytes
 *  0  1  2   3   4   5  6  7    8
 * ```
 */
class IveProtocol : LightstickProtocol {

    override fun handshake(): List<ByteArray> = emptyList()

    override fun encode(state: LightState): ByteArray {
        val k = state.brightness / 255f
        val body = byteArrayOf(
            0x01,
            0xFF.toByte(),
            0x00,
            scale(state.r, k),
            scale(state.g, k),
            scale(state.b, k),
            0x00,
            0x00,
        )
        return body + checksum(body)
    }

    override fun parseStatus(bytes: ByteArray): DeviceStatus? = null

    private fun scale(component: Int, k: Float): Byte =
        ((component * k).toInt().coerceIn(0, 255)).toByte()

    companion object {
        fun checksum(bytes: ByteArray): Byte =
            (bytes.sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
    }
}

val IveProfile = DeviceProfile(
    id = "ive",
    displayName = "IVE Lightstick",
    advertisedNamePattern = Regex("^(IVE|IVE LIGHT STICK)", RegexOption.IGNORE_CASE),
    serviceUuid = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1911"),
    commandCharUuid = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b19"),
    writeType = WriteType.WITHOUT_RESPONSE,
    notifyCharUuid = null,
    minWriteIntervalMs = 12,
    protocol = IveProtocol(),
)
