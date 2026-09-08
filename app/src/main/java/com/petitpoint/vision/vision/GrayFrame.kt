package com.petitpoint.vision.vision

import android.graphics.PointF
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max

/**
 * Cadru monocrom copiat din CameraX și rotit logic în orientarea ecranului.
 * Folosim doar canalul Y pentru tracking rapid și complet offline.
 */
class GrayFrame private constructor(
    val width: Int,
    val height: Int,
    private val pixels: ByteArray
) {

    data class LocalStats(val mean: Float, val gradient: Float)

    companion object {
        fun from(image: ImageProxy): GrayFrame {
            val plane = image.planes[0]
            val sourceWidth = image.width
            val sourceHeight = image.height
            val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
            val outWidth = if (rotation == 90 || rotation == 270) sourceHeight else sourceWidth
            val outHeight = if (rotation == 90 || rotation == 270) sourceWidth else sourceHeight
            val output = ByteArray(outWidth * outHeight)

            val buffer = plane.buffer
            val base = buffer.position()
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            for (sy in 0 until sourceHeight) {
                for (sx in 0 until sourceWidth) {
                    val sourceIndex = base + sy * rowStride + sx * pixelStride
                    if (sourceIndex >= buffer.limit()) continue
                    val value = buffer.get(sourceIndex)
                    val dx: Int
                    val dy: Int
                    when (rotation) {
                        90 -> {
                            dx = sourceHeight - 1 - sy
                            dy = sx
                        }
                        180 -> {
                            dx = sourceWidth - 1 - sx
                            dy = sourceHeight - 1 - sy
                        }
                        270 -> {
                            dx = sy
                            dy = sourceWidth - 1 - sx
                        }
                        else -> {
                            dx = sx
                            dy = sy
                        }
                    }
                    output[dy * outWidth + dx] = value
                }
            }
            return GrayFrame(outWidth, outHeight, output)
        }
    }

    fun value(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        return pixels[y * width + x].toInt() and 0xff
    }

    /** Mapează coordonatele PreviewView FILL_CENTER în coordonatele cadrului analizat. */
    fun viewToFrame(point: PointF, viewWidth: Int, viewHeight: Int): PointF? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val scale = max(viewWidth.toFloat() / width.toFloat(), viewHeight.toFloat() / height.toFloat())
        val shownWidth = width * scale
        val shownHeight = height * scale
        val offsetX = (viewWidth - shownWidth) / 2f
        val offsetY = (viewHeight - shownHeight) / 2f
        val x = (point.x - offsetX) / scale
        val y = (point.y - offsetY) / scale
        if (x < 0f || y < 0f || x >= width || y >= height) return null
        return PointF(x, y)
    }

    fun frameToView(point: PointF, viewWidth: Int, viewHeight: Int): PointF {
        val scale = max(viewWidth.toFloat() / width.toFloat(), viewHeight.toFloat() / height.toFloat())
        val shownWidth = width * scale
        val shownHeight = height * scale
        val offsetX = (viewWidth - shownWidth) / 2f
        val offsetY = (viewHeight - shownHeight) / 2f
        return PointF(point.x * scale + offsetX, point.y * scale + offsetY)
    }

    fun globalMean(step: Int = 16): Float {
        var sum = 0L
        var count = 0
        val safeStep = step.coerceAtLeast(2)
        var y = safeStep / 2
        while (y < height) {
            var x = safeStep / 2
            while (x < width) {
                sum += value(x, y)
                count++
                x += safeStep
            }
            y += safeStep
        }
        return if (count == 0) 0f else sum.toFloat() / count.toFloat()
    }

    fun localStats(centerX: Float, centerY: Float, radius: Int = 4): LocalStats? {
        val cx = centerX.toInt()
        val cy = centerY.toInt()
        if (cx - radius - 1 < 0 || cy - radius - 1 < 0 ||
            cx + radius + 1 >= width || cy + radius + 1 >= height
        ) return null

        var sum = 0L
        var count = 0
        var gradientSum = 0L
        var gradientCount = 0

        for (y in cy - radius..cy + radius) {
            for (x in cx - radius..cx + radius) {
                val v = value(x, y)
                sum += v
                count++
                if (x < cx + radius) {
                    gradientSum += abs(v - value(x + 1, y))
                    gradientCount++
                }
                if (y < cy + radius) {
                    gradientSum += abs(v - value(x, y + 1))
                    gradientCount++
                }
            }
        }

        return LocalStats(
            mean = if (count == 0) 0f else sum.toFloat() / count,
            gradient = if (gradientCount == 0) 0f else gradientSum.toFloat() / gradientCount
        )
    }

    /** Patch centrat, normalizat față de media lui, folosit ca semnătură de tracking. */
    fun normalizedPatch(centerX: Int, centerY: Int, radius: Int = 6, step: Int = 2): IntArray? {
        if (centerX - radius < 0 || centerY - radius < 0 ||
            centerX + radius >= width || centerY + radius >= height
        ) return null

        var count = 0
        var sum = 0
        var y = centerY - radius
        while (y <= centerY + radius) {
            var x = centerX - radius
            while (x <= centerX + radius) {
                sum += value(x, y)
                count++
                x += step
            }
            y += step
        }
        if (count == 0) return null
        val mean = sum.toFloat() / count
        val result = IntArray(count)
        var i = 0
        y = centerY - radius
        while (y <= centerY + radius) {
            var x = centerX - radius
            while (x <= centerX + radius) {
                result[i++] = (value(x, y) - mean).toInt()
                x += step
            }
            y += step
        }
        return result
    }

    fun patchScore(
        centerX: Int,
        centerY: Int,
        template: IntArray,
        radius: Int = 6,
        step: Int = 2
    ): Float? {
        if (centerX - radius < 0 || centerY - radius < 0 ||
            centerX + radius >= width || centerY + radius >= height
        ) return null

        var count = 0
        var sum = 0
        var y = centerY - radius
        while (y <= centerY + radius) {
            var x = centerX - radius
            while (x <= centerX + radius) {
                sum += value(x, y)
                count++
                x += step
            }
            y += step
        }
        if (count == 0 || count != template.size) return null
        val mean = sum.toFloat() / count

        var i = 0
        var error = 0f
        y = centerY - radius
        while (y <= centerY + radius) {
            var x = centerX - radius
            while (x <= centerX + radius) {
                val centered = value(x, y) - mean
                error += abs(centered - template[i])
                i++
                x += step
            }
            y += step
        }
        return error / count.toFloat()
    }
}
