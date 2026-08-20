package com.orangechuice.lightstick.device

import java.util.UUID

enum class WriteType { WITH_RESPONSE, WITHOUT_RESPONSE }

/**
 * Everything the app needs to know about one model of lightstick.
 *
 * Adding a lightstick is one [DeviceProfile] plus one [LightstickProtocol] — no
 * changes to the BLE, pattern, or UI layers. That boundary is the project's
 * central bet; keep it clean.
 */
data class DeviceProfile(
    val id: String,
    val displayName: String,
    /** Matched against the advertised name client-side during scanning. */
    val advertisedNamePattern: Regex,
    val serviceUuid: UUID,
    val commandCharUuid: UUID,
    val writeType: WriteType,
    /** Vendor status characteristic. Null when battery comes from standard 0x2A19. */
    val notifyCharUuid: UUID?,
    val minWriteIntervalMs: Long,
    val protocol: LightstickProtocol,
)

/** Standard GATT battery service — used when [DeviceProfile.notifyCharUuid] is null. */
object StandardGatt {
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
}
