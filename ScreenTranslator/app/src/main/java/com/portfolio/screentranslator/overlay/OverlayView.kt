package com.portfolio.screentranslator.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.portfolio.screentranslator.translate.TranslatedBlock
import kotlin.math.max
import kotlin.math.min

/**
 * Paints one or more translated blocks.
 * - Normal blocks: the background is painted only where the original *lines* were (tight
 *   per-line boxes in the sampled background colour), and the translation is laid out
 *   across the block at roughly the original glyph size, shrinking until it fits.
 * - SFX: no box, outlined text, so the drawing underneath is not hidden.
 */
class OverlayView(context: Context) : View(context) {

    var blocks: List<TranslatedBlock> = emptyList()
        set(v) { field = v; invalidate() }

    var fontScale = 1f
        set(v) { field = v; invalidate() }

    /** false = opaque panel behind the text, true = outline (halo) only. */
    var outlineStyle = false

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
    }
    private val density = context.resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        for (b in blocks) {
            if (b.isSfx) drawSfx(canvas, b) else drawBlock(canvas, b)
        }
    }

    private fun drawBlock(canvas: Canvas, b: TranslatedBlock) {
        boxPaint.color = b.bgColor
        boxPaint.style = Paint.Style.FILL
        // Padding proportional to the glyph size: ML Kit boxes hug the glyphs, so without it the
        // anti-aliased edges and descenders of the original text stay visible.
        val pad = max(4 * density, b.lineHeight * 0.4f)
        val sorted = if (b.vertical) b.lines.sortedBy { it.left } else b.lines.sortedBy { it.top }
        for (line in sorted) {
            canvas.drawRoundRect(RectF(line).apply { inset(-pad, -pad) }, 3 * density, 3 * density, boxPaint)
        }
        // Fill the gap between consecutive lines / columns so nothing peeks through in between.
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]; val c = sorted[i + 1]
            val gap = if (b.vertical) RectF(a.left.toFloat(), min(a.top, c.top).toFloat(), c.right.toFloat(), max(a.bottom, c.bottom).toFloat())
                      else RectF(min(a.left, c.left).toFloat(), a.top.toFloat(), max(a.right, c.right).toFloat(), c.bottom.toFloat())
            if ((b.vertical && c.left - a.right < b.lineHeight * 1.5f) || (!b.vertical && c.top - a.bottom < b.lineHeight * 1.5f))
                canvas.drawRect(gap.apply { inset(-pad * 0.5f, -pad * 0.5f) }, boxPaint)
        }

        textPaint.color = b.fgColor
        textPaint.isFakeBoldText = true   // thin Korean glyphs over artwork are hard to read
        val scaled = resources.displayMetrics.scaledDensity
        // Start at the original glyph size: for horizontal text the line height is ~1.2x the
        // font size; for vertical columns the column width *is* the glyph size.
        val glyphSp = b.lineHeight / scaled / (if (b.vertical) 1.0f else 1.2f)
        val startSp = (glyphSp * fontScale).coerceIn(10f, 44f)
        val layout = fitText(b.text, textPaint, b.box, minSp = 9f, startSp = startSp) ?: return
        val dy = b.box.top + max(0f, (b.box.height() - layout.height) / 2f)
        if (b.vertical && !outlineStyle) {
            // Horizontal replacement for vertical text: an opaque panel just big enough for it.
            val textW = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
            val left = b.box.left + (b.box.width() - textW) / 2f
            val panel = RectF(left - pad, dy - pad * 0.6f, left + textW + pad, dy + layout.height + pad * 0.6f)
            canvas.drawRoundRect(panel, 4 * density, 4 * density, boxPaint)
        }
        canvas.save()
        canvas.translate(b.box.left.toFloat(), dy)
        if (outlineStyle) {
            // Thick halo in the background colour hides whatever is directly under the glyphs.
            strokePaint.textSize = textPaint.textSize
            strokePaint.color = b.bgColor
            strokePaint.strokeWidth = max(3 * density, textPaint.textSize * 0.22f)
            strokePaint.isFakeBoldText = true
            StaticLayout.Builder.obtain(b.text, 0, b.text.length, strokePaint, max(1, b.box.width()))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build().draw(canvas)
        }
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawSfx(canvas: Canvas, b: TranslatedBlock) {
        val scaled = resources.displayMetrics.scaledDensity
        val startSp = (b.box.height() / scaled * 0.8f * fontScale).coerceIn(10f, 30f)
        val fill = fitText(b.text, textPaint.apply { color = Color.WHITE; isFakeBoldText = true }, b.box, 10f, startSp)
            ?: return
        strokePaint.textSize = textPaint.textSize
        strokePaint.color = Color.BLACK
        strokePaint.strokeWidth = 4f
        strokePaint.isFakeBoldText = true
        val stroke = StaticLayout.Builder.obtain(b.text, 0, b.text.length, strokePaint, max(1, b.box.width()))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        canvas.save()
        val dy = b.box.top + max(0f, (b.box.height() - fill.height) / 2f)
        canvas.translate(b.box.left.toFloat(), dy)
        stroke.draw(canvas)
        fill.draw(canvas)
        canvas.restore()
    }

    /** Shrinks the font from [startSp] until the wrapped text fits inside [box]. */
    private fun fitText(text: String, paint: TextPaint, box: Rect, minSp: Float, startSp: Float): StaticLayout? {
        val width = box.width()
        if (width < 8 || box.height() < 6) return null
        val scaled = resources.displayMetrics.scaledDensity
        var size = max(minSp, startSp)
        while (true) {
            paint.textSize = size * scaled
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
            if (layout.height <= box.height() + 2 * scaled || size <= minSp) return layout
            size -= 1f
        }
    }
}
