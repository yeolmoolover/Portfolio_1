package com.portfolio.screentranslator.translate

import android.graphics.Bitmap
import android.graphics.Rect

/** One block of recognised source text, in screen pixel coordinates. */
data class SourceBlock(
    val id: Int,
    val text: String,
    val box: Rect,
    /** true when the OCR thinks the lines run vertically (common in Japanese manga). */
    val vertical: Boolean = false,
)

/** A translated block to draw. `box` is in screen pixels. */
data class TranslatedBlock(
    val sourceId: Int?,          // null for SFX the model found on its own
    val text: String,
    val box: Rect,
    val isSfx: Boolean,
    val bgColor: Int,
    val fgColor: Int,
)

data class TranslationRequest(
    val blocks: List<SourceBlock>,
    /** Downscaled JPEG of the screen; null when comic mode is off. */
    val imageJpeg: ByteArray?,
    val imageWidth: Int,
    val imageHeight: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val sourceLangHint: String,  // auto | ja | zh | en
    /** Previous screens (source -> korean) for consistency of names / tone. */
    val history: List<Pair<String, String>>,
)

data class TranslatedText(val sourceId: Int?, val text: String, val boxNorm: FloatArray?, val isSfx: Boolean)

data class TranslationResult(
    val items: List<TranslatedText>,
    val engineUsed: String,
)

class QuotaExceededException(msg: String) : Exception(msg)
class EngineUnavailableException(msg: String) : Exception(msg)

interface TranslationEngine {
    val name: String
    /** True when this engine can read the screenshot (needed for SFX detection). */
    val supportsImage: Boolean
    suspend fun translate(req: TranslationRequest): TranslationResult
}
