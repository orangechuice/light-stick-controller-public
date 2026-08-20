package com.orangechuice.lightstick.pattern

import com.orangechuice.lightstick.device.LightState
import kotlin.math.cos

/**
 * A pattern is a pure function of elapsed time.
 *
 * Patterns do not schedule anything and do not own a timer — playback cadence
 * belongs to [PatternPlayer], which ticks at the write gate's interval. Keeping
 * them pure is what makes timing testable without a device, and what lets the
 * audio-reactive source slot in as just another implementation.
 */
interface PatternSource {
    /** @param timeMs milliseconds since this pattern started playing. */
    fun tick(timeMs: Long): LightState
}

/** A fixed colour. Also what manual colour-picker control runs through. */
class SolidPattern(private val state: LightState) : PatternSource {
    override fun tick(timeMs: Long): LightState = state
}

/** Smooth fade in and out. One full breath per [periodMs]. */
class BreathingPattern(
    private val color: LightState,
    private val periodMs: Long = 3_000L,
    private val minBrightness: Int = 0,
) : PatternSource {
    override fun tick(timeMs: Long): LightState {
        val phase = phaseOf(timeMs, periodMs)
        // (1 - cos) / 2: starts dark, peaks mid-cycle, returns to dark.
        val curve = (1.0 - cos(phase * 2.0 * Math.PI)) / 2.0
        val span = color.brightness - minBrightness
        return color.copy(brightness = (minBrightness + span * curve).toInt().coerceIn(0, 255))
    }
}

/** Full hue sweep, one revolution per [periodMs]. */
class RainbowPattern(
    private val periodMs: Long = 5_000L,
    private val saturation: Float = 1f,
    private val brightness: Int = 255,
) : PatternSource {
    override fun tick(timeMs: Long): LightState =
        LightState.fromHsv(
            h = phaseOf(timeMs, periodMs).toFloat() * 360f,
            s = saturation,
            v = 1f,
            brightness = brightness,
        )
}

/**
 * Hard on/off. [dutyCycle] is the fraction of each period spent lit.
 *
 * Double rather than Float so the comparison against [phaseOf] happens at one
 * precision — a Float duty widens to a slightly *larger* Double and lights the
 * boundary tick that should have been dark.
 */
class StrobePattern(
    private val color: LightState,
    private val periodMs: Long = 200L,
    private val dutyCycle: Double = 0.5,
) : PatternSource {
    override fun tick(timeMs: Long): LightState =
        if (phaseOf(timeMs, periodMs) < dutyCycle.coerceIn(0.0, 1.0)) color else LightState.OFF
}

data class Keyframe(val timeMs: Long, val state: LightState)

/**
 * An editable timeline of (timestamp, colour, brightness) points, linearly
 * interpolated between them.
 *
 * With [loop] set, the last keyframe interpolates back around to the first over
 * the remainder of [durationMs], so the cycle joins seamlessly.
 */
class KeyframePattern(
    keyframes: List<Keyframe>,
    private val durationMs: Long,
    private val loop: Boolean = true,
) : PatternSource {

    private val frames = keyframes.sortedBy { it.timeMs }

    init {
        require(frames.isNotEmpty()) { "a keyframe timeline needs at least one frame" }
        require(durationMs > 0) { "durationMs must be positive" }
    }

    override fun tick(timeMs: Long): LightState {
        if (frames.size == 1) return frames.first().state

        val t = if (loop) {
            (timeMs % durationMs + durationMs) % durationMs
        } else {
            timeMs.coerceIn(0L, durationMs)
        }

        val nextIndex = frames.indexOfFirst { it.timeMs > t }

        // Before the first keyframe, or past the last one without looping.
        if (nextIndex == 0) return frames.first().state
        if (nextIndex == -1) {
            if (!loop) return frames.last().state
            // Wrap: interpolate from the last frame back to the first.
            val from = frames.last()
            val span = (durationMs - from.timeMs) + frames.first().timeMs
            if (span <= 0) return frames.first().state
            return lerp(from.state, frames.first().state, (t - from.timeMs).toDouble() / span)
        }

        val from = frames[nextIndex - 1]
        val to = frames[nextIndex]
        val span = (to.timeMs - from.timeMs).toDouble()
        return if (span <= 0.0) to.state else lerp(from.state, to.state, (t - from.timeMs) / span)
    }

    private fun lerp(a: LightState, b: LightState, f: Double): LightState {
        val k = f.coerceIn(0.0, 1.0)
        fun mix(x: Int, y: Int) = (x + (y - x) * k).toInt()
        return LightState.of(
            mix(a.r, b.r),
            mix(a.g, b.g),
            mix(a.b, b.b),
            mix(a.brightness, b.brightness),
        )
    }
}

/**
 * Phase within one cycle, always in `[0, 1)`.
 *
 * Computed from `timeMs % periodMs` rather than by accumulating per-tick deltas,
 * so a pattern's period cannot drift no matter how irregular the tick cadence is
 * — the same fixed-period property a hardware rate test checks, and for the same
 * reason: on a device that fails silently, a cycle stretching past its nominal
 * period is the only readable evidence that writes are being dropped.
 */
internal fun phaseOf(timeMs: Long, periodMs: Long): Double {
    if (periodMs <= 0L) return 0.0
    return ((timeMs % periodMs) + periodMs) % periodMs / periodMs.toDouble()
}
