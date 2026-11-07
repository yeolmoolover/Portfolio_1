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
 * Always-visible control bar pinned just below the status bar:
 *   [번역]  [원문 보기 / 번역 보기]  [✕]
 * The window also has FLAG_WATCH_OUTSIDE_TOUCH, so any touch elsewhere on the screen is
 * reported through [onOutsideTouch] (used to dismiss the translation in manual mode);
 * touches on the bar itself are not "outside" and therefore never dismiss anything.
 */
class TopBar(
    private val context: Context,
    private val wm: WindowManager,
    topInsetPx: Int,
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
        root.background = GradientDrawable().apply {
            cornerRadius = 22 * d
            setColor(Color.argb(215, 30, 30, 30))
        }
        root.setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
        root.addView(btnTranslate); root.addView(btnOriginal); root.addView(btnStop)
        btnTranslate.setOnClickListener { onTranslate() }
        btnOriginal.setOnClickListener { onToggleOriginal() }
        btnStop.setOnClickListener { onStop() }
        root.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutsideTouch()
            false
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topInsetPx + (4 * d).toInt()
        }
        runCatching { wm.addView(root, lp); attached = true }
    }

    private fun button(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
        isClickable = true
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
