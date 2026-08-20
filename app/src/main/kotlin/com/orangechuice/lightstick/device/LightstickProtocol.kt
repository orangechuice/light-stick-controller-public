package com.orangechuice.lightstick.device

/**
 * Translates desired state into the bytes a particular lightstick understands.
 *
 * Pure Kotlin by design: no Android, no BLE. Everything here is unit-testable
 * without a device, which matters more than usual because this firmware family
 * drops malformed packets silently — every encode bug presents identically as
 * "the light doesn't respond".
 */
interface LightstickProtocol {

    /** Packets to replay immediately after connecting. Empty when none is needed. */
    fun handshake(): List<ByteArray>

    /**
     * Encode one whole [LightState] into a single wire packet.
     *
     * Deliberately not split into setColor/setBrightness/setMode: on KATSEYE those
     * are not separate wire commands, and a device where they aren't must not be
     * forced to throw from two thirds of its interface.
     */
    fun encode(state: LightState): ByteArray

    /** Parse a vendor status/notify payload, or null when the device has none. */
    fun parseStatus(bytes: ByteArray): DeviceStatus?

    /**
     * A packet that asks the device to report its status, or null when status
     * arrives unprompted (or not at all).
     *
     * Devices that only answer when asked need the question sent *after* the
     * notify subscription is live, not during [handshake] — a reply to a query
     * sent while nothing is listening is simply lost. The connection layer sends
     * this on subscription for that reason.
     */
    fun statusQuery(): ByteArray? = null
}

/** Whatever a device reports about itself outside the standard GATT services. */
data class DeviceStatus(
    val batteryPercent: Int? = null,
    val raw: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceStatus) return false
        return batteryPercent == other.batteryPercent &&
            raw.contentEqualsOrBothNull(other.raw)
    }

    override fun hashCode(): Int =
        31 * (batteryPercent ?: 0) + (raw?.contentHashCode() ?: 0)
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null else contentEquals(other)
