package com.example.mobiledashcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.time.Instant

@OptIn(ExperimentalCamera2Interop::class)
class EvidenceRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    var isRecording: Boolean = false
        private set

    private val previewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val effectThread = HandlerThread("watermark-effect").apply { start() }
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var activeCamera: DashcamCamera? = null
    private var activeQuality: VideoQuality = VideoQuality.FHD_1080P

    fun createPreview(): PreviewView = previewView

    fun loadCameras(onLoaded: (List<DashcamCamera>) -> Unit, onError: (Throwable) -> Unit) {
        AppLogger.log(context, "loadCameras start")
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val provider = future.get()
                cameraProvider = provider
                provider.availableCameraInfos.mapIndexed { index, info ->
                    val cameraId = Camera2CameraInfo.from(info).cameraId
                    val lensFacing = lensFacingOf(info)
                    DashcamCamera(cameraId, lensFacing, cameraLabel(index, cameraId, lensFacing))
                }
            }.onSuccess { cameras ->
                AppLogger.log(context, "loadCameras success count=${cameras.size} ids=${cameras.joinToString { it.cameraId }}")
                onLoaded(cameras)
            }.onFailure { error ->
                AppLogger.log(context, "loadCameras failed", error)
                onError(error)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun startCamera(camera: DashcamCamera, quality: VideoQuality): Result<Unit> {
        val provider = cameraProvider ?: return Result.failure(IllegalStateException("摄像头还没有准备好"))
        val rotation = currentRotation()
        AppLogger.log(context, "startCamera camera=${camera.cameraId} quality=${quality.label} rotation=$rotation")
        return runCatching {
            activeCamera = camera
            activeQuality = quality
            provider.unbindAll()
            bindUseCases(provider, camera, quality, rotation)
            AppLogger.log(context, "bindUseCases success")
        }
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        camera: DashcamCamera,
        quality: VideoQuality,
        rotation: Int
    ) {
        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelectorFor(quality))
            .build()
        videoCapture = VideoCapture.withOutput(recorder).also {
            it.targetRotation = rotation
        }

        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(videoCapture!!)
            .build()

        provider.bindToLifecycle(lifecycleOwner, selectorFor(camera.cameraId), group)
    }

    fun startRecording(
        watermarkFrameProvider: WatermarkFrameProvider,
        onFinalized: (Result<EvidenceClip>) -> Unit
    ) {
        val capture = videoCapture ?: return onFinalized(Result.failure(IllegalStateException("摄像头还没有准备好")))
        val displayName = EvidenceStorage.nextDisplayName()
        val rawFile = EvidenceStorage.tempRawVideoFile(context, displayName)
        rawFile.delete()
        val outputOptions = FileOutputOptions.Builder(rawFile).build()
        val start = Instant.now()
        capture.targetRotation = currentRotation()
        AppLogger.log(context, "startRecording rawFile=${rawFile.absolutePath} displayName=$displayName rotation=${currentRotation()} text=${watermarkFrameProvider.currentText()}")

        runCatching {
            var pending = capture.output.prepareRecording(context, outputOptions)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pending = pending.withAudioEnabled()
            } else {
                AppLogger.log(context, "record audio permission missing; recording without audio")
            }
            activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    isRecording = false
                    activeRecording = null
                    if (event.hasError()) {
                        AppLogger.log(context, "record finalize error=${event.error}")
                        onFinalized(Result.failure(IllegalStateException("录制失败：${event.error}")))
                    } else {
                        AppLogger.log(context, "record finalize raw success file=${rawFile.absolutePath}")
                        WatermarkExporter.export(context, rawFile, displayName, start) { exportResult ->
                            exportResult.onSuccess { uri ->
                                val clip = EvidenceClip(
                                    videoUri = uri,
                                    displayName = displayName,
                                    startedAt = start,
                                    endedAt = Instant.now(),
                                    orientation = orientationLabel(),
                                    resolution = activeQuality.label,
                                    codec = "device-default",
                                    bitrate = bitrateFor(activeQuality),
                                    sha256 = EvidenceStorage.sha256(context, uri),
                                    cameraId = activeCamera?.cameraId ?: "unknown"
                                )
                                onFinalized(Result.success(clip))
                            }.onFailure { error ->
                                onFinalized(Result.failure(error))
                            }
                        }
                    }
                }
            }
            isRecording = true
        }.onFailure { error ->
            AppLogger.log(context, "startRecording failed", error)
            isRecording = false
            onFinalized(Result.failure(error))
        }
    }

    fun stopRecording() {
        AppLogger.log(context, "stopRecording")
        runCatching { activeRecording?.stop() }.onFailure { AppLogger.log(context, "stopRecording failed", it) }
    }

    fun release() {
        AppLogger.log(context, "release")
        runCatching { activeRecording?.stop() }
        runCatching { cameraProvider?.unbindAll() }
        effectThread.quitSafely()
        isRecording = false
    }

    private fun currentRotation(): Int = previewView.display?.rotation ?: Surface.ROTATION_0

    private fun selectorFor(cameraId: String): CameraSelector {
        return CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId } }
            .build()
    }

    private fun lensFacingOf(info: CameraInfo): Int? {
        return runCatching {
            Camera2CameraInfo.from(info).getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
        }.getOrNull()
    }

    private fun cameraLabel(index: Int, cameraId: String, lensFacing: Int?): String {
        val side = when (lensFacing) {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "前置"
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "后置"
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接"
            else -> "未知"
        }
        return "${side}摄像头 ${index + 1} (ID $cameraId)"
    }

    private fun qualitySelectorFor(quality: VideoQuality): QualitySelector {
        val preferred = when (quality) {
            VideoQuality.FHD_1080P -> Quality.FHD
            VideoQuality.HD_720P -> Quality.HD
        }
        return QualitySelector.fromOrderedList(
            listOf(preferred, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )
    }

    private fun orientationLabel(): String {
        return if (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
    }

    private fun bitrateFor(quality: VideoQuality): Int {
        return when (quality) {
            VideoQuality.FHD_1080P -> 4_000_000
            VideoQuality.HD_720P -> 2_000_000
        }
    }
}