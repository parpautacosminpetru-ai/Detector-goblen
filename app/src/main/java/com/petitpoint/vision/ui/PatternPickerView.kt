package com.petitpoint.vision.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.petitpoint.vision.model.GridCell
import com.petitpoint.vision.model.PatternGrid
import kotlin.math.min

/** Full-chart view used only to choose one printed symbol by touching a cell. */
class PatternPickerView(context: Context) : View(context) {
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.YELLOW
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
    }

    private var grid: PatternGrid? = null
    private var imageRect = RectF()
    private var selected: GridCell? = null

    var onCellSelected: ((GridCell) -> Unit)? = null

    init {
        setBackgroundColor(Color.rgb(20, 20, 20))
    }

    fun setGrid(patternGrid: PatternGrid) {
        grid = patternGrid
        selected = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pattern = grid ?: return

        canvas.drawText("Atinge un simbol din diagramă", 24f, 52f, textPaint)

        val availableWidth = width.toFloat()
        val availableHeight = (height - 80).coerceAtLeast(1).toFloat()
        val scale = min(
            availableWidth / pattern.bitmap.width.toFloat(),
            availableHeight / pattern.bitmap.height.toFloat()
        )
        val drawWidth = pattern.bitmap.width * scale
        val drawHeight = pattern.bitmap.height * scale
        val left = (availableWidth - drawWidth) / 2f
        val top = 70f + (availableHeight - drawHeight) / 2f
        imageRect.set(left, top, left + drawWidth, top + drawHeight)

        canvas.drawBitmap(pattern.bitmap, null, imageRect, imagePaint)

        selected?.let { cell ->
            val cellW = imageRect.width() / pattern.cols
            val cellH = imageRect.height() / pattern.rows
            val rect = RectF(
                imageRect.left + cell.col * cellW,
                imageRect.top + cell.row * cellH,
                imageRect.left + (cell.col + 1) * cellW,
                imageRect.top + (cell.row + 1) * cellH
            )
            canvas.drawRect(rect, selectionPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val pattern = grid ?: return false
        if (!imageRect.contains(event.x, event.y)) return true

        val nx = (event.x - imageRect.left) / imageRect.width()
        val ny = (event.y - imageRect.top) / imageRect.height()
        val col = (nx * pattern.cols).toInt().coerceIn(0, pattern.cols - 1)
        val row = (ny * pattern.rows).toInt().coerceIn(0, pattern.rows - 1)
        selected = GridCell(row, col)
        invalidate()
        onCellSelected?.invoke(selected!!)
        return true
    }
}
