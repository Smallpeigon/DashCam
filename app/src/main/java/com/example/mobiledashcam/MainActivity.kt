package com.example.mobiledashcam

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Timer
import kotlin.concurrent.fixedRateTimer

class MainActivity : ComponentActivity() {
    private lateinit var recorder: EvidenceRecorder
    private lateinit var watermark: TextView
    private lateinit var recordButton: ImageButton
    private lateinit var cameraButton: ImageButton
    private lateinit var qualityButton: ImageButton
    private lateinit var statusText: TextView
    private var timer: Timer? = null
    private var cameraOptions: List<DashcamCamera> = emptyList()
    private var selectedCameraIndex = 0
    private var selectedQuality = VideoQuality.FHD_1080P
    private var controlsReady = false
    private var isBindingCamera = false
    private var recordingByService = false
    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshRecordingState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.log(this, "MainActivity onCreate")
        recorder = EvidenceRecorder(this, this)
        selectedQuality = UserSettings.loadQuality(this)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
        root.addView(recorder.createPreview(), FrameLayout.LayoutParams(-1, -1))

        watermark = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(5f, 1.5f, 1.5f, 0xcc000000.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        root.addView(watermark, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setShadowLayer(5f, 1.5f, 1.5f, 0xdd000000.toInt())
            text = "准备中"
        }
        root.addView(statusText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(22)
        })

        cameraButton = iconButton("C").apply {
            contentDescription = "选择摄像头"
            setOnClickListener { showCameraDialog() }
        }
        qualityButton = iconButton("HD").apply {
            contentDescription = "选择分辨率"
            setOnClickListener { showQualityDialog() }
        }
        recordButton = ImageButton(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setImageDrawable(RecordButtonDrawable(false))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "开始录制"
            setOnClickListener { toggleRecording() }
        }

        root.addView(cameraButton, FrameLayout.LayoutParams(dp(52), dp(52)))
        root.addView(qualityButton, FrameLayout.LayoutParams(dp(52), dp(52)))
        root.addView(recordButton, FrameLayout.LayoutParams(dp(86), dp(86)))

        setContentView(root)
        layoutControls()
        requestPermissionsIfNeeded()
        startClock()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            recordingStateReceiver,
            IntentFilter(DashcamRecordingService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        refreshRecordingState()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(recordingStateReceiver) }
        super.onStop()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 7)
        } else {
            loadCamerasAndStart()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            loadCamerasAndStart()
        } else {
            Toast.makeText(this, "需要摄像头、麦克风和通知权限", Toast.LENGTH_LONG).show()
            AppLogger.log(this, "permissions denied")
        }
    }

    private fun loadCamerasAndStart() {
        recorder.loadCameras({ cameras ->
            cameraOptions = cameras
            val savedId = UserSettings.loadCameraId(this)
            selectedCameraIndex = cameras.indexOfFirst { it.cameraId == savedId }.takeIf { it >= 0 } ?: 0
            controlsReady = true
            updateStatusText()
            if (DashcamRecordingService.isRecordingActive) {
                setRecordingUi(true)
            } else {
                startSelectedCamera()
            }
        }, { error ->
            Toast.makeText(this, "读取摄像头失败：${error.message}", Toast.LENGTH_LONG).show()
            AppLogger.log(this, "loadCamerasAndStart failed", error)
        })
    }

    private fun showCameraDialog() {
        if (recordingByService) return
        if (cameraOptions.isEmpty()) {
            Toast.makeText(this, "摄像头还没有准备好", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择摄像头")
            .setSingleChoiceItems(cameraOptions.map { it.displayName }.toTypedArray(), selectedCameraIndex) { dialog, which ->
                selectedCameraIndex = which
                updateStatusText()
                startSelectedCamera()
                dialog.dismiss()
            }
            .show()
    }

    private fun showQualityDialog() {
        if (recordingByService) return
        val qualities = listOf(VideoQuality.FHD_1080P, VideoQuality.HD_720P)
        val labels = qualities.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("录制分辨率")
            .setSingleChoiceItems(labels, qualities.indexOf(selectedQuality).coerceAtLeast(0)) { dialog, which ->
                selectedQuality = qualities[which]
                UserSettings.saveQuality(this, selectedQuality)
                updateStatusText()
                startSelectedCamera()
                dialog.dismiss()
            }
            .show()
    }

    private fun startSelectedCamera(): Boolean {
        AppLogger.log(this, "startSelectedCamera index=$selectedCameraIndex quality=${selectedQuality.label}")
        if (DashcamRecordingService.isRecordingActive) {
            setRecordingUi(true)
            return false
        }
        val camera = cameraOptions.getOrNull(selectedCameraIndex) ?: return false
        isBindingCamera = true
        val result = recorder.startCamera(camera, selectedQuality)
        isBindingCamera = false
        return result.fold(
            onSuccess = {
                UserSettings.saveCameraId(this, camera.cameraId)
                UserSettings.saveQuality(this, selectedQuality)
                updateStatusText()
                true
            },
            onFailure = {
                Toast.makeText(this, "切换摄像头失败：${it.message}", Toast.LENGTH_LONG).show()
                AppLogger.log(this, "startSelectedCamera failed", it)
                false
            }
        )
    }

    private fun toggleRecording() {
        if (recordingByService) {
            DashcamRecordingService.stop(this)
            setSavingUi()
            Toast.makeText(this, "正在保存视频...", Toast.LENGTH_SHORT).show()
        } else {
            if (!startSelectedCamera()) return
            recorder.unbindCamera()
            DashcamRecordingService.start(this)
            setRecordingUi(true)
            Toast.makeText(this, "已开始后台录制", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshRecordingState() {
        val active = DashcamRecordingService.isRecordingActive
        if (active) {
            setRecordingUi(true)
        } else if (recordingByService) {
            setRecordingUi(false)
            if (controlsReady) loadCamerasAndStart()
        }
    }

    private fun setRecordingUi(recording: Boolean) {
        recordingByService = recording
        recordButton.isEnabled = true
        recordButton.contentDescription = if (recording) "停止录制" else "开始录制"
        recordButton.setImageDrawable(RecordButtonDrawable(recording))
        cameraButton.isEnabled = !recording
        qualityButton.isEnabled = !recording
        cameraButton.alpha = if (recording) 0.35f else 1f
        qualityButton.alpha = if (recording) 0.35f else 1f
        updateStatusText(if (recording) "录制中" else null)
    }

    private fun setSavingUi() {
        recordingByService = true
        recordButton.setImageDrawable(RecordButtonDrawable(true))
        recordButton.isEnabled = false
        cameraButton.isEnabled = false
        qualityButton.isEnabled = false
        statusText.text = "正在保存..."
    }

    private fun updateStatusText(prefix: String? = null) {
        val camera = cameraOptions.getOrNull(selectedCameraIndex)?.displayName ?: "未选择摄像头"
        val text = "${selectedQuality.label} · $camera"
        statusText.text = if (prefix == null) text else "$prefix · $text"
    }

    private fun startClock() {
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
        timer = fixedRateTimer("dashcam-clock", true, 0L, 1000L) {
            val text = LocalDateTime.now().format(formatter)
            runOnUiThread {
                watermark.text = text
                repositionWatermark()
            }
        }
    }

    private fun repositionWatermark() {
        val params = watermark.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.BOTTOM or Gravity.END
        params.rightMargin = dp(12)
        params.bottomMargin = dp(8)
        watermark.visibility = View.VISIBLE
        watermark.layoutParams = params
    }

    private fun layoutControls() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        (recordButton.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (landscape) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            rightMargin = if (landscape) dp(28) else 0
            bottomMargin = if (landscape) 0 else dp(30)
            recordButton.layoutParams = this
        }
        (cameraButton.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (landscape) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.BOTTOM or Gravity.START
            rightMargin = if (landscape) dp(45) else 0
            leftMargin = if (landscape) 0 else dp(28)
            bottomMargin = if (landscape) dp(96) else dp(46)
            cameraButton.layoutParams = this
        }
        (qualityButton.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (landscape) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.BOTTOM or Gravity.END
            rightMargin = if (landscape) dp(45) else dp(28)
            bottomMargin = if (landscape) 0 else dp(46)
            topMargin = if (landscape) dp(96) else 0
            qualityButton.layoutParams = this
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLogger.log(this, "onConfigurationChanged orientation=${newConfig.orientation}")
        layoutControls()
        repositionWatermark()
        if (!recordingByService && controlsReady && !isBindingCamera && !DashcamRecordingService.isRecordingActive) {
            startSelectedCamera()
        }
    }

    override fun onDestroy() {
        AppLogger.log(this, "MainActivity onDestroy")
        timer?.cancel()
        if (!recordingByService) recorder.release()
        super.onDestroy()
    }

    private fun iconButton(label: String): ImageButton {
        return ImageButton(this).apply {
            background = oval(0x66000000, Color.WHITE, dp(1))
            setImageDrawable(textIcon(label))
            scaleType = ImageView.ScaleType.CENTER
        }
    }

    private fun textIcon(label: String) = TextDrawable(label, Color.WHITE, 15f, Typeface.DEFAULT_BOLD)

    private fun oval(fill: Int, stroke: Int, strokeWidth: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(strokeWidth, stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
