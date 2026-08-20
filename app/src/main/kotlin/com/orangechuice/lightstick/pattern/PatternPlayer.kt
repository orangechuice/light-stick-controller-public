package com.orangechuice.lightstick.pattern

import com.orangechuice.lightstick.ble.WriteGate
import com.orangechuice.lightstick.device.LightState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The single clock in the app.
 *
 * Evaluates whichever [PatternSource] is current and posts the result. Every
 * pattern shares this loop; none of them schedules anything itself.
 *
 * [intervalMs] is deliberately *shorter* than the write gate's interval rather
 * than equal to it. This loop and the gate are independent, so at matching
 * periods a freshly analysed audio frame could wait a full interval here for a
 * tick and another full interval there for the write slot — two rate limits in
 * series, for one rate limit's worth of benefit. The gate conflates and
 * dead-bands, so oversampling it costs CPU and nothing on the radio: a static
 * colour is still one write plus a keepalive every 5 s no matter how fast this
 * runs.
 */
class PatternPlayer(
    private val gate: WriteGate,
    private val intervalMs: Long,
    scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var source: PatternSource = SolidPattern(LightState.OFF)
    @Volatile private var startedAt: Long = nowMs()

    /**
     * Switch patterns.
     *
     * @param restart resets the clock so the new pattern begins at phase zero.
     *   Pass false when swapping in a rebuilt version of the pattern already
     *   playing — nudging the brightness slider during a rainbow should change
     *   the brightness, not snap the hue back to the start of the sweep.
     */
    fun play(pattern: PatternSource, restart: Boolean = true) {
        if (restart) startedAt = nowMs()
        source = pattern
    }

    private val job = scope.launch {
        while (isActive) {
            gate.set(source.tick(nowMs() - startedAt))
            delay(intervalMs)
        }
    }

    fun cancel() {
        job.cancel()
    }
}
