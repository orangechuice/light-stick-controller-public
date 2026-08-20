package com.orangechuice.lightstick.ble

/**
 * The one thing [WriteGate] needs from a connection.
 *
 * Narrow on purpose: it lets the gate — the load-bearing piece of the whole app —
 * be unit-tested with virtual time and no BLE stack at all.
 */
fun interface PacketWriter {
    suspend fun write(bytes: ByteArray)
}
