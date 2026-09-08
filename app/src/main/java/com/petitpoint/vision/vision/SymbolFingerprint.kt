package com.petitpoint.vision.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Amprentă vizuală offline pentru simbolurile tipărite din diagramă.
 *
 * v2 normalizează simbolul în interiorul celulei: ignoră liniile grilei, găsește cerneala,
 * centrează forma și o scalează într-un pătrat 12x12. Astfel un triunghi desenat de aplicație
 * se poate potrivi cu triunghiul tipărit chiar dacă grosimea sau dimensiunea sunt ușor diferite.
 */
object SymbolFingerprint {
    private const val OUTPUT_SIZE = 12
    private const val RAW_SIZE = 16
    private const val INNER_MARGIN = 0.16f

    fun fromRect(bitmap: Bitmap, rect: Rect): BooleanArray {
        if (bitmap.width <= 0 || bitmap.height <= 0 || rect.width() <= 0 || rect.height() <= 0) {
            return BooleanArray(OUTPUT_SIZE * OUTPUT_SIZE)
        }

        val values = IntArray(RAW_SIZE * RAW_SIZE)
        val innerLeft = rect.left + rect.width() * INNER_MARGIN
        val innerTop = rect.top + rect.height() * INNER_MARGIN
        val innerWidth = rect.width() * (1f - INNER_MARGIN * 2f)
        val innerHeight = rect.height() * (1f - INNER_MARGIN * 2f)

        var index = 0
        for (y in 0 until RAW_SIZE) {
            for (x in 0 until RAW_SIZE) {
                val fx = (x + 0.5f) / RAW_SIZE.toFloat()
                val fy = (y + 0.5f) / RAW_SIZE.toFloat()
                val px = (innerLeft + fx * innerWidth).toInt().coerceIn(0, bitmap.width - 1)
                val py = (innerTop + fy * innerHeight).toInt().coerceIn(0, bitmap.height - 1)
                val color = bitmap.getPixel(px, py)
                values[index++] = (
                    Color.red(color) * 30 +
                        Color.green(color) * 59 +
                        Color.blue(color) * 11
                    ) / 100
            }
        }

        val sorted = values.copyOf().apply { sort() }
        val dark = sorted[(sorted.lastIndex * 0.10f).roundToInt().coerceIn(sorted.indices)]
        val light = sorted[(sorted.lastIndex * 0.82f).roundToInt().coerceIn(sorted.indices)]
        var threshold = (dark * 0.35f + light * 0.65f).roundToInt().coerceIn(0, 255)

        var rawMask = BooleanArray(values.size) { values[it] < threshold }
        var inkCount = rawMask.count { it }
        if (inkCount < 3) {
            val mean = values.average().toInt()
            threshold = (mean - 7).coerceIn(0, 255)
            rawMask = BooleanArray(values.size) { values[it] < threshold }
            inkCount = rawMask.count { it }
        }
        if (inkCount < 2) return BooleanArray(OUTPUT_SIZE * OUTPUT_SIZE)

        var minX = RAW_SIZE
        var minY = RAW_SIZE
        var maxX = -1
        var maxY = -1
        for (y in 0 until RAW_SIZE) {
            for (x in 0 until RAW_SIZE) {
                if (!rawMask[y * RAW_SIZE + x]) continue
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
            }
        }
        if (maxX < minX || maxY < minY) return BooleanArray(OUTPUT_SIZE * OUTPUT_SIZE)

        val sourceW = maxX - minX + 1
        val sourceH = maxY - minY + 1
        val usable = OUTPUT_SIZE - 2
        val scale = min(usable.toFloat() / sourceW.toFloat(), usable.toFloat() / sourceH.toFloat())
        val drawW = max(1, (sourceW * scale).roundToInt())
        val drawH = max(1, (sourceH * scale).roundToInt())
        val offsetX = (OUTPUT_SIZE - drawW) / 2
        val offsetY = (OUTPUT_SIZE - drawH) / 2
        val output = BooleanArray(OUTPUT_SIZE * OUTPUT_SIZE)

        for (oy in 0 until drawH) {
            for (ox in 0 until drawW) {
                val sx = minX + ((ox + 0.5f) * sourceW / drawW).toInt().coerceIn(0, sourceW - 1)
                val sy = minY + ((oy + 0.5f) * sourceH / drawH).toInt().coerceIn(0, sourceH - 1)

                var ink = false
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val rx = (sx + dx).coerceIn(minX, maxX)
                        val ry = (sy + dy).coerceIn(minY, maxY)
                        if (rawMask[ry * RAW_SIZE + rx]) ink = true
                    }
                }
                if (ink) {
                    val tx = offsetX + ox
                    val ty = offsetY + oy
                    if (tx in 0 until OUTPUT_SIZE && ty in 0 until OUTPUT_SIZE) {
                        output[ty * OUTPUT_SIZE + tx] = true
                    }
                }
            }
        }
        return output
    }

    /**
     * 0 = identic. Permitem o deplasare de un pixel, fiindcă fotografiile paginilor nu sunt
     * perfect centrate în fiecare căsuță.
     */
    fun distance(a: BooleanArray, b: BooleanArray): Float {
        if (a.size != OUTPUT_SIZE * OUTPUT_SIZE || b.size != a.size) return 1f

        var best = 1f
        for (shiftY in -1..1) {
            for (shiftX in -1..1) {
                var different = 0
                for (y in 0 until OUTPUT_SIZE) {
                    for (x in 0 until OUTPUT_SIZE) {
                        val bx = x + shiftX
                        val by = y + shiftY
                        val av = a[y * OUTPUT_SIZE + x]
                        val bv = if (bx in 0 until OUTPUT_SIZE && by in 0 until OUTPUT_SIZE) {
                            b[by * OUTPUT_SIZE + bx]
                        } else {
                            false
                        }
                        if (av != bv) different++
                    }
                }
                val hamming = different.toFloat() / a.size.toFloat()
                val densityPenalty = abs(a.count { it } - b.count { it }).toFloat() / a.size.toFloat() * 0.15f
                best = min(best, (hamming + densityPenalty).coerceAtMost(1f))
            }
        }
        return best
    }
}
