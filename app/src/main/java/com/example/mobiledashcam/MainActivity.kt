package com.example.mobiledashcam

import android.Manifest
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
        root.addView(watermark, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

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
            setOnClickListener { showCameraDialog() }
        }
        qualityButton = iconButton("HD").apply {
            setOnClickListener { showQualityDialog() }
        }
        recordButton = ImageButton(this).apply {
            background = oval(Color.TRANSPARENT, 0xffff3b30.toInt(), dp(6))
            setImageDrawable(ovalIcon(0xffff3b30.toInt(), true))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "开始录制"
            setOnClickListener { toggleRecording() }
        }

        root.addView(cameraButton, FrameLayout.LayoutParams(dp(52), dp(52)))
        root.addView(qualityButton, FrameLayout.LayoutParams(dp(52), dp(52)))
        root.addView(recordButton, FrameLayout.LayoutParams(dp(78), dp(78)))

        setContentView(root)
        layoutControls()
        requestPermissionsIfNeeded()
        startClock()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
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
            Toast.makeText(this, "需要摄像头和麦克风权限", Toast.LENGTH_LONG).show()
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
            startSelectedCamera()
        }, { error ->
            Toast.makeText(this, "读取摄像头失败：${error.message}", Toast.LENGTH_LONG).show()
            AppLogger.log(this, "loadCamerasAndStart failed", error)
        })
    }

    private fun showCameraDialog() {
        if (recorder.isRecording) return
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
        if (recorder.isRecording) return
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
        if (recorder.isRecording) {
            recordButton.isEnabled = false
            statusText.text = "正在保存..."
            recorder.stopRecording()
        } else {
            if (!startSelectedCamera()) return
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            AppLogger.log(this, "recording orientation locked")
            setRecordingUi(true)
            recorder.startRecording(WatermarkFrameProvider { watermark.text.toString() }) { result ->
                runOnUiThread {
                    setRecordingUi(false)
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    result.onSuccess {
                        EvidenceMetadataWriter.write(this, it)
                        Toast.makeText(this, "视频已保存到 MobileDashcam", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this, "录制失败：${it.message}", Toast.LENGTH_LONG).show()
                        AppLogger.log(this, "record failed callback", it)
                    }
                }
            }
        }
    }

    private fun setRecordingUi(recording: Boolean) {
        recordButton.isEnabled = true
        recordButton.contentDescription = if (recording) "停止录制" else "开始录制"
        recordButton.setImageDrawable(ovalIcon(if (recording) Color.WHITE else 0xffff3b30.toInt(), !recording))
        cameraButton.isEnabled = !recording
        qualityButton.isEnabled = !recording
        cameraButton.alpha = if (recording) 0.35f else 1f
        qualityButton.alpha = if (recording) 0.35f else 1f
        updateStatusText(if (recording) "录制中" else null)
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
            rightMargin = if (landscape) dp(42) else 0
            leftMargin = if (landscape) 0 else dp(28)
            bottomMargin = if (landscape) dp(92) else dp(42)
            cameraButton.layoutParams = this
        }
        (qualityButton.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (landscape) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.BOTTOM or Gravity.END
            rightMargin = if (landscape) dp(42) else dp(28)
            bottomMargin = if (landscape) 0 else dp(42)
            topMargin = if (landscape) dp(92) else 0
            qualityButton.layoutParams = this
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLogger.log(this, "onConfigurationChanged orientation=${newConfig.orientation}")
        layoutControls()
        repositionWatermark()
        if (recorder.isRecording) {
            Toast.makeText(this, "录制中已锁定方向，停止后可旋转", Toast.LENGTH_SHORT).show()
            AppLogger.log(this, "orientation changed while recording; locked")
        } else if (controlsReady && !isBindingCamera) {
            startSelectedCamera()
        }
    }

    override fun onDestroy() {
        AppLogger.log(this, "MainActivity onDestroy")
        timer?.cancel()
        recorder.release()
        super.onDestroy()
    }

    private fun iconButton(label: String): ImageButton {
        return ImageButton(this).apply {
            background = oval(0x66000000, Color.WHITE, dp(1))
            setImageDrawable(textIcon(label))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = label
        }
    }

    private fun textIcon(label: String) = TextDrawable(label, Color.WHITE, 15f, Typeface.DEFAULT_BOLD)

    private fun ovalIcon(color: Int, ring: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (ring) Color.TRANSPARENT else color)
        setStroke(if (ring) dp(7) else 0, color)
    }

    private fun oval(fill: Int, stroke: Int, strokeWidth: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(strokeWidth, stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
