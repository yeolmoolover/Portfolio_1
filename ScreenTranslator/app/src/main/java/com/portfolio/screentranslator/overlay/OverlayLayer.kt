package com.portfolio.screentranslator.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import com.portfolio.screentranslator.translate.TranslatedBlock

/**
 * One small overlay window per translated block instead of a single full-screen window.
 *
 * The windows are TOUCHABLE on purpose: on the test device (One UI) a pass-through
 * (FLAG_NOT_TOUCHABLE) overlay is rendered at most 80 % opaque regardless of the requested
 * alpha, which let the original text show through. A touchable window is drawn at full
 * opacity; a tap on a translation simply dismisses the translation ([onTouch]), which is the
 * same thing a tap anywhere else does in manual mode.
 */
class OverlayLayer(
    private val context: Context,
    private val wm: WindowManager,
    private val alpha: Float,
    private val fontScale: Float,
    private val outlineStyle: Boolean = false,
    private val debug: Boolean = false,
    private val onTouch: (() -> Unit)? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private val views = ArrayList<OverlayView>()
    private val density = context.resources.displayMetrics.density

    private val current = ArrayList<TranslatedBlock>()

    /** Must be called on the main thread. Replaces everything. */
    var blocks: List<TranslatedBlock>
        get() = current
        set(value) {
            current.clear(); current += value
            clearViews()
            for (b in value) addBlock(b)
        }

    /** Must be called on the main thread. Adds windows without touching the existing ones. */
    fun append(more: List<TranslatedBlock>) {
        for (b in more) { current += b; addBlock(b) }
    }

    private fun addBlock(b: TranslatedBlock) {
        // Window = block box plus the largest padding the view may paint around a line.
        val pad = (maxOf(6 * density, b.lineHeight * 0.55f)).toInt() + 1
        val r = Rect(b.box)
        r.union(b.srcBox)
        for (l in b.lines) r.union(l)
        r.inset(-pad, -pad)
        if (r.width() <= 0 || r.height() <= 0) return
        fun shift(x: Rect) = Rect(x.left - r.left, x.top - r.top, x.right - r.left, x.bottom - r.top)
        val shifted = b.copy(box = shift(b.box), lines = b.lines.map(::shift), srcBox = shift(b.srcBox))
        val v = OverlayView(context).apply {
            fontScale = this@OverlayLayer.fontScale
            outlineStyle = this@OverlayLayer.outlineStyle
            debug = this@OverlayLayer.debug
            blocks = listOf(shifted)
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) onTouch?.invoke()
                true
            }
        }
        val lp = WindowManager.LayoutParams(
            r.width(), r.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = r.left; y = r.top
            this.alpha = this@OverlayLayer.alpha
        }
        runCatching { wm.addView(v, lp); views += v }
    }

    private fun clearViews() {
        for (v in views) runCatching { wm.removeView(v) }
        views.clear()
    }

    fun destroy() { main.post { clearViews() } }
}
