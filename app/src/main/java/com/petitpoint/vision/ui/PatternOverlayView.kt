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
import com.petitpoint.vision.vision.NeedleGuidanceState
import kotlin.math.max

/**
 * Suprapunere AR pentru Petit Point.
 *
 * În modul de lucru desenăm doar forma simbolului, cu fundal transparent, direct peste ochiul
 * real al pânzei. Imaginea paginii nu este afișată peste cameră. Patru puncte interne descriu
 * transformarea geometrică a regiunii curente; GoblenActivity le poate calcula și dintr-o singură
 * atingere după ce scannerul a estimat pasul ochiurilor.
 */
class PatternOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val calibrationPoints = ArrayList<PointF>(4)
    private val perspective = Matrix()

    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 238
    }

    private val focusRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.CYAN
        strokeWidth = 0.14f
        alpha = 255
    }

    private val focusDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 245
    }

    private val focusArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 0.11f
        strokeCap = Paint.Cap.ROUND
        alpha = 245
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

    private var sourceSymbol: Bitmap? = null
    private var floatingSymbol: Bitmap? = null
    private var focusedCell: GridCell? = null
    private var focusedHorizontalDirection = 1

    fun configureGrid(rows: Int, cols: Int, gridRegion: GridRegion) {
        totalRows = rows.coerceAtLeast(1)
        totalCols = cols.coerceAtLeast(1)
        region = sanitizeRegion(gridRegion)
        focusedCell = null
        NeedleGuidanceState.setFocusedCell(null)
        clearCalibration()
        invalidate()
    }

    /**
     * Primește pozițiile logice ale codului și bitmap-ul simbolului. Bitmap-ul original rămâne al
     * apelantului; aici construim o mască transparentă de contrast ridicat pentru afișare AR.
     */
    fun setTargets(cells: List<GridCell>, symbolBitmap: Bitmap?) {
        targets = cells
        if (sourceSymbol !== symbolBitmap) {
            sourceSymbol = symbolBitmap
            floatingSymbol?.takeIf { !it.isRecycled }?.recycle()
            floatingSymbol = symbolBitmap?.let(::makeFloatingSymbol)
        }
        if (focusedCell != null && !targets.contains(focusedCell)) {
            focusedCell = null
            NeedleGuidanceState.setFocusedCell(null)
        }
        invalidate()
    }

    fun setCompletedCells(cells: Set<GridCell>) {
        completedCells = cells.toSet()
        invalidate()
    }

    fun setFocusedCell(cell: GridCell?, horizontalDirection: Int) {
        focusedCell = cell
        focusedHorizontalDirection = if (horizontalDirection >= 0) 1 else -1
        NeedleGuidanceState.setFocusedCell(cell)
        invalidate()
    }

    fun setOverlayOpacity(alpha: Int) {
        symbolPaint.alpha = alpha.coerceIn(70, 255)
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
        NeedleGuidanceState.clearDetection()
        invalidate()
    }

    fun hasCalibration(): Boolean = calibrationPoints.size == 4

    fun calibrationPointsSnapshot(): List<PointF> =
        calibrationPoints.map { PointF(it.x, it.y) }

    fun setTrackedCalibrationPoints(points: List<PointF>) {
        if (points.size != 4) return
        calibrationPoints.clear()
        calibrationPoints.addAll(points.map { PointF(it.x, it.y) })
        calibrationMode = false
        rebuildPerspective()
        invalidate()
    }

    /**
     * Folosit când camera face zoom după calibrare. CameraX face crop în jurul centrului imaginii,
     * deci aceeași transformare de scalare menține etichetele lipite de pânză.
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

        val symbol = floatingSymbol
        if (symbol != null && !symbol.isRecycled) {
            for (cell in targets) {
                if (cell.row !in 0 until totalRows || cell.col !in 0 until totalCols) continue
                if (!region.contains(cell) || completedCells.contains(cell)) continue
                val rect = symbolRect(cell)
                canvas.drawBitmap(symbol, null, rect, symbolPaint)
            }
        }

        val focus = focusedCell
        if (focus != null && region.contains(focus) && !completedCells.contains(focus)) {
            drawFocusedCell(canvas, focus)
        }

        canvas.restore()
    }

    private fun symbolRect(cell: GridCell): RectF {
        val inset = 0.08f
        return RectF(
            cell.col + inset,
            cell.row + inset,
            cell.col + 1f - inset,
            cell.row + 1f - inset
        )
    }

    private fun drawFocusedCell(canvas: Canvas, cell: GridCell) {
        val cx = cell.col + 0.5f
        val cy = cell.row + 0.5f
        val ring = RectF(
            cell.col - 0.10f,
            cell.row - 0.10f,
            cell.col + 1.10f,
            cell.row + 1.10f
        )
        canvas.drawOval(ring, focusRingPaint)
        canvas.drawCircle(cx, cy, 0.07f, focusDotPaint)

        val startX: Float
        val endX: Float
        if (focusedHorizontalDirection > 0) {
            startX = cell.col + 0.12f
            endX = cell.col + 0.88f
        } else {
            startX = cell.col + 0.88f
            endX = cell.col + 0.12f
        }
        val arrowY = cell.row - 0.24f
        canvas.drawLine(startX, arrowY, endX, arrowY, focusArrowPaint)
        val sign = if (focusedHorizontalDirection > 0) 1f else -1f
        val head = 0.14f
        canvas.drawLine(endX, arrowY, endX - sign * head, arrowY - head, focusArrowPaint)
        canvas.drawLine(endX, arrowY, endX - sign * head, arrowY + head, focusArrowPaint)
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

    private fun makeFloatingSymbol(source: Bitmap): Bitmap {
        val width = max(1, source.width)
        val height = max(1, source.height)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val color = pixels[i]
            val gray = (Color.red(color) * 30 + Color.green(color) * 59 + Color.blue(color) * 11) / 100
            if (gray >= 232) {
                pixels[i] = Color.TRANSPARENT
            } else {
                val darkness = 255 - gray
                val alpha = (110 + darkness * 1.15f).toInt().coerceIn(120, 255)
                // Magenta intens rămâne vizibil atât pe pânza deschisă, cât și peste zone cusute.
                pixels[i] = Color.argb(alpha, 255, 0, 190)
            }
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun sanitizeRegion(input: GridRegion): GridRegion {
        val startRow = input.startRow.coerceIn(0, totalRows - 1)
        val startCol = input.startCol.coerceIn(0, totalCols - 1)
        val rowCount = input.rowCount.coerceIn(1, totalRows - startRow)
        val colCount = input.colCount.coerceIn(1, totalCols - startCol)
        return GridRegion(startRow, startCol, rowCount, colCount)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        floatingSymbol?.takeIf { !it.isRecycled }?.recycle()
        floatingSymbol = null
        sourceSymbol = null
    }
}
