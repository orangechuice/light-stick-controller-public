package com.orangechuice.lightstick.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * On Android 12+ scanning needs BLUETOOTH_SCAN and BLUETOOTH_CONNECT; the
 * manifest's `neverForLocation` flag is what excuses us from location. Below
 * that, the legacy Bluetooth permissions are install-time but a scan still
 * requires location at runtime.
 */
val blePermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/**
 * Requests the BLE permissions once on first composition and reports the result.
 *
 * @return true once every required permission is granted.
 */
@Composable
fun rememberBlePermissionState(): Boolean {
    val context = LocalContext.current

    fun allGranted() = blePermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(allGranted()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = allGranted() }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(blePermissions)
    }

    return granted
}

/** Whether the mic is available, and how to ask for it. */
class MicPermissionState(val granted: Boolean, val request: () -> Unit)

/**
 * The microphone, requested only when the user asks for music sync.
 *
 * Deliberately not bundled into the launch-time request: a lightstick app that
 * wants the microphone before it has done anything at all reads as suspicious,
 * and the permission is genuinely optional — everything except music sync works
 * without it.
 *
 * POST_NOTIFICATIONS rides along on Android 13+ because the foreground service
 * that keeps sync alive through a screen-off has a notification. Denying it
 * costs the notification, not the feature.
 */
@Composable
fun rememberMicPermissionState(): MicPermissionState {
    val context = LocalContext.current

    fun micGranted() = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(micGranted()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = micGranted() }

    val requested = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
    }

    return MicPermissionState(granted = granted, request = { launcher.launch(requested) })
}
