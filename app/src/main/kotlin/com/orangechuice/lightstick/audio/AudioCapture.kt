package com.orangechuice.lightstick.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The microphone as a flow of fixed-size frames.
 *
 * Two details decide whether reactive mode feels alive or mushy, and both are
 * here rather than in the analysis:
 *
 *  - **[MediaRecorder.AudioSource.UNPROCESSED]**, but only where the device
 *    declares it actually works. The default `MIC` source runs automatic gain
 *    control and noise suppression, which flatten exactly the dynamics being
 *    detected — and yet UNPROCESSED cannot simply be preferred blind. See
 *    [preferredSources].
 *  - **AGC / NS / AEC explicitly disabled** on the session where the effects
 *    exist. Some devices attach them regardless of the source.
 *
 * Caller must hold `RECORD_AUDIO`; that is checked at the call site, not here.
 *
 * [sampleRate] should come from [nativeSampleRate] rather than being chosen —
 * see there for why picking a round number costs latency.
 */
class AudioCapture(
    private val context: Context,
    private val sampleRate: Int = FALLBACK_SAMPLE_RATE,
    /**
     * Samples per emission. This is the analyser's *hop*, not its window: the
     * analyser keeps its own history, so this only sets how often it is handed
     * new audio, and every frame of it is latency before analysis even begins.
     */
    private val frameSize: Int = 1024,
) {
    private val frameMs = frameSize * 1000f / sampleRate

    /** Time-based, so changing [frameSize] does not silently shorten the probe. */
    private val warmupFrames = ceil(WARMUP_MS / frameMs).toInt().coerceAtLeast(1)
    private val probeFrames = ceil(PROBE_MS / frameMs).toInt().coerceAtLeast(1)

    /**
     * Cold flow — one [AudioRecord] per collection, released when collection ends.
     *
     * Runs on a thread of its own rather than on `Dispatchers.IO`, for reasons
     * that are all about jitter rather than throughput. A pool thread cannot be
     * given audio priority, because the priority would outlive this flow on a
     * thread the pool is about to hand to someone else; and a pool thread can
     * sit behind another coroutine's disk read, which is exactly the stall that
     * turns a steady beat into an uneven one. [channelFlow] is what makes that
     * possible: a plain `flow` may only emit from the context it was collected
     * in, so the capture would have to hop back to the collector's thread on
     * every frame.
     */
    @SuppressLint("MissingPermission")
    fun frames(): Flow<ShortArray> = channelFlow {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            // Daemon: a capture thread must never be the reason the process
            // refuses to exit.
            Thread(runnable, "lightstick-audio").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        try {
            withContext(dispatcher) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                capture()
            }
        } finally {
            dispatcher.close()
        }
    }
        // The channel between the capture thread and the analyser. Its default
        // depth of 64 is a latency trap: one stalled collector and the analyser
        // spends the next second working through a backlog, permanently behind.
        // Small enough to ride out a hiccup, small enough that it cannot hide one.
        .buffer(BACKLOG_FRAMES)

    @SuppressLint("MissingPermission")
    private suspend fun ProducerScope<ShortArray>.capture() {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "AudioRecord rejected ${sampleRate}Hz mono 16-bit" }

        // Android's own minimum in practice, now that frames are hop-sized.
        // That is the intent rather than an accident: the buffer is the depth of
        // audio allowed to pile up ahead of the reader, so every byte over the
        // minimum is latency the analyser can inherit after a scheduling hiccup.
        // The consumer here is one FFT, comfortably faster than real time, so it
        // has no use for the slack a heavier one would need.
        val bufferBytes = maxOf(minBuffer, frameSize * 2 * BUFFER_FRAMES)

        val record = openBestSource(bufferBytes)

        // Keeping these costs nothing in latency, which is worth writing down
        // because the opposite looked plausible. An effect chain on a session is
        // a known way to be denied the fast capture path, and `dumpsys
        // media.audio_flinger` did show exactly three effects on our session —
        // these three — next to `Flags 0x000` and a 20 ms HAL burst. Detaching
        // them was measured: the session went to zero effects, and capture
        // latency stayed at 17.7 ms against a baseline of 18.3. The reason is
        // one line up in the same dump — `Fast capture thread: no` and `Fast
        // track available: no` are properties of the record thread itself, so
        // there is no fast path on this input for any client to be denied.
        val effects = disableProcessing(record.audioSessionId)

        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "microphone did not start — another app may be holding it"
            }

            val latency = CaptureLatency(record, sampleRate)
            val frame = ShortArray(frameSize)
            var framesRead = 0L
            while (currentCoroutineContext().isActive) {
                var filled = 0
                while (filled < frameSize) {
                    val read = record.read(frame, filled, frameSize - filled)
                    if (read <= 0) error("microphone read failed ($read)")
                    filled += read
                }
                framesRead += frameSize
                latency.sample(framesRead)
                send(frame.copyOf())
            }
        } finally {
            // Runs on cancellation too; a leaked AudioRecord holds the mic for
            // every other app on the phone until the process dies.
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop failed", e)
            }
            record.release()
            effects.forEach { it.release() }
        }
    }

    /**
     * Sources to try, in order of preference.
     *
     * `UNPROCESSED` is only listed when [AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED]
     * says the device supports it — and that check is not optional bookkeeping.
     * Constructing an `AudioRecord` with an unsupported `UNPROCESSED` source
     * succeeds, starts, and returns frames forever; the frames are just silence.
     * Measured on this hardware: an rms of 0.00005, about 1.6 counts of a 16-bit
     * sample, with music playing loudly in the room. Nothing about that failure
     * is visible from the AudioRecord API — it looks exactly like a quiet room.
     *
     * `CAMCORDER` next, ahead of the voice paths, and the reason is headroom
     * rather than fidelity. It is the source Android intends for recording loud
     * scenes, so it is the one least likely to be running enough analog gain to
     * rail against full scale. `VOICE_RECOGNITION` is tuned for speech at
     * conversational level: excellent in a living room, and the first to clip at
     * a concert. Clipping is the one degradation nothing downstream can undo —
     * flat peaks mean no transients, and no transients means no beats — so the
     * loud case gets priority over the quiet one, which adaptive gain already
     * handles for free.
     */
    private fun preferredSources(): List<Int> = buildList {
        val supportsUnprocessed = try {
            context.getSystemService(AudioManager::class.java)
                ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        } catch (e: Throwable) {
            Log.w(TAG, "could not query unprocessed support", e)
            false
        }

        Log.i(TAG, "microphone: unprocessed supported = $supportsUnprocessed")
        if (supportsUnprocessed) add(MediaRecorder.AudioSource.UNPROCESSED)
        add(MediaRecorder.AudioSource.CAMCORDER)
        add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        add(MediaRecorder.AudioSource.MIC)
    }

    /**
     * Picks a source by listening to each one, not by trusting that it opened.
     *
     * The `UNPROCESSED` failure this guards against cost most of a debugging
     * session: the source opened, started, and delivered frames indefinitely,
     * and every one was silence. No API call reports that. The only way to know
     * a capture path works is to read from it and look at what comes back.
     *
     * The comparison is deliberately relative — each candidate against the
     * loudest candidate — because an absolute "is this loud enough" threshold is
     * exactly the kind of constant that cannot be set without knowing the room.
     * A dead path sits 40 dB or more below a live one, which no amount of quiet
     * accounts for, while two working sources land within a few dB of each other
     * and preference order breaks the tie.
     */
    @SuppressLint("MissingPermission")
    private fun openBestSource(bufferBytes: Int): AudioRecord {
        val candidates = preferredSources()
        val measured = candidates.mapNotNull { source ->
            probe(source, bufferBytes)?.let { level -> source to level }
        }

        if (measured.isEmpty()) {
            // Nothing could be probed. Rather than give up, take whatever opens:
            // a source we cannot measure still beats no music sync at all.
            return candidates.firstNotNullOfOrNull { open(it, bufferBytes) }
                ?: error("Could not open the microphone")
        }

        val loudest = measured.maxOf { it.second }

        // A probe run in a quiet room proves nothing. A dead path and a live one
        // listening to an empty room differ by around 16 dB, which is inside the
        // margin below, so the comparison could confidently choose the dead one.
        // With nothing audible to compare, fall back to plain preference order —
        // no worse than not probing, and the case that matters most for this
        // feature, a loud room, is exactly the case where the probe is decisive.
        val confident = loudest >= PROBE_CONFIDENCE

        val chosen = when {
            !confident -> measured.first().first
            else -> measured.firstOrNull { it.second >= loudest * LIVE_SOURCE_RATIO }?.first
                ?: measured.first().first
        }

        Log.i(
            TAG,
            "microphone: probed " +
                measured.joinToString { "source ${it.first}=%.5f".format(it.second) } +
                " -> chose source $chosen" +
                if (confident) "" else " (room too quiet to compare; used preference order)",
        )

        return open(chosen, bufferBytes)
            ?: candidates.firstNotNullOfOrNull { open(it, bufferBytes) }
            ?: error("Could not open the microphone")
    }

    /** @return rms of a short listen, or null if the source could not be read. */
    private fun probe(source: Int, bufferBytes: Int): Float? {
        val record = open(source, bufferBytes) ?: return null
        return try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return null

            val frame = ShortArray(frameSize)
            // Discarded: the first frames after a start are routinely zeros while
            // the path spins up, which would make every source look dead.
            repeat(warmupFrames) { if (!readFully(record, frame)) return null }

            var sumSquares = 0.0
            repeat(probeFrames) {
                if (!readFully(record, frame)) return null
                for (sample in frame) {
                    val value = sample / 32_768f
                    sumSquares += (value * value).toDouble()
                }
            }
            sqrt(sumSquares / (probeFrames.toLong() * frameSize)).toFloat()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "could not probe audio source $source", e)
            null
        } finally {
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "probe stop failed", e)
            }
            record.release()
        }
    }

    private fun readFully(record: AudioRecord, frame: ShortArray): Boolean {
        var filled = 0
        while (filled < frame.size) {
            val read = record.read(frame, filled, frame.size - filled)
            if (read <= 0) return false
            filled += read
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun open(source: Int, bufferBytes: Int): AudioRecord? = try {
        val record = AudioRecord(
            source,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (record.state == AudioRecord.STATE_INITIALIZED) {
            Log.i(TAG, "microphone: opened audio source $source")
            record
        } else {
            Log.w(TAG, "audio source $source did not initialise")
            record.release()
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "audio source $source unavailable", e)
        null
    }

    /** Kept so they can be released; an effect that is GC'd re-enables itself. */
    private fun disableProcessing(sessionId: Int): List<AudioEffect> = buildList {
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.let { it.enabled = false; add(it) }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let { it.enabled = false; add(it) }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.let { it.enabled = false; add(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Not fatal: the mode still works, it just reads a little flatter.
            Log.w(TAG, "could not disable mic processing", e)
        }
    }

    /**
     * How old a sample already is by the time the app is handed it.
     *
     * This is the one term in the latency budget that no amount of restructuring
     * above it can touch, and the only one that cannot be reasoned about from
     * the code: it is the microphone, the HAL, and whatever buffering sits
     * between them. On this hardware `dumpsys media.audio_flinger` reports a HAL
     * frame count of 960 at 48 kHz — audio arrives in 20 ms bursts — and the
     * record track carries `Flags 0x000`, meaning the ordinary capture path
     * rather than the fast one. That sets an expectation; this measures it.
     *
     * The arithmetic is a two-point extrapolation. [AudioRecord.getTimestamp]
     * reports that one specific frame was captured at one specific instant, and
     * frames arrive at a known rate, so the capture time of the newest frame in
     * hand follows — and the difference between that and now is the answer.
     *
     * Reported rather than acted on. It exists to decide whether rewriting
     * capture on AAudio in low-latency mode is worth the native dependency,
     * which is a question about a number nobody has yet.
     */
    private class CaptureLatency(
        private val record: AudioRecord,
        private val sampleRate: Int,
    ) {
        private val timestamp = AudioTimestamp()
        private var reportAt = 0L
        private var min = Long.MAX_VALUE
        private var max = Long.MIN_VALUE
        private var total = 0L
        private var count = 0
        private var unsupported = false

        fun sample(framesRead: Long) {
            if (unsupported) return

            val ok = try {
                record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) ==
                    AudioRecord.SUCCESS
            } catch (e: Throwable) {
                Log.w(TAG, "capture timestamps unavailable", e)
                unsupported = true
                return
            }
            // Not fatal and not necessarily permanent — the first calls after a
            // start routinely fail while the path spins up.
            if (!ok) return

            val now = System.nanoTime()
            if (reportAt == 0L) reportAt = now + REPORT_INTERVAL_NS

            // `framePosition` counts from the same origin as [framesRead] — the
            // start of recording — so their difference is how far the driver has
            // run ahead of the reader, in frames, and dividing by the rate turns
            // that into the age of what we are holding.
            val drift = (framesRead - timestamp.framePosition) * NANOS_PER_SECOND / sampleRate
            val capturedAt = timestamp.nanoTime + drift
            val latency = now - capturedAt

            // A negative reading means the two counters do not share an origin,
            // which happens after an overrun drops frames the reader never saw.
            // Averaging it in would quietly flatter the result.
            if (latency < 0) return

            if (latency < min) min = latency
            if (latency > max) max = latency
            total += latency
            count++

            if (now >= reportAt) {
                Log.i(
                    TAG,
                    "microphone: capture latency min=%.1f mean=%.1f max=%.1f ms over %d frames"
                        .format(min / 1e6, total / count / 1e6, max / 1e6, count),
                )
                reportAt = now + REPORT_INTERVAL_NS
                min = Long.MAX_VALUE
                max = Long.MIN_VALUE
                total = 0
                count = 0
            }
        }

        private companion object {
            const val NANOS_PER_SECOND = 1_000_000_000L
            const val REPORT_INTERVAL_NS = 5_000_000_000L
        }
    }

    companion object {
        /**
         * The rate the input hardware actually runs at.
         *
         * Asking for a round 44,100 Hz on a device whose audio path runs at
         * 48,000 — which is nearly all of them — silently inserts a resampler,
         * and disqualifies the capture from the fast path Android reserves for
         * clients that match the native configuration. Both cost latency, and
         * neither is visible from the [AudioRecord] API: it opens, it starts,
         * and it returns perfectly good frames, just later than it had to.
         *
         * The result must be handed to [AudioCapture] *and* to [AudioAnalyzer],
         * which derives its bin ranges from it — a mismatch there would move
         * every band edge.
         */
        fun nativeSampleRate(context: Context): Int {
            val reported = try {
                context.getSystemService(AudioManager::class.java)
                    ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                    ?.toIntOrNull()
            } catch (e: Throwable) {
                Log.w(TAG, "could not query the native sample rate", e)
                null
            }

            // The property describes the output path. Input almost always
            // matches, but "almost always" is not a thing to open a mic on, and
            // getMinBufferSize is the cheap way to ask whether AudioRecord will
            // actually take it — it returns an error code rather than a size for
            // a configuration the device does not support.
            val usable = reported?.takeIf {
                AudioRecord.getMinBufferSize(
                    it,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ) > 0
            }

            val chosen = usable ?: FALLBACK_SAMPLE_RATE
            Log.i(TAG, "microphone: native rate ${reported ?: "unknown"}, using $chosen")
            return chosen
        }

        /** Every device supports this, which is the only reason to fall back to it. */
        const val FALLBACK_SAMPLE_RATE = 44_100

        private const val TAG = "Lightstick"
        private const val BUFFER_FRAMES = 4

        /** Frames the thread handoff may run ahead by before it is backpressured. */
        private const val BACKLOG_FRAMES = 4

        /** Discarded after `startRecording` before a probe believes anything. */
        private const val WARMUP_MS = 120f

        /** How long a probe listens. The whole scan costs well under a second. */
        private const val PROBE_MS = 120f

        /**
         * How far below the loudest candidate a source may sit and still count as
         * live. Two working sources differ by a few dB; a dead one by forty.
         */
        private const val LIVE_SOURCE_RATIO = 0.1f

        /**
         * There must be this much sound in the room before one source's reading
         * is evidence about another. Measured references: a dead capture path
         * reads 0.00005, and music in a living room read 0.008 to 0.04.
         */
        private const val PROBE_CONFIDENCE = 0.002f
    }
}
