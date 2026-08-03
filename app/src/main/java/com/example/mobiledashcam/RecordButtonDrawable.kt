package com.example.mobiledashcam

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class RecordButtonDrawable(private val recording: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.centerX().toFloat()
        val cy = b.centerY().toFloat()
        val outerRadius = minOf(b.width(), b.height()) * 0.42f
        val innerRadius = minOf(b.width(), b.height()) * 0.22f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outerRadius * 0.14f
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, outerRadius, paint)

        paint.style = Paint.Style.FILL
        paint.color = if (recording) 0xffff3b30.toInt() else Color.WHITE
        canvas.drawCircle(cx, cy, innerRadius, paint)
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
