package com.portfolio.floatingtouch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ☰ 버튼을 누르면 주변에 부채꼴로 '뿅뿅뿅' 튀어나오는 빠른 메뉴.
 *  [1 빠른넘김] [2 자동터치] [3 자동스크롤] [설정 앱 열기]
 * 1·2·3 은 누를 때마다 켜기/끄기 (켜짐 = 진한 회색, 꺼짐 = 연한 회색). 앱으로 이동하지 않는다.
 * ☰ 를 다시 누르거나 5초 동안 아무것도 안 누르면 닫힌다.
 */
class MiniMenu(
    private val context: Context,
    private val wm: WindowManager,
    private val prefs: Prefs,
    private val onOpenApp: () -> Unit,
) {
    private class Chip(val feature: Feature?, val view: FloatButtonView)

    private val chips = mutableListOf<Chip>()
    private val handler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { close() }
    private val density = context.resources.displayMetrics.density

    val isOpen get() = chips.isNotEmpty()

    fun toggle(cx: Float, cy: Float) = if (isOpen) close() else open(cx, cy)

    fun open(cx: Float, cy: Float) {
        close(immediate = true)
        val size = (46 * density).roundToInt()
        val radius = 80 * density
        val screen = ScreenUtil.size(wm)
        // ☰ 가 화면 오른쪽에 있으면 왼쪽으로, 왼쪽에 있으면 오른쪽으로 펼친다 (위→아래 순서 1,2,3,⚙)
        val fanLeft = cx > screen.x / 2f
        val anglesDeg = listOf(-67.5, -22.5, 22.5, 67.5)
        val defs = listOf(
            Feature.SCROLL to (if (prefs.scrollDown) R.drawable.ic_page_down else R.drawable.ic_page_up),
            Feature.TAP to R.drawable.ic_auto_tap,
            Feature.AUTO to R.drawable.ic_auto_scroll,
            null to R.drawable.ic_settings,
        )
        defs.forEachIndexed { i, (feature, iconRes) ->
            val t = anglesDeg[i] * PI / 180
            val a = if (fanLeft) PI - t else t
            val x = (cx + radius * cos(a) - size / 2f).roundToInt().coerceIn(0, screen.x - size)
            val y = (cy + radius * sin(a) - size / 2f).roundToInt().coerceIn(0, screen.y - size)

            // 켜짐 = 진한 회색, 꺼짐 = 연한 회색 (설정 ⚙ 는 기본 회색)
            val view = (if (feature == null) FloatButtonView(context)
            else FloatButtonView(context, FloatButtonView.GRAY_OFF, FloatButtonView.GRAY_ACTIVE))
                .apply { setIcon(iconRes) }
            val params = ScreenUtil.overlayParams(size).apply { this.x = x; this.y = y }
            val chip = Chip(feature, view)
            view.setOnClickListener {
                if (feature == null) {
                    close(immediate = true)
                    onOpenApp()
                } else {
                    Controls.toggle(context, feature)
                    refreshStates()
                    restartAutoClose()
                }
            }
            runCatching { wm.addView(view, params) }.onSuccess {
                chips += chip
                // 뿅! — 순서대로 살짝 튀어나오는 애니메이션
                view.scaleX = 0f; view.scaleY = 0f; view.alpha = 0f
                view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setStartDelay(i * 60L).setDuration(220)
                    .setInterpolator(OvershootInterpolator(2.2f)).start()
            }
        }
        refreshStates()
        restartAutoClose()
    }

    fun refreshStates() {
        chips.forEach { c -> c.feature?.let { c.view.setActive(Controls.isOn(prefs, it)) } }
    }

    fun close(immediate: Boolean = false) {
        handler.removeCallbacks(autoClose)
        val list = chips.toList()
        chips.clear()
        list.forEachIndexed { i, c ->
            if (immediate) {
                runCatching { wm.removeView(c.view) }
            } else {
                c.view.animate().scaleX(0f).scaleY(0f).alpha(0f)
                    .setStartDelay((list.size - 1 - i) * 30L).setDuration(120)
                    .withEndAction { runCatching { wm.removeView(c.view) } }.start()
            }
        }
    }

    private fun restartAutoClose() {
        handler.removeCallbacks(autoClose)
        handler.postDelayed(autoClose, 5000)
    }
}
