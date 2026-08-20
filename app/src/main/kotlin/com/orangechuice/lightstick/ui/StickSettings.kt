package com.orangechuice.lightstick.ui

import android.content.SharedPreferences
import com.orangechuice.lightstick.pattern.MusicMode

/**
 * The controls a stick was left on, kept across disconnects.
 *
 * Stored per device identifier rather than globally: two sticks connected at
 * once are controlled independently, so remembering one set of controls for all
 * of them would hand the wrong colour to whichever one reconnected second.
 *
 * Only the per-stick controls live here. Sensitivity and auto-sensitivity are
 * app-wide rather than per-stick, and the last-used address has its own keys.
 */
data class StickSettings(
    val hue: Float = 0f,
    val saturation: Float = 1f,
    val brightness: Int = 255,
    val pattern: PatternChoice = PatternChoice.MANUAL,
    val musicMode: MusicMode = MusicMode.PULSE,
) {
    fun encode(): String = listOf(
        FORMAT_VERSION,
        hue.toString(),
        saturation.toString(),
        brightness.toString(),
        pattern.name,
        musicMode.name,
    ).joinToString(SEPARATOR)

    companion object {
        val DEFAULT = StickSettings()

        private const val FORMAT_VERSION = "1"
        private const val SEPARATOR = "|"

        /**
         * Null for anything this build cannot read — absent, truncated, or from a
         * format version it does not know — which the caller turns into [DEFAULT].
         *
         * Within a record it knows, each field falls back on its own: an enum
         * constant added by a later build lands on one unrecognised name, and
         * losing the colour along with it would be a worse answer than keeping it.
         */
        fun decode(raw: String?): StickSettings? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(SEPARATOR)
            if (parts.size != 6 || parts[0] != FORMAT_VERSION) return null

            return StickSettings(
                hue = parts[1].toFloatOrNull()?.let { ((it % 360f) + 360f) % 360f } ?: DEFAULT.hue,
                saturation = parts[2].toFloatOrNull()?.coerceIn(0f, 1f) ?: DEFAULT.saturation,
                brightness = parts[3].toIntOrNull()?.coerceIn(0, 255) ?: DEFAULT.brightness,
                pattern = enumValues<PatternChoice>().firstOrNull { it.name == parts[4] } ?: DEFAULT.pattern,
                musicMode = enumValues<MusicMode>().firstOrNull { it.name == parts[5] } ?: DEFAULT.musicMode,
            )
        }
    }
}

/** Per-device [StickSettings], keyed by the identifier the connection uses. */
class StickSettingsStore(private val prefs: SharedPreferences) {

    fun load(identifier: String): StickSettings =
        StickSettings.decode(prefs.getString(key(identifier), null)) ?: StickSettings.DEFAULT

    fun save(identifier: String, settings: StickSettings) {
        prefs.edit().putString(key(identifier), settings.encode()).apply()
    }

    fun clear(identifier: String) {
        prefs.edit().remove(key(identifier)).apply()
    }

    private fun key(identifier: String) = "$KEY_PREFIX$identifier"

    private companion object {
        const val KEY_PREFIX = "stick_settings_"
    }
}
