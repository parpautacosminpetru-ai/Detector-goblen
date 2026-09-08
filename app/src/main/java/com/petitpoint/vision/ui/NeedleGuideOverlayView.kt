package com.petitpoint.vision.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.petitpoint.vision.vision.NeedleGuidanceState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Desenează ținta curentă, vârful acului vizibil și săgeata de corecție.
 * Folosește aceeași geometrie FILL_CENTER ca PreviewView.
 */
class NeedleGuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.CYAN
        strokeWidth = 3.2f * density
        alpha = 245
    }
    private val targetFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.CYAN
        alpha = 42
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(255, 80, 80)
        strokeWidth = 3.2f * density
        strokeCap = Paint.Cap.ROUND
        alpha = 245
    }
    private val needleTipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
        alpha = 255
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        alpha = 255
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 15, 15, 15)
    }
    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.MAGENTA
        strokeWidth = 2f * density
    }
    private val arrowTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 44f * scaledDensity
        textAlign = Paint.Align.CENTER
        fakeBoldText = true
    }
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 17f * scaledDensity
        textAlign = Paint.Align.CENTER
        fakeBoldText = true
    }
    private val confidencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 12f * scaledDensity
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val focused = NeedleGuidanceState.focusedCell()
        if (focused == null) {
            postInvalidateDelayed(220L)
            return
        }

        val snapshot = NeedleGuidanceState.snapshot()
        val now = System.currentTimeMillis()
        if (snapshot == null || now - snapshot.updatedAtMs > 900L) {
            drawBadge(canvas, "…", "AC: aștept alinierea / camera", null)
            postInvalidateDelayed(120L)
            return
        }

        val target = frameToView(
            snapshot.targetX,
            snapshot.targetY,
            snapshot.frameWidth,
            snapshot.frameHeight
        )
        drawTarget(canvas, target, snapshot.aligned)

        val tipX = snapshot.needleTipX
        val tipY = snapshot.needleTipY
        if (tipX != null && tipY != null) {
            val tip = frameToView(tipX, tipY, snapshot.frameWidth, snapshot.frameHeight)
            val tailX = snapshot.needleTailX
            val tailY = snapshot.needleTailY
            if (tailX != null && tailY != null) {
                val tail = frameToView(tailX, tailY, snapshot.frameWidth, snapshot.frameHeight)
                canvas.drawLine(tail.x, tail.y, tip.x, tip.y, needlePaint)
            }
            canvas.drawCircle(tip.x, tip.y, 7f * density, needleTipPaint)
            drawArrow(canvas, tip, target)
        }

        val confidence = if (snapshot.confidence > 0f) {
            "încredere ${(snapshot.confidence * 100f).toInt()}%"
        } else {
            null
        }
        drawBadge(canvas, snapshot.arrow, snapshot.message, confidence)
        postInvalidateDelayed(90L)
    }

    private fun drawTarget(canvas: Canvas, target: PointF, aligned: Boolean) {
        val outer = if (aligned) 28f else 22f
        val inner = if (aligned) 12f else 8f
        canvas.drawCircle(target.x, target.y, outer * density, targetFillPaint)
        canvas.drawCircle(target.x, target.y, outer * density, targetPaint)
        canvas.drawCircle(target.x, target.y, inner * density, targetPaint)
        canvas.drawLine(
            target.x - 34f * density,
            target.y,
            target.x + 34f * density,
            target.y,
            targetPaint
        )
        canvas.drawLine(
            target.x,
            target.y - 34f * density,
            target.x,
            target.y + 34f * density,
            targetPaint
        )
    }

    private fun drawArrow(canvas: Canvas, from: PointF, to: PointF) {
        canvas.drawLine(from.x, from.y, to.x, to.y, arrowPaint)
        val angle = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
        val headLength = 22f * density
        val headAngle = Math.toRadians(28.0)
        val x1 = to.x - (cos(angle - headAngle) * headLength).toFloat()
        val y1 = to.y - (sin(angle - headAngle) * headLength).toFloat()
        val x2 = to.x - (cos(angle + headAngle) * headLength).toFloat()
        val y2 = to.y - (sin(angle + headAngle) * headLength).toFloat()
        canvas.drawLine(to.x, to.y, x1, y1, arrowPaint)
        canvas.drawLine(to.x, to.y, x2, y2, arrowPaint)
    }

    private fun drawBadge(canvas: Canvas, arrow: String, message: String, confidence: String?) {
        val centerX = width / 2f
        val centerY = max(145f * density, height * 0.22f)
        val badgeWidth = (width * 0.78f).coerceAtMost(430f * density)
        val badgeHeight = 126f * density
        val rect = RectF(
            centerX - badgeWidth / 2f,
            centerY - badgeHeight / 2f,
            centerX + badgeWidth / 2f,
            centerY + badgeHeight / 2f
        )
        canvas.drawRoundRect(rect, 18f * density, 18f * density, badgePaint)
        canvas.drawRoundRect(rect, 18f * density, 18f * density, badgeBorderPaint)
        canvas.drawText(arrow, centerX, centerY - 3f * density, arrowTextPaint)
        canvas.drawText(message, centerX, centerY + 35f * density, messagePaint)
        if (confidence != null) {
            canvas.drawText(confidence, centerX, centerY + 55f * density, confidencePaint)
        }
    }

    private fun frameToView(
        frameX: Float,
        frameY: Float,
        frameWidth: Int,
        frameHeight: Int
    ): PointF {
        val scale = max(
            width.toFloat() / frameWidth.toFloat(),
            height.toFloat() / frameHeight.toFloat()
        )
        val shownWidth = frameWidth * scale
        val shownHeight = frameHeight * scale
        val offsetX = (width - shownWidth) / 2f
        val offsetY = (height - shownHeight) / 2f
        return PointF(frameX * scale + offsetX, frameY * scale + offsetY)
    }
}
