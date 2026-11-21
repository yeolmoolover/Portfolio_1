package com.portfolio.floatingtouch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** 버튼 한 상태(보통/켜짐)의 배경색 + 아이콘색 */
data class ButtonLook(val bg: Int, val icon: Int)

/**
 * 둥근 사각형 플로팅 버튼 (Iconsax 아이콘).
 * normal / active(켜짐·실행 중) 두 가지 모양을 가진다. 기본값은 회색 / 진한 회색.
 */
class FloatButtonView(
    context: Context,
    private var normal: ButtonLook = ButtonLook(GRAY, WHITE),
    private var activeLook: ButtonLook = ButtonLook(GRAY_ACTIVE, WHITE),
    strokeColor: Int = 0x33FFFFFF,
) : FrameLayout(context) {

    private val strokePx = (1 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private val bg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setStroke(strokePx, strokeColor)
    }
    private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }

    init {
        background = bg
        addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        updateColor()
    }

    fun setIcon(res: Int) {
        icon.setImageResource(res)
    }

    /** 라이트/다크 모드 전환 등으로 색 구성을 바꿀 때 */
    fun setLooks(normal: ButtonLook, active: ButtonLook, strokeColor: Int) {
        this.normal = normal
        this.activeLook = active
        bg.setStroke(strokePx, strokeColor)
        updateColor()
    }

    private var active = false

    fun setActive(active: Boolean) {
        this.active = active
        updateColor()
    }

    /** 눌린 순간 0.1초만 아주 살짝 어둡게 (누르고 있거나 드래그하는 동안은 원래 색) */
    private var dimmed = false
    private val undim = Runnable { dimmed = false; updateColor() }

    override fun setPressed(pressed: Boolean) {
        val wasPressed = isPressed
        super.setPressed(pressed)
        if (pressed && !wasPressed) {
            dimmed = true
            removeCallbacks(undim)
            postDelayed(undim, PRESS_FLASH_MS)
        }
        updateColor()
    }

    private fun updateColor() {
        val look = if (active) activeLook else normal
        bg.setColor(if (dimmed) darken(look.bg, PRESS_DIM) else look.bg)
        icon.setColorFilter(look.icon, android.graphics.PorterDuff.Mode.SRC_IN)
    }

    private fun darken(color: Int, amount: Float): Int {
        fun ch(shift: Int) = (((color shr shift) and 0xFF) * (1 - amount)).toInt()
        return (color and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bg.cornerRadius = w * 0.28f            // 둥근 사각형
        val pad = (w * 0.22f).toInt()          // 아이콘 여백
        icon.setPadding(pad, pad, pad, pad)
    }

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GRAY = 0xFF5F6368.toInt()          // 기본 회색
        const val GRAY_ACTIVE = 0xFF2B2D30.toInt()   // 실행 중: 진한 회색
        const val GRAY_OFF = 0xFFB4B8BD.toInt()      // 빠른 메뉴에서 '꺼짐' (다크 모드)
        const val PRESS_DIM = 0.12f                  // 눌렀을 때 12% 어둡게
        const val PRESS_FLASH_MS = 100L              // 어두운 상태 유지 시간 (0.1초)
    }
}

/**
 * 메뉴(≡) 버튼과 튀어나오는 메뉴의 색.
 * 라이트 모드: 흰 바탕 + 회색 선 아이콘 / 다크 모드: 회색 바탕 + 흰 아이콘 (기존)
 */
object MenuTheme {
    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private const val L_BG = 0xFFFFFFFF.toInt()
    private const val L_ICON = 0xFF5F6368.toInt()
    private const val L_ICON_OFF = 0xFFA8ADB3.toInt()
    private const val L_STROKE = 0xFFDADCE0.toInt()

    /** ≡ 버튼, ⚙ 설정 버튼 (켜짐/꺼짐 없음) */
    fun plain(context: Context): Triple<ButtonLook, ButtonLook, Int> =
        if (isNight(context)) Triple(
            ButtonLook(FloatButtonView.GRAY, FloatButtonView.WHITE),
            ButtonLook(FloatButtonView.GRAY_ACTIVE, FloatButtonView.WHITE), 0x33FFFFFF,
        ) else Triple(ButtonLook(L_BG, L_ICON), ButtonLook(L_BG, L_ICON), L_STROKE)

    /** 1·2·3 토글 (normal = 꺼짐, active = 켜짐) */
    fun toggle(context: Context): Triple<ButtonLook, ButtonLook, Int> =
        if (isNight(context)) Triple(
            ButtonLook(FloatButtonView.GRAY_OFF, FloatButtonView.WHITE),
            ButtonLook(FloatButtonView.GRAY_ACTIVE, FloatButtonView.WHITE), 0x33FFFFFF,
        ) else Triple(ButtonLook(L_BG, L_ICON_OFF), ButtonLook(L_ICON, FloatButtonView.WHITE), L_STROKE)

    fun apply(view: FloatButtonView, look: Triple<ButtonLook, ButtonLook, Int>) =
        view.setLooks(look.first, look.second, look.third)
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
