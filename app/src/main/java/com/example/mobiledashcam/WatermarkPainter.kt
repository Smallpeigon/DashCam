package com.example.mobiledashcam

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

object WatermarkPainter {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 124f
        setShadowLayer(8f, 3f, 3f, Color.BLACK)
    }

    fun draw(canvas: Canvas, text: String) {
        if (text.isBlank()) return
        val margin = 12f
        val bounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, bounds)
        val left = canvas.width - bounds.width() - margin
        val bottom = canvas.height - margin
        canvas.drawText(text, left, bottom, textPaint)
    }
}