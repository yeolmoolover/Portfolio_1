package com.portfolio.floatingtouch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 둥근 사각형 플로팅 버튼 (회색 바탕 + Iconsax 아이콘).
 * active=true 면 더 진한 회색 (실행 중 표시).
 */
class FloatButtonView(
    context: Context,
    private val baseColor: Int = GRAY,
    private val activeColor: Int = GRAY_ACTIVE,
) : FrameLayout(context) {

    private val bg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(baseColor)
        setStroke((1 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1), 0x33FFFFFF)
    }
    private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }

    init {
        background = bg
        addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setIcon(res: Int) {
        icon.setImageResource(res)
    }

    fun setActive(active: Boolean) {
        bg.setColor(if (active) activeColor else baseColor)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bg.cornerRadius = w * 0.28f            // 둥근 사각형
        val pad = (w * 0.22f).toInt()          // 아이콘 여백
        icon.setPadding(pad, pad, pad, pad)
    }

    companion object {
        const val GRAY = 0xFF5F6368.toInt()          // 기본 회색
        const val GRAY_ACTIVE = 0xFF2B2D30.toInt()   // 실행 중: 진한 회색
        const val GRAY_OFF = 0xFFB4B8BD.toInt()      // 빠른 메뉴에서 '꺼짐'
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
