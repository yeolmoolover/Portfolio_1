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
        val padX = 3 * density
        // Vertical padding closes the gap between consecutive lines so the box reads as one.
        val padY = max(2 * density, b.lineHeight * 0.25f)
        for (line in b.lines) {
            val r = RectF(line).apply { inset(-padX, -padY) }
            canvas.drawRoundRect(r, 3 * density, 3 * density, boxPaint)
        }

        textPaint.color = b.fgColor
        textPaint.isFakeBoldText = false
        val scaled = resources.displayMetrics.scaledDensity
        // Start at the original glyph size (line height ~ 1.2 x font size), then shrink to fit.
        val startSp = (b.lineHeight / scaled / 1.2f * fontScale).coerceIn(8f, 40f)
        val layout = fitText(b.text, textPaint, b.box, minSp = 7f, startSp = startSp) ?: return
        canvas.save()
        val dy = b.box.top + max(0f, (b.box.height() - layout.height) / 2f)
        canvas.translate(b.box.left.toFloat(), dy)
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
