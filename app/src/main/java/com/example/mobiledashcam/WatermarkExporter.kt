package com.example.mobiledashcam

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(UnstableApi::class)
object WatermarkExporter {
    private val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    fun export(
        context: Context,
        rawFile: File,
        displayName: String,
        startedAt: Instant,
        onComplete: (Result<Uri>) -> Unit
    ) {
        val outputFile = EvidenceStorage.tempWatermarkedVideoFile(context, displayName)
        outputFile.delete()
        AppLogger.log(context, "WatermarkExporter start raw=${rawFile.absolutePath} output=${outputFile.absolutePath}")

        val overlay = object : TextOverlay() {
            override fun getText(presentationTimeUs: Long): SpannableString {
                val instant = startedAt.plusMillis(presentationTimeUs / 1000L)
                val text = LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(formatter)
                return SpannableString(text).apply {
                    setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(AbsoluteSizeSpan(62, true), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            override fun getOverlaySettings(presentationTimeUs: Long): StaticOverlaySettings {
                return StaticOverlaySettings.Builder()
                    .setOverlayFrameAnchor(1f, -1f)
                    .setBackgroundFrameAnchor(0.92f, -0.94f)
                    .build()
            }
        }

        val videoEffects: List<Effect> = listOf(OverlayEffect(listOf(overlay)))
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(rawFile)))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                    runCatching {
                        val uri = EvidenceStorage.publishVideoToGallery(context, outputFile, displayName)
                        AppLogger.log(context, "WatermarkExporter success galleryUri=$uri")
                        rawFile.delete()
                        outputFile.delete()
                        uri
                    }.onSuccess { onComplete(Result.success(it)) }
                        .onFailure { error ->
                            AppLogger.log(context, "WatermarkExporter publish failed", error)
                            onComplete(Result.failure(error))
                        }
                }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    AppLogger.log(context, "WatermarkExporter failed", exportException)
                    onComplete(Result.failure(exportException))
                }
            })
            .build()

        transformer.start(edited, outputFile.absolutePath)
    }
}