package com.orangechuice.lightstick.device

/**
 * The complete desired state of a lightstick at one instant.
 *
 * Colour and brightness travel together because on some devices — KATSEYE among
 * them — there is no hardware brightness command and brightness is folded into
 * RGB by the protocol. See [LightstickProtocol.encode].
 *
 * Components are 0..255 and are coerced into range on construction, so a pattern
 * that overshoots cannot produce a malformed packet.
 */
data class LightState(
    val r: Int,
    val g: Int,
    val b: Int,
    val brightness: Int = 255,
) {
    init {
        require(r in 0..255) { "r out of range: $r" }
        require(g in 0..255) { "g out of range: $g" }
        require(b in 0..255) { "b out of range: $b" }
        require(brightness in 0..255) { "brightness out of range: $brightness" }
    }

    /**
     * The same colour with its hue turned [degrees] around the wheel, keeping
     * saturation, value and [brightness].
     *
     * A fully desaturated colour has no hue to turn, so white and grey come back
     * unchanged. That is the deliberate choice over inverting the channels
     * directly, which is the other obvious way to get an "opposite": channel
     * inversion maps white onto black, which would turn a mode that alternates
     * two colours into one that switches the light off every other beat.
     */
    fun hueRotated(degrees: Float): LightState {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f

        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val span = max - min

        // Grey: no hue to rotate. Also the only case where the divisions below
        // would be by zero.
        if (span == 0f) return this

        // Ties for max are fine wherever they land: a tie puts the hue exactly on
        // a primary or secondary boundary, where both candidate branches agree.
        val hue = when (max) {
            rf -> 60f * ((gf - bf) / span)
            gf -> 60f * ((bf - rf) / span + 2f)
            else -> 60f * ((rf - gf) / span + 4f)
        }

        // fromHsv wraps its hue, so a negative sum here needs no correction.
        return fromHsv(hue + degrees, span / max, max, brightness)
    }

    companion object {
        val OFF = LightState(0, 0, 0)

        /** Clamping factory for pattern sources, which compute in floats. */
        fun of(r: Int, g: Int, b: Int, brightness: Int = 255) = LightState(
            r.coerceIn(0, 255),
            g.coerceIn(0, 255),
            b.coerceIn(0, 255),
            brightness.coerceIn(0, 255),
        )

        /**
         * @param h hue in degrees, wrapped into 0..360
         * @param s saturation 0f..1f
         * @param v value 0f..1f
         */
        fun fromHsv(h: Float, s: Float, v: Float, brightness: Int = 255): LightState {
            val hue = ((h % 360f) + 360f) % 360f
            val sat = s.coerceIn(0f, 1f)
            val value = v.coerceIn(0f, 1f)

            val c = value * sat
            val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
            val m = value - c

            val (r1, g1, b1) = when ((hue / 60f).toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }

            return of(
                ((r1 + m) * 255f).toInt(),
                ((g1 + m) * 255f).toInt(),
                ((b1 + m) * 255f).toInt(),
                brightness,
            )
        }
    }
}
