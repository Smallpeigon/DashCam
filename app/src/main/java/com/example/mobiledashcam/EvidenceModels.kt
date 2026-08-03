package com.example.mobiledashcam

import android.net.Uri
import java.time.Instant

data class EvidenceClip(
    val videoUri: Uri,
    val displayName: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val orientation: String,
    val resolution: String,
    val codec: String,
    val bitrate: Int,
    val sha256: String,
    val cameraId: String
)

data class DashcamCamera(
    val cameraId: String,
    val lensFacing: Int?,
    val displayName: String
)

enum class VideoQuality(val label: String) {
    FHD_1080P("1080p"),
    HD_720P("720p")
}

fun interface WatermarkFrameProvider {
    fun currentText(): String
}
