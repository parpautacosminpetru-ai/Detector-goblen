package com.petitpoint.vision.vision

import android.content.Context
import android.graphics.PointF

/**
 * Salvează calibrarea întregii pânze în coordonate normalizate 0..1.
 * Astfel calibrarea poate fi refolosită după relansarea aplicației dacă telefonul/ghergaful
 * au rămas în aceeași poziție.
 */
class CanvasCalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("canvas_calibration_v1", Context.MODE_PRIVATE)

    data class Saved(
        val points: List<PointF>,
        val zoomRatio: Float
    )

    fun save(points: List<PointF>, viewWidth: Int, viewHeight: Int, zoomRatio: Float) {
        if (points.size != 4 || viewWidth <= 0 || viewHeight <= 0) return
        val editor = prefs.edit()
        points.forEachIndexed { index, p ->
            editor.putFloat("x$index", (p.x / viewWidth.toFloat()).coerceIn(-2f, 3f))
            editor.putFloat("y$index", (p.y / viewHeight.toFloat()).coerceIn(-2f, 3f))
        }
        editor.putFloat("zoom", zoomRatio.coerceAtLeast(1f))
        editor.putBoolean("valid", true)
        editor.apply()
    }

    fun load(viewWidth: Int, viewHeight: Int): Saved? {
        if (!prefs.getBoolean("valid", false) || viewWidth <= 0 || viewHeight <= 0) return null
        val points = (0..3).map { index ->
            PointF(
                prefs.getFloat("x$index", Float.NaN) * viewWidth,
                prefs.getFloat("y$index", Float.NaN) * viewHeight
            )
        }
        if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        return Saved(points, prefs.getFloat("zoom", 1f).coerceAtLeast(1f))
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
