package com.petitpoint.vision.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detector live pentru textura/grila repetitiva a panzei Petit Point.
 * Nu are nevoie de internet, OCR sau OpenCV: masoara energia muchiilor pe X/Y si cauta
 * periodicitatea dominanta. Rezultatul este folosit atat pentru feedback vizual, cat si pentru
 * auto-alinierea aproximativa a diagramei peste panza.
 */
class GridDetector {

    data class Result(
        val confidence: Float,
        val verticalLines: FloatArray,
        val horizontalLines: FloatArray,
        val cellWidthPx: Float,
        val cellHeightPx: Float
    )

    private data class AxisResult(
        val confidence: Float,
        val period: Float,
        val lines: FloatArray
    )

    fun detect(frame: GrayFrame): Result {
        if (frame.width < 80 || frame.height < 80) {
            return Result(0f, floatArrayOf(), floatArrayOf(), 0f, 0f)
        }

        val verticalEnergy = verticalEdgeProjection(frame)
        val horizontalEnergy = horizontalEdgeProjection(frame)

        val xAxis = analyseAxis(
            verticalEnergy,
            minPeriod = 5,
            maxPeriod = min(90, max(8, frame.width / 5))
        )
        val yAxis = analyseAxis(
            horizontalEnergy,
            minPeriod = 5,
            maxPeriod = min(90, max(8, frame.height / 5))
        )

        val combined = sqrt((xAxis.confidence * yAxis.confidence).coerceAtLeast(0f))
        return Result(
            confidence = combined.coerceIn(0f, 1f),
            verticalLines = xAxis.lines,
            horizontalLines = yAxis.lines,
            cellWidthPx = xAxis.period,
            cellHeightPx = yAxis.period
        )
    }

    private fun verticalEdgeProjection(frame: GrayFrame): FloatArray {
        val output = FloatArray(frame.width)
        val top = (frame.height * 0.10f).toInt().coerceAtLeast(2)
        val bottom = (frame.height * 0.90f).toInt().coerceAtMost(frame.height - 3)
        val stepY = max(2, frame.height / 280)

        for (x in 2 until frame.width - 2) {
            var sum = 0f
            var count = 0
            var y = top
            while (y <= bottom) {
                sum += abs(frame.value(x + 1, y) - frame.value(x - 1, y)).toFloat()
                count++
                y += stepY
            }
            output[x] = if (count == 0) 0f else sum / count
        }
        return smooth(output)
    }

    private fun horizontalEdgeProjection(frame: GrayFrame): FloatArray {
        val output = FloatArray(frame.height)
        val left = (frame.width * 0.10f).toInt().coerceAtLeast(2)
        val right = (frame.width * 0.90f).toInt().coerceAtMost(frame.width - 3)
        val stepX = max(2, frame.width / 320)

        for (y in 2 until frame.height - 2) {
            var sum = 0f
            var count = 0
            var x = left
            while (x <= right) {
                sum += abs(frame.value(x, y + 1) - frame.value(x, y - 1)).toFloat()
                count++
                x += stepX
            }
            output[y] = if (count == 0) 0f else sum / count
        }
        return smooth(output)
    }

    private fun smooth(input: FloatArray): FloatArray {
        if (input.size < 5) return input.copyOf()
        val output = FloatArray(input.size)
        for (i in input.indices) {
            var sum = 0f
            var weight = 0f
            for (d in -2..2) {
                val j = i + d
                if (j !in input.indices) continue
                val w = when (abs(d)) {
                    0 -> 3f
                    1 -> 2f
                    else -> 1f
                }
                sum += input[j] * w
                weight += w
            }
            output[i] = if (weight == 0f) input[i] else sum / weight
        }
        return output
    }

    private fun analyseAxis(signal: FloatArray, minPeriod: Int, maxPeriod: Int): AxisResult {
        if (signal.size < minPeriod * 5) return AxisResult(0f, 0f, floatArrayOf())

        val margin = max(4, signal.size / 20)
        val start = margin
        val end = signal.size - margin
        if (end - start < minPeriod * 4) return AxisResult(0f, 0f, floatArrayOf())

        var mean = 0f
        var count = 0
        for (i in start until end) {
            mean += signal[i]
            count++
        }
        mean /= max(1, count)

        var variance = 0f
        for (i in start until end) {
            val d = signal[i] - mean
            variance += d * d
        }
        val std = sqrt(variance / max(1, count))
        if (std < 0.75f) return AxisResult(0f, 0f, floatArrayOf())

        val safeMax = min(maxPeriod, (end - start) / 3)
        var bestLag = 0
        var bestScore = -1f

        for (lag in minPeriod..safeMax) {
            var numerator = 0f
            var leftEnergy = 0f
            var rightEnergy = 0f
            var i = start
            while (i + lag < end) {
                val a = signal[i] - mean
                val b = signal[i + lag] - mean
                numerator += a * b
                leftEnergy += a * a
                rightEnergy += b * b
                i++
            }
            val denom = sqrt(leftEnergy * rightEnergy)
            if (denom <= 0.0001f) continue
            var correlation = numerator / denom

            // Favorizam perioada fundamentala fata de multiplii ei, fara sa fortam perioade foarte mici.
            if (lag >= minPeriod * 2) {
                val half = lag / 2
                if (half >= minPeriod) {
                    correlation -= autocorrelationAt(signal, mean, start, end, half) * 0.10f
                }
            }
            if (correlation > bestScore) {
                bestScore = correlation
                bestLag = lag
            }
        }

        if (bestLag <= 0 || bestScore < 0.07f) return AxisResult(0f, 0f, floatArrayOf())

        var bestPhase = 0
        var bestPhaseMean = Float.NEGATIVE_INFINITY
        for (phase in 0 until bestLag) {
            var sum = 0f
            var n = 0
            var p = start + ((phase - start) % bestLag + bestLag) % bestLag
            while (p < end) {
                sum += signal[p]
                n++
                p += bestLag
            }
            if (n > 0) {
                val score = sum / n
                if (score > bestPhaseMean) {
                    bestPhaseMean = score
                    bestPhase = phase
                }
            }
        }

        val phaseStrength = ((bestPhaseMean - mean) / (std * 2.2f)).coerceIn(0f, 1f)
        val correlationStrength = ((bestScore - 0.05f) / 0.55f).coerceIn(0f, 1f)
        val confidence = (correlationStrength * 0.62f + phaseStrength * 0.38f).coerceIn(0f, 1f)

        if (confidence < 0.10f) return AxisResult(confidence, bestLag.toFloat(), floatArrayOf())

        val lines = ArrayList<Float>()
        var p = bestPhase
        while (p < signal.size) {
            if (p >= 0) lines.add(p.toFloat())
            p += bestLag
        }
        p = bestPhase - bestLag
        while (p >= 0) {
            lines.add(p.toFloat())
            p -= bestLag
        }
        lines.sort()

        return AxisResult(
            confidence = confidence,
            period = bestLag.toFloat(),
            lines = lines.toFloatArray()
        )
    }

    private fun autocorrelationAt(
        signal: FloatArray,
        mean: Float,
        start: Int,
        end: Int,
        lag: Int
    ): Float {
        var numerator = 0f
        var leftEnergy = 0f
        var rightEnergy = 0f
        var i = start
        while (i + lag < end) {
            val a = signal[i] - mean
            val b = signal[i + lag] - mean
            numerator += a * b
            leftEnergy += a * a
            rightEnergy += b * b
            i++
        }
        val denom = sqrt(leftEnergy * rightEnergy)
        return if (denom <= 0.0001f) 0f else numerator / denom
    }
}
