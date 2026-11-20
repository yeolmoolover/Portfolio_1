package com.portfolio.floatingtouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * 접근성 서비스.
 * - TYPE_ACCESSIBILITY_OVERLAY 창으로 플로팅 버튼을 띄운다 (별도 "다른 앱 위에 표시" 권한 불필요)
 * - dispatchGesture() 로 스와이프(스크롤)와 탭(자동 터치)을 주입한다
 */
class FloatingService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: FloatingService? = null
            private set

        private const val TAP_PRESS_MS = 40L
        /** 오버레이를 터치 불가로 바꾼 뒤 입력 시스템에 반영될 때까지 기다리는 시간 */
        private const val TOUCHABLE_SETTLE_MS = 50L
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var scrollItem: OverlayItem? = null
    private var scrollView: CircleButtonView? = null
    private var tapItem: OverlayItem? = null
    private var tapView: CircleButtonView? = null
    private var markerItem: OverlayItem? = null
    private var markerView: TargetMarkerView? = null

    private var scrolling = false
    var tapping = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        instance = this
        applySettings()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        stopTapping()
        handler.removeCallbacksAndMessages(null)
        scrollItem?.detach(); scrollItem = null; scrollView = null
        tapItem?.detach(); tapItem = null; tapView = null
        markerItem?.detach(); markerItem = null; markerView = null
        instance = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            scrollItem?.clampToScreen()
            tapItem?.clampToScreen()
            markerItem?.clampToScreen()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    /** 설정 화면에서 값이 바뀔 때마다 호출된다. */
    fun applySettings() {
        val screen = ScreenUtil.size(wm)

        // ---- 1. 빠른 스크롤 버튼 ----
        if (prefs.scrollEnabled) {
            if (scrollItem == null) {
                val v = CircleButtonView(this, 0xFF1E88E5.toInt(), 0xFF0D47A1.toInt())
                scrollView = v
                scrollItem = OverlayItem(this, wm, prefs, Prefs.KEY_SCROLL, v, edgeHide = true) {
                    onScrollClicked()
                }.also { it.attach(screen.x - dp(72), screen.y / 3) }
            }
            scrollView?.setLabel(if (prefs.scrollDown) "▼" else "▲")
            scrollItem?.applyStyle(prefs.sizeDp(Prefs.KEY_SCROLL), prefs.alpha(Prefs.KEY_SCROLL))
        } else {
            scrollItem?.detach(); scrollItem = null; scrollView = null
        }

        // ---- 2. 자동 반복 터치 버튼 + 터치 위치 조준점 ----
        if (prefs.tapEnabled) {
            if (markerItem == null) {
                val mv = TargetMarkerView(this)
                markerView = mv
                markerItem = OverlayItem(this, wm, prefs, Prefs.KEY_TARGET, mv, edgeHide = false) {}
                    .also {
                        it.applyStyle(48, 1f)
                        it.attach(screen.x / 2 - dp(24), screen.y / 2 - dp(24))
                    }
            }
            if (tapItem == null) {
                val v = CircleButtonView(this, 0xFFE53935.toInt(), 0xFF43A047.toInt())
                tapView = v
                tapItem = OverlayItem(this, wm, prefs, Prefs.KEY_TAP, v, edgeHide = true) {
                    if (tapping) stopTapping() else startTapping()
                }.also { it.attach(screen.x - dp(72), screen.y / 3 + dp(90)) }
            }
            tapView?.setLabel(if (tapping) "■" else "▶")
            tapItem?.applyStyle(prefs.sizeDp(Prefs.KEY_TAP), prefs.alpha(Prefs.KEY_TAP))
        } else {
            stopTapping()
            tapItem?.detach(); tapItem = null; tapView = null
            markerItem?.detach(); markerItem = null; markerView = null
        }
    }

    fun resetPositions() {
        val screen = ScreenUtil.size(wm)
        scrollItem?.resetPosition(screen.x - dp(72), screen.y / 3)
        tapItem?.resetPosition(screen.x - dp(72), screen.y / 3 + dp(90))
        markerItem?.resetPosition(screen.x / 2 - dp(24), screen.y / 2 - dp(24))
    }

    // ===================== 1. 빠른 스크롤 =====================

    private fun onScrollClicked() {
        if (scrolling) return
        scrolling = true
        scrollView?.setActive(true)

        val s = ScreenUtil.size(wm)
        val x = s.x / 2f
        val top = s.y * 0.25f
        val bottom = s.y * 0.75f
        val path = Path().apply {
            if (prefs.scrollDown) { moveTo(x, bottom); lineTo(x, top) } // 손가락 위로 → 아래 내용
            else { moveTo(x, top); lineTo(x, bottom) }
        }
        val duration = prefs.scrollDurationMs
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        // 스와이프 경로 위에 플로팅 버튼이 있으면 버튼이 터치를 가로채므로 잠시 터치 불가로 만든다.
        setAllTouchable(false)
        handler.postDelayed({
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) = finishScroll()
                override fun onCancelled(g: GestureDescription?) = finishScroll()
            }, null)
            if (!accepted) finishScroll()
        }, TOUCHABLE_SETTLE_MS)
    }

    private fun finishScroll() {
        handler.post {
            scrolling = false
            scrollView?.setActive(false)
            setAllTouchable(true)
        }
    }

    private fun setAllTouchable(touchable: Boolean) {
        scrollItem?.setTouchable(touchable)
        tapItem?.setTouchable(touchable)
        // 조준점은 자동 터치 실행 중에는 항상 터치 불가 상태를 유지해야 한다
        markerItem?.setTouchable(touchable && !tapping)
    }

    // ===================== 2. 자동 반복 터치 =====================

    private val tapRunnable = object : Runnable {
        override fun run() {
            if (!tapping) return
            performTap()
            handler.postDelayed(this, prefs.tapIntervalMs)
        }
    }

    fun startTapping() {
        val marker = markerItem ?: return
        tapping = true
        // 조준점 창이 탭을 가로채지 않도록 터치 불가 + 드래그 금지
        marker.setTouchable(false)
        marker.draggable = false
        markerView?.running = true
        tapView?.setActive(true)
        tapView?.setLabel("■")
        handler.postDelayed(tapRunnable, TOUCHABLE_SETTLE_MS)
        Toast.makeText(this, "자동 터치 시작 (${prefs.tapIntervalMs / 1000.0}초 간격)", Toast.LENGTH_SHORT).show()
    }

    fun stopTapping() {
        if (!tapping) return
        tapping = false
        handler.removeCallbacks(tapRunnable)
        markerItem?.let {
            it.setTouchable(true)
            it.draggable = true
        }
        markerView?.running = false
        tapView?.setActive(false)
        tapView?.setLabel("▶")
    }

    private fun performTap() {
        val marker = markerItem ?: return
        val (x, y) = marker.centerOnScreen()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_PRESS_MS))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
