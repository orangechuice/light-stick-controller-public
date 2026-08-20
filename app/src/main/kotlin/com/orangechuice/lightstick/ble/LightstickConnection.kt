package com.orangechuice.lightstick.ble

import android.util.Log
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType as KableWriteType
import com.juul.kable.characteristicOf
import com.orangechuice.lightstick.device.DeviceProfile
import com.orangechuice.lightstick.device.StandardGatt
import com.orangechuice.lightstick.device.WriteType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach

/**
 * One connected lightstick. Wraps a Kable [Peripheral] with the profile's
 * characteristics resolved and the write type it expects.
 *
 * Kable resolves [characteristicOf] lazily against the discovered GATT profile,
 * matching on UUID *and* the property implied by the operation — so a write with
 * [KableWriteType.WithoutResponse] picks the characteristic that actually
 * supports it.
 */
class LightstickConnection(
    private val peripheral: Peripheral,
    private val profile: DeviceProfile,
    val deviceName: String,
    val identifier: String,
) : PacketWriter {

    private val commandCharacteristic = characteristicOf(
        service = profile.serviceUuid.toString(),
        characteristic = profile.commandCharUuid.toString(),
    )

    private val batteryCharacteristic = characteristicOf(
        service = StandardGatt.BATTERY_SERVICE.toString(),
        characteristic = StandardGatt.BATTERY_LEVEL.toString(),
    )

    val state: StateFlow<State> = peripheral.state

    private val _lastPacket = MutableStateFlow<ByteArray?>(null)

    /**
     * The last packet handed to the radio, for the debug panel. This firmware
     * reports nothing about packets it rejects, so seeing the bytes we computed
     * is the only way to tell "the app got the encoding wrong" from "the stick
     * ignored correct bytes".
     */
    val lastPacket: StateFlow<ByteArray?> = _lastPacket.asStateFlow()

    /** The vendor status characteristic, when the device has one instead of 0x180F. */
    private val statusCharacteristic = profile.notifyCharUuid?.let {
        characteristicOf(
            service = profile.serviceUuid.toString(),
            characteristic = it.toString(),
        )
    }

    /**
     * Battery level, from whichever source this device actually has.
     *
     * The Fanlight sticks expose standard Battery Level 0x2A19, which is
     * notify-capable and so is subscribed to rather than polled. TWICE has no
     * 0x180F at all — it reports over
     * its UART notify characteristic — and observing the standard service there
     * throws `NoSuchElementException` on every connect. So the source is chosen by
     * [DeviceProfile.notifyCharUuid] rather than assumed.
     *
     * Kable keeps this flow alive across reconnects.
     */
    val batteryPercent: Flow<Int> =
        if (statusCharacteristic == null) {
            peripheral.observe(batteryCharacteristic).map { it.first().toInt() and 0xFF }
        } else {
            // The query goes in onSubscription, which Kable runs once the CCCD is
            // written — sending it any earlier means the reply lands before there
            // is anything listening for it, and the level never appears.
            peripheral.observe(statusCharacteristic) {
                profile.protocol.statusQuery()?.let { query -> write(query) }
            }
                .onEach { if (LOG_PACKETS) Log.d(TAG, "<- ${it.toHex()}") }
                .mapNotNull { profile.protocol.parseStatus(it)?.batteryPercent }
        }

    /**
     * One-shot read of the current battery level, or null when the device has no
     * readable battery characteristic.
     *
     * Needed on the standard path because notifications only fire when the value
     * *changes* — on a stick sitting at 96% that can be a very long time, during
     * which the subscription alone leaves the readout blank. A vendor status
     * characteristic is notify-only, so there is nothing to read here.
     */
    suspend fun readBatteryPercent(): Int? =
        if (statusCharacteristic != null) null
        else peripheral.read(batteryCharacteristic).firstOrNull()?.toInt()?.and(0xFF)

    suspend fun connect() {
        peripheral.connect()
        if (LOG_GATT_TABLE) dumpGattTable()
        requestFastConnectionInterval()
        // Empty for KATSEYE — no handshake exists. The path is coded anyway
        // because IVE or XG may need one.
        profile.protocol.handshake().forEach { write(it) }
    }

    /**
     * Ask the stack for the shortest connection interval it will give us.
     *
     * Android leaves a fresh link on `CONNECTION_PRIORITY_BALANCED`, which is a
     * 30–50 ms connection interval. A Write Without Response is not sent when it
     * is issued — it waits for the next connection event — so the default alone
     * puts ~25 ms of average latency between a kick drum and the light, with
     * jitter on top that reads as the beat being *uneven* rather than merely
     * late. Worse, [DeviceProfile.minWriteIntervalMs] paces writes at 15 ms, so
     * at the default interval the app hands the stack packets faster than the
     * link can drain them and the lag grows instead of holding steady.
     *
     * `High` asks for 11.25–15 ms, which is also what makes that 15 ms pacing
     * honest rather than aspirational.
     *
     * It is a request, not a guarantee: the peripheral can refuse, and some
     * OEMs quietly revert to balanced after a while. The return value only says
     * the request was accepted for delivery, not that the interval changed —
     * the negotiated value shows up in logcat:
     *
     * ```
     * adb logcat -s BluetoothGatt | grep onConnectionUpdated
     * ```
     *
     * where `interval` is in 1.25 ms units (39 ≈ 49 ms, 12 = 15 ms).
     */
    private fun requestFastConnectionInterval() {
        val android = peripheral as? AndroidPeripheral ?: return
        try {
            val accepted = android.requestConnectionPriority(AndroidPeripheral.Priority.High)
            Log.i(TAG, "connection priority high: accepted=$accepted")
        } catch (e: Throwable) {
            // Not fatal: the light still works, it just answers the music later.
            Log.w(TAG, "could not request a faster connection interval", e)
        }
    }

    /**
     * Log every discovered service and characteristic with its properties.
     *
     * Worth having permanently: on a stick whose profile is a guess, the first
     * question is always whether the characteristic being written even exists,
     * and Kable's own logging stops at "Discovered N services".
     */
    private fun dumpGattTable() {
        Log.i(TAG, "GATT table for $deviceName ($identifier):")
        peripheral.services.orEmpty().forEach { service ->
            Log.i(TAG, "  service ${service.serviceUuid}")
            service.characteristics.forEach { characteristic ->
                Log.i(
                    TAG,
                    "    char ${characteristic.characteristicUuid} props=${characteristic.properties}",
                )
            }
        }
    }

    suspend fun disconnect() {
        peripheral.disconnect()
    }

    override suspend fun write(bytes: ByteArray) {
        peripheral.write(commandCharacteristic, bytes, profile.writeType.toKable())
        _lastPacket.value = bytes
        if (LOG_PACKETS) Log.d(TAG, "-> ${bytes.toHex()}")
    }

    private companion object {
        const val TAG = "Lightstick"

        /**
         * Every outgoing packet is logged as hex. Cheap, and the difference
         * between a five-minute bug and a two-hour one on a device that fails
         * silently.
         */
        const val LOG_PACKETS = true

        /** Dump the full GATT table on connect. See [dumpGattTable]. */
        const val LOG_GATT_TABLE = true
    }
}

fun WriteType.toKable(): KableWriteType = when (this) {
    WriteType.WITH_RESPONSE -> KableWriteType.WithResponse
    WriteType.WITHOUT_RESPONSE -> KableWriteType.WithoutResponse
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
