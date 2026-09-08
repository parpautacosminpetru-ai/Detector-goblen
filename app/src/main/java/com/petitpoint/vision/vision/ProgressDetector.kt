package com.petitpoint.vision.vision

import com.petitpoint.vision.model.GridCell
import kotlin.math.abs
import kotlin.math.max

/**
 * Detector experimental de progres. Memorează aspectul fiecărei poziții țintă după calibrare și
 * consideră o căsuță executată numai dacă schimbarea locală rămâne stabilă mai multe cadre.
 * Schimbările foarte mari sau simultane sunt tratate ca mână/umbră și nu sunt validate.
 */
class ProgressDetector {

    data class CellSample(
        val cell: GridCell,
        val frameX: Float,
        val frameY: Float
    )

    data class Result(
        val newlyCompleted: Set<GridCell> = emptySet(),
        val baselineJustCaptured: Boolean = false,
        val occlusionRejected: Boolean = false
    )

    private data class Baseline(
        var correctedMean: Float,
        var gradient: Float,
        var consecutiveChanged: Int = 0
    )

    private val baselines = HashMap<GridCell, Baseline>()
    private val completed = HashSet<GridCell>()

    @Synchronized
    fun resetAll() {
        baselines.clear()
        completed.clear()
    }

    @Synchronized
    fun resetBaselineKeepCompleted() {
        baselines.clear()
    }

    @Synchronized
    fun completedSnapshot(): Set<GridCell> = completed.toSet()

    @Synchronized
    fun process(frame: GrayFrame, samples: List<CellSample>): Result {
        if (samples.isEmpty()) return Result()

        val globalMean = frame.globalMean()
        val addedThisFrame = HashSet<GridCell>()
        var hadAnyBaselineBefore = baselines.isNotEmpty()

        // Învățăm pozițiile care nu au încă referință. Cele deja marcate executate nu mai contează.
        for (sample in samples) {
            if (completed.contains(sample.cell) || baselines.containsKey(sample.cell)) continue
            val stats = frame.localStats(sample.frameX, sample.frameY) ?: continue
            baselines[sample.cell] = Baseline(
                correctedMean = stats.mean - globalMean,
                gradient = stats.gradient
            )
            addedThisFrame.add(sample.cell)
        }

        if (!hadAnyBaselineBefore && addedThisFrame.isNotEmpty()) {
            return Result(baselineJustCaptured = true)
        }

        data class Candidate(val cell: GridCell, val distance: Float, val stats: GrayFrame.LocalStats)
        val candidates = ArrayList<Candidate>()
        val stableSamples = ArrayList<Pair<GridCell, GrayFrame.LocalStats>>()

        for (sample in samples) {
            if (completed.contains(sample.cell) || addedThisFrame.contains(sample.cell)) continue
            val baseline = baselines[sample.cell] ?: continue
            val stats = frame.localStats(sample.frameX, sample.frameY) ?: continue
            val correctedMean = stats.mean - globalMean
            val meanDelta = abs(correctedMean - baseline.correctedMean)
            val gradientDelta = abs(stats.gradient - baseline.gradient)
            val distance = meanDelta * 0.72f + gradientDelta * 0.95f

            when {
                distance > HARD_OCCLUSION_DISTANCE -> {
                    baseline.consecutiveChanged = 0
                }
                distance >= CHANGE_THRESHOLD -> {
                    candidates.add(Candidate(sample.cell, distance, stats))
                }
                else -> {
                    baseline.consecutiveChanged = 0
                    stableSamples.add(sample.cell to stats)
                }
            }
        }

        // Dacă multe ținte se schimbă în același cadru, de obicei este mâna, o umbră sau camera.
        val maxAllowedChanged = max(6, (samples.size * 0.32f).toInt())
        if (candidates.size > maxAllowedChanged) {
            for (candidate in candidates) {
                baselines[candidate.cell]?.consecutiveChanged = 0
            }
            return Result(occlusionRejected = true)
        }

        // Adaptare foarte lentă la lumină/expunere, doar pentru pozițiile care par neschimbate.
        for ((cell, stats) in stableSamples) {
            val baseline = baselines[cell] ?: continue
            val correctedMean = stats.mean - globalMean
            baseline.correctedMean = baseline.correctedMean * 0.985f + correctedMean * 0.015f
            baseline.gradient = baseline.gradient * 0.985f + stats.gradient * 0.015f
        }

        val newlyCompleted = HashSet<GridCell>()
        val candidateCells = candidates.mapTo(HashSet()) { it.cell }

        for (candidate in candidates) {
            val baseline = baselines[candidate.cell] ?: continue
            baseline.consecutiveChanged++
            if (baseline.consecutiveChanged >= REQUIRED_STABLE_FRAMES) {
                completed.add(candidate.cell)
                newlyCompleted.add(candidate.cell)
                baselines.remove(candidate.cell)
            }
        }

        // Orice bază care nu mai este candidat își pierde seria de confirmări.
        for ((cell, baseline) in baselines) {
            if (!candidateCells.contains(cell)) baseline.consecutiveChanged = 0
        }

        return Result(newlyCompleted = newlyCompleted)
    }

    private companion object {
        const val CHANGE_THRESHOLD = 14.5f
        const val HARD_OCCLUSION_DISTANCE = 55f
        const val REQUIRED_STABLE_FRAMES = 8
    }
}
