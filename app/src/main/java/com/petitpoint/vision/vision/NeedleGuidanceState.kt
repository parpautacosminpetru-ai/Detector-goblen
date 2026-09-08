package com.petitpoint.vision.vision

import com.petitpoint.vision.model.GridCell

/**
 * Stare foarte mică, numai în memorie, care leagă traseul de lucru de detectorul acului și de
 * overlay-ul vizual. Nu salvează imagini și nu folosește rețeaua.
 */
object NeedleGuidanceState {

    data class Snapshot(
        val frameWidth: Int,
        val frameHeight: Int,
        val targetX: Float,
        val targetY: Float,
        val needleTipX: Float? = null,
        val needleTipY: Float? = null,
        val needleTailX: Float? = null,
        val needleTailY: Float? = null,
        val arrow: String,
        val message: String,
        val confidence: Float,
        val aligned: Boolean,
        val updatedAtMs: Long = System.currentTimeMillis()
    )

    @Volatile
    private var focusedCell: GridCell? = null

    @Volatile
    private var latestSnapshot: Snapshot? = null

    fun setFocusedCell(cell: GridCell?) {
        if (focusedCell != cell) {
            focusedCell = cell
            latestSnapshot = null
        }
    }

    fun focusedCell(): GridCell? = focusedCell

    fun publish(snapshot: Snapshot) {
        latestSnapshot = snapshot
    }

    fun clearDetection() {
        latestSnapshot = null
    }

    fun snapshot(): Snapshot? = latestSnapshot
}
