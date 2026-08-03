package com.example.mobiledashcam

import android.content.Context
import org.json.JSONObject

object EvidenceMetadataWriter {
    fun write(context: Context, clip: EvidenceClip) {
        val metadata = JSONObject()
            .put("videoUri", clip.videoUri.toString())
            .put("displayName", clip.displayName)
            .put("startedAt", clip.startedAt.toString())
            .put("endedAt", clip.endedAt.toString())
            .put("orientation", clip.orientation)
            .put("resolution", clip.resolution)
            .put("codec", clip.codec)
            .put("bitrate", clip.bitrate)
            .put("cameraId", clip.cameraId)
            .put("sha256", clip.sha256)
            .put("packageName", context.packageName)

        val output = context.getExternalFilesDir("evidence")?.resolve("${clip.displayName.removeSuffix(".mp4")}.json")
        output?.parentFile?.mkdirs()
        output?.writeText(metadata.toString(2))
    }
}
