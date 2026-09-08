package com.petitpoint.vision.vision

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max

/**
 * Tracker local pentru cele patru colțuri calibrate. Nu trimite cadrele nicăieri și nu are nevoie
 * de model AI extern. Caută în fiecare cadru patch-ul de textură memorat în jurul fiecărui reper.
 */
class AnchorTracker {

    data class Result(
        val viewPoints: List<PointF>,
        val stable: Boolean,
        val quality: Float,
        val hasMoved: Boolean
    )

    private data class Anchor(
        var x: Float,
        var y: Float,
        val template: IntArray
    )

    private val anchors = ArrayList<Anchor>(4)
    private var frameWidth = 0
    private var frameHeight = 0

    @Synchronized
    fun reset() {
        anchors.clear()
        frameWidth = 0
        frameHeight = 0
    }

    @Synchronized
    fun process(
        frame: GrayFrame,
        viewWidth: Int,
        viewHeight: Int,
        calibrationPoints: List<PointF>
    ): Result? {
        if (calibrationPoints.size != 4 || viewWidth <= 0 || viewHeight <= 0) {
            reset()
            return null
        }

        if (anchors.size != 4 || frameWidth != frame.width || frameHeight != frame.height) {
            if (!initialize(frame, viewWidth, viewHeight, calibrationPoints)) return null
            return Result(
                viewPoints = calibrationPoints.map { PointF(it.x, it.y) },
                stable = true,
                quality = 1f,
                hasMoved = false
            )
        }

        val previous = anchors.map { PointF(it.x, it.y) }
        val found = arrayOfNulls<PointF>(4)
        val scores = FloatArray(4) { Float.MAX_VALUE }
        val goodShiftsX = ArrayList<Float>(4)
        val goodShiftsY = ArrayList<Float>(4)

        for (i in anchors.indices) {
            val anchor = anchors[i]
            val match = search(frame, anchor)
            if (match != null && match.second <= MAX_SCORE) {
                val p = match.first
                found[i] = p
                scores[i] = match.second
                goodShiftsX.add(p.x - anchor.x)
                goodShiftsY.add(p.y - anchor.y)
            }
        }

        if (goodShiftsX.size < 3) {
            return Result(
                viewPoints = calibrationPoints.map { PointF(it.x, it.y) },
                stable = false,
                quality = 0f,
                hasMoved = false
            )
        }

        val fallbackDx = median(goodShiftsX)
        val fallbackDy = median(goodShiftsY)
        var scoreSum = 0f
        var scoreCount = 0
        var movedInFrame = false

        for (i in anchors.indices) {
            val anchor = anchors[i]
            val p = found[i] ?: PointF(anchor.x + fallbackDx, anchor.y + fallbackDy)
            if (abs(p.x - anchor.x) > 0.35f || abs(p.y - anchor.y) > 0.35f) movedInFrame = true

            // Ușoară netezire pentru ca simbolurile să nu tremure pe ecran.
            anchor.x = anchor.x * 0.35f + p.x * 0.65f
            anchor.y = anchor.y * 0.35f + p.y * 0.65f

            if (scores[i] != Float.MAX_VALUE) {
                scoreSum += scores[i]
                scoreCount++
            }
        }

        val output = anchors.map { anchor ->
            frame.frameToView(PointF(anchor.x, anchor.y), viewWidth, viewHeight)
        }

        val movedOnScreen = output.indices.any { i ->
            val oldView = frame.frameToView(previous[i], viewWidth, viewHeight)
            abs(output[i].x - oldView.x) > 0.6f || abs(output[i].y - oldView.y) > 0.6f
        }

        val avgScore = if (scoreCount == 0) MAX_SCORE else scoreSum / scoreCount
        val quality = (1f - avgScore / MAX_SCORE).coerceIn(0f, 1f)
        return Result(
            viewPoints = output,
            stable = true,
            quality = quality,
            hasMoved = movedInFrame || movedOnScreen
        )
    }

    private fun initialize(
        frame: GrayFrame,
        viewWidth: Int,
        viewHeight: Int,
        calibrationPoints: List<PointF>
    ): Boolean {
        anchors.clear()
        for (viewPoint in calibrationPoints) {
            val framePoint = frame.viewToFrame(viewPoint, viewWidth, viewHeight) ?: run {
                anchors.clear()
                return false
            }
            val x = framePoint.x.toInt()
            val y = framePoint.y.toInt()
            val template = frame.normalizedPatch(x, y, PATCH_RADIUS, PATCH_STEP) ?: run {
                anchors.clear()
                return false
            }
            anchors.add(Anchor(framePoint.x, framePoint.y, template))
        }
        frameWidth = frame.width
        frameHeight = frame.height
        return anchors.size == 4
    }

    private fun search(frame: GrayFrame, anchor: Anchor): Pair<PointF, Float>? {
        val radius = max(10, (frame.width * 0.025f).toInt()).coerceAtMost(24)
        val centerX = anchor.x.toInt()
        val centerY = anchor.y.toInt()

        var bestX = centerX
        var bestY = centerY
        var bestScore = Float.MAX_VALUE

        var dy = -radius
        while (dy <= radius) {
            var dx = -radius
            while (dx <= radius) {
                val x = centerX + dx
                val y = centerY + dy
                val score = frame.patchScore(x, y, anchor.template, PATCH_RADIUS, PATCH_STEP)
                if (score != null && score < bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
                dx += 2
            }
            dy += 2
        }

        // Rafinare la pixel în jurul celui mai bun candidat grosier.
        val coarseX = bestX
        val coarseY = bestY
        for (y in coarseY - 2..coarseY + 2) {
            for (x in coarseX - 2..coarseX + 2) {
                val score = frame.patchScore(x, y, anchor.template, PATCH_RADIUS, PATCH_STEP)
                if (score != null && score < bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
            }
        }

        return if (bestScore == Float.MAX_VALUE) null else PointF(bestX.toFloat(), bestY.toFloat()) to bestScore
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }

    private companion object {
        const val PATCH_RADIUS = 6
        const val PATCH_STEP = 2
        const val MAX_SCORE = 28f
    }
}
