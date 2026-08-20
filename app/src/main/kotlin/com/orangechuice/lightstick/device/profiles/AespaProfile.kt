package com.orangechuice.lightstick.device.profiles

import com.orangechuice.lightstick.device.DeviceProfile
import com.orangechuice.lightstick.device.DeviceStatus
import com.orangechuice.lightstick.device.LightState
import com.orangechuice.lightstick.device.LightstickProtocol
import com.orangechuice.lightstick.device.WriteType
import java.util.UUID

/**
 * aespa Official Light Stick (Ver. 2) — SM Entertainment "FANLIGHT_V2" platform,
 * Telink chipset.
 *
 * Shares the Telink stock SDK service and characteristic UUIDs with the KATSEYE,
 * XG and IVE sticks, and *nothing else*: those UUIDs are the Telink SDK defaults,
 * not a Fanlight-KR signature. SM built a different frame on top, so the 9-byte
 * `01 FF 00 R G B 00 00 CHK` packet is silently dropped by this firmware.
 *
 * Wire format, written to `2b19` as Write Without Response:
 *
 * ```
 * 01 01 [LEN] [CMD] [payload…] [CHK]
 *  0    1   2     3    4…            LEN = 5 + payload.size
 * ```
 *
 * [CHK] is `(LEN + CMD + payload) mod 256` — note that bytes 0 and 1 are *not*
 * summed, unlike the Fanlight family where the checksum covers everything.
 *
 * This stick's colour command has no observable counterpart in ordinary use, so
 * the frame below is validated against hardware rather than against traffic.
 */
class AespaProtocol : LightstickProtocol {

    /** None. The firmware acts on a colour command written cold. */
    override fun handshake(): List<ByteArray> = emptyList()

    /**
     * Lighting is `CMD = 0x00` with payload `[SCMD, R, G, B, 0x00, 0x00]`.
     *
     * There is no brightness field, so brightness is client-side RGB scaling as
     * on every other stick here. The two trailing zeros are fixed in every frame
     * seen; sibling platforms on this chipset expose STAY/STROBE/DIMMING/FADING
     * sub-commands, so these are most likely effect parameters that only matter
     * for `SCMD != 0`.
     */
    override fun encode(state: LightState): ByteArray {
        val k = state.brightness / 255f
        return frame(
            cmd = CMD_LIGHTING,
            payload = byteArrayOf(
                SCMD_LIGHTING_STAY,
                scale(state.r, k),
                scale(state.g, k),
                scale(state.b, k),
                0x00,
                0x00,
            ),
        )
    }

    /**
     * Battery percentage, read straight out of the reply.
     *
     * Byte 3 of any response carries the command it answers; byte 4 of a battery
     * reply is the percentage, used directly with no conversion. Verified on
     * hardware: `01 01 09 07 45 04 56 AD 5C` — byte 4 is `0x45`, i.e. 69%.
     *
     * Bytes 5-7 (`04 56 AD` in that frame) are undecoded. Reading bytes 5-6 as a
     * big-endian millivolt figure is the tempting alternative and is wrong for
     * this device: it yields 1110 mV for the frame above, which floors at 10% and
     * would report a two-thirds-full stick as nearly flat.
     *
     * A value outside 0..100 is treated as "not a percentage" rather than
     * clamped: this stick is not known to produce one, and inventing a plausible
     * number is worse than showing nothing to someone deciding whether to swap
     * batteries mid-concert.
     */
    override fun parseStatus(bytes: ByteArray): DeviceStatus? {
        if (bytes.size < 5) return null
        if (bytes[RES_CMD_INDEX] != RES_BATTERY) return DeviceStatus(raw = bytes)
        val percent = bytes[BATTERY_PERCENT_INDEX].toInt() and 0xFF
        return DeviceStatus(
            batteryPercent = percent.takeIf { it in 0..100 },
            raw = bytes,
        )
    }

    /**
     * The battery request, sent once notifications are subscribed.
     *
     * This stick has no standard `0x180F` service at all — the level only ever
     * arrives as a reply on the command characteristic, which also carries
     * notifications.
     */
    override fun statusQuery(): ByteArray =
        frame(cmd = CMD_QUERY, payload = byteArrayOf(SCMD_BATTERY))

    private fun scale(component: Int, k: Float): Byte =
        ((component * k).toInt().coerceIn(0, 255)).toByte()

    companion object {
        private const val PACKET: Byte = 0x01
        private const val TP: Byte = 0x01

        const val CMD_LIGHTING: Byte = 0x00
        const val CMD_QUERY: Byte = 0x50
        const val CMD_TURN_OFF: Byte = 0x51

        const val SCMD_LIGHTING_STAY: Byte = 0x00
        const val SCMD_BATTERY: Byte = 0x07

        private const val RES_CMD_INDEX = 3
        private const val RES_BATTERY: Byte = 0x07
        private const val BATTERY_PERCENT_INDEX = 4

        /**
         * `[PACKET][TP][LEN][CMD][payload…][CHK]`. LEN counts the whole frame:
         * four header bytes plus the checksum, plus the payload.
         */
        fun frame(cmd: Byte, payload: ByteArray): ByteArray {
            val len = (5 + payload.size).toByte()
            val checksum = checksum(len, cmd, payload)
            return byteArrayOf(PACKET, TP, len, cmd) + payload + checksum
        }

        /** Sum of LEN, CMD and the payload, mod 256. Bytes 0–1 are excluded. */
        fun checksum(len: Byte, cmd: Byte, payload: ByteArray): Byte {
            val sum = (len.toInt() and 0xFF) +
                (cmd.toInt() and 0xFF) +
                payload.sumOf { it.toInt() and 0xFF }
            return (sum and 0xFF).toByte()
        }
    }
}

val AespaProfile = DeviceProfile(
    id = "aespa",
    displayName = "aespa Light Stick",
    // Bonded name observed as `aespa_OFL_V2`; this family also ships under
    // `SME-aespa-…` model names, so the prefix is matched case-insensitively
    // rather than the exact bonded string.
    advertisedNamePattern = Regex("^(aespa|SME-aespa)", RegexOption.IGNORE_CASE),
    serviceUuid = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1911"),
    commandCharUuid = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b19"),
    writeType = WriteType.WITHOUT_RESPONSE,
    // The command characteristic is also the notify characteristic: replies come
    // back on `2b19`. Declaring it non-null is what keeps the connection layer off
    // the standard battery path, which this stick does not have — observing
    // 0x180F here throws on every connect.
    notifyCharUuid = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b19"),
    // `onConnectionUpdated` reports interval=12 — 15 ms — after the app requests
    // high priority. Writing faster than the interval queues packets in the stack
    // rather than delivering them sooner.
    minWriteIntervalMs = 15,
    protocol = AespaProtocol(),
)
