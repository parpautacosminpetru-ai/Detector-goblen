package com.petitpoint.vision.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Afiseaza direct ce grila vede scannerul live, independent de diagrama incarcata. */
class ScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.rgb(0, 255, 150)
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.CYAN
    }

    private var verticalLines: List<Float> = emptyList()
    private var horizontalLines: List<Float> = emptyList()
    private var confidence: Float = 0f

    fun setDetectedGrid(vertical: List<Float>, horizontal: List<Float>, confidence: Float) {
        verticalLines = vertical.filter { it.isFinite() }
        horizontalLines = horizontal.filter { it.isFinite() }
        this.confidence = confidence.coerceIn(0f, 1f)
        invalidate()
    }

    fun clearDetectedGrid() {
        verticalLines = emptyList()
        horizontalLines = emptyList()
        confidence = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (confidence < 0.10f) return

        linePaint.alpha = (55 + confidence * 150f).toInt().coerceIn(55, 205)
        val maxLines = 80

        for (x in verticalLines.take(maxLines)) {
            if (x in 0f..width.toFloat()) canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
        }
        for (y in horizontalLines.take(maxLines)) {
            if (y in 0f..height.toFloat()) canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
        }

        val cx = width / 2f
        val cy = height / 2f
        val r = 18f
        centerPaint.alpha = (90 + confidence * 160f).toInt().coerceIn(90, 250)
        canvas.drawCircle(cx, cy, r, centerPaint)
        canvas.drawLine(cx - r * 1.7f, cy, cx + r * 1.7f, cy, centerPaint)
        canvas.drawLine(cx, cy - r * 1.7f, cx, cy + r * 1.7f, centerPaint)
    }
}
