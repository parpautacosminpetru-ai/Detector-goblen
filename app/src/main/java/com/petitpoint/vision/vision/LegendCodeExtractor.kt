package com.petitpoint.vision.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import com.petitpoint.vision.model.CodeLegendEntry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Extrage offline perechi cod -> simbol din fotografii cu legenda goblenului.
 * OCR-ul citește codul, iar aici căutăm o mică formă tipărită în stânga/dreapta codului.
 */
object LegendCodeExtractor {

    private const val MIN_SYMBOL_SCORE = 0.16f

    private data class Candidate(val rect: Rect, val score: Float)

    fun extract(bitmap: Bitmap, recognizedText: Text): List<CodeLegendEntry> {
        val bestByCode = LinkedHashMap<String, CodeLegendEntry>()

        for (block in recognizedText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val code = normalizeCode(element.text) ?: continue
                    val box = element.boundingBox ?: continue
                    val candidate = findBestSymbol(bitmap, box) ?: continue
                    if (candidate.score < MIN_SYMBOL_SCORE) continue

                    val rect = candidate.rect
                    if (rect.width() < 4 || rect.height() < 4) continue

                    val rawCrop = Bitmap.createBitmap(
                        bitmap,
                        rect.left,
                        rect.top,
                        rect.width(),
                        rect.height()
                    )
                    val symbolBitmap = rawCrop.copy(Bitmap.Config.ARGB_8888, false) ?: rawCrop
                    if (symbolBitmap !== rawCrop && !rawCrop.isRecycled) rawCrop.recycle()

                    val fingerprint = SymbolFingerprint.fromRect(bitmap, rect)
                    val entry = CodeLegendEntry(code, symbolBitmap, fingerprint, candidate.score)
                    val previous = bestByCode[code]
                    if (previous == null || entry.score > previous.score) {
                        previous?.symbolBitmap?.takeIf { !it.isRecycled }?.recycle()
                        bestByCode[code] = entry
                    } else {
                        symbolBitmap.takeIf { !it.isRecycled }?.recycle()
                    }
                }
            }
        }

        return bestByCode.values.sortedWith(
            compareBy<CodeLegendEntry>({ it.code.toIntOrNull() == null }, { it.code.toIntOrNull() ?: Int.MAX_VALUE }, { it.code })
        )
    }

    private fun normalizeCode(raw: String): String? {
        val cleaned = raw
            .uppercase()
            .replace(Regex("[^A-Z0-9]"), "")

        if (cleaned.isBlank() || cleaned.length > 8) return null
        if (cleaned == "ECRU" || cleaned == "BLANC") return cleaned
        if (!cleaned.any { it.isDigit() }) return null

        val digitCount = cleaned.count { it.isDigit() }
        val letterCount = cleaned.count { it.isLetter() }
        if (digitCount == 0 || letterCount > 3) return null
        if (letterCount == 0 && cleaned.length > 5) return null
        return cleaned
    }

    private fun findBestSymbol(bitmap: Bitmap, codeBox: Rect): Candidate? {
        if (bitmap.width < 8 || bitmap.height < 8) return null

        val base = max(codeBox.height(), 10)
        val maxSize = min(bitmap.width, bitmap.height)
        val minSize = min(16, maxSize)
        val size = (base * 1.8f).toInt().coerceIn(minSize, maxSize)
        val centerY = codeBox.centerY()
        val gap = max(2, (base * 0.20f).toInt())
        val step = max(4, (size * 0.62f).toInt())
        var best: Candidate? = null

        fun consider(left: Int, top: Int, penalty: Float) {
            if (left < 0 || top < 0 || left + size > bitmap.width || top + size > bitmap.height) return
            val rect = Rect(left, top, left + size, top + size)
            val score = (symbolScore(bitmap, rect) - penalty).coerceAtLeast(0f)
            if (best == null || score > best!!.score) best = Candidate(rect, score)
        }

        val top = (centerY - size / 2).coerceIn(0, max(0, bitmap.height - size))

        for (index in 0..5) {
            val right = codeBox.left - gap - index * step
            consider(right - size, top, index * 0.035f)
        }

        for (index in 0..3) {
            val left = codeBox.right + gap + index * step
            consider(left, top, 0.07f + index * 0.04f)
        }

        return best
    }

    private fun symbolScore(bitmap: Bitmap, rect: Rect): Float {
        val sampleStep = max(1, min(rect.width(), rect.height()) / 28)
        var count = 0
        var sum = 0.0
        var sumSquares = 0.0
        var gradient = 0.0
        var gradientCount = 0

        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                val gray = gray(bitmap.getPixel(x, y))
                sum += gray
                sumSquares += gray * gray
                count++

                if (x + sampleStep < rect.right) {
                    gradient += abs(gray - gray(bitmap.getPixel(x + sampleStep, y)))
                    gradientCount++
                }
                if (y + sampleStep < rect.bottom) {
                    gradient += abs(gray - gray(bitmap.getPixel(x, y + sampleStep)))
                    gradientCount++
                }
                x += sampleStep
            }
            y += sampleStep
        }
        if (count == 0) return 0f

        val mean = sum / count
        val variance = (sumSquares / count - mean * mean).coerceAtLeast(0.0)
        val stdDev = sqrt(variance)

        var dark = 0
        y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                if (gray(bitmap.getPixel(x, y)) < mean - 14.0) dark++
                x += sampleStep
            }
            y += sampleStep
        }

        val darkRatio = dark.toFloat() / count.toFloat()
        if (darkRatio < 0.012f || darkRatio > 0.82f) return 0f

        val densityScore = when {
            darkRatio <= 0.34f -> (darkRatio / 0.34f).coerceIn(0f, 1f)
            else -> ((0.82f - darkRatio) / 0.48f).coerceIn(0f, 1f)
        }
        val textureScore = (stdDev / 58.0).toFloat().coerceIn(0f, 1f)
        val edgeScore = if (gradientCount == 0) 0f else
            ((gradient / gradientCount) / 32.0).toFloat().coerceIn(0f, 1f)

        return densityScore * 0.38f + textureScore * 0.30f + edgeScore * 0.32f
    }

    private fun gray(color: Int): Double =
        (Color.red(color) * 30 + Color.green(color) * 59 + Color.blue(color) * 11) / 100.0
}
