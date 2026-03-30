package com.tailapp.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
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
        wakeLock.acquire()

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
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
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
    }
}
