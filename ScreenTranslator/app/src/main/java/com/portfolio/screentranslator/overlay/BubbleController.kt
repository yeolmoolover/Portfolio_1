package com.portfolio.screentranslator.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.portfolio.screentranslator.R
import kotlin.math.abs

/**
 * Draggable floating button with a small menu. Separate window from the overlay so it
 * stays touchable while the translation layer passes touches through.
 */
class BubbleController(
    private val context: Context,
    private val wm: WindowManager,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onTranslateNow()
        fun onToggleAuto(): Boolean
        fun onToggleComic(): Boolean
        fun onCompare()
        fun onClear()
        fun onStop()
        /** Called with true while the user holds the button (show original), false on release. */
        fun onPeekOriginal(peeking: Boolean)
    }

    private val root: View = LayoutInflater.from(context).inflate(R.layout.bubble, null)
    private val btn: TextView = root.findViewById(R.id.btn_translate)
    private val menu: View = root.findViewById(R.id.menu)
    private val status: TextView = root.findViewById(R.id.status)
    private val btnAuto: TextView = root.findViewById(R.id.btn_auto)
    private val btnComic: TextView = root.findViewById(R.id.btn_comic)
    private val handler = Handler(Looper.getMainLooper())

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = context.resources.displayMetrics.widthPixels - (92 * context.resources.displayMetrics.density).toInt()
        y = context.resources.displayMetrics.heightPixels / 3
    }

    private var attached = false

    fun show(autoOn: Boolean, comicOn: Boolean) {
        setAuto(autoOn); setComic(comicOn)
        if (!attached) { wm.addView(root, lp); attached = true }
        installTouch()
        root.findViewById<View>(R.id.btn_auto).setOnClickListener { setAuto(callbacks.onToggleAuto()) }
        root.findViewById<View>(R.id.btn_comic).setOnClickListener { setComic(callbacks.onToggleComic()) }
        root.findViewById<View>(R.id.btn_compare).setOnClickListener { hideMenu(); callbacks.onCompare() }
        root.findViewById<View>(R.id.btn_clear).setOnClickListener { hideMenu(); callbacks.onClear() }
        root.findViewById<View>(R.id.btn_stop).setOnClickListener { callbacks.onStop() }
    }

    /** Screen-space rectangle currently covered by the bubble + menu + status (for OCR exclusion). */
    fun bounds(): android.graphics.Rect {
        val pad = (8 * context.resources.displayMetrics.density).toInt()
        return android.graphics.Rect(lp.x - pad, lp.y - pad, lp.x + root.width + pad, lp.y + root.height + pad)
    }

    fun hide() {
        if (attached) { wm.removeView(root); attached = false }
    }

    fun setStatus(text: String?) {
        handler.post {
            if (text.isNullOrBlank()) status.visibility = View.GONE
            else { status.text = text; status.visibility = View.VISIBLE }
        }
    }

    fun setBusy(busy: Boolean) {
        handler.post { btn.text = if (busy) "…" else "번역"; btn.alpha = if (busy) 0.6f else 1f }
    }

    private fun setAuto(on: Boolean) { btnAuto.text = if (on) "자동: 켜짐" else "자동: 꺼짐" }
    private fun setComic(on: Boolean) { btnComic.text = if (on) "만화: 켜짐" else "만화: 꺼짐" }
    private fun hideMenu() { menu.visibility = View.GONE }

    /** Tap = translate, long press = peek original, drag = move, double tap = menu. */
    private fun installTouch() {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var peeking = false; var lastTapTime = 0L
        val slop = 12 * context.resources.displayMetrics.density
        val longPress = Runnable {
            if (!moved) { peeking = true; callbacks.onPeekOriginal(true) }
        }
        btn.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y
                    moved = false; peeking = false
                    handler.postDelayed(longPress, 350)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) { moved = true; handler.removeCallbacks(longPress) }
                    if (moved && !peeking) {
                        lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                        if (attached) wm.updateViewLayout(root, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (peeking) { peeking = false; callbacks.onPeekOriginal(false) }
                    else if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 300) {
                            menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                            lastTapTime = 0
                        } else {
                            lastTapTime = now
                            handler.postDelayed({ if (lastTapTime == now) { lastTapTime = 0; callbacks.onTranslateNow() } }, 300)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }
}
