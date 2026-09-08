package com.petitpoint.vision.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Găsește dreptunghiul grilei tipărite într-o fotografie de pagină.
 *
 * Știm dinainte câte coloane/rânduri are fiecare pagină. În loc să împărțim fotografia întreagă
 * (care conține numere, margini și titlu), căutăm perioada liniilor verticale/orizontale și
 * decupăm exact grila. Acest pas este esențial pentru potrivirea corectă a simbolurilor.
 */
object ChartGridCropper {
    data class Result(
        val bitmap: Bitmap,
        val sourceRect: Rect,
        val confidence: Float
    )

    private data class AxisFit(val start: Int, val step: Float, val score: Float)

    fun crop(source: Bitmap, rows: Int, cols: Int): Result {
        if (source.width < 100 || source.height < 100 || rows <= 0 || cols <= 0) {
            return Result(source.copy(Bitmap.Config.ARGB_8888, false), Rect(0, 0, source.width, source.height), 0f)
        }

        val vertical = projection(source, vertical = true)
        val horizontal = projection(source, vertical = false)

        val xFit = fitAxis(vertical, cols, minCoverage = 0.70f, maxCoverage = 0.98f)
        val yFit = fitAxis(horizontal, rows, minCoverage = 0.72f, maxCoverage = 0.99f)

        if (xFit == null || yFit == null) {
            return Result(source.copy(Bitmap.Config.ARGB_8888, false), Rect(0, 0, source.width, source.height), 0f)
        }

        val left = xFit.start.coerceIn(0, source.width - 2)
        val right = (xFit.start + xFit.step * cols).toInt().coerceIn(left + 2, source.width)
        val top = yFit.start.coerceIn(0, source.height - 2)
        val bottom = (yFit.start + yFit.step * rows).toInt().coerceIn(top + 2, source.height)

        val rect = Rect(left, top, right, bottom)
        val cropped = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
        val confidence = ((xFit.score + yFit.score) * 0.5f).coerceIn(0f, 1f)
        return Result(cropped, rect, confidence)
    }

    /**
     * Returnează pentru fiecare x/y cât de "linie de grilă" pare: 0..1, mai mare = mai întunecat
     * și mai continuu. Simbolurile sunt locale; liniile grilei traversează aproape toată pagina.
     */
    private fun projection(bitmap: Bitmap, vertical: Boolean): FloatArray {
        val length = if (vertical) bitmap.width else bitmap.height
        val cross = if (vertical) bitmap.height else bitmap.width
        val out = FloatArray(length)

        val crossStart = (cross * 0.04f).toInt()
        val crossEnd = (cross * 0.98f).toInt().coerceAtLeast(crossStart + 1)
        val stride = max(1, cross / 700)

        for (i in 0 until length) {
            var darkness = 0f
            var strong = 0
            var samples = 0
            var j = crossStart
            while (j < crossEnd) {
                val color = if (vertical) bitmap.getPixel(i, j) else bitmap.getPixel(j, i)
                val gray = (Color.red(color) * 30 + Color.green(color) * 59 + Color.blue(color) * 11) / 100
                darkness += (255 - gray) / 255f
                if (gray < 185) strong++
                samples++
                j += stride
            }
            if (samples > 0) {
                val meanDark = darkness / samples
                val continuous = strong.toFloat() / samples.toFloat()
                out[i] = meanDark * 0.62f + continuous * 0.38f
            }
        }

        // Netezire foarte mică pentru linii fotografiate pe 2-3 pixeli.
        val smoothed = FloatArray(length)
        for (i in 0 until length) {
            var sum = 0f
            var n = 0
            for (k in -1..1) {
                val idx = i + k
                if (idx in out.indices) { sum += out[idx]; n++ }
            }
            smoothed[i] = if (n > 0) sum / n else out[i]
        }
        return smoothed
    }

    private fun fitAxis(values: FloatArray, cells: Int, minCoverage: Float, maxCoverage: Float): AxisFit? {
        if (values.size < cells + 2) return null

        val size = values.size
        val minStep = max(3f, size * minCoverage / cells.toFloat())
        val maxStep = max(minStep + 1f, size * maxCoverage / cells.toFloat())

        var best: AxisFit? = null
        var step = minStep
        while (step <= maxStep) {
            val extent = step * cells
            val maxStart = (size - extent).toInt().coerceAtLeast(0)
            val startStride = max(1, (step / 5f).toInt())
            var start = 0
            while (start <= maxStart) {
                var score = 0f
                var n = 0
                for (i in 0..cells) {
                    val center = (start + i * step).toInt()
                    var peak = 0f
                    for (d in -2..2) {
                        val idx = center + d
                        if (idx in values.indices) peak = max(peak, values[idx])
                    }
                    score += peak
                    n++
                }
                if (n > 0) score /= n.toFloat()

                // Penalizăm grile care pornesc/lipsesc prea aproape de muchia fotografiei.
                val leftMargin = start.toFloat() / size
                val rightMargin = (size - (start + extent)).toFloat() / size
                if (leftMargin < 0.005f || rightMargin < 0.005f) score *= 0.92f

                val current = best
                if (current == null || score > current.score) best = AxisFit(start, step, score)
                start += startStride
            }
            step += 0.5f
        }

        val fit = best ?: return null
        // Proiecțiile tipice sunt ~0.05-0.30; scalăm într-o încredere intuitivă.
        val normalized = ((fit.score - 0.045f) / 0.18f).coerceIn(0f, 1f)
        return fit.copy(score = normalized)
    }
}
