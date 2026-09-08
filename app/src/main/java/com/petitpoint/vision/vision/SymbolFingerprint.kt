package com.petitpoint.vision.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

/**
 * Small offline visual fingerprint for a printed chart symbol.
 *
 * It intentionally ignores the outer part of the cell so chart grid lines do not dominate the
 * comparison. This is not OCR: equal printed symbols are grouped by their visual shape.
 */
object SymbolFingerprint {
    private const val SAMPLE_SIZE = 12
    private const val INNER_MARGIN = 0.18f

    fun fromRect(bitmap: Bitmap, rect: Rect): BooleanArray {
        val values = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        var sum = 0L

        val innerLeft = rect.left + rect.width() * INNER_MARGIN
        val innerTop = rect.top + rect.height() * INNER_MARGIN
        val innerWidth = rect.width() * (1f - INNER_MARGIN * 2f)
        val innerHeight = rect.height() * (1f - INNER_MARGIN * 2f)

        var index = 0
        for (y in 0 until SAMPLE_SIZE) {
            for (x in 0 until SAMPLE_SIZE) {
                val fx = (x + 0.5f) / SAMPLE_SIZE.toFloat()
                val fy = (y + 0.5f) / SAMPLE_SIZE.toFloat()
                val px = (innerLeft + fx * innerWidth).toInt().coerceIn(0, bitmap.width - 1)
                val py = (innerTop + fy * innerHeight).toInt().coerceIn(0, bitmap.height - 1)
                val color = bitmap.getPixel(px, py)
                val gray = (Color.red(color) * 30 + Color.green(color) * 59 + Color.blue(color) * 11) / 100
                values[index++] = gray
                sum += gray
            }
        }

        val mean = (sum / values.size).toInt()
        // Keeping a small margin below the mean makes sparse dark symbols stand out from paper.
        val threshold = (mean - 8).coerceIn(0, 255)
        return BooleanArray(values.size) { i -> values[i] < threshold }
    }

    /** 0 = identical, 1 = every sampled bit differs. */
    fun distance(a: BooleanArray, b: BooleanArray): Float {
        if (a.size != b.size || a.isEmpty()) return 1f
        var different = 0
        for (i in a.indices) {
            if (a[i] != b[i]) different++
        }
        return different.toFloat() / a.size.toFloat()
    }
}
