package com.orangechuice.lightstick.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.orangechuice.lightstick.R

/**
 * Holds the app in the foreground for as long as music sync is running.
 *
 * It captures nothing and writes nothing — the mic and the BLE link stay in the
 * ViewModel. Its only job is the foreground-service state itself: from Android 9
 * on, a backgrounded process is handed silence instead of microphone input, so
 * without this the light freezes the moment the screen goes off. At a show, the
 * screen going off is the normal case.
 *
 * Declared `microphone|connectedDevice` because both are true and Android 14
 * enforces that the declared types match what the app actually does.
 */
class MusicSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        } catch (e: Throwable) {
            // Most likely RECORD_AUDIO was revoked between the check and here.
            // Sync will still run while the app is visible; it just won't
            // survive the screen going off.
            Log.w(TAG, "could not enter the foreground", e)
            stopSelf()
        }
        // Deliberately not sticky: a restart with no ViewModel behind it would
        // be a notification for a light that is no longer being driven.
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Music sync",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Syncing your lightstick to music")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "Lightstick"
        private const val CHANNEL_ID = "music_sync"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, MusicSyncService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Throwable) {
                Log.w(TAG, "could not start music sync service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MusicSyncService::class.java))
        }
    }
}
