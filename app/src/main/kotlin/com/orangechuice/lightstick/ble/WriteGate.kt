package com.orangechuice.lightstick.ble

import com.orangechuice.lightstick.device.DeviceProfile
import com.orangechuice.lightstick.device.LightState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A single coroutine owns the command characteristic. Everything upstream — the
 * colour picker, the pattern player, and later the audio pipeline — posts
 * *desired state*; the gate alone decides what actually goes on the wire.
 *
 * Three things happen here and nowhere else:
 *
 *  - **Conflation.** The channel is [Channel.CONFLATED]: latest value wins, and
 *    writes are never queued. A backed-up queue means the light falls
 *    progressively further behind its input, which is the single failure mode
 *    that makes reactive mode feel broken.
 *  - **Rate limiting.** [DeviceProfile.minWriteIntervalMs] is enforced here only.
 *  - **Idle keepalive.** The stick's supervision timeout multiplier is 1000, so
 *    an idle link is torn down after 10 s. Re-sending the current state every
 *    [KEEPALIVE_MS] keeps a user sitting on a static colour connected. The
 *    `withTimeoutOrNull` below is what implements that; it is not decoration.
 */
class WriteGate(
    private val writer: PacketWriter,
    private val profile: DeviceProfile,
    scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = {},
) {
    private val desired = Channel<LightState>(Channel.CONFLATED)

    /** Never suspends, never queues. Safe to call from any thread, at any rate. */
    fun set(state: LightState) {
        desired.trySend(state)
    }

    private val job = scope.launch {
        var last: LightState? = null
        var lastWriteAt = 0L
        while (isActive) {
            // Returns null on timeout — that is the keepalive tick, not an error.
            var state = withTimeoutOrNull(KEEPALIVE_MS) { desired.receive() } ?: last ?: continue

            val stale = nowMs() - lastWriteAt >= KEEPALIVE_MS

            // Dead-band. Straight back to waiting rather than burning an
            // interval on a state the stick already has — the pattern player
            // ticks faster than this loop writes, so most arrivals are
            // unchanged and must not cost anything.
            if (state == last && !stale) continue

            // Paced from when the last write *started*, not from when it
            // returned. Sleeping a flat interval after each write made the real
            // period `interval + however long the radio took`, which at 15 ms
            // nominal is appreciably more than 15 ms.
            val wait = profile.minWriteIntervalMs - (nowMs() - lastWriteAt)
            if (wait > 0) {
                delay(wait)
                // Something newer may have landed while we waited, and the
                // channel is conflated so at most one value is buffered and it
                // is the freshest. Taking it here is up to a full interval of
                // latency that would otherwise be spent writing a stale colour.
                desired.tryReceive().getOrNull()?.let { state = it }
            }

            // Stamped before the attempt, and kept on failure: a write that
            // threw still occupied the radio, and without this a persistently
            // failing link is retried as fast as the producer can post.
            lastWriteAt = nowMs()
            try {
                writer.write(profile.protocol.encode(state))
                last = state
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Forget `last` so the dead-band does not suppress the retry:
                // a failed write must not be remembered as one that landed.
                last = null
                onError(e)
            }
        }
    }

    /** Stops the gate. Called on disconnect; the gate is not reusable afterwards. */
    fun cancel() {
        job.cancel()
        desired.close()
    }

    companion object {
        const val KEEPALIVE_MS = 5_000L
    }
}
