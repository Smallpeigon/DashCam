package com.example.mobiledashcam

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable

class TextDrawable(
    private val label: String,
    color: Int,
    textSizeSp: Float,
    typeface: Typeface
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textAlign = Paint.Align.CENTER
        textSize = textSizeSp * 3f
        this.typeface = typeface
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val centerY = bounds.centerY() - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(label, bounds.centerX().toFloat(), centerY, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
