package com.orangechuice.lightstick.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orangechuice.lightstick.ble.DiscoveredDevice
import com.orangechuice.lightstick.ble.toHex
import com.orangechuice.lightstick.device.DeviceRegistry
import com.orangechuice.lightstick.device.LightState
import com.orangechuice.lightstick.device.profiles.KatseyeProfile
import com.orangechuice.lightstick.pattern.MusicMode
import kotlinx.coroutines.flow.StateFlow

private val PRESETS = listOf(
    LightState(255, 0, 0),
    LightState(255, 120, 0),
    LightState(255, 230, 0),
    LightState(0, 220, 60),
    LightState(0, 160, 255),
    LightState(90, 0, 255),
    LightState(255, 0, 160),
    LightState(255, 255, 255),
)

private fun LightState.toComposeColor() = Color(r, g, b)

@Composable
fun ControlScreen(
    state: UiState,
    levels: StateFlow<AudioLevels>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onConnectAddress: (String, String) -> Unit,
    onReconnect: () -> Unit,
    onForgetSaved: () -> Unit,
    onDisconnect: (String) -> Unit,
    onDisconnectAll: () -> Unit,
    onSelectActiveStick: (String) -> Unit,
    onHue: (Float) -> Unit,
    onSaturation: (Float) -> Unit,
    onBrightness: (Int) -> Unit,
    onPreset: (LightState) -> Unit,
    onPattern: (PatternChoice) -> Unit,
    onMusicMode: (MusicMode) -> Unit,
    onSensitivity: (Float) -> Unit,
    onAutoSensitivity: (Boolean) -> Unit,
    onRequestMic: () -> Unit,
    onToggleShowAll: () -> Unit,
    onDismissError: () -> Unit,
    permissionsGranted: Boolean,
) {
    var showAddDeviceSection by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!permissionsGranted) {
            Banner(
                text = "Bluetooth permission denied. Grant it in Settings → Apps → " +
                    "Lightstick → Permissions, then reopen the app.",
                onDismiss = null,
            )
        }

        state.error?.let { Banner(text = it, onDismiss = onDismissError) }

        if (state.isAnyConnected) {
            MultiStickHeader(
                state = state,
                onSelectActiveStick = onSelectActiveStick,
                onDisconnect = onDisconnect,
                onToggleAddDevice = { showAddDeviceSection = !showAddDeviceSection },
                showAddDevice = showAddDeviceSection,
            )

            if (showAddDeviceSection) {
                PairedSection(
                    state = state,
                    enabled = permissionsGranted,
                    onConnectAddress = onConnectAddress,
                )
                ScanSection(
                    state = state,
                    enabled = permissionsGranted,
                    onStartScan = onStartScan,
                    onStopScan = onStopScan,
                    onConnect = onConnect,
                    onToggleShowAll = onToggleShowAll,
                )
            }

            state.activeStick?.let { activeStick ->
                PatternSection(activeStick.pattern, onPattern)

                if (activeStick.pattern == PatternChoice.MUSIC) {
                    MusicSection(
                        state = state,
                        levels = levels,
                        activeStick = activeStick,
                        onMusicMode = onMusicMode,
                        onSensitivity = onSensitivity,
                        onAutoSensitivity = onAutoSensitivity,
                        onRequestMic = onRequestMic,
                    )
                }

                ColorSection(activeStick, onHue, onSaturation, onBrightness, onPreset)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDisconnectAll,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Disconnect All Lightsticks") }
            }
        } else {
            StatusCard(state)

            PairedSection(
                state = state,
                enabled = permissionsGranted,
                onConnectAddress = onConnectAddress,
            )
            SavedDeviceSection(
                state = state,
                enabled = permissionsGranted,
                onReconnect = onReconnect,
                onForgetSaved = onForgetSaved,
            )
            ScanSection(
                state = state,
                enabled = permissionsGranted,
                onStartScan = onStartScan,
                onStopScan = onStopScan,
                onConnect = onConnect,
                onToggleShowAll = onToggleShowAll,
            )
        }

        DebugPanel(state)
    }
}

@Composable
private fun MultiStickHeader(
    state: UiState,
    onSelectActiveStick: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onToggleAddDevice: () -> Unit,
    showAddDevice: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Connected Lightsticks (${state.connectedSticks.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            state.connectedSticks.values.forEach { stick ->
                val isActive = stick.identifier == state.activeStickId
                Card(
                    onClick = { onSelectActiveStick(stick.identifier) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    when (stick.phase) {
                                        ConnectionPhase.CONNECTED -> Color(0xFF4CD964)
                                        ConnectionPhase.CONNECTING -> Color(0xFFFFCC00)
                                        ConnectionPhase.DISCONNECTING -> Color(0xFFFFCC00)
                                        ConnectionPhase.DISCONNECTED -> Color(0xFF8E8E93)
                                    },
                                ),
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stick.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(
                                text = "${stick.profile.displayName} • ${stick.pattern.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                        stick.batteryPercent?.let { battery ->
                            Text("$battery%", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.size(8.dp))
                        }
                        TextButton(onClick = { onDisconnect(stick.identifier) }) {
                            Text("Disconnect")
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onToggleAddDevice,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showAddDevice) "Close Scan" else "+ Add Lightstick")
            }
        }
    }
}

@Composable
private fun StatusCard(state: UiState) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (state.isConnecting) Color(0xFFFFCC00) else Color(0xFF8E8E93),
                    ),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "No lightsticks connected",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (state.isConnecting) "Connecting…" else "Scan or tap a paired device to connect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun PairedSection(
    state: UiState,
    enabled: Boolean,
    onConnectAddress: (String, String) -> Unit,
) {
    if (state.bondedDevices.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Paired Devices", style = MaterialTheme.typography.labelMedium)
        state.bondedDevices.forEach { device ->
            val isAlreadyConnected = state.connectedSticks.containsKey(device.address)
            Card(
                onClick = { if (!isAlreadyConnected) onConnectAddress(device.address, device.name) },
                enabled = enabled && !isAlreadyConnected && !state.isConnecting,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${device.profile?.displayName ?: "Known Device"} • ${device.address}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Text(
                        text = if (isAlreadyConnected) "Connected" else "Connect",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedDeviceSection(
    state: UiState,
    enabled: Boolean,
    onReconnect: () -> Unit,
    onForgetSaved: () -> Unit,
) {
    val address = state.savedAddress ?: return
    if (state.connectedSticks.containsKey(address)) return

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Last used device", style = MaterialTheme.typography.labelMedium)
            Text(
                text = state.savedName ?: KatseyeProfile.displayName,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onReconnect,
                    enabled = enabled && !state.isConnecting,
                    modifier = Modifier.weight(1f),
                ) { Text("Reconnect (no scan)") }
                TextButton(onClick = onForgetSaved) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun ScanSection(
    state: UiState,
    enabled: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onToggleShowAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = if (state.scanning) onStopScan else onStartScan,
                enabled = enabled && !state.isConnecting,
                modifier = Modifier.weight(1f),
            ) { Text(if (state.scanning) "Stop scan" else "Scan for Lightsticks") }

            OutlinedButton(onClick = onToggleShowAll) {
                Text(if (state.showAllDevices) "Matches only" else "Show all")
            }
        }

        val devices = state.visibleDevices
        if (devices.isEmpty()) {
            Text(
                text = when {
                    state.scanning && state.showAllDevices -> "Scanning for nearby BLE devices…"
                    // Built from the registry rather than spelled out: the literal
                    // version went stale the moment a fourth device was added.
                    state.scanning ->
                        DeviceRegistry.ALL_PROFILES.joinToString(
                            prefix = "Scanning for ",
                            separator = ", ",
                            postfix = "…",
                        ) { it.displayName }
                    else -> "Tap Scan to find nearby lightsticks in Bluetooth pairing mode."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().height(260.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(devices, key = { it.identifier }) { device ->
                    val isConnected = state.connectedSticks.containsKey(device.identifier)
                    DeviceRow(
                        device = device,
                        isConnected = isConnected,
                        onClick = { if (!isConnected) onConnect(device) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, isConnected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = !isConnected,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (device.matchesProfile) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "(unnamed)",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${device.matchedProfile?.displayName ?: "BLE Device"} • ${device.identifier}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Text(
                text = if (isConnected) "Connected" else "${device.rssi} dBm",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatternSection(selected: PatternChoice, onSelect: (PatternChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pattern", style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PatternChoice.entries.forEach { choice ->
                FilterChip(
                    selected = choice == selected,
                    onClick = { onSelect(choice) },
                    label = { Text(choice.label) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MusicSection(
    state: UiState,
    levels: StateFlow<AudioLevels>,
    activeStick: ConnectedStickState,
    onMusicMode: (MusicMode) -> Unit,
    onSensitivity: (Float) -> Unit,
    onAutoSensitivity: (Boolean) -> Unit,
    onRequestMic: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Music Sync (${activeStick.name})", style = MaterialTheme.typography.labelMedium)

            if (!state.micGranted) {
                Text(
                    text = "Microphone access is needed so the light can react to music.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onRequestMic, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant microphone permission")
                }
            } else {
                MeterCard(levels)

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MusicMode.entries.forEach { mode ->
                        FilterChip(
                            selected = mode == activeStick.musicMode,
                            onClick = { onMusicMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sensitivity", style = MaterialTheme.typography.labelMedium)
                    FilterChip(
                        selected = state.autoSensitivity,
                        onClick = { onAutoSensitivity(!state.autoSensitivity) },
                        label = { Text("Auto") },
                    )
                }

                if (!state.autoSensitivity) {
                    LabelledSlider(
                        label = "",
                        value = state.sensitivity,
                        onValueChange = onSensitivity,
                        brush = Brush.horizontalGradient(listOf(Color.Gray, Color.White)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorSection(
    stick: ConnectedStickState,
    onHue: (Float) -> Unit,
    onSaturation: (Float) -> Unit,
    onBrightness: (Int) -> Unit,
    onPreset: (LightState) -> Unit,
) {
    val showColor = stick.colorApplies

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showColor) {
            LabelledSlider(
                label = "Hue (${stick.name})",
                value = stick.hue / 360f,
                onValueChange = { onHue(it * 360f) },
                brush = Brush.horizontalGradient(
                    (0..12).map { LightState.fromHsv(it * 30f, 1f, 1f).toComposeColor() },
                ),
            )

            LabelledSlider(
                label = "Saturation",
                value = stick.saturation,
                onValueChange = onSaturation,
                brush = Brush.horizontalGradient(
                    listOf(Color.White, LightState.fromHsv(stick.hue, 1f, 1f).toComposeColor()),
                ),
            )
        }

        LabelledSlider(
            label = "Brightness  ${stick.brightness}",
            value = stick.brightness / 255f,
            onValueChange = { onBrightness((it * 255f).toInt().coerceIn(0, 255)) },
            brush = Brush.horizontalGradient(
                if (showColor) {
                    listOf(
                        Color.Black,
                        LightState.fromHsv(stick.hue, stick.saturation, 1f).toComposeColor(),
                    )
                } else {
                    listOf(Color.Black, Color.White)
                },
            ),
        )

        if (showColor) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PRESETS.forEach { preset ->
                    Surface(
                        modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = preset.toComposeColor(),
                        onClick = { onPreset(preset) },
                    ) {}
                }
            }
        } else {
            Text(
                text = "${stick.colorOwnerLabel} runs its own colours. Brightness still applies.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    brush: Brush,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
        GradientSlider(value = value, onValueChange = onValueChange, brush = brush)
    }
}

@Composable
private fun GradientSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    brush: Brush,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(brush)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onValueChange((down.position.x / size.width).coerceIn(0f, 1f))
                    drag(down.id) { change ->
                        change.consume()
                        onValueChange((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val thumbX = (value * size.width).coerceIn(12.dp.toPx(), size.width - 12.dp.toPx())
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(thumbX - 3.dp.toPx(), 4.dp.toPx()),
                size = Size(6.dp.toPx(), size.height - 8.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.3f),
                topLeft = Offset(thumbX - 3.dp.toPx(), 4.dp.toPx()),
                size = Size(6.dp.toPx(), size.height - 8.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(1.dp.toPx()),
            )
        }
    }
}

@Composable
private fun MeterCard(levelsFlow: StateFlow<AudioLevels>) {
    val levels by levelsFlow.collectAsStateWithLifecycle()
    val beating = levels.beatAt > 0L && (System.currentTimeMillis() - levels.beatAt < BEAT_FLASH_MS)
    val clipping = levels.clippingAt > 0L && (System.currentTimeMillis() - levels.clippingAt < CLIP_WARNING_MS)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (clipping) "INPUT CLIPPING — turn input down" else "Audio levels",
                color = if (clipping) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        if (beating) Color(0xFFFFCC00) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                    ),
            )
        }
        MeterBar("Bass", levels.bass, Color(0xFFFF3B30))
        MeterBar("Mid", levels.mid, Color(0xFF4CD964))
        MeterBar("Treble", levels.treble, Color(0xFF0A84FF))
    }
}

@Composable
private fun MeterBar(label: String, value: Float, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(56.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(color),
            )
        }
    }
}

private const val BEAT_FLASH_MS = 140L
private const val CLIP_WARNING_MS = 2_000L

@Composable
private fun DebugPanel(state: UiState) {
    val activeStick = state.activeStick
    val protocol = remember(activeStick?.profile) { activeStick?.profile?.protocol ?: KatseyeProfile.protocol }
    val encoded = remember(activeStick?.color) {
        activeStick?.color?.let { protocol.encode(it).toHex() } ?: "—"
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Debug", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            HexRow("active stick", activeStick?.name ?: "None")
            HexRow("profile", activeStick?.profile?.displayName ?: "—")
            HexRow("encoded", encoded)
            HexRow("last sent", activeStick?.lastPacketHex ?: "—")
        }
    }
}

@Composable
private fun HexRow(label: String, hex: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(80.dp),
        )
        Text(text = hex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Banner(text: String, onDismiss: (() -> Unit)?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}
