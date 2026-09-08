package com.petitpoint.vision.model

import android.graphics.Bitmap

/**
 * Asocierea dintre codul de ață citit din legenda fotografiată și simbolul tipărit lângă el.
 * Fingerprint-ul este folosit offline pentru a găsi același simbol în grila diagramei.
 */
data class CodeLegendEntry(
    val code: String,
    val symbolBitmap: Bitmap,
    val fingerprint: BooleanArray,
    val score: Float
)
