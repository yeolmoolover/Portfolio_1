package com.portfolio.floatingtouch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/** 원형 플로팅 버튼 뷰 (아이콘 문자 + 배경색) */
class CircleButtonView(context: Context, private val baseColor: Int, private val activeColor: Int) :
    FrameLayout(context) {

    private val bg = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(baseColor)
        setStroke((2 * context.resources.displayMetrics.density).toInt(), 0x88FFFFFF.toInt())
    }
    private val label = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

    init {
        background = bg
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setLabel(text: String) {
        label.text = text
    }

    fun setActive(active: Boolean) {
        bg.setColor(if (active) activeColor else baseColor)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, w * 0.42f)
    }
}

/** 자동 터치 위치 표시용 조준점. 중심이 실제 터치 지점이다. */
class TargetMarkerView(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
        color = 0xFFE53935.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33E53935 }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    var running = false
        set(v) {
            field = v
            ring.color = if (v) 0xFF43A047.toInt() else 0xFFE53935.toInt()
            dot.color = ring.color
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - ring.strokeWidth
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawLine(cx, cy - r, cx, cy + r, ring)
        canvas.drawLine(cx - r, cy, cx + r, cy, ring)
        canvas.drawCircle(cx, cy, 3 * d, dot)
    }
}
