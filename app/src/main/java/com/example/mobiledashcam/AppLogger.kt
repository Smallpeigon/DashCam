package com.example.mobiledashcam

import android.content.Context
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object AppLogger {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun log(context: Context, message: String, error: Throwable? = null) {
        runCatching {
            val dir = context.getExternalFilesDir("evidence") ?: return
            dir.mkdirs()
            val file = dir.resolve("mobiledashcam.log")
            val line = buildString {
                append(LocalDateTime.now().format(formatter))
                append("  ")
                append(message)
                if (error != null) {
                    append("\n")
                    append(error.stackTraceToString())
                }
                append("\n")
            }
            file.appendText(line)
        }
    }
}