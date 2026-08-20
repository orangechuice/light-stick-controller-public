package com.orangechuice.lightstick.ui

import android.app.Application
import android.bluetooth.BluetoothManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.juul.kable.ConnectionLostException
import com.juul.kable.PeripheralBuilder
import com.juul.kable.State
import com.juul.kable.logs.Logging
import com.juul.kable.peripheral
import com.orangechuice.lightstick.audio.AudioAnalysis
import com.orangechuice.lightstick.audio.AudioAnalyzer
import com.orangechuice.lightstick.audio.AudioCapture
import com.orangechuice.lightstick.audio.MusicSyncService
import com.orangechuice.lightstick.ble.DiscoveredDevice
import com.orangechuice.lightstick.ble.LightstickConnection
import com.orangechuice.lightstick.ble.LightstickScanner
import com.orangechuice.lightstick.ble.WriteGate
import com.orangechuice.lightstick.ble.toHex
import com.orangechuice.lightstick.device.DeviceProfile
import com.orangechuice.lightstick.device.DeviceRegistry
import com.orangechuice.lightstick.device.LightState
import com.orangechuice.lightstick.device.profiles.KatseyeProfile
import com.orangechuice.lightstick.pattern.BreathingPattern
import com.orangechuice.lightstick.pattern.Keyframe
import com.orangechuice.lightstick.pattern.KeyframePattern
import com.orangechuice.lightstick.pattern.MusicMode
import com.orangechuice.lightstick.pattern.MusicPattern
import com.orangechuice.lightstick.pattern.PatternPlayer
import com.orangechuice.lightstick.pattern.PatternSource
import com.orangechuice.lightstick.pattern.RainbowPattern
import com.orangechuice.lightstick.pattern.SolidPattern
import com.orangechuice.lightstick.pattern.StrobePattern
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PatternChoice(
    val label: String,
    val usesColor: Boolean = true,
) {
    MANUAL("Manual"),
    BREATHING("Breathe"),
    RAINBOW("Rainbow", usesColor = false),
    STROBE("Strobe"),
    TIMELINE("Timeline", usesColor = false),
    MUSIC("Sync to music"),
}

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

data class BondedDevice(
    val address: String,
    val name: String,
    val profile: DeviceProfile? = null,
)

data class AudioLevels(
    val bass: Float = 0f,
    val mid: Float = 0f,
    val treble: Float = 0f,
    val level: Float = 0f,
    val silent: Boolean = true,
    val beatAt: Long = 0L,
    val clippingAt: Long = 0L,
    val listening: Boolean = false,
)

data class ConnectedStickState(
    val identifier: String,
    val name: String,
    val profile: DeviceProfile,
    val phase: ConnectionPhase = ConnectionPhase.CONNECTED,
    val batteryPercent: Int? = null,
    val lastPacketHex: String? = null,
    val hue: Float = 0f,
    val saturation: Float = 1f,
    val brightness: Int = 255,
    val pattern: PatternChoice = PatternChoice.MANUAL,
    val musicMode: MusicMode = MusicMode.PULSE,
) {
    val color: LightState get() = LightState.fromHsv(hue, saturation, 1f, brightness)

    val colorApplies: Boolean
        get() = when (pattern) {
            PatternChoice.MUSIC -> musicMode.usesColor
            else -> pattern.usesColor
        }

    val colorOwnerLabel: String
        get() = if (pattern == PatternChoice.MUSIC) musicMode.label else pattern.label

    val settings: StickSettings
        get() = StickSettings(
            hue = hue,
            saturation = saturation,
            brightness = brightness,
            pattern = pattern,
            musicMode = musicMode,
        )
}

data class UiState(
    val scanning: Boolean = false,
    val devices: List<DiscoveredDevice> = emptyList(),
    val showAllDevices: Boolean = false,
    val connectedSticks: Map<String, ConnectedStickState> = emptyMap(),
    val activeStickId: String? = null,
    val isConnecting: Boolean = false,
    val error: String? = null,
    val sensitivity: Float = 0.5f,
    val autoSensitivity: Boolean = true,
    val micGranted: Boolean = false,
    val savedAddress: String? = null,
    val savedName: String? = null,
    val bondedDevices: List<BondedDevice> = emptyList(),
) {
    val visibleDevices: List<DiscoveredDevice>
        get() = if (showAllDevices) devices else devices.filter { it.matchesProfile }

    val activeStick: ConnectedStickState?
        get() = activeStickId?.let { connectedSticks[it] } ?: connectedSticks.values.firstOrNull()

    val isAnyConnected: Boolean
        get() = connectedSticks.values.any { it.phase == ConnectionPhase.CONNECTED }

    val isAnyInMusicMode: Boolean
        get() = connectedSticks.values.any { it.phase == ConnectionPhase.CONNECTED && it.pattern == PatternChoice.MUSIC }
}

private class StickSession(
    val identifier: String,
    val displayName: String,
    val profile: DeviceProfile,
    val connection: LightstickConnection,
    var gate: WriteGate? = null,
    var player: PatternPlayer? = null,
    val musicPattern: MusicPattern = MusicPattern(nowMs = System::currentTimeMillis),
    var activeChoice: PatternChoice? = null,
    val connectionJobs: MutableList<Job> = mutableListOf(),
)

class ControlViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = LightstickScanner()
    private val prefs = application.getSharedPreferences("lightstick", Application.MODE_PRIVATE)
    private val settingsStore = StickSettingsStore(prefs)

    private val _state = MutableStateFlow(
        UiState(
            savedAddress = prefs.getString(KEY_ADDRESS, null),
            savedName = prefs.getString(KEY_NAME, null),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private val sessions = mutableMapOf<String, StickSession>()
    private var audioJob: Job? = null
    private val settingsSaveJobs = mutableMapOf<String, Job>()

    @Volatile
    private var analyzer: AudioAnalyzer? = null

    private val _levels = MutableStateFlow(AudioLevels())
    val levels: StateFlow<AudioLevels> = _levels.asStateFlow()

    // ---- scanning -------------------------------------------------------

    fun startScan() {
        if (scanJob?.isActive == true) return
        if (!bluetoothEnabled()) {
            _state.update { it.copy(error = "Bluetooth is off — turn it on to scan.") }
            return
        }
        _state.update { it.copy(scanning = true, error = null, devices = emptyList()) }
        scanJob = viewModelScope.launch {
            scanner.scan()
                .catch { cause -> _state.update { it.copy(scanning = false, error = cause.describe()) } }
                .collect { devices ->
                    _state.update { it.copy(devices = devices) }
                    devices.firstOrNull { it.matchesProfile }?.let { match ->
                        if (match.identifier != _state.value.savedAddress) {
                            rememberDevice(match.identifier, match.name ?: match.matchedProfile?.displayName ?: "Lightstick")
                        }
                    }
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(scanning = false) }
    }

    private suspend fun stopScanAndSettle() {
        scanJob?.cancelAndJoin()
        scanJob = null
        _state.update { it.copy(scanning = false) }
        delay(SCAN_SETTLE_MS)
    }

    // ---- connection -----------------------------------------------------

    private val peripheralConfig: PeripheralBuilder.() -> Unit = {
        logging { level = Logging.Level.Events }
        observationExceptionHandler { cause ->
            Log.w(TAG, "observation failure suppressed", cause)
        }
    }

    fun connect(device: DiscoveredDevice) {
        val targetProfile = device.matchedProfile
            ?: DeviceRegistry.findProfileForName(device.name)
            ?: KatseyeProfile
        connectToAddress(
            address = device.identifier,
            displayName = device.name ?: targetProfile.displayName,
            profile = targetProfile,
            advertisement = device.advertisement,
        )
    }

    fun refreshBondedDevices() {
        val adapter = getApplication<Application>()
            .getSystemService(BluetoothManager::class.java)
            ?.adapter
        val bonded = try {
            adapter?.bondedDevices.orEmpty()
                .mapNotNull { device ->
                    val profile = DeviceRegistry.findProfileForName(device.name)
                    if (profile != null) {
                        BondedDevice(address = device.address, name = device.name ?: profile.displayName, profile = profile)
                    } else null
                }
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot read bonded devices", e)
            emptyList()
        }
        _state.update { it.copy(bondedDevices = bonded) }
    }

    fun reconnect() {
        val address = _state.value.savedAddress ?: return
        val name = _state.value.savedName ?: "Lightstick"
        val profile = DeviceRegistry.findProfileForName(name) ?: KatseyeProfile
        connectToAddress(address, name, profile)
    }

    fun connectToAddress(
        address: String,
        displayName: String,
        profile: DeviceProfile = DeviceRegistry.findProfileForName(displayName) ?: KatseyeProfile,
        advertisement: com.juul.kable.Advertisement? = null,
    ) {
        if (sessions.containsKey(address)) return
        _state.update { it.copy(isConnecting = true, error = null) }

        viewModelScope.launch {
            stopScanAndSettle()
            val peripheral = if (advertisement != null) {
                viewModelScope.peripheral(advertisement, peripheralConfig)
            } else {
                viewModelScope.peripheral(address, peripheralConfig)
            }
            establish(peripheral, displayName, address, profile)
        }
    }

    private suspend fun establish(
        peripheral: com.juul.kable.Peripheral,
        displayName: String,
        identifier: String,
        profile: DeviceProfile,
    ) {
        val conn = LightstickConnection(
            peripheral = peripheral,
            profile = profile,
            deviceName = displayName,
            identifier = identifier,
        )

        var connected = false
        var failure: Throwable? = null
        for (attempt in 1..CONNECT_ATTEMPTS) {
            try {
                conn.connect()
                connected = true
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure = e
                Log.w(TAG, "connect attempt $attempt/$CONNECT_ATTEMPTS failed for $identifier", e)
                if (attempt < CONNECT_ATTEMPTS) {
                    _state.update { it.copy(error = "Attempt $attempt failed for $displayName, retrying…") }
                    delay(RETRY_DELAY_MS)
                }
            }
        }

        if (!connected) {
            _state.update {
                it.copy(isConnecting = false, error = connectFailureMessage(failure))
            }
            return
        }

        val gate = WriteGate(
            writer = conn,
            profile = profile,
            scope = viewModelScope,
            onError = { cause ->
                Log.w(TAG, "write failed on $identifier", cause)
                _state.update { it.copy(error = cause.describe()) }
            },
        )
        val player = PatternPlayer(
            gate = gate,
            intervalMs = PATTERN_TICK_MS,
            scope = viewModelScope,
        )

        val session = StickSession(
            identifier = identifier,
            displayName = displayName,
            profile = profile,
            connection = conn,
            gate = gate,
            player = player,
        )
        sessions[identifier] = session

        observe(session)
        observeBattery(session)
        rememberDevice(identifier, displayName)

        val saved = settingsStore.load(identifier)
        val stickState = ConnectedStickState(
            identifier = identifier,
            name = displayName,
            profile = profile,
            phase = ConnectionPhase.CONNECTED,
            hue = saved.hue,
            saturation = saved.saturation,
            brightness = saved.brightness,
            pattern = saved.pattern,
            musicMode = saved.musicMode,
        )

        _state.update { s ->
            s.copy(
                isConnecting = false,
                error = null,
                connectedSticks = s.connectedSticks + (identifier to stickState),
                activeStickId = identifier,
            )
        }

        applyPatternFor(identifier)
    }

    fun disconnect(identifier: String) {
        val session = sessions[identifier] ?: return
        _state.update { s ->
            val updated = s.connectedSticks[identifier]?.copy(phase = ConnectionPhase.DISCONNECTING)
            s.copy(
                connectedSticks = if (updated != null) s.connectedSticks + (identifier to updated) else s.connectedSticks
            )
        }

        viewModelScope.launch {
            session.player?.cancel()
            session.gate?.cancel()
            try {
                session.connection.write(session.profile.protocol.encode(LightState.OFF))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "final off write failed for $identifier", e)
            }

            try {
                session.connection.disconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "disconnect failed for $identifier", e)
            } finally {
                teardownSession(identifier)
            }
        }
    }

    fun disconnectAll() {
        sessions.keys.toList().forEach { disconnect(it) }
    }

    private fun observe(session: StickSession) {
        val conn = session.connection
        session.connectionJobs += viewModelScope.launch {
            conn.state.collect { bleState ->
                if (bleState is State.Disconnected) {
                    val currentPhase = _state.value.connectedSticks[session.identifier]?.phase
                    if (currentPhase == ConnectionPhase.CONNECTED) {
                        teardownSession(session.identifier)
                        _state.update { s ->
                            s.copy(
                                error = "Connection lost to ${session.displayName}" +
                                    (bleState.status?.let { st -> " ($st)" } ?: ""),
                            )
                        }
                    }
                }
            }
        }
        session.connectionJobs += viewModelScope.launch {
            conn.lastPacket.collect { packet ->
                _state.update { s ->
                    val updated = s.connectedSticks[session.identifier]?.copy(lastPacketHex = packet?.toHex())
                    if (updated != null) s.copy(connectedSticks = s.connectedSticks + (session.identifier to updated)) else s
                }
            }
        }
    }

    private fun observeBattery(session: StickSession) {
        val conn = session.connection
        session.connectionJobs += viewModelScope.launch {
            try {
                conn.readBatteryPercent()?.let { percent ->
                    updateBattery(session.identifier, percent)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "initial battery read failed for ${session.identifier}", e)
            }

            conn.batteryPercent
                .catch { cause -> Log.w(TAG, "battery observation ended for ${session.identifier}", cause) }
                .collect { percent -> updateBattery(session.identifier, percent) }
        }
    }

    private fun updateBattery(identifier: String, percent: Int) {
        _state.update { s ->
            val updated = s.connectedSticks[identifier]?.copy(batteryPercent = percent)
            if (updated != null) s.copy(connectedSticks = s.connectedSticks + (identifier to updated)) else s
        }
    }

    private fun teardownSession(identifier: String) {
        val session = sessions.remove(identifier) ?: return
        flushSettings(identifier)
        session.player?.cancel()
        session.gate?.cancel()
        session.connectionJobs.forEach { it.cancel() }
        session.connectionJobs.clear()

        _state.update { s ->
            val newSticks = s.connectedSticks - identifier
            val newActive = if (s.activeStickId == identifier) newSticks.keys.firstOrNull() else s.activeStickId
            s.copy(connectedSticks = newSticks, activeStickId = newActive)
        }
        syncAudio()
    }

    // ---- per-stick controls ---------------------------------------------

    fun setActiveStick(identifier: String) {
        _state.update { it.copy(activeStickId = identifier) }
    }

    fun setHue(hue: Float, identifier: String? = null) = updateStick(identifier) { it.copy(hue = hue) }

    fun setSaturation(saturation: Float, identifier: String? = null) = updateStick(identifier) { it.copy(saturation = saturation) }

    fun setBrightness(brightness: Int, identifier: String? = null) = updateStick(identifier) { it.copy(brightness = brightness) }

    fun setColorFromRgb(colorState: LightState, identifier: String? = null) {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(colorState.r, colorState.g, colorState.b, hsv)
        updateStick(identifier) { it.copy(hue = hsv[0], saturation = hsv[1]) }
    }

    fun selectPattern(choice: PatternChoice, identifier: String? = null) = updateStick(identifier) { it.copy(pattern = choice) }

    fun selectMusicMode(mode: MusicMode, identifier: String? = null) = updateStick(identifier) { it.copy(musicMode = mode) }

    fun setAllHue(hue: Float) {
        _state.value.connectedSticks.keys.forEach { id -> setHue(hue, id) }
    }

    fun setAllPattern(choice: PatternChoice) {
        _state.value.connectedSticks.keys.forEach { id -> selectPattern(choice, id) }
    }

    fun setSensitivity(value: Float) {
        _state.update { it.copy(sensitivity = value.coerceIn(0f, 1f)) }
        _state.value.connectedSticks.keys.forEach { applyPatternFor(it) }
    }

    fun setAutoSensitivity(auto: Boolean) {
        _state.update { it.copy(autoSensitivity = auto) }
        _state.value.connectedSticks.keys.forEach { applyPatternFor(it) }
    }

    fun onMicPermission(granted: Boolean) {
        _state.update { it.copy(micGranted = granted) }
        syncAudio()
    }

    private inline fun updateStick(identifier: String?, crossinline transform: (ConnectedStickState) -> ConnectedStickState) {
        val targetId = identifier ?: _state.value.activeStickId ?: return
        _state.update { s ->
            val targetState = s.connectedSticks[targetId] ?: return@update s
            s.copy(connectedSticks = s.connectedSticks + (targetId to transform(targetState)))
        }
        applyPatternFor(targetId)
        scheduleSettingsSave(targetId)
    }

    // ---- remembered settings --------------------------------------------

    /**
     * Debounced because a slider drag lands here on every frame, and only the
     * value the finger stops on is worth a write. [flushSettings] covers the
     * disconnect that arrives inside the debounce window.
     */
    private fun scheduleSettingsSave(identifier: String) {
        settingsSaveJobs[identifier]?.cancel()
        settingsSaveJobs[identifier] = viewModelScope.launch {
            delay(SETTINGS_SAVE_DEBOUNCE_MS)
            settingsSaveJobs.remove(identifier)
            writeSettings(identifier)
        }
    }

    private fun flushSettings(identifier: String) {
        settingsSaveJobs.remove(identifier)?.cancel()
        writeSettings(identifier)
    }

    private fun writeSettings(identifier: String) {
        val stick = _state.value.connectedSticks[identifier] ?: return
        settingsStore.save(identifier, stick.settings)
    }

    private fun applyPatternFor(identifier: String) {
        val session = sessions[identifier] ?: return
        val stickState = _state.value.connectedSticks[identifier] ?: return
        val color = stickState.color
        val s = _state.value

        val patternSource: PatternSource = when (stickState.pattern) {
            PatternChoice.MANUAL -> SolidPattern(color)
            PatternChoice.BREATHING -> BreathingPattern(color, periodMs = 3_000L)
            PatternChoice.RAINBOW -> RainbowPattern(periodMs = 5_000L, brightness = stickState.brightness)
            PatternChoice.STROBE -> StrobePattern(color, periodMs = 200L, dutyCycle = 0.35)
            PatternChoice.MUSIC -> session.musicPattern.apply {
                mode = stickState.musicMode
                baseColor = color
                sensitivity = s.sensitivity
                autoSensitivity = s.autoSensitivity
                analyzer?.autoRange = s.autoSensitivity
            }
            PatternChoice.TIMELINE -> KeyframePattern(
                keyframes = listOf(
                    Keyframe(0L, LightState.of(255, 0, 96, stickState.brightness)),
                    Keyframe(1_500L, LightState.of(255, 180, 0, stickState.brightness)),
                    Keyframe(3_000L, LightState.of(0, 160, 255, stickState.brightness)),
                    Keyframe(4_500L, LightState.of(140, 0, 255, stickState.brightness)),
                ),
                durationMs = 6_000L,
            )
        }

        val restart = session.activeChoice != stickState.pattern
        session.activeChoice = stickState.pattern
        session.player?.play(patternSource, restart = restart)

        syncAudio()
    }

    // ---- audio ----------------------------------------------------------

    private fun syncAudio() {
        val s = _state.value
        val wanted = s.isAnyInMusicMode && s.micGranted
        if (wanted) startAudio() else stopAudio()
    }

    private fun startAudio() {
        if (audioJob?.isActive == true) return

        MusicSyncService.start(getApplication())
        _levels.value = AudioLevels(listening = true)

        audioJob = viewModelScope.launch(Dispatchers.Default) {
            val sampleRate = AudioCapture.nativeSampleRate(getApplication())
            val analyzer = AudioAnalyzer(
                sampleRate = sampleRate,
                windowSize = WINDOW_SIZE,
                hopSize = HOP_SIZE,
                autoRange = _state.value.autoSensitivity,
            )
            this@ControlViewModel.analyzer = analyzer
            var publishedAt = 0L
            try {
                AudioCapture(getApplication(), sampleRate = sampleRate, frameSize = HOP_SIZE)
                    .frames()
                    .collect { frame ->
                        val now = System.currentTimeMillis()
                        val analysis = analyzer.process(frame, now)

                        sessions.values.forEach { session ->
                            session.musicPattern.submit(analysis)
                        }

                        if (analysis.beat || analysis.clipping ||
                            now - publishedAt >= METER_INTERVAL_MS
                        ) {
                            publishedAt = now
                            publish(analysis, now)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "audio capture failed", e)
                MusicSyncService.stop(getApplication())
                _levels.value = AudioLevels()
                _state.update {
                    it.copy(error = "Microphone unavailable: ${e.describe()}")
                }
            }
        }
    }

    private fun publish(analysis: AudioAnalysis, now: Long) {
        _levels.update {
            it.copy(
                bass = analysis.bass,
                mid = analysis.mid,
                treble = analysis.treble,
                level = analysis.level,
                silent = analysis.silent,
                beatAt = if (analysis.beat) now else it.beatAt,
                clippingAt = if (analysis.clipping) now else it.clippingAt,
                listening = true,
            )
        }
    }

    private fun stopAudio() {
        if (audioJob == null) return
        audioJob?.cancel()
        audioJob = null
        analyzer = null
        MusicSyncService.stop(getApplication())
        _levels.value = AudioLevels()
        sessions.values.forEach { session ->
            session.musicPattern.submit(AudioAnalysis.SILENT)
        }
    }

    fun toggleShowAllDevices() = _state.update { it.copy(showAllDevices = !it.showAllDevices) }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun rememberDevice(identifier: String, name: String) {
        prefs.edit()
            .putString(KEY_ADDRESS, identifier)
            .putString(KEY_NAME, name)
            .apply()
        _state.update { it.copy(savedAddress = identifier, savedName = name) }
    }

    fun forgetSavedDevice() {
        _state.value.savedAddress?.let { address ->
            settingsSaveJobs.remove(address)?.cancel()
            settingsStore.clear(address)
        }
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
        _state.update { it.copy(savedAddress = null, savedName = null) }
    }

    private fun bluetoothEnabled(): Boolean {
        val manager = getApplication<Application>()
            .getSystemService(BluetoothManager::class.java)
        return manager?.adapter?.isEnabled == true
    }

    override fun onCleared() {
        super.onCleared()
        stopAudio()
        sessions.keys.toList().forEach { id ->
            flushSettings(id)
            val session = sessions.remove(id)
            session?.player?.cancel()
            session?.gate?.cancel()
            session?.connectionJobs?.forEach { it.cancel() }
        }
    }

    private companion object {
        const val TAG = "Lightstick"
        const val KEY_ADDRESS = "last_address"
        const val KEY_NAME = "last_name"
        const val SCAN_SETTLE_MS = 400L
        const val WINDOW_SIZE = 1024
        const val HOP_SIZE = 256
        const val PATTERN_TICK_MS = 5L
        const val METER_INTERVAL_MS = 50L
        const val CONNECT_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 2_500L
        const val SETTINGS_SAVE_DEBOUNCE_MS = 400L
    }
}

private fun connectFailureMessage(failure: Throwable?): String {
    val detail = failure?.describe() ?: "Could not connect"
    if (failure is ConnectionLostException) {
        return "$detail\n\nThe link connects and then drops. The stick is asking to encrypt " +
            "using a bond your phone no longer has a key for. Pair it from Bluetooth " +
            "settings while it is in pairing mode, or reset the stick, then try again."
    }
    return detail
}

private fun Throwable.describe(): String = when {
    message.isNullOrBlank() -> this::class.java.simpleName
    else -> "${this::class.java.simpleName}: $message"
}
