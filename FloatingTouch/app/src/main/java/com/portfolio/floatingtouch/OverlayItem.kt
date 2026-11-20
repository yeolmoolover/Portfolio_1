package com.portfolio.floatingtouch

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 화면 위에 떠 있는 드래그 가능한 오버레이 하나.
 * - 짧게 탭: onClick
 * - 드래그: 이동
 * - edgeHide=true 일 때: 화면 왼쪽/오른쪽 끝까지 끌어다 놓으면 엣지에 숨김(일부만 보임),
 *   숨겨진 상태에서 탭하면 다시 나타남
 */
class OverlayItem(
    context: Context,
    private val wm: WindowManager,
    private val prefs: Prefs,
    private val key: String,
    val view: View,
    private val edgeHide: Boolean,
    private val onClick: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeTriggerPx = (20 * density).roundToInt()

    var sizePx: Int = (Prefs.DEFAULT_SIZE_DP * density).roundToInt()
        private set
    private var alpha = 1f
    val params: WindowManager.LayoutParams = ScreenUtil.overlayParams(sizePx)

    /** -1 왼쪽 숨김, 1 오른쪽 숨김, 0 보임 */
    var hiddenSide = 0
        private set
    private var attached = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    var draggable = true

    init {
        setupTouch()
    }

    fun attach(defaultX: Int, defaultY: Int) {
        if (attached) return
        val sx = prefs.posX(key)
        val sy = prefs.posY(key)
        params.x = if (sx == Int.MIN_VALUE) defaultX else sx
        params.y = if (sy == Int.MIN_VALUE) defaultY else sy
        hiddenSide = if (edgeHide) prefs.hiddenSide(key) else 0
        wm.addView(view, params)
        attached = true
        clampToScreen()
    }

    fun detach() {
        if (!attached) return
        runCatching { wm.removeView(view) }
        attached = false
    }

    /** 크기(dp)와 투명도(0.2~1.0)를 적용 */
    fun applyStyle(sizeDp: Int, alpha: Float) {
        sizePx = (sizeDp * density).roundToInt()
        this.alpha = alpha
        params.width = sizePx
        params.height = sizePx
        if (hiddenSide != 0) params.x = hiddenX(hiddenSide)
        applyAlpha()
        update()
    }

    fun setTouchable(touchable: Boolean) {
        ScreenUtil.setTouchable(params, touchable)
        update()
    }

    /** 화면 회전 등으로 화면 크기가 바뀌었을 때 화면 안으로 되돌림 */
    fun clampToScreen() {
        val s = ScreenUtil.size(wm)
        params.y = params.y.coerceIn(0, (s.y - sizePx).coerceAtLeast(0))
        params.x = if (hiddenSide != 0) hiddenX(hiddenSide)
        else params.x.coerceIn(0, (s.x - sizePx).coerceAtLeast(0))
        applyAlpha()
        update()
    }

    /** 실제 화면 좌표 기준 중심점 (제스처 좌표계와 동일) */
    fun centerOnScreen(): Pair<Float, Float> {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Pair(loc[0] + view.width / 2f, loc[1] + view.height / 2f)
    }

    fun resetPosition(defaultX: Int, defaultY: Int) {
        hiddenSide = 0
        params.x = defaultX
        params.y = defaultY
        clampToScreen()
        save()
    }

    private fun hiddenX(side: Int): Int {
        val w = ScreenUtil.size(wm).x
        // 버튼의 약 35%만 화면에 보이게
        return if (side < 0) -(sizePx * 0.65f).roundToInt() else w - (sizePx * 0.35f).roundToInt()
    }

    private fun hide(side: Int) {
        hiddenSide = side
        params.x = hiddenX(side)
        applyAlpha()
        update()
    }

    private fun reveal() {
        val w = ScreenUtil.size(wm).x
        params.x = if (hiddenSide < 0) 0 else w - sizePx
        hiddenSide = 0
        applyAlpha()
        update()
        save()
    }

    private fun applyAlpha() {
        view.alpha = if (hiddenSide != 0) minOf(alpha, 0.5f) else alpha
    }

    private fun update() {
        if (attached) runCatching { wm.updateViewLayout(view, params) }
    }

    private fun save() = prefs.savePosition(key, params.x, params.y, hiddenSide)

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    view.scaleX = 0.92f
                    view.scaleY = 0.92f
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (draggable && !dragging && hypot(dx, dy) > touchSlop) {
                        dragging = true
                        if (hiddenSide != 0) {
                            hiddenSide = 0
                            applyAlpha()
                        }
                    }
                    if (dragging) {
                        val s = ScreenUtil.size(wm)
                        params.x = startX + dx.roundToInt()
                        params.y = (startY + dy.roundToInt()).coerceIn(0, (s.y - sizePx).coerceAtLeast(0))
                        update()
                    }
                }

                MotionEvent.ACTION_UP -> {
                    view.scaleX = 1f
                    view.scaleY = 1f
                    if (!dragging) {
                        if (hiddenSide != 0) reveal() else onClick()
                    } else {
                        val w = ScreenUtil.size(wm).x
                        when {
                            edgeHide && e.rawX <= edgeTriggerPx -> hide(-1)
                            edgeHide && e.rawX >= w - edgeTriggerPx -> hide(1)
                            else -> {
                                params.x = params.x.coerceIn(0, (w - sizePx).coerceAtLeast(0))
                                update()
                            }
                        }
                        save()
                    }
                    dragging = false
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.scaleX = 1f
                    view.scaleY = 1f
                    if (dragging) {
                        clampToScreen()
                        save()
                    }
                    dragging = false
                }
            }
            true
        }
    }
}
