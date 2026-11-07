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
        fun addAll(px: IntArray, n: Int) { var i = 0; while (i < n) { val q = quantize(px[i]); bgHist[q] = (bgHist[q] ?: 0) + 1; i += 2 } }
        val w = outer.width(); val h = outer.height()
        val row = IntArray(w); val col = IntArray(h)
        bmp.getPixels(row, 0, w, outer.left, outer.top, w, 1); addAll(row, w)
        bmp.getPixels(row, 0, w, outer.left, outer.bottom - 1, w, 1); addAll(row, w)
        bmp.getPixels(col, 0, 1, outer.left, outer.top, 1, h); addAll(col, h)
        bmp.getPixels(col, 0, 1, outer.right - 1, outer.top, 1, h); addAll(col, h)
        val bg = bgHist.maxByOrNull { it.value }?.key ?: 0xFFFFFFFF.toInt()
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
