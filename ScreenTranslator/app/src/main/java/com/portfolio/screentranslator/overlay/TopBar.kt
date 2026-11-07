package com.portfolio.screentranslator.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Always-visible full-width control bar pinned just below the status bar:
 *   [    번역    |  원문 보기 / 번역 보기  |  ✕  ]
 * The window also has FLAG_WATCH_OUTSIDE_TOUCH, so any touch elsewhere on the screen is
 * reported through [onOutsideTouch] (used to dismiss the translation in manual mode);
 * touches on the bar itself are not "outside" and therefore never dismiss anything.
 */
class TopBar(
    private val context: Context,
    private val wm: WindowManager,
    topInsetPx: Int,
    reversed: Boolean,
    private val onTranslate: () -> Unit,
    private val onToggleOriginal: () -> Unit,
    private val onStop: () -> Unit,
    private val onOutsideTouch: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val d = context.resources.displayMetrics.density
    private val root = LinearLayout(context)
    private val btnTranslate = button("번역")
    private val btnOriginal = button("원문 보기")
    private val btnStop = button("✕")
    private var attached = false

    init {
        root.orientation = LinearLayout.HORIZONTAL
        root.gravity = Gravity.CENTER_VERTICAL
        root.background = GradientDrawable().apply { setColor(Color.argb(225, 28, 28, 28)) }
        root.setPadding(0, 0, 0, 0)
        fun weight(w: Float) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
        val order = if (reversed) listOf(btnStop to 1f, btnOriginal to 2f, btnTranslate to 2f)
                    else listOf(btnTranslate to 2f, btnOriginal to 2f, btnStop to 1f)
        order.forEachIndexed { i, (v, w) ->
            if (i > 0) root.addView(divider())
            root.addView(v, weight(w))
        }
        btnTranslate.setOnClickListener { onTranslate() }
        btnOriginal.setOnClickListener { onToggleOriginal() }
        btnStop.setOnClickListener { onStop() }
        root.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutsideTouch()
            false
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = topInsetPx
        }
        runCatching { wm.addView(root, lp); attached = true }
    }

    private fun button(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(0, (11 * d).toInt(), 0, (11 * d).toInt())
        isClickable = true
    }

    private fun divider() = android.view.View(context).apply {
        setBackgroundColor(Color.argb(90, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(1, (24 * d).toInt())
    }

    /** Screen-space bounds of the bar, so its own label is excluded from OCR. */
    fun bounds(): android.graphics.Rect {
        val loc = IntArray(2); root.getLocationOnScreen(loc)
        return android.graphics.Rect(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
    }

    fun setShowingOriginal(showing: Boolean) {
        main.post { btnOriginal.text = if (showing) "번역 보기" else "원문 보기" }
    }

    fun setBusy(busy: Boolean) {
        main.post { btnTranslate.text = if (busy) "…" else "번역"; btnTranslate.alpha = if (busy) 0.5f else 1f }
    }

    fun destroy() {
        main.post { if (attached) runCatching { wm.removeView(root) }; attached = false }
    }
}
