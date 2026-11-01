package com.portfolio.screentranslator.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.portfolio.screentranslator.translate.SourceBlock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * ML Kit text recognition. In "auto" mode the Japanese and Chinese recognisers run in
 * parallel (both also read Latin) and the one that found more CJK characters wins.
 */
class OcrEngine {
    private val latin: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val japanese: TextRecognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val chinese: TextRecognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    suspend fun recognise(bitmap: Bitmap, sourceLang: String): List<SourceBlock> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text: Text = when (sourceLang) {
            "ja" -> japanese.process(image).await()
            "zh" -> chinese.process(image).await()
            "en" -> latin.process(image).await()
            else -> coroutineScope {
                val ja = async { japanese.process(image).await() }
                val zh = async { chinese.process(image).await() }
                val a = ja.await(); val b = zh.await()
                if (score(a) >= score(b)) a else b
            }
        }
        return toBlocks(text, bitmap.width, bitmap.height)
    }

    private fun score(t: Text): Int {
        var kana = 0; var han = 0; var total = 0
        for (ch in t.text) {
            val c = ch.code
            if (c in 0x3040..0x30FF) kana++
            if (c in 0x4E00..0x9FFF) han++
            if (!ch.isWhitespace()) total++
        }
        // Kana is a strong Japanese signal; weight it so the Japanese model wins on manga.
        return total + kana * 3 + han
    }

    private fun toBlocks(text: Text, w: Int, h: Int): List<SourceBlock> {
        val out = ArrayList<SourceBlock>()
        var id = 1
        for (block in text.textBlocks) {
            val box = block.boundingBox ?: continue
            val s = block.text.trim()
            if (s.length < 1) continue
            if (!hasLetters(s)) continue
            val r = Rect(box)
            r.left = r.left.coerceIn(0, w); r.right = r.right.coerceIn(0, w)
            r.top = r.top.coerceIn(0, h); r.bottom = r.bottom.coerceIn(0, h)
            if (r.width() < 4 || r.height() < 4) continue
            val vertical = block.lines.size >= 2 && r.height() > r.width() * 1.8 &&
                block.lines.all { (it.boundingBox?.width() ?: 0) <= (it.boundingBox?.height() ?: 0) * 1.5 }
            out += SourceBlock(id++, s, r, vertical)
        }
        return out
    }

    private fun hasLetters(s: String): Boolean = s.any { it.isLetter() }
}
