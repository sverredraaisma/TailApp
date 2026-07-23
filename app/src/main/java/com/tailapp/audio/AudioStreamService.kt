package com.tailapp.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.tailapp.R
import com.tailapp.TailApp

class AudioStreamService : Service() {

    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TailApp:AudioStream")
        // Bounded so a service that is killed without onDestroy cannot pin the CPU awake.
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio Streaming",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TailApp Audio")
            .setContentText("Streaming audio to device")
            .setSmallIcon(R.drawable.ic_notification_audio)
            .setOngoing(true)
            .build()

        // API 34 enforces that the declared type matches; below R the manifest's
        // foregroundServiceType is what applies, so 0 is the correct argument.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            // API 31+ throws when the app is not allowed to start a foreground
            // service (e.g. it went to the background first). Stop cleanly rather
            // than crashing, and let the stream manager reflect that.
            Log.e(TAG, "startForeground rejected", e)
            stopSelf()
            return START_NOT_STICKY
        }
        // Restarting without the app's stream state would leave a zombie notification.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val fftStreamManager = (application as TailApp).container.fftStreamManager
        if (fftStreamManager.isStreaming.value) {
            fftStreamManager.stop()
        }
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "audio_stream_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "AudioStreamService"
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L // 4 hours
    }
}
