package com.petitpoint.vision.vision

import com.petitpoint.vision.model.GridCell
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Detector de progres pentru Petit Point. Pentru fiecare celulă țintă învață mai multe cadre de
 * referință, apoi caută o schimbare locală persistentă. În paralel, pentru celula curentă din traseu
 * pornește detectorul experimental al acului vizibil și publică direcția până la ochiul țintă.
 */
class ProgressDetector {

    data class CellSample(
        val cell: GridCell,
        val frameX: Float,
        val frameY: Float,
        val radius: Int = 5
    )

    data class Result(
        val newlyCompleted: Set<GridCell> = emptySet(),
        val baselineJustCaptured: Boolean = false,
        val occlusionRejected: Boolean = false
    )

    private data class Baseline(
        var correctedMean: Float,
        var gradient: Float,
        var samples: Int = 1,
        var consecutiveChanged: Int = 0
    )

    private val baselines = HashMap<GridCell, Baseline>()
    private val completed = HashSet<GridCell>()
    private val needleDetector = NeedleDetector()
    private var baselineAnnounced = false
    private var needleFrameCounter = 0
    private var lastNeedleFocus: GridCell? = null

    @Synchronized
    fun resetAll() {
        baselines.clear()
        completed.clear()
        baselineAnnounced = false
        needleDetector.reset()
        needleFrameCounter = 0
        lastNeedleFocus = null
        NeedleGuidanceState.clearDetection()
    }

    @Synchronized
    fun resetBaselineKeepCompleted() {
        baselines.clear()
        baselineAnnounced = false
        needleDetector.reset()
        needleFrameCounter = 0
        NeedleGuidanceState.clearDetection()
    }

    @Synchronized
    fun completedSnapshot(): Set<GridCell> = completed.toSet()

    @Synchronized
    fun process(frame: GrayFrame, samples: List<CellSample>): Result {
        if (samples.isEmpty()) {
            NeedleGuidanceState.clearDetection()
            return Result()
        }

        updateNeedleGuidance(frame, samples)

        val globalMean = frame.globalMean()

        // Mai întâi construim o referință stabilă din mai multe cadre; un singur cadru este prea
        // fragil pentru Petit Point și poate prinde degetul imediat după calibrare.
        var readyInView = 0
        var baselinesInView = 0
        for (sample in samples) {
            if (completed.contains(sample.cell)) continue
            val stats = frame.localStats(sample.frameX, sample.frameY, sample.radius.coerceIn(3, 16)) ?: continue
            val correctedMean = stats.mean - globalMean
            val baseline = baselines[sample.cell]
            if (baseline == null) {
                baselines[sample.cell] = Baseline(correctedMean, stats.gradient)
            } else if (baseline.samples < BASELINE_FRAMES) {
                val nextCount = baseline.samples + 1
                val alpha = 1f / nextCount.toFloat()
                baseline.correctedMean += (correctedMean - baseline.correctedMean) * alpha
                baseline.gradient += (stats.gradient - baseline.gradient) * alpha
                baseline.samples = nextCount
            }
            val current = baselines[sample.cell] ?: continue
            baselinesInView++
            if (current.samples >= BASELINE_FRAMES) readyInView++
        }

        val enoughBaseline = baselinesInView > 0 &&
            readyInView >= max(1, (baselinesInView * 0.70f).toInt())
        if (!enoughBaseline) return Result()

        val baselineJustCaptured = !baselineAnnounced
        baselineAnnounced = true

        data class Candidate(val cell: GridCell, val distance: Float)
        val candidates = ArrayList<Candidate>()
        val stable = ArrayList<Pair<GridCell, GrayFrame.LocalStats>>()

        for (sample in samples) {
            if (completed.contains(sample.cell)) continue
            val baseline = baselines[sample.cell] ?: continue
            if (baseline.samples < BASELINE_FRAMES) continue
            val stats = frame.localStats(sample.frameX, sample.frameY, sample.radius.coerceIn(3, 16)) ?: continue
            val correctedMean = stats.mean - globalMean
            val meanDelta = abs(correctedMean - baseline.correctedMean)
            val gradientDelta = abs(stats.gradient - baseline.gradient)

            // Firul poate avea aproape aceeași luminozitate ca pânza, de aceea muchiile cântăresc mai mult.
            val distance = meanDelta * 0.58f + gradientDelta * 1.35f
            val threshold = (5.5f + baseline.gradient * 0.28f).coerceIn(6.2f, 12.5f)
            val hardOcclusion = threshold * 4.4f

            when {
                distance > hardOcclusion -> baseline.consecutiveChanged = 0
                distance >= threshold -> candidates.add(Candidate(sample.cell, distance))
                else -> {
                    baseline.consecutiveChanged = 0
                    stable.add(sample.cell to stats)
                }
            }
        }

        // Mâna/umbra modifică simultan multe celule; nu le marcăm ca fiind cusute.
        val maxAllowedChanged = max(7, (samples.size * 0.42f).toInt())
        if (candidates.size > maxAllowedChanged) {
            for (candidate in candidates) baselines[candidate.cell]?.consecutiveChanged = 0
            return Result(baselineJustCaptured = baselineJustCaptured, occlusionRejected = true)
        }

        // Adaptare lentă la schimbări de expunere.
        for ((cell, stats) in stable) {
            val baseline = baselines[cell] ?: continue
            val correctedMean = stats.mean - globalMean
            baseline.correctedMean = baseline.correctedMean * 0.992f + correctedMean * 0.008f
            baseline.gradient = baseline.gradient * 0.992f + stats.gradient * 0.008f
        }

        val candidateCells = candidates.mapTo(HashSet()) { it.cell }
        val newlyCompleted = HashSet<GridCell>()
        for (candidate in candidates) {
            val baseline = baselines[candidate.cell] ?: continue
            baseline.consecutiveChanged++
            if (baseline.consecutiveChanged >= REQUIRED_STABLE_FRAMES) {
                completed.add(candidate.cell)
                newlyCompleted.add(candidate.cell)
                baselines.remove(candidate.cell)
            }
        }

        for ((cell, baseline) in baselines) {
            if (!candidateCells.contains(cell)) baseline.consecutiveChanged = 0
        }

        return Result(
            newlyCompleted = newlyCompleted,
            baselineJustCaptured = baselineJustCaptured
        )
    }

    private fun updateNeedleGuidance(frame: GrayFrame, samples: List<CellSample>) {
        val focus = NeedleGuidanceState.focusedCell()
        if (focus == null) {
            if (lastNeedleFocus != null) needleDetector.reset()
            lastNeedleFocus = null
            NeedleGuidanceState.clearDetection()
            return
        }

        if (focus != lastNeedleFocus) {
            lastNeedleFocus = focus
            needleDetector.reset()
            needleFrameCounter = 0
            NeedleGuidanceState.clearDetection()
        }

        val sample = samples.firstOrNull { it.cell == focus }
        if (sample == null) {
            NeedleGuidanceState.clearDetection()
            return
        }

        needleFrameCounter++
        if (needleFrameCounter % NEEDLE_EVERY_N_FRAMES != 0) return

        val searchRadius = (sample.radius * 9 + 18).coerceIn(48, 150)
        val detection = needleDetector.detect(
            frame = frame,
            targetX = sample.frameX,
            targetY = sample.frameY,
            searchRadius = searchRadius
        )

        if (detection == null) {
            NeedleGuidanceState.publish(
                NeedleGuidanceState.Snapshot(
                    frameWidth = frame.width,
                    frameHeight = frame.height,
                    targetX = sample.frameX,
                    targetY = sample.frameY,
                    arrow = "?",
                    message = "AC: NU VĂD ACUL",
                    confidence = 0f,
                    aligned = false
                )
            )
            return
        }

        val dx = sample.frameX - detection.tipX
        val dy = sample.frameY - detection.tipY
        val distance = hypot(dx, dy)
        val tolerance = max(3.5f, sample.radius * 0.65f)
        val aligned = distance <= tolerance

        val arrow: String
        val message: String
        if (aligned) {
            arrow = "✓"
            message = "AC: AICI"
        } else if (abs(dx) >= abs(dy)) {
            if (dx > 0f) {
                arrow = "→"
                message = "AC: DREAPTA"
            } else {
                arrow = "←"
                message = "AC: STÂNGA"
            }
        } else {
            if (dy > 0f) {
                arrow = "↓"
                message = "AC: JOS"
            } else {
                arrow = "↑"
                message = "AC: SUS"
            }
        }

        NeedleGuidanceState.publish(
            NeedleGuidanceState.Snapshot(
                frameWidth = frame.width,
                frameHeight = frame.height,
                targetX = sample.frameX,
                targetY = sample.frameY,
                needleTipX = detection.tipX,
                needleTipY = detection.tipY,
                needleTailX = detection.tailX,
                needleTailY = detection.tailY,
                arrow = arrow,
                message = message,
                confidence = detection.confidence,
                aligned = aligned
            )
        )
    }

    private companion object {
        const val BASELINE_FRAMES = 6
        const val REQUIRED_STABLE_FRAMES = 4
        const val NEEDLE_EVERY_N_FRAMES = 3
    }
}
