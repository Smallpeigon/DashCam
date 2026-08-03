package com.example.mobiledashcam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DashcamRecordingService : LifecycleService() {
    private lateinit var recorder: EvidenceRecorder
    private val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
    private var recorderReleased = false

    override fun onCreate() {
        super.onCreate()
        recorder = EvidenceRecorder(this, this, withPreview = false)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopBackgroundRecording()
            else -> startBackgroundRecording()
        }
        return Service.START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startBackgroundRecording() {
        if (recorder.isRecording) return
        if (!hasRequiredPermissions()) {
            stopSelf()
            return
        }

        isRecordingActive = true
        notifyStateChanged()
        startForeground(NOTIFICATION_ID, notification("正在录制"))
        recorder.loadCameras({ cameras ->
            val savedCameraId = UserSettings.loadCameraId(this)
            val camera = cameras.firstOrNull { it.cameraId == savedCameraId } ?: cameras.firstOrNull()
            if (camera == null) {
                finishServiceRecording()
                stopSelf()
                return@loadCameras
            }
            val quality = UserSettings.loadQuality(this)
            if (recorder.startCamera(camera, quality).isFailure) {
                finishServiceRecording()
                stopSelf()
                return@loadCameras
            }
            recorder.startRecording(WatermarkFrameProvider { LocalDateTime.now().format(formatter) }) { result ->
                result.onSuccess { EvidenceMetadataWriter.write(this, it) }
                result.onFailure { AppLogger.log(this, "background record failed", it) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                finishServiceRecording()
                stopSelf()
            }
        }, {
            AppLogger.log(this, "background load cameras failed", it)
            finishServiceRecording()
            stopSelf()
        })
    }

    private fun stopBackgroundRecording() {
        if (recorder.isRecording) {
            startForeground(NOTIFICATION_ID, notification("正在保存..."))
            recorder.stopRecording()
        } else {
            finishServiceRecording()
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (recorder.isRecording) recorder.stopRecording()
        if (!recorderReleased) {
            recorder.release()
            recorderReleased = true
        }
        if (isRecordingActive && !recorder.isRecording) finishServiceRecording()
        super.onDestroy()
    }

    private fun notifyStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun finishServiceRecording() {
        if (!recorderReleased) {
            recorder.release()
            recorderReleased = true
        }
        isRecordingActive = false
        notifyStateChanged()
    }

    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun notification(text: String): Notification {
        val stopIntent = Intent(this, DashcamRecordingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
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
        const val ACTION_STATE_CHANGED = "com.example.mobiledashcam.RECORDING_STATE_CHANGED"
        @Volatile var isRecordingActive: Boolean = false

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DashcamRecordingService::class.java))
        }

        fun stop(context: Context) {
            val intent = Intent(context, DashcamRecordingService::class.java).apply { action = ACTION_STOP }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
