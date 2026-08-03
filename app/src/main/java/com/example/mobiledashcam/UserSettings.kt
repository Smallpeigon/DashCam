package com.example.mobiledashcam

import android.content.Context

object UserSettings {
    private const val PREFS = "dashcam_settings"
    private const val CAMERA_ID = "camera_id"
    private const val QUALITY = "quality"

    fun loadCameraId(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CAMERA_ID, null)
    }

    fun saveCameraId(context: Context, cameraId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CAMERA_ID, cameraId).apply()
    }

    fun loadQuality(context: Context): VideoQuality {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(QUALITY, VideoQuality.FHD_1080P.name)
        return runCatching { VideoQuality.valueOf(raw ?: VideoQuality.FHD_1080P.name) }.getOrDefault(VideoQuality.FHD_1080P)
    }

    fun saveQuality(context: Context, quality: VideoQuality) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(QUALITY, quality.name).apply()
    }
}
