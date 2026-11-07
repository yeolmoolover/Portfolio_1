package com.portfolio.screentranslator.util

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ImageUtil {

    /** Converts an RGBA_8888 [Image] from ImageReader into a Bitmap (handles row padding). */
    fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    /**
     * Tiny perceptual fingerprint (16x32 grey) used to detect "screen changed" cheaply.
     */
    fun fingerprint(bmp: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bmp, 16, 32, true)
        val out = IntArray(16 * 32)
        val px = IntArray(16 * 32)
        small.getPixels(px, 0, 16, 0, 0, 16, 32)
        for (i in px.indices) {
            val p = px[i]
            out[i] = ((p shr 16 and 0xFF) * 3 + (p shr 8 and 0xFF) * 6 + (p and 0xFF)) / 10
        }
        small.recycle()
        return out
    }

    /** Mean absolute difference between two fingerprints (0..255). */
    fun diff(a: IntArray?, b: IntArray?): Int {
        if (a == null || b == null || a.size != b.size) return 255
        var sum = 0
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / a.size
    }

    /** Result of [refineVerticalText]: true text area, one rect per column, glyph size. */
    class InkRegion(val box: Rect, val columns: List<Rect>, val glyph: Int)

    /**
     * ML Kit reports vertical CJK text with (almost) zero-width boxes. Starting from that hint,
     * measure where the ink really is: expand left/right over columns that contain dark pixels
     * (allowing gaps smaller than a glyph), then top/bottom the same way. Works on the plain
     * background of a speech bubble; returns null when nothing dark is found.
     */
    fun refineVerticalText(bmp: Bitmap, hint: Rect, bgLum: Int): InkRegion? {
        val w = bmp.width; val h = bmp.height
        val cx = hint.centerX().coerceIn(0, w - 1)
        val span = maxOf(hint.height(), 80)
        val x0 = (cx - span).coerceAtLeast(0); val x1 = (cx + span).coerceAtMost(w - 1)
        val y0 = (hint.top - hint.height() / 3).coerceAtLeast(0)
        val y1 = (hint.bottom + hint.height() / 3).coerceAtMost(h - 1)
        if (x1 - x0 < 4 || y1 - y0 < 4) return null
        val rw = x1 - x0 + 1; val rh = y1 - y0 + 1
        val px = IntArray(rw * rh)
        bmp.getPixels(px, 0, rw, x0, y0, rw, rh)
        val thr = bgLum - 70
        val ink = BooleanArray(rw * rh) { luminance(px[it]) < thr }

        // column profile over the hinted vertical range
        val hy0 = (hint.top - y0).coerceIn(0, rh - 1); val hy1 = (hint.bottom - y0).coerceIn(0, rh - 1)
        val colInk = IntArray(rw) { x -> var c = 0; for (y in hy0..hy1) if (ink[y * rw + x]) c++; c }
        val cxr = cx - x0
        // find the central run (seed) - allow a tiny search around the hint centre
        var seed = -1
        for (d in 0..maxOf(4, hint.width())) {
            if (cxr + d < rw && colInk[cxr + d] > 0) { seed = cxr + d; break }
            if (cxr - d >= 0 && colInk[cxr - d] > 0) { seed = cxr - d; break }
        }
        if (seed < 0) return null
        fun runAround(i: Int): IntRange { var a = i; var b = i; while (a > 0 && colInk[a - 1] > 0) a--; while (b < rw - 1 && colInk[b + 1] > 0) b++; return a..b }
        val first = runAround(seed)
        var glyph = maxOf(6, first.last - first.first + 1)
        val maxGap = (glyph * 1.1f).toInt()
        // expand left / right collecting column runs
        val runs = ArrayList<IntRange>(); runs += first
        var x = first.first - 1; var gap = 0
        while (x >= 0) { if (colInk[x] > 0) { val r = runAround(x); runs.add(0, r); x = r.first - 1; gap = 0 } else { gap++; if (gap > maxGap) break; x-- } }
        x = first.last + 1; gap = 0
        while (x < rw) { if (colInk[x] > 0) { val r = runAround(x); runs.add(r); x = r.last + 1; gap = 0 } else { gap++; if (gap > maxGap) break; x++ } }
        // merge runs narrower than a third of a glyph into neighbours (strokes of one column)
        val merged = ArrayList<IntRange>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && r.first - last.last - 1 < glyph * 0.45f) merged[merged.size - 1] = last.first..r.last else merged += r
        }
        val widths = merged.map { it.last - it.first + 1 }.sorted()
        glyph = maxOf(glyph, widths[widths.size / 2])
        val left = merged.first().first; val right = merged.last().last
        // row profile over the found columns, expand up / down from the hint centre
        val rowInk = IntArray(rh) { y -> var c = 0; for (xx in left..right) if (ink[y * rw + xx]) c++; c }
        val cyr = ((hint.centerY() - y0).coerceIn(0, rh - 1))
        var top = cyr; var bottom = cyr
        var g = 0; var yy = cyr
        while (yy >= 0) { if (rowInk[yy] > 0) { top = yy; g = 0 } else { g++; if (g > maxGap) break }; yy-- }
        g = 0; yy = cyr
        while (yy < rh) { if (rowInk[yy] > 0) { bottom = yy; g = 0 } else { g++; if (g > maxGap) break }; yy++ }
        if (bottom - top < glyph) return null
        val box = Rect(x0 + left, y0 + top, x0 + right + 1, y0 + bottom + 1)
        val cols = merged.map { Rect(x0 + it.first, y0 + top, x0 + it.last + 1, y0 + bottom + 1) }
        return InkRegion(box, cols, glyph)
    }

    /**
     * Copy with boosted contrast for OCR only: scanlations often use thin light-grey type that
     * the recogniser misses or only partly detects. Mid greys are pushed towards black.
     */
    fun boostForOcr(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val c = 1.9f                      // contrast
        val t = 128f * (1f - c) - 25f     // keep white white-ish, pull greys down
        val cm = android.graphics.ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        ))
        val paint = android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** JPEG with the long edge limited to [maxLongEdge] px (keeps LLM image tokens small). */
    fun toJpeg(bmp: Bitmap, maxLongEdge: Int, quality: Int = 70): Triple<ByteArray, Int, Int> {
        val scale = min(1f, maxLongEdge.toFloat() / max(bmp.width, bmp.height))
        val w = max(1, (bmp.width * scale).toInt())
        val h = max(1, (bmp.height * scale).toInt())
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        if (scaled !== bmp) scaled.recycle()
        return Triple(bos.toByteArray(), w, h)
    }

    /**
     * Picks a background and a text colour for a text box from the screenshot:
     * background = most common colour in a thin ring just outside the box,
     * foreground = the in-box colour with the largest luminance distance from it.
     */
    fun sampleColors(bmp: Bitmap, box: Rect): Pair<Int, Int> {
        val ring = 6
        val outer = Rect(box.left - ring, box.top - ring, box.right + ring, box.bottom + ring)
        outer.left = outer.left.coerceIn(0, bmp.width - 1); outer.right = outer.right.coerceIn(1, bmp.width)
        outer.top = outer.top.coerceIn(0, bmp.height - 1); outer.bottom = outer.bottom.coerceIn(1, bmp.height)

        val bgHist = HashMap<Int, Int>()
        val sumR = HashMap<Int, Long>(); val sumG = HashMap<Int, Long>(); val sumB = HashMap<Int, Long>()
        fun addAll(px: IntArray, n: Int) {
            var i = 0
            while (i < n) {
                val p = px[i]; val q = quantize(p)
                bgHist[q] = (bgHist[q] ?: 0) + 1
                sumR[q] = (sumR[q] ?: 0L) + (p shr 16 and 0xFF)
                sumG[q] = (sumG[q] ?: 0L) + (p shr 8 and 0xFF)
                sumB[q] = (sumB[q] ?: 0L) + (p and 0xFF)
                i += 2
            }
        }
        val w = outer.width(); val h = outer.height()
        val row = IntArray(w); val col = IntArray(h)
        bmp.getPixels(row, 0, w, outer.left, outer.top, w, 1); addAll(row, w)
        bmp.getPixels(row, 0, w, outer.left, outer.bottom - 1, w, 1); addAll(row, w)
        bmp.getPixels(col, 0, 1, outer.left, outer.top, 1, h); addAll(col, h)
        bmp.getPixels(col, 0, 1, outer.right - 1, outer.top, 1, h); addAll(col, h)
        // Most common colour bin, then the exact average inside it (so white stays #FFFFFF).
        val bin = bgHist.maxByOrNull { it.value }
        val bg = if (bin == null) 0xFFFFFFFF.toInt() else {
            val n = bin.value.toLong().coerceAtLeast(1)
            (0xFF shl 24) or ((sumR[bin.key]!! / n).toInt() shl 16) or ((sumG[bin.key]!! / n).toInt() shl 8) or (sumB[bin.key]!! / n).toInt()
        }
        val bgLum = luminance(bg)

        var fg = if (bgLum > 128) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        var bestDist = 0
        val stepX = max(1, box.width() / 24); val stepY = max(1, box.height() / 12)
        var yy = box.top
        while (yy < box.bottom) {
            var xx = box.left
            while (xx < box.right) {
                if (xx in 0 until bmp.width && yy in 0 until bmp.height) {
                    val p = bmp.getPixel(xx, yy)
                    val d = abs(luminance(p) - bgLum)
                    if (d > bestDist) { bestDist = d; fg = p or 0xFF000000.toInt() }
                }
                xx += stepX
            }
            yy += stepY
        }
        if (bestDist < 40) fg = if (bgLum > 128) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        return bg to fg
    }

    private fun quantize(p: Int): Int {
        val r = (p shr 16 and 0xFF) and 0xF0
        val g = (p shr 8 and 0xFF) and 0xF0
        val b = (p and 0xFF) and 0xF0
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b or 0x080808
    }

    fun luminance(p: Int): Int = ((p shr 16 and 0xFF) * 3 + (p shr 8 and 0xFF) * 6 + (p and 0xFF)) / 10
}
