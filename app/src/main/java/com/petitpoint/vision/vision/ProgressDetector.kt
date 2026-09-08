package com.petitpoint.vision.vision

import com.petitpoint.vision.model.GridCell
import kotlin.math.abs
import kotlin.math.max

/**
 * Detector de progres pentru Petit Point. Pentru fiecare celula tinta invata mai multe cadre de
 * referinta, apoi cauta o schimbare locala persistenta. Raza mostrei este adaptata la marimea
 * celulei pe ecran, ca sa nu ratam firul foarte fin.
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
    private var baselineAnnounced = false

    @Synchronized
    fun resetAll() {
        baselines.clear()
        completed.clear()
        baselineAnnounced = false
    }

    @Synchronized
    fun resetBaselineKeepCompleted() {
        baselines.clear()
        baselineAnnounced = false
    }

    @Synchronized
    fun completedSnapshot(): Set<GridCell> = completed.toSet()

    @Synchronized
    fun process(frame: GrayFrame, samples: List<CellSample>): Result {
        if (samples.isEmpty()) return Result()

        val globalMean = frame.globalMean()

        // Mai intai construim o referinta stabila din mai multe cadre; un singur cadru e prea fragil
        // pentru petit point si poate prinde degetul imediat dupa calibrare.
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

        val enoughBaseline = baselinesInView > 0 && readyInView >= max(1, (baselinesInView * 0.70f).toInt())
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

            // Firul poate avea aproape aceeasi luminozitate ca panza, de aceea muchiile cantaresc mai mult.
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

        // Mana/umbra modifica simultan multe celule; nu le marcam ca fiind cusute.
        val maxAllowedChanged = max(7, (samples.size * 0.42f).toInt())
        if (candidates.size > maxAllowedChanged) {
            for (candidate in candidates) baselines[candidate.cell]?.consecutiveChanged = 0
            return Result(baselineJustCaptured = baselineJustCaptured, occlusionRejected = true)
        }

        // Adaptare lenta la schimbari de expunere.
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

    private companion object {
        const val BASELINE_FRAMES = 6
        const val REQUIRED_STABLE_FRAMES = 4
    }
}
