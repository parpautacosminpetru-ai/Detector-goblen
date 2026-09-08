package com.petitpoint.vision.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.petitpoint.vision.model.GridCell
import com.petitpoint.vision.model.GridRegion

/**
 * Suprapunere AR cu perspectivă. Patru puncte ancorează regiunea diagramei pe pânza reală.
 * Punctele pot fi apoi mutate automat de tracker fără ca utilizatorul să recalibreze manual.
 */
class PatternOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val calibrationPoints = ArrayList<PointF>(4)
    private val perspective = Matrix()

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 214, 10)
        alpha = 110
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(0, 255, 170)
        strokeWidth = 0.06f
        alpha = 230
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 130
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.MAGENTA
    }
    private val pointTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.MAGENTA
    }

    var calibrationMode: Boolean = false
        private set

    var onCalibrationProgress: ((Int) -> Unit)? = null
    var onCalibrationComplete: (() -> Unit)? = null

    private var totalRows = 1
    private var totalCols = 1
    private var region = GridRegion(0, 0, 1, 1)
    private var targets: List<GridCell> = emptyList()
    private var completedCells: Set<GridCell> = emptySet()
    private var selectedSymbol: Bitmap? = null

    fun configureGrid(rows: Int, cols: Int, gridRegion: GridRegion) {
        totalRows = rows.coerceAtLeast(1)
        totalCols = cols.coerceAtLeast(1)
        region = sanitizeRegion(gridRegion)
        clearCalibration()
        invalidate()
    }

    fun setTargets(cells: List<GridCell>, symbolBitmap: Bitmap?) {
        targets = cells
        selectedSymbol = symbolBitmap
        invalidate()
    }

    fun setCompletedCells(cells: Set<GridCell>) {
        completedCells = cells.toSet()
        invalidate()
    }

    fun setOverlayOpacity(alpha: Int) {
        val safe = alpha.coerceIn(20, 240)
        targetPaint.alpha = (safe * 0.75f).toInt()
        symbolPaint.alpha = safe
        invalidate()
    }

    fun beginCalibration() {
        calibrationPoints.clear()
        calibrationMode = true
        onCalibrationProgress?.invoke(0)
        invalidate()
    }

    fun clearCalibration() {
        calibrationPoints.clear()
        calibrationMode = false
        perspective.reset()
        invalidate()
    }

    fun hasCalibration(): Boolean = calibrationPoints.size == 4

    fun calibrationPointsSnapshot(): List<PointF> =
        calibrationPoints.map { PointF(it.x, it.y) }

    /** Primește cele patru puncte rafinate de trackerul din fluxul camerei. */
    fun setTrackedCalibrationPoints(points: List<PointF>) {
        if (points.size != 4) return
        calibrationPoints.clear()
        calibrationPoints.addAll(points.map { PointF(it.x, it.y) })
        calibrationMode = false
        rebuildPerspective()
        invalidate()
    }

    /**
     * CameraX face zoom prin crop în jurul centrului. Scalarea punctelor în jurul centrului e o
     * aproximație foarte bună instantanee, după care trackerul live rafinează poziția.
     */
    fun scaleCalibrationAbout(centerX: Float, centerY: Float, factor: Float) {
        if (calibrationPoints.size != 4 || !factor.isFinite() || factor <= 0f) return
        for (point in calibrationPoints) {
            point.x = centerX + (point.x - centerX) * factor
            point.y = centerY + (point.y - centerY) * factor
        }
        rebuildPerspective()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!calibrationMode) return false
        if (event.action != MotionEvent.ACTION_UP) return true

        if (calibrationPoints.size < 4) {
            calibrationPoints.add(PointF(event.x, event.y))
            onCalibrationProgress?.invoke(calibrationPoints.size)
        }

        if (calibrationPoints.size == 4) {
            calibrationMode = false
            rebuildPerspective()
            onCalibrationComplete?.invoke()
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawCalibrationGuides(canvas)
        if (calibrationPoints.size != 4) return
        if (!rebuildPerspective()) return

        canvas.save()
        canvas.concat(perspective)

        val visibleTargets = targets.asSequence()
            .filter { it.row in 0 until totalRows && it.col in 0 until totalCols }
            .filter { region.contains(it) }
            .filterNot { completedCells.contains(it) }

        for (cell in visibleTargets) {
            val rect = RectF(
                cell.col.toFloat(),
                cell.row.toFloat(),
                cell.col + 1f,
                cell.row + 1f
            )
            canvas.drawRect(rect, targetPaint)
            selectedSymbol?.let { symbol ->
                canvas.drawBitmap(symbol, null, rect, symbolPaint)
            }
            canvas.drawRect(rect, borderPaint)
        }
        canvas.restore()
    }

    private fun drawCalibrationGuides(canvas: Canvas) {
        if (calibrationPoints.isEmpty() || !calibrationMode) return

        for (i in calibrationPoints.indices) {
            val p = calibrationPoints[i]
            canvas.drawCircle(p.x, p.y, 22f, pointPaint)
            canvas.drawText((i + 1).toString(), p.x, p.y + 11f, pointTextPaint)
            if (i > 0) {
                val previous = calibrationPoints[i - 1]
                canvas.drawLine(previous.x, previous.y, p.x, p.y, guidePaint)
            }
        }
        if (calibrationPoints.size == 4) {
            val first = calibrationPoints.first()
            val last = calibrationPoints.last()
            canvas.drawLine(last.x, last.y, first.x, first.y, guidePaint)
        }
    }

    private fun rebuildPerspective(): Boolean {
        if (calibrationPoints.size != 4) return false
        val startCol = region.startCol.toFloat()
        val startRow = region.startRow.toFloat()
        val endCol = (region.startCol + region.colCount).toFloat()
        val endRow = (region.startRow + region.rowCount).toFloat()

        val src = floatArrayOf(
            startCol, startRow,
            endCol, startRow,
            endCol, endRow,
            startCol, endRow
        )
        val dst = floatArrayOf(
            calibrationPoints[0].x, calibrationPoints[0].y,
            calibrationPoints[1].x, calibrationPoints[1].y,
            calibrationPoints[2].x, calibrationPoints[2].y,
            calibrationPoints[3].x, calibrationPoints[3].y
        )
        perspective.reset()
        return perspective.setPolyToPoly(src, 0, dst, 0, 4)
    }

    private fun sanitizeRegion(input: GridRegion): GridRegion {
        val startRow = input.startRow.coerceIn(0, totalRows - 1)
        val startCol = input.startCol.coerceIn(0, totalCols - 1)
        val rowCount = input.rowCount.coerceIn(1, totalRows - startRow)
        val colCount = input.colCount.coerceIn(1, totalCols - startCol)
        return GridRegion(startRow, startCol, rowCount, colCount)
    }
}
