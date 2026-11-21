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
        ToggleTileService.refresh(this)
        ControlNotification.cancel(this)
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
        val visible = prefs.overlayVisible
        ToggleTileService.refresh(this)
        ControlNotification.show(this, prefs)

        // ---- 1. 빠른 스크롤 버튼 ----
        if (visible && prefs.scrollEnabled) {
            if (scrollItem == null) {
                val v = CircleButtonView(this, 0xFF1E88E5.toInt(), 0xFF0D47A1.toInt())
                scrollView = v
                scrollItem = OverlayItem(
                    this, wm, prefs, Prefs.KEY_SCROLL, v, edgeHide = true,
                    onClick = { onScrollClicked() },
                    onLongPress = { setOverlayVisible(false) },
                ).also { it.attach(screen.x - dp(72), screen.y / 3) }
            }
            scrollView?.setLabel(if (prefs.scrollDown) "▼" else "▲")
            scrollItem?.applyStyle(prefs.sizeDp(Prefs.KEY_SCROLL), prefs.alpha(Prefs.KEY_SCROLL))
        } else {
            scrollItem?.detach(); scrollItem = null; scrollView = null
        }

        // ---- 2. 자동 반복 터치 버튼 + 터치 위치 조준점 ----
        if (visible && prefs.tapEnabled) {
            if (markerItem == null) {
                val mv = TargetMarkerView(this)
                markerView = mv
                markerItem = OverlayItem(this, wm, prefs, Prefs.KEY_TARGET, mv, edgeHide = false, onClick = {})
                    .also {
                        it.applyStyle(prefs.sizeDp(Prefs.KEY_TARGET), prefs.alpha(Prefs.KEY_TARGET))
                        it.attach(screen.x / 2 - dp(24), screen.y / 2 - dp(24))
                    }
            }
            if (tapItem == null) {
                val v = CircleButtonView(this, 0xFFE53935.toInt(), 0xFF43A047.toInt())
                tapView = v
                tapItem = OverlayItem(
                    this, wm, prefs, Prefs.KEY_TAP, v, edgeHide = true,
                    onClick = { if (tapping) stopTapping() else startTapping() },
                    onLongPress = { setOverlayVisible(false) },
                ).also { it.attach(screen.x - dp(72), screen.y / 3 + dp(90)) }
            }
            tapView?.setLabel(if (tapping) "■" else "▶")
            tapItem?.applyStyle(prefs.sizeDp(Prefs.KEY_TAP), prefs.alpha(Prefs.KEY_TAP))
            markerItem?.applyStyle(
                prefs.sizeDp(Prefs.KEY_TARGET), prefs.alpha(Prefs.KEY_TARGET), keepCenter = true
            )
        } else {
            stopTapping()
            tapItem?.detach(); tapItem = null; tapView = null
            markerItem?.detach(); markerItem = null; markerView = null
        }
    }

    /** 서비스는 켜 둔 채로 플로팅 버튼만 숨기기/보이기 */
    fun setOverlayVisible(visible: Boolean) {
        prefs.overlayVisible = visible
        applySettings()
        if (!visible) {
            Toast.makeText(
                this, "플로팅 버튼 숨김 — 알림표시줄이나 빠른 설정 타일에서 다시 켤 수 있어요", Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 알림표시줄 버튼 처리 */
    fun handleControl(action: String) {
        val sOn = ControlNotification.scrollOn(prefs)
        val tOn = ControlNotification.tapOn(prefs)
        when (action) {
            ControlNotification.ACTION_SCROLL -> {
                prefs.scrollEnabled = !sOn
                prefs.tapEnabled = tOn
                prefs.overlayVisible = true
            }
            ControlNotification.ACTION_TAP -> {
                prefs.tapEnabled = !tOn
                prefs.scrollEnabled = sOn
                prefs.overlayVisible = true
            }
            ControlNotification.ACTION_OFF -> prefs.overlayVisible = false
            ControlNotification.ACTION_REPOST -> Unit
            else -> return
        }
        // 둘 다 꺼졌으면 '숨김' 상태로 정리 (타일/앱 스위치와 상태 일치)
        if (!prefs.scrollEnabled && !prefs.tapEnabled) {
            prefs.overlayVisible = false
            prefs.scrollEnabled = true
            prefs.tapEnabled = true
        }
        applySettings()
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
        // 설정한 거리만큼, 화면 세로 중앙을 기준으로 위아래 대칭 배치
        val half = s.y * prefs.scrollDistancePct / 100f / 2f
        val top = s.y / 2f - half
        val bottom = s.y / 2f + half
        val (startY, endY) = if (prefs.scrollDown) bottom to top // 손가락 위로 → 아래 내용
        else top to bottom
        val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        val duration = prefs.scrollDurationMs
        val holdMs = prefs.scrollHoldMs

        // 1단계: 이동. holdMs > 0 이면 손가락을 떼지 않은 채로 끝낸다(willContinue=true).
        val moveStroke = GestureDescription.StrokeDescription(path, 0, duration, holdMs > 0)
        val moveGesture = GestureDescription.Builder().addStroke(moveStroke).build()

        val finishCb = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = finishScroll()
            override fun onCancelled(g: GestureDescription?) = finishScroll()
        }

        // 스와이프 경로 위에 플로팅 버튼이 있으면 버튼이 터치를 가로채므로 잠시 터치 불가로 만든다.
        setAllTouchable(false)
        handler.postDelayed({
            val cb = if (holdMs <= 0) finishCb else object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    // 2단계: 끝 지점에서 움직이지 않고 holdMs 동안 누르고 있다가 뗀다 → 관성 제거
                    val holdPath = Path().apply { moveTo(x, endY) }
                    val holdStroke = moveStroke.continueStroke(holdPath, 0, holdMs, false)
                    val ok = dispatchGesture(
                        GestureDescription.Builder().addStroke(holdStroke).build(), finishCb, null
                    )
                    if (!ok) finishScroll()
                }
                override fun onCancelled(g: GestureDescription?) = finishScroll()
            }
            val accepted = dispatchGesture(moveGesture, cb, null)
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
