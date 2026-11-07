package com.portfolio.screentranslator.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.portfolio.screentranslator.translate.SourceBlock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * ML Kit text recognition. In "auto" mode the Japanese and Chinese recognisers run in
 * parallel (both also read Latin) and the one that found more CJK characters wins.
 *
 * Blocks that are already Korean are dropped: the target language is Korean, and the
 * ja/zh recognisers cannot read Hangul - they output look-alike hanzi/kana instead, so
 * the source text itself never contains Hangul. A Korean recogniser therefore runs in
 * parallel, and any source block overlapping a Hangul block it found is discarded.
 */
class OcrEngine {
    private val latin: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val japanese: TextRecognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val chinese: TextRecognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val korean: TextRecognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    /** Result of a pass: blocks to translate plus how much Korean was on screen. */
    data class Result(val blocks: List<SourceBlock>, val koreanBlocks: Int)

    suspend fun recognise(bitmap: Bitmap, sourceLang: String): Result = coroutineScope {
        val image = InputImage.fromBitmap(bitmap, 0)
        // Korean pass only decides "is this already Korean?" - half resolution is plenty and
        // roughly halves its latency, which otherwise dominates the parallel OCR step.
        val small = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
        val ko = async { try { korean.process(InputImage.fromBitmap(small, 0)).await() } finally { small.recycle() } }
        val text: Text = when (sourceLang) {
            "ja" -> japanese.process(image).await()
            "zh" -> chinese.process(image).await()
            "en" -> latin.process(image).await()
            else -> {
                val ja = async { japanese.process(image).await() }
                val zh = async { chinese.process(image).await() }
                val a = ja.await(); val b = zh.await()
                if (score(a) >= score(b)) a else b
            }
        }
        // Lines the Korean model reads as Hangul, with how many Hangul letters each holds.
        val koreanLines = ko.await().textBlocks.flatMap { it.lines }
            .filter { isMostlyHangul(it.text) && it.text.count { c -> c.code in 0xAC00..0xD7A3 } >= 2 }
            .mapNotNull { l -> l.boundingBox?.let { b ->
                KoLine(Rect(b.left * 2, b.top * 2, b.right * 2, b.bottom * 2), l.text.count { c -> c.code in 0xAC00..0xD7A3 })
            } }
        toBlocks(text, bitmap.width, bitmap.height, koreanLines, sourceLang)
    }

    private class KoLine(val box: Rect, val hangul: Int)

    /** True when the block's letters are mostly in the script of [lang]. */
    private fun matchesLanguage(s: String, lang: String): Boolean {
        var kana = 0; var han = 0; var latin = 0; var letters = 0
        for (ch in s) {
            if (!ch.isLetter()) continue
            letters++
            val c = ch.code
            when {
                c in 0x3040..0x30FF -> kana++
                c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF -> han++
                c < 0x0250 -> latin++
            }
        }
        if (letters == 0) return false
        return when (lang) {
            "ja" -> kana > 0 || han * 2 > letters          // kanji-only blocks are also Japanese
            "zh" -> han * 2 > letters && kana == 0
            "en" -> latin * 2 > letters && kana == 0 && han == 0
            else -> true
        }
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

    private fun toBlocks(text: Text, w: Int, h: Int, koreanLines: List<KoLine>, lang: String): Result {
        val out = ArrayList<SourceBlock>()
        var korean = 0
        var id = 1
        for (block in text.textBlocks) {
            val box = block.boundingBox ?: continue
            val s = block.text.trim()
            if (s.isEmpty() || !s.any { it.isLetter() }) continue
            if (isMostlyHangul(s)) { korean++; continue }
            if (!matchesLanguage(s, lang)) continue
            val r = clamp(Rect(box), w, h)
            if (r.width() < 4 || r.height() < 4) continue

            val allLines = block.lines.mapNotNull { it.boundingBox?.let { b -> clamp(Rect(b), w, h) } }
                .filter { it.width() >= 2 && it.height() >= 2 }
            // Is this block really Korean? The Korean model also emits Hangul garbage over
            // Chinese/Japanese glyphs, so require BOTH: most lines covered by Hangul lines AND
            // about as many Hangul letters as the ja/zh model saw characters.
            val covered = allLines.count { line -> koreanLines.any { k -> overlap(line, k.box) > 0.4f } }
            val hangulChars = koreanLines.filter { k -> overlap(r, k.box) > 0.3f }.sumOf { it.hangul }
            val cjkChars = s.count { it.isLetter() }
            if (allLines.isNotEmpty() && covered * 2 > allLines.size && hangulChars * 10 >= cjkChars * 7) { korean++; continue }
            // All lines are erased when drawing (even ones the Korean model misread).
            val lineRects = allLines.ifEmpty { listOf(r) }
            // Vertical (tategaki) when most lines are tall narrow columns.
            val tall = lineRects.count { it.height() > it.width() * 1.6 }
            val vertical = s.replace("\n", "").length >= 2 && tall * 2 > lineRects.size
            val glyph = lineRects.map { if (vertical) it.width() else it.height() }.sorted()
            val lineHeight = glyph[glyph.size / 2]
            out += SourceBlock(id++, s, r, vertical, lineRects, lineHeight)
        }
        return Result(mergeVerticalColumns(out), korean)
    }

    /**
     * ML Kit often returns each column of a vertical speech bubble as its own block. Merge
     * vertical blocks that sit side by side (small horizontal gap, overlapping in height)
     * into one block, text joined right-to-left (manga reading order), so the bubble gets
     * one translation and one erase area.
     */
    private fun mergeVerticalColumns(blocks: List<SourceBlock>): List<SourceBlock> {
        val groups = ArrayList<MutableList<SourceBlock>>()
        for (b in blocks) {
            if (!b.vertical) { groups += mutableListOf(b); continue }
            val g = groups.firstOrNull { grp ->
                grp.all { it.vertical } && grp.any { o ->
                    val gapX = maxOf(0, maxOf(b.box.left, o.box.left) - minOf(b.box.right, o.box.right))
                    val overlapY = minOf(b.box.bottom, o.box.bottom) - maxOf(b.box.top, o.box.top)
                    val glyph = maxOf(b.lineHeight, o.lineHeight)
                    gapX <= glyph * 2.2f && overlapY >= minOf(b.box.height(), o.box.height()) * 0.3f
                }
            }
            if (g != null) g += b else groups += mutableListOf(b)
        }
        var id = 1
        return groups.map { g ->
            if (g.size == 1) g[0].copy(id = id++) else {
                val sorted = g.sortedByDescending { it.box.right }          // right-to-left
                val box = Rect(sorted[0].box); for (o in sorted) box.union(o.box)
                val lines = sorted.flatMap { it.lines }
                val glyphs = sorted.map { it.lineHeight }.sorted()
                SourceBlock(id++, sorted.joinToString(" ") { it.text.replace("\n", "") }, box, true, lines, glyphs[glyphs.size / 2])
            }
        }
    }

    /** Intersection area / smaller area. */
    private fun overlap(a: Rect, b: Rect): Float {
        val i = Rect()
        if (!i.setIntersect(a, b)) return 0f
        val inter = i.width().toFloat() * i.height()
        val small = minOf(a.width().toFloat() * a.height(), b.width().toFloat() * b.height()).coerceAtLeast(1f)
        return inter / small
    }

    private fun clamp(r: Rect, w: Int, h: Int): Rect {
        r.left = r.left.coerceIn(0, w); r.right = r.right.coerceIn(0, w)
        r.top = r.top.coerceIn(0, h); r.bottom = r.bottom.coerceIn(0, h)
        return r
    }

    companion object {
        /** True when more than half of the letters are Hangul (already Korean - skip). */
        fun isMostlyHangul(s: String): Boolean {
            var hangul = 0; var letters = 0
            for (ch in s) {
                if (!ch.isLetter()) continue
                letters++
                val c = ch.code
                if (c in 0xAC00..0xD7A3 || c in 0x1100..0x11FF || c in 0x3130..0x318F) hangul++
            }
            return letters > 0 && hangul * 2 > letters
        }
    }
}
