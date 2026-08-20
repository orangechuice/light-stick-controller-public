package com.orangechuice.lightstick.ble

import com.orangechuice.lightstick.device.LightState
import com.orangechuice.lightstick.device.profiles.KatseyeProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate is the load-bearing piece: conflation, rate limiting and the idle
 * keepalive all live here and nowhere else. Tested with virtual time, so a
 * 60-second keepalive check runs instantly and needs no radio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WriteGateTest {

    private class RecordingWriter : PacketWriter {
        val packets = mutableListOf<ByteArray>()
        override suspend fun write(bytes: ByteArray) {
            packets += bytes
        }
    }

    private val interval = KatseyeProfile.minWriteIntervalMs

    @Test
    fun `writes the state it is given`() = runTest {
        val writer = RecordingWriter()
        val gate = WriteGate(writer, KatseyeProfile, backgroundScope, nowMs = { currentTime })

        gate.set(LightState(255, 0, 0))
        advanceTimeBy(interval + 1)
        runCurrent()

        assertEquals(1, writer.packets.size)
        assertEquals("01FF00FF00000000FF", writer.packets.first().joinToString("") { "%02X".format(it) })
    }

    @Test
    fun `dead-band suppresses an unchanged state`() = runTest {
        val writer = RecordingWriter()
        val gate = WriteGate(writer, KatseyeProfile, backgroundScope, nowMs = { currentTime })

        repeat(20) {
            gate.set(LightState(0, 255, 0))
            advanceTimeBy(interval + 1)
            runCurrent()
        }

        assertEquals("repeated identical states must not re-write", 1, writer.packets.size)
    }

    @Test
    fun `conflates faster-than-interval input to the latest value`() = runTest {
        val writer = RecordingWriter()
        val gate = WriteGate(writer, KatseyeProfile, backgroundScope, nowMs = { currentTime })

        // 100 distinct states posted with no time passing at all: the channel is
        // CONFLATED, so 99 of them are dropped rather than queued. A queue here
        // is what makes the light fall progressively behind its input.
        repeat(100) { i -> gate.set(LightState(i + 1, 0, 0)) }
        advanceTimeBy(interval + 1)
        runCurrent()

        assertEquals(1, writer.packets.size)
        assertEquals(100, writer.packets.last()[3].toInt() and 0xFF)
    }

    @Test
    fun `never writes faster than the profile interval`() = runTest {
        // Stamped inside the writer, not by the caller: sampling after
        // advanceTimeBy would attribute a write to the tick that observed it
        // rather than the tick it happened on.
        val stamps = mutableListOf<Long>()
        val writer = PacketWriter { stamps += currentTime }
        val gate = WriteGate(writer, KatseyeProfile, backgroundScope, nowMs = { currentTime })

        repeat(50) { i ->
            gate.set(LightState(i + 1, 0, 0))
            advanceTimeBy(1)
            runCurrent()
        }

        stamps.zipWithNext { a, b ->
            assertTrue("writes only ${b - a} ms apart", b - a >= interval)
        }
    }

    /**
     * The interval is a floor on the *period*, not a delay bolted onto the end
     * of each write. Sleeping a flat interval after the write made the real
     * period `interval + however long the radio took`, so a link that answered
     * slowly quietly ran the light slower still.
     */
    @Test
    fun `a slow write does not push the next one out by its own duration`() = runTest {
        val writeCost = 10L
        val stamps = mutableListOf<Long>()
        val writer = PacketWriter {
            stamps += currentTime
            delay(writeCost)
        }
        val gate = WriteGate(writer, KatseyeProfile, backgroundScope, nowMs = { currentTime })

        repeat(100) { i ->
            gate.set(LightState(i + 1, 0, 0))
            advanceTimeBy(1)
            runCurrent()
        }

        assertTrue("expected several writes, got ${stamps.size}", stamps.size >= 3)
        stamps.zipWithNext { a, b ->
            assertTrue("writes only ${b - a} ms apart", b - a >= interval)
            assertTrue(
                "writes ${b - a} ms apart — the write's own duration was added to the interval",
                b - a < interval + writeCost,
            )
        }
    }

    /**
     * What the gate writes is what was true when the slot came up, not what was
     * true when it started waiting for one. Without this the light is reliably a
     * full interval behind its input — the interval is spent, so it may as well
     * be spent on the freshest colour.
     */
    @Test
    fun `a state arriving during the wait is written instead of the stale one`() = runTest {
        val writer = RecordingWriter()
        val gate = WriteGate(writer, KatseyeProfile, backgroundScope, nowMs = { currentTime })

        val stale = LightState(10, 0, 0)
        val fresh = LightState(200, 0, 0)

        gate.set(stale)
        runCurrent()
        // Mid-wait: the gate has taken `stale` and is sitting out the interval.
        advanceTimeBy(interval / 2)
        gate.set(fresh)
        advanceTimeBy(interval)
        runCurrent()

        assertEquals(1, writer.packets.size)
        assertEquals(
            "the gate wrote the colour it took before waiting, not the current one",
            200,
            writer.packets.single()[3].toInt() and 0xFF,
        )
    }

    @Test
    fun `keepalive re-sends a static colour before the supervision timeout`() = runTest {
        val writer = RecordingWriter()
        val gate = WriteGate(writer, KatseyeProfile, backgroundScope, nowMs = { currentTime })

        gate.set(LightState(0, 0, 255))
        advanceTimeBy(interval + 1)
        runCurrent()
        assertEquals(1, writer.packets.size)

        // 60 seconds of a user sitting on one colour with no input at all. The
        // link's supervision timeout is 10 s, so silence would drop it.
        advanceTimeBy(60_000)
        runCurrent()

        val expected = 60_000 / WriteGate.KEEPALIVE_MS
        assertTrue(
            "expected ~$expected keepalives, got ${writer.packets.size - 1}",
            writer.packets.size - 1 >= expected - 1,
        )
        assertTrue(
            "keepalive must re-send the same colour",
            writer.packets.all { it.contentEquals(writer.packets.first()) },
        )
        assertTrue(
            "no gap may exceed the 10 s supervision timeout",
            WriteGate.KEEPALIVE_MS < 10_000L,
        )
    }

    @Test
    fun `a failed write is retried rather than remembered as sent`() = runTest {
        var fail = true
        val packets = mutableListOf<ByteArray>()
        val writer = PacketWriter { bytes ->
            if (fail) error("radio busy") else packets += bytes
        }
        val errors = mutableListOf<Throwable>()
        val gate = WriteGate(
            writer,
            KatseyeProfile,
            backgroundScope,
            nowMs = { currentTime },
            onError = { errors += it },
        )

        val red = LightState(255, 0, 0)
        gate.set(red)
        advanceTimeBy(interval + 1)
        runCurrent()
        assertEquals(1, errors.size)
        assertTrue(packets.isEmpty())

        fail = false
        gate.set(red)
        advanceTimeBy(interval + 1)
        runCurrent()

        assertEquals("the same state must go out again after a failure", 1, packets.size)
    }

    @Test
    fun `cancel stops writing`() = runTest {
        val writer = RecordingWriter()
        val gate = WriteGate(writer, KatseyeProfile, backgroundScope, nowMs = { currentTime })

        gate.set(LightState(255, 0, 0))
        advanceTimeBy(interval + 1)
        runCurrent()
        val before = writer.packets.size

        gate.cancel()
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(before, writer.packets.size)
    }
}
