package com.orangechuice.lightstick.ble

import com.juul.kable.Advertisement
import com.juul.kable.Scanner
import com.orangechuice.lightstick.device.DeviceProfile
import com.orangechuice.lightstick.device.DeviceRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold

/** A peripheral seen during a scan, plus whether it looks like a known lightstick device. */
data class DiscoveredDevice(
    val identifier: String,
    val name: String?,
    val rssi: Int,
    val matchesProfile: Boolean,
    val matchedProfile: DeviceProfile?,
    val advertisement: Advertisement,
)

/**
 * Scans for lightsticks matching any supported [DeviceProfile].
 */
class LightstickScanner(
    private val profiles: List<DeviceProfile> = DeviceRegistry.ALL_PROFILES,
) {

    private val scanner = Scanner {
        filters = null
    }

    /** Emits the accumulated device list, newest information winning, sorted by signal. */
    fun scan(): Flow<List<DiscoveredDevice>> = scanner.advertisements
        .map { advertisement ->
            val matched = advertisement.name?.let { name ->
                profiles.firstOrNull { it.advertisedNamePattern.containsMatchIn(name) }
            }
            DiscoveredDevice(
                identifier = advertisement.identifier.toString(),
                name = advertisement.name,
                rssi = advertisement.rssi,
                matchesProfile = matched != null,
                matchedProfile = matched,
                advertisement = advertisement,
            )
        }
        .runningFold(emptyMap<String, DiscoveredDevice>()) { acc, device ->
            acc + (device.identifier to device)
        }
        .map { devices ->
            devices.values.sortedWith(
                compareByDescending<DiscoveredDevice> { it.matchesProfile }.thenByDescending { it.rssi },
            )
        }
}
