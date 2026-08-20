package com.orangechuice.lightstick.device

import com.orangechuice.lightstick.device.profiles.AespaProfile
import com.orangechuice.lightstick.device.profiles.IveProfile
import com.orangechuice.lightstick.device.profiles.KatseyeProfile
import com.orangechuice.lightstick.device.profiles.TwiceProfile
import com.orangechuice.lightstick.device.profiles.XgProfile

/**
 * Registry of all supported lightstick device profiles.
 */
object DeviceRegistry {
    val ALL_PROFILES: List<DeviceProfile> = listOf(
        KatseyeProfile,
        XgProfile,
        IveProfile,
        TwiceProfile,
        AespaProfile,
    )

    fun findProfileForName(name: String?): DeviceProfile? {
        if (name == null) return null
        return ALL_PROFILES.firstOrNull { it.advertisedNamePattern.containsMatchIn(name) }
    }

    fun findProfileById(id: String?): DeviceProfile? {
        if (id == null) return null
        return ALL_PROFILES.firstOrNull { it.id == id }
    }
}
