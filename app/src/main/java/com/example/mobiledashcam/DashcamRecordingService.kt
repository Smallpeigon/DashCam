package com.example.mobiledashcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class DashcamRecordingService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            sendBroadcast(Intent(ACTION_STOP_REQUESTED).setPackage(packageName))
            return START_NOT_STICKY
        }
        isRecordingActive = true
        startForeground(NOTIFICATION_ID, notification("正在录制"))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRecordingActive = false
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val stopIntent = Intent(this, DashcamRecordingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("鸽录仪")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止录制", stopPendingIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "录制状态", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "dashcam_recording"
        private const val NOTIFICATION_ID = 31
        private const val ACTION_STOP = "com.example.mobiledashcam.STOP_RECORDING"
        const val ACTION_STOP_REQUESTED = "com.example.mobiledashcam.STOP_REQUESTED"
        @Volatile var isRecordingActive: Boolean = false

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DashcamRecordingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DashcamRecordingService::class.java))
            isRecordingActive = false
        }
    }
}
