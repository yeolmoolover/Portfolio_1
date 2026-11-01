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
 * Full-screen, non-touchable view that paints translations on top of the original text.
 * - Normal blocks: filled box in the sampled background colour + text in the sampled text colour,
 *   font shrunk until it fits the original box (so the artwork around it stays visible).
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
        val pad = 4 * density
        val box = RectF(b.box).apply { inset(-pad, -pad) }
        boxPaint.color = b.bgColor
        boxPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(box, 4 * density, 4 * density, boxPaint)

        textPaint.color = b.fgColor
        textPaint.isFakeBoldText = false
        val layout = fitText(b.text, textPaint, b.box, minSp = 8f, maxSp = 22f * fontScale, sp = fontScale) ?: return
        canvas.save()
        val dy = b.box.top + max(0f, (b.box.height() - layout.height) / 2f)
        canvas.translate(b.box.left.toFloat(), dy)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawSfx(canvas: Canvas, b: TranslatedBlock) {
        val maxSp = 26f * fontScale
        val fill = fitText(b.text, textPaint.apply { color = Color.WHITE; isFakeBoldText = true }, b.box, 10f, maxSp, fontScale)
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

    /** Shrinks the font until the wrapped text fits inside [box]; returns null if the box is unusable. */
    private fun fitText(text: String, paint: TextPaint, box: Rect, minSp: Float, maxSp: Float, sp: Float): StaticLayout? {
        val width = box.width()
        if (width < 8 || box.height() < 6) return null
        val scaled = resources.displayMetrics.scaledDensity
        var size = maxSp
        // start from a size proportional to the original glyph height, never above maxSp
        val fromBox = min(maxSp, box.height() / scaled * 0.9f)
        size = min(size, max(minSp, fromBox))
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
