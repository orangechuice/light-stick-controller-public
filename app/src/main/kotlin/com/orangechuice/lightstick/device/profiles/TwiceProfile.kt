package com.orangechuice.lightstick.device.profiles

import com.orangechuice.lightstick.device.DeviceProfile
import com.orangechuice.lightstick.device.DeviceStatus
import com.orangechuice.lightstick.device.LightState
import com.orangechuice.lightstick.device.LightstickProtocol
import com.orangechuice.lightstick.device.WriteType
import java.util.UUID

/**
 * TWICE Lightstick (CANDY BONG ∞) — Cocoa Entertainment platform, Nordic nRF.
 *
 * A different platform from the Fanlight sticks in every respect: Nordic UART as
 * the transport, a length-prefixed frame, no checksum, and — unlike KATSEYE, XG
 * and IVE — a connection sequence that must run before the firmware will act on
 * anything.
 *
 * Wire format, written to `6e400002` as Write Request:
 *
 * ```
 * FF [OPCODE] [LEN] [payload…] FF     // LEN counts payload bytes only
 * ```
 */
class TwiceProtocol : LightstickProtocol {

    /**
     * The opening exchange this firmware requires before it will act.
     *
     * This is not decoration. Written cold, a colour command is acknowledged at
     * the ATT layer and silently dropped by the firmware — the light simply does
     * not change, which is indistinguishable from a malformed packet. Replaying
     * this first makes every subsequent command work.
     *
     * Which single packet lifts the gate is not isolated; the sequence was
     * verified as a whole, so it is replayed as a whole. Narrowing it is a
     * nice-to-have, not a correctness matter — these are five cheap writes once
     * per connection.
     */
    override fun handshake(): List<ByteArray> = listOf(
        byteArrayOf(0xFF.toByte(), 0x16, 0x00, 0xFF.toByte()),
        byteArrayOf(0xFF.toByte(), 0x18, 0x00, 0xFF.toByte(), 0x00, 0x00),
        byteArrayOf(0xFF.toByte(), 0xC6.toByte(), 0x00, 0xFF.toByte()),
        byteArrayOf(0xFF.toByte(), 0xC8.toByte(), 0x00, 0xFF.toByte()),
        byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0xFF.toByte()),
        // Wake the emitter and pin hardware brightness at maximum, so that the
        // RGB scaling in encode() is the only attenuation in play. Without this
        // the stick can sit at brightness 0 from a previous LED-off and every
        // colour lands invisibly.
        byteArrayOf(0xFF.toByte(), 0x15, 0x02, 0x00, 0x01, 0xFF.toByte()),
        byteArrayOf(0xFF.toByte(), 0x13, 0x01, MAX_BRIGHTNESS, 0xFF.toByte()),
    )

    /**
     * The firmware has a real brightness command, but it is a 1..10 scale — far
     * too coarse for the music-reactive modes, which is what brightness is
     * mostly for here. So brightness is folded into RGB exactly as on the
     * Fanlight sticks, with the hardware level pinned to max by [handshake].
     *
     * That also keeps one [LightState] mapping to one packet, which is the whole
     * reason [LightstickProtocol.encode] can stay a single-packet interface.
     *
     * The fourth payload byte is that same 1..10 hardware level, pinned to max.
     * This byte is commonly left at 1 — the *dimmest* setting — which is why
     * colours driven through this opcode look washed out next to the palette
     * ones. It is a free parameter, so nothing about the firmware requires the
     * dim value.
     */
    override fun encode(state: LightState): ByteArray {
        val k = state.brightness / 255f
        return byteArrayOf(
            0xFF.toByte(),
            0xE6.toByte(),
            0x04,
            scale(state.r, k),
            scale(state.g, k),
            scale(state.b, k),
            MAX_BRIGHTNESS,
            0xFF.toByte(),
        )
    }

    /**
     * Battery arrives as a reply to `FF 16 00 FF`, never unsolicited:
     *
     * ```
     * FF 16 02 [BATT] 02
     *  0  1  2    3    4
     * ```
     *
     * Byte 3 is the level this frame reports.
     *
     * **It is deliberately not reported as a percentage**, because it demonstrably
     * is not one. Observed on a stick with fresh batteries fitted the same day:
     *
     * ```
     * FF 16 02 11 02     byte 3 = 17
     * FF 16 02 0D D9     byte 3 = 13     (~1 hour later)
     * FF 16 02 0C 03     byte 3 = 12     (7 seconds after that)
     * ```
     *
     * Fresh cells do not read 17%, and byte 4 swings too hard (`02`, `D9`, `03`)
     * to be a second data field, while failing to check out as a checksum over the
     * preceding bytes. Byte 3 as tenths of a volt (1.7 V → 1.2 V, sagging under LED
     * load) fits, as does a 16-bit millivolt reading across bytes 3–4, but neither
     * is confirmed. Surfacing a made-up percentage would put a confidently wrong
     * number in front of someone deciding whether to swap batteries mid-concert.
     *
     * The frame is still returned as [DeviceStatus.raw] so a future decode has the
     * data. To settle it: read this at a known-full and a known-empty charge and
     * see which model the endpoints fit.
     *
     * Replies to the other queries (firmware `B4`, interactive data `C6`/`C8`/`CA`)
     * share this characteristic, so anything that is not a battery frame has to
     * fall through rather than be read at a fixed offset.
     */
    override fun parseStatus(bytes: ByteArray): DeviceStatus? {
        if (bytes.size < 4) return null
        if (bytes[0] != 0xFF.toByte()) return null
        // Both opcodes appear in the vendor's dispatch for a battery reply.
        val opcode = bytes[1].toInt() and 0xFF
        if (opcode != 0x16 && opcode != 0xB2) return null
        return DeviceStatus(batteryPercent = null, raw = bytes)
    }

    /**
     * The battery query, re-sent once notifications are actually subscribed.
     *
     * [handshake] already sends this byte-for-byte, but that happens before
     * anything is listening, so the reply is lost. Asking again on subscription
     * is what makes the level appear.
     */
    override fun statusQuery(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0x16, 0x00, 0xFF.toByte())

    private fun scale(component: Int, k: Float): Byte =
        ((component * k).toInt().coerceIn(0, 255)).toByte()

    companion object {
        /** Hardware brightness runs 0x01..0x0A; 0x0A is full. */
        const val MAX_BRIGHTNESS: Byte = 0x0A
    }
}

val TwiceProfile = DeviceProfile(
    id = "twice",
    displayName = "TWICE Lightstick",
    advertisedNamePattern = Regex("^TWICE LIGHT ?STICK", RegexOption.IGNORE_CASE),
    serviceUuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
    commandCharUuid = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
    // This characteristic is normally driven with Write Request, which costs two
    // connection events per packet (one out, one for the response). At the
    // 48.75 ms interval this link settles on that is ~97 ms per update —
    // measured, and visibly steppy under music sync. It advertises
    // WRITE_NO_RESPONSE as well, so we use that instead: discrete taps never
    // needed the rate, continuous music sync does.
    writeType = WriteType.WITHOUT_RESPONSE,
    // No standard 0x180F on this device — status comes back on the UART's notify
    // characteristic. Note the app does not yet subscribe to notifyCharUuid, so
    // this is declared ahead of the plumbing that will consume it.
    notifyCharUuid = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
    // Pinned to the connection interval this link actually negotiates, the same
    // way KATSEYE's is: `onConnectionUpdated` reports interval=39, so 48.75 ms.
    // Unlike the Fanlight sticks it does *not* drop to interval=6 when the app
    // asks for high priority — the stick advertises Peripheral Preferred
    // Connection Parameters (0x2A04) and wins the negotiation. Writing faster
    // than the interval would queue packets in the stack rather than deliver
    // them sooner, which makes the light fall progressively behind.
    minWriteIntervalMs = 49,
    protocol = TwiceProtocol(),
)
