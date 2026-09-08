package com.petitpoint.vision.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Detector experimental pentru porțiunea VIZIBILĂ a acului.
 *
 * Caută, într-o zonă limitată în jurul ochiului curent, o linie subțire și coerentă care contrastează
 * față de fundal. Acul metalic primește un mic avantaj când este mai luminos decât pânza. Direcțiile
 * perfect orizontale/verticale sunt penalizate ca să nu confundăm ușor firele regulate ale pânzei cu
 * acul. Nu poate detecta partea acului care este complet ascunsă în spatele pânzei.
 */
class NeedleDetector {

    data class Detection(
        val tipX: Float,
        val tipY: Float,
        val tailX: Float,
        val tailY: Float,
        val confidence: Float,
        val lineContrast: Float,
        val lengthPx: Float
    )

    private data class Orientation(
        val dx: Float,
        val dy: Float,
        val nx: Float,
        val ny: Float,
        val axisPenalty: Float
    )

    private val orientations: List<Orientation> = ArrayList<Orientation>().apply {
        for (degrees in 0 until 180 step 15) {
            val radians = degrees * PI / 180.0
            val dx = cos(radians).toFloat()
            val dy = sin(radians).toFloat()
            val mod90 = min(degrees % 90, 90 - (degrees % 90))
            val axisPenalty = when {
                mod90 <= 1 -> 0.46f
                mod90 <= 15 -> 0.82f
                else -> 1f
            }
            add(Orientation(dx, dy, -dy, dx, axisPenalty))
        }
    }

    private var lastTipX = Float.NaN
    private var lastTipY = Float.NaN
    private var misses = 0

    @Synchronized
    fun reset() {
        lastTipX = Float.NaN
        lastTipY = Float.NaN
        misses = 0
    }

    @Synchronized
    fun detect(
        frame: GrayFrame,
        targetX: Float,
        targetY: Float,
        searchRadius: Int
    ): Detection? {
        val radius = searchRadius.coerceIn(42, 160)
        val halfProbe = (radius * 0.22f).roundToInt().coerceIn(10, 30)
        val sideOffset = (radius * 0.026f).roundToInt().coerceIn(2, 4)
        val stride = 6
        val sampleStep = 3
        val margin = halfProbe + sideOffset + 3

        val minX = max(margin, (targetX - radius).roundToInt())
        val maxX = min(frame.width - margin - 1, (targetX + radius).roundToInt())
        val minY = max(margin, (targetY - radius).roundToInt())
        val maxY = min(frame.height - margin - 1, (targetY + radius).roundToInt())
        if (minX >= maxX || minY >= maxY) return registerMiss()

        var bestScore = 0f
        var bestMeanAbs = 0f
        var bestX = 0
        var bestY = 0
        var bestOrientation: Orientation? = null
        val maxCenterDistance = radius * 1.12f

        var cy = minY
        while (cy <= maxY) {
            var cx = minX
            while (cx <= maxX) {
                val centerDistance = hypot(cx - targetX, cy - targetY)
                if (centerDistance <= maxCenterDistance) {
                    val centerValue = frame.value(cx, cy)
                    val localEdge =
                        abs(frame.value(cx + 2, cy) - frame.value(cx - 2, cy)) +
                            abs(frame.value(cx, cy + 2) - frame.value(cx, cy - 2))

                    // Evităm zonele complet plate; păstrăm însă punctele foarte luminoase/întunecate.
                    if (localEdge >= 12 || centerValue >= 228 || centerValue <= 28) {
                        for (orientation in orientations) {
                            var signedSum = 0f
                            var absSum = 0f
                            var centerSum = 0f
                            var sideSum = 0f
                            var count = 0

                            var t = -halfProbe
                            while (t <= halfProbe) {
                                val px = (cx + orientation.dx * t).roundToInt()
                                val py = (cy + orientation.dy * t).roundToInt()
                                val sx1 = (px + orientation.nx * sideOffset).roundToInt()
                                val sy1 = (py + orientation.ny * sideOffset).roundToInt()
                                val sx2 = (px - orientation.nx * sideOffset).roundToInt()
                                val sy2 = (py - orientation.ny * sideOffset).roundToInt()

                                if (px in 0 until frame.width && py in 0 until frame.height &&
                                    sx1 in 0 until frame.width && sy1 in 0 until frame.height &&
                                    sx2 in 0 until frame.width && sy2 in 0 until frame.height
                                ) {
                                    val c = frame.value(px, py).toFloat()
                                    val side = (frame.value(sx1, sy1) + frame.value(sx2, sy2)) * 0.5f
                                    val contrast = c - side
                                    signedSum += contrast
                                    absSum += abs(contrast)
                                    centerSum += c
                                    sideSum += side
                                    count++
                                }
                                t += sampleStep
                            }

                            if (count >= 6) {
                                val meanAbs = absSum / count.toFloat()
                                val coherence = abs(signedSum) / (absSum + 1f)
                                if (meanAbs >= 3.4f && coherence >= 0.16f) {
                                    val centerMean = centerSum / count.toFloat()
                                    val sideMean = sideSum / count.toFloat()
                                    val polarityBonus = if (centerMean > sideMean) 1.16f else 0.90f
                                    val proximity = (1f - centerDistance / (radius * 1.75f))
                                        .coerceIn(0.48f, 1f)

                                    var continuity = 1f
                                    if (lastTipX.isFinite() && lastTipY.isFinite()) {
                                        val d = hypot(cx - lastTipX, cy - lastTipY)
                                        continuity += 0.20f * (1f - (d / radius).coerceIn(0f, 1f))
                                    }

                                    val score = meanAbs * (0.52f + coherence * 0.48f) *
                                        orientation.axisPenalty * polarityBonus * proximity * continuity

                                    if (score > bestScore) {
                                        bestScore = score
                                        bestMeanAbs = meanAbs
                                        bestX = cx
                                        bestY = cy
                                        bestOrientation = orientation
                                    }
                                }
                            }
                        }
                    }
                }
                cx += stride
            }
            cy += stride
        }

        val orientation = bestOrientation ?: return registerMiss()
        if (bestScore < 4.2f) return registerMiss()

        val strongThreshold = max(3.0f, bestMeanAbs * 0.40f)
        val maxExtent = min(radius, 150)

        fun lineContrastAt(offset: Int): Float? {
            val px = (bestX + orientation.dx * offset).roundToInt()
            val py = (bestY + orientation.dy * offset).roundToInt()
            val sx1 = (px + orientation.nx * sideOffset).roundToInt()
            val sy1 = (py + orientation.ny * sideOffset).roundToInt()
            val sx2 = (px - orientation.nx * sideOffset).roundToInt()
            val sy2 = (py - orientation.ny * sideOffset).roundToInt()
            if (px !in 0 until frame.width || py !in 0 until frame.height ||
                sx1 !in 0 until frame.width || sy1 !in 0 until frame.height ||
                sx2 !in 0 until frame.width || sy2 !in 0 until frame.height
            ) return null
            val center = frame.value(px, py).toFloat()
            val side = (frame.value(sx1, sy1) + frame.value(sx2, sy2)) * 0.5f
            return abs(center - side)
        }

        fun extent(sign: Int): Int {
            var lastStrong = 0
            var weakInRow = 0
            var offset = 0
            while (offset < maxExtent) {
                offset += 2
                val contrast = lineContrastAt(offset * sign) ?: break
                if (contrast >= strongThreshold) {
                    lastStrong = offset
                    weakInRow = 0
                } else {
                    weakInRow++
                    if (weakInRow >= 3 && offset > halfProbe) break
                }
            }
            return lastStrong
        }

        val negative = extent(-1)
        val positive = extent(1)
        if (negative + positive < 10) return registerMiss()

        val end1X = bestX - orientation.dx * negative
        val end1Y = bestY - orientation.dy * negative
        val end2X = bestX + orientation.dx * positive
        val end2Y = bestY + orientation.dy * positive
        val length = hypot(end2X - end1X, end2Y - end1Y)
        if (length < 10f) return registerMiss()

        val d1 = hypot(end1X - targetX, end1Y - targetY)
        val d2 = hypot(end2X - targetX, end2Y - targetY)
        var tipX = if (d1 <= d2) end1X else end2X
        var tipY = if (d1 <= d2) end1Y else end2Y
        val tailX = if (d1 <= d2) end2X else end1X
        val tailY = if (d1 <= d2) end2Y else end1Y

        if (lastTipX.isFinite() && lastTipY.isFinite()) {
            val jump = hypot(tipX - lastTipX, tipY - lastTipY)
            if (jump < radius * 0.62f) {
                tipX = lastTipX * 0.52f + tipX * 0.48f
                tipY = lastTipY * 0.52f + tipY * 0.48f
            }
        }

        val scoreConfidence = ((bestScore - 4.0f) / 12f).coerceIn(0f, 1f)
        val lengthConfidence = ((length - 8f) / 34f).coerceIn(0.28f, 1f)
        val confidence = (scoreConfidence * lengthConfidence).coerceIn(0f, 1f)
        if (confidence < 0.12f) return registerMiss()

        lastTipX = tipX
        lastTipY = tipY
        misses = 0
        return Detection(
            tipX = tipX,
            tipY = tipY,
            tailX = tailX,
            tailY = tailY,
            confidence = confidence,
            lineContrast = bestMeanAbs,
            lengthPx = length
        )
    }

    private fun registerMiss(): Detection? {
        misses++
        if (misses >= 6) {
            lastTipX = Float.NaN
            lastTipY = Float.NaN
        }
        return null
    }
}
