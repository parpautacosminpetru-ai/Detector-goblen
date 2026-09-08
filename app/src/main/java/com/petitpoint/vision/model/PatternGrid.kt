package com.petitpoint.vision.model

import android.graphics.Bitmap
import android.graphics.Rect
import com.petitpoint.vision.vision.SymbolFingerprint
import kotlin.math.ceil
import kotlin.math.floor

/** One logical square from the printed Petit Point chart. */
data class GridCell(val row: Int, val col: Int)

/**
 * The part of the chart currently visible under the phone camera.
 * Values are zero based internally.
 */
data class GridRegion(
    val startRow: Int,
    val startCol: Int,
    val rowCount: Int,
    val colCount: Int
) {
    fun contains(cell: GridCell): Boolean =
        cell.row in startRow until (startRow + rowCount) &&
            cell.col in startCol until (startCol + colCount)
}

class PatternGrid(
    val bitmap: Bitmap,
    val rows: Int,
    val cols: Int
) {
    init {
        require(rows > 0) { "rows must be > 0" }
        require(cols > 0) { "cols must be > 0" }
    }

    private val fingerprintCache = HashMap<GridCell, BooleanArray>()

    fun cellRect(cell: GridCell): Rect {
        val cellWidth = bitmap.width.toFloat() / cols.toFloat()
        val cellHeight = bitmap.height.toFloat() / rows.toFloat()

        val left = floor(cell.col * cellWidth).toInt().coerceIn(0, bitmap.width - 1)
        val top = floor(cell.row * cellHeight).toInt().coerceIn(0, bitmap.height - 1)
        val right = ceil((cell.col + 1) * cellWidth).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ceil((cell.row + 1) * cellHeight).toInt().coerceIn(top + 1, bitmap.height)
        return Rect(left, top, right, bottom)
    }

    fun symbolBitmap(cell: GridCell): Bitmap {
        val rect = cellRect(cell)
        val insetX = (rect.width() * 0.12f).toInt()
        val insetY = (rect.height() * 0.12f).toInt()
        val left = (rect.left + insetX).coerceAtMost(rect.right - 1)
        val top = (rect.top + insetY).coerceAtMost(rect.bottom - 1)
        val width = (rect.width() - insetX * 2).coerceAtLeast(1).coerceAtMost(bitmap.width - left)
        val height = (rect.height() - insetY * 2).coerceAtLeast(1).coerceAtMost(bitmap.height - top)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    fun matchingCells(reference: GridCell, maxDistance: Float = 0.18f): List<GridCell> {
        val referenceFingerprint = fingerprint(reference)
        val result = ArrayList<GridCell>()

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val cell = GridCell(row, col)
                val distance = SymbolFingerprint.distance(referenceFingerprint, fingerprint(cell))
                if (distance <= maxDistance) {
                    result.add(cell)
                }
            }
        }
        return result
    }

    private fun fingerprint(cell: GridCell): BooleanArray =
        fingerprintCache.getOrPut(cell) {
            SymbolFingerprint.fromRect(bitmap, cellRect(cell))
        }
}
