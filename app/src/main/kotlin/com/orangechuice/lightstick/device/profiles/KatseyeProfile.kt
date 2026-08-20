package com.orangechuice.lightstick.device.profiles

import com.orangechuice.lightstick.device.DeviceProfile
import com.orangechuice.lightstick.device.DeviceStatus
import com.orangechuice.lightstick.device.LightState
import com.orangechuice.lightstick.device.LightstickProtocol
import com.orangechuice.lightstick.device.WriteType
import java.util.UUID

/**
 * KATSEYE OLS — Fanlight (KR) platform, Telink chipset.
 * Verified by hand in nRF Connect against firmware `12-MAY-2025 23:13:46`.
 *
 * Wire format, written to `2b19` as Write Without Response:
 *
 * ```
 * 01 FF 00 [R] [G] [B] 00 00 [CHK]     // exactly 9 bytes
 *  0  1  2   3   4   5  6  7    8
 * ```
 *
 * There is no brightness command and no mode/pattern command — byte 0 was 0x01
 * on all 521 writes observed. Brightness is client-side RGB
 * scaling; patterns are a stream of RGB values.
 */
class KatseyeProtocol : LightstickProtocol {

    /** None. The only non-command write observed was the battery CCCD. */
    override fun handshake(): List<ByteArray> = emptyList()

    override fun encode(state: LightState): ByteArray {
        // No hardware brightness field exists, so fold brightness into RGB.
        // A device with a real brightness byte would emit it separately here.
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

    /** Battery is the standard 0x2A19 characteristic, not a vendor payload. */
    override fun parseStatus(bytes: ByteArray): DeviceStatus? = null

    private fun scale(component: Int, k: Float): Byte =
        ((component * k).toInt().coerceIn(0, 255)).toByte()

    companion object {
        /**
         * Sum of all preceding bytes, mod 256. Verified 521/521 against capture.
         *
         * `sum(bytes 3..5)` fits the captured data equally well — 0x01 + 0xFF is
         * 0 mod 256 and bytes 2, 6, 7 are zero — but the sum-of-all form is the
         * more common convention and stays correct if a command with a different
         * header ever turns up.
         */
        fun checksum(bytes: ByteArray): Byte =
            (bytes.sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
    }
}

val KatseyeProfile = DeviceProfile(
    id = "katseye",
    displayName = "KATSEYE OLS",
    // Per-unit suffix stability is unverified — matched as a prefix, not exactly.
    advertisedNamePattern = Regex("^KATSEYE OLS"),
    serviceUuid = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1911"),
    commandCharUuid = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b19"),
    writeType = WriteType.WITHOUT_RESPONSE,
    // Battery is standard 0x2A19, so no vendor notify characteristic is needed.
    notifyCharUuid = null,
    // Matched to the connection interval this link actually negotiates, which is
    // the rate the radio can carry rather than a guess about it: the stick asks
    // for `interval=9` — 11.25 ms — once the app requests high priority, observed
    // in `onConnectionUpdated`. 12 is the smallest value that still gives each
    // write its own connection event; going below the interval would queue
    // packets in the stack rather than deliver them sooner, which is the one
    // failure that makes the light fall progressively behind instead of merely
    // lagging. Was 15, chosen when analysis arrived every ~23 ms and this was
    // nowhere near the narrowest point in the chain.
    minWriteIntervalMs = 12,
    protocol = KatseyeProtocol(),
)

/**
 * Never write to `...0a0b0c0d2b12`. Its own 0x2901 descriptor names it OTA;
 * arbitrary writes to a firmware-update characteristic risk bricking the stick.
 * Recorded here so it is visible in code, not only in the protocol notes.
 */
@Suppress("unused")
private val OTA_CHARACTERISTIC_DO_NOT_WRITE: UUID =
    UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b12")
