package com.orangechuice.lightstick.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orangechuice.lightstick.ui.theme.LightstickTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ControlViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LightstickTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val permissionsGranted = rememberBlePermissionState()
                    val mic = rememberMicPermissionState()

                    LaunchedEffect(permissionsGranted) {
                        if (permissionsGranted) viewModel.refreshBondedDevices()
                    }

                    LaunchedEffect(mic.granted) {
                        viewModel.onMicPermission(mic.granted)
                    }

                    ControlScreen(
                        state = state,
                        levels = viewModel.levels,
                        onStartScan = viewModel::startScan,
                        onStopScan = viewModel::stopScan,
                        onConnect = viewModel::connect,
                        onConnectAddress = { addr, name -> viewModel.connectToAddress(addr, name) },
                        onReconnect = viewModel::reconnect,
                        onForgetSaved = viewModel::forgetSavedDevice,
                        onDisconnect = { id -> viewModel.disconnect(id) },
                        onDisconnectAll = viewModel::disconnectAll,
                        onSelectActiveStick = viewModel::setActiveStick,
                        onHue = { h -> viewModel.setHue(h) },
                        onSaturation = { s -> viewModel.setSaturation(s) },
                        onBrightness = { b -> viewModel.setBrightness(b) },
                        onPreset = { p -> viewModel.setColorFromRgb(p) },
                        onPattern = { p -> viewModel.selectPattern(p) },
                        onMusicMode = { m -> viewModel.selectMusicMode(m) },
                        onSensitivity = viewModel::setSensitivity,
                        onAutoSensitivity = viewModel::setAutoSensitivity,
                        onRequestMic = mic.request,
                        onToggleShowAll = viewModel::toggleShowAllDevices,
                        onDismissError = viewModel::dismissError,
                        permissionsGranted = permissionsGranted,
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopScan()
    }
}
