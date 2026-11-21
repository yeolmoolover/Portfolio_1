package com.portfolio.floatingtouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.app.NotificationManager
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
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

        /** 자동 스크롤: 한 구간(연속 드래그 조각) 길이 */
        private const val AUTO_SEGMENT_MS = 200L
        /** 자동 스크롤: 손 떼기 전 멈춤 (관성 방지) */
        private const val AUTO_LIFT_HOLD_MS = 80L
        /** 자동 스크롤: 사용자 터치 등으로 중단되면 이만큼 쉬었다가 재개 */
        private const val AUTO_RESUME_DELAY_MS = 1000L
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

    private var miniItem: OverlayItem? = null
    private val miniMenu by lazy { MiniMenu(this, wm, prefs) { openApp() } }
    private var autoItem: OverlayItem? = null
    private var autoView: CircleButtonView? = null

    private var scrolling = false
    var tapping = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        instance = this
        // 이전 버전에서 띄우던 알림표시줄 알림이 남아 있으면 지운다 (알림 기능 삭제됨)
        runCatching { getSystemService(NotificationManager::class.java).cancelAll() }
        applySettings()
    }

    // ===================== 앱 지정 모드: 지금 화면 맨 앞의 앱 감지 =====================

    /** 마지막으로 감지된 '일반 앱' 패키지 (키보드·상태바·이 앱 자체는 제외) */
    var foregroundPackage: String? = null
        private set

    fun isInTargetApp(): Boolean = foregroundPackage?.let { it in prefs.targetApps } == true

    /** 앱 지정 모드를 반영한 실제 표시 여부 */
    private fun effectiveVisible(): Boolean =
        prefs.overlayVisible && (!prefs.appModeEnabled || isInTargetApp())

    /** 설정 화면에 보여줄 최근 감지 기록 (문제 확인용) */
    val detectLog = ArrayDeque<String>()
    private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    private fun log(msg: String) {
        detectLog.addFirst("${timeFmt.format(java.util.Date())}  $msg")
        while (detectLog.size > 20) detectLog.removeLast()
    }

    /** 앱을 벗어난 것으로 판단한 뒤 실제로 숨기기까지의 유예 (잠깐 스쳐가는 창 무시) */
    private val hideRunnable = Runnable { applySettings() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString()

        // 상태바/알림창, 키보드는 '앱 전환'이 아님
        if (pkg == "com.android.systemui" || pkg in imePackages()) return
        // 팝업·광고·패널·토스트·오버레이 같은 창은 무시하고, 실제 '화면(Activity)' 전환만 본다
        if (!isActivityWindow(pkg, cls)) {
            log("무시  $pkg / ${cls?.substringAfterLast('.')}")
            return
        }
        if (pkg == packageName) {
            // 이 앱 설정 화면이 열림: 버튼은 그대로 두되(미리보기용) 실행 중인 동작은 멈춘다
            log("설정화면  $pkg")
            stopTapping()
            stopAuto()
            return
        }
        if (pkg == foregroundPackage) return
        foregroundPackage = pkg

        if (!prefs.appModeEnabled) {
            log("앱 전환  $pkg")
            return
        }
        val inTarget = isInTargetApp()
        log((if (inTarget) "지정앱 진입  " else "다른 앱  ") + pkg)
        handler.removeCallbacks(hideRunnable)
        // 지정한 앱에 들어오면: 켜 둔 기능 버튼, 모두 꺼져 있으면 작은 '앱 열기' 버튼이 나타난다
        if (inTarget) applySettings() else handler.postDelayed(hideRunnable, 400)
    }

    /**
     * 이벤트가 앱의 실제 화면(Activity) 전환인지 판단.
     * - 그 패키지의 Activity 클래스면 true
     * - 패키지는 있는데 Activity 가 아니면(대화상자, 팝업, 뷰 등) false
     * - 이 사용자 공간에 설치돼 있지 않은 패키지(예: 보안 폴더에만 있는 앱)는
     *   안드로이드 기본 위젯 클래스가 아니면 화면 전환으로 본다
     */
    private fun isActivityWindow(pkg: String, cls: String?): Boolean {
        if (cls.isNullOrEmpty()) return false
        return try {
            packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            val installed = runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
            !installed && !cls.startsWith("android.")
        }
    }

    private fun imePackages(): Set<String> = runCatching {
        getSystemService(InputMethodManager::class.java).enabledInputMethodList
            .map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** 플로팅 버튼 길게 누르기 → 설정 앱 열기 */
    private fun openApp() {
        miniMenu.close(immediate = true)
        stopTapping()
        stopAuto()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }
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
        stopAuto()
        handler.removeCallbacksAndMessages(null)
        scrollItem?.detach(); scrollItem = null; scrollView = null
        tapItem?.detach(); tapItem = null; tapView = null
        markerItem?.detach(); markerItem = null; markerView = null
        autoItem?.detach(); autoItem = null; autoView = null
        if (::wm.isInitialized) miniMenu.close(immediate = true)
        miniItem?.detach(); miniItem = null
        instance = null
        ToggleTileService.refresh(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            scrollItem?.clampToScreen()
            tapItem?.clampToScreen()
            markerItem?.clampToScreen()
            autoItem?.clampToScreen()
            miniItem?.clampToScreen()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    /** 설정 화면에서 값이 바뀔 때마다 호출된다. */
    fun applySettings() {
        val screen = ScreenUtil.size(wm)
        val visible = effectiveVisible()
        ToggleTileService.refresh(this)

        // ---- 1. 빠른 스크롤 버튼 ----
        if (visible && prefs.scrollEnabled) {
            if (scrollItem == null) {
                val v = CircleButtonView(this, 0xFF1E88E5.toInt(), 0xFF0D47A1.toInt())
                scrollView = v
                scrollItem = OverlayItem(
                    this, wm, prefs, Prefs.KEY_SCROLL, v, edgeHide = true,
                    onClick = { onScrollClicked() },
                    onLongPress = { openApp() },
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
                    onLongPress = { openApp() },
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

        // ---- 3. 자동 스크롤 버튼 ----
        if (visible && prefs.autoEnabled) {
            if (autoItem == null) {
                val v = CircleButtonView(this, 0xFF8E24AA.toInt(), 0xFF4A148C.toInt())
                autoView = v
                autoItem = OverlayItem(
                    this, wm, prefs, Prefs.KEY_AUTO, v, edgeHide = true,
                    onClick = { if (autoWanted) stopAuto() else startAuto() },
                    onLongPress = { openApp() },
                ).also { it.attach(screen.x - dp(72), screen.y / 3 + dp(180)) }
            }
            autoView?.setLabel(if (autoWanted) "■" else "↓")
            autoItem?.applyStyle(prefs.sizeDp(Prefs.KEY_AUTO), prefs.alpha(Prefs.KEY_AUTO))
        } else {
            stopAuto()
            autoItem?.detach(); autoItem = null; autoView = null
        }

        // ---- ☰ 메뉴 버튼: 앱 지정 모드에서 지정 앱 안에 있으면 항상 표시 ----
        val showMini = prefs.appModeEnabled && isInTargetApp()
        if (showMini) {
            if (miniItem == null) {
                val v = CircleButtonView(this, 0xFF00897B.toInt(), 0xFF004D40.toInt())
                v.setLabel("☰")
                miniItem = OverlayItem(
                    this, wm, prefs, Prefs.KEY_MINI, v, edgeHide = true,
                    onClick = {
                        miniItem?.centerOnScreen()?.let { (x, y) -> miniMenu.toggle(x, y) }
                    },
                    onLongPress = { openApp() },
                    onDragStart = { miniMenu.close(immediate = true) },
                ).also { it.attach(screen.x - dp(52), (screen.y * 0.6f).roundToInt()) }
            }
            miniItem?.applyStyle(prefs.sizeDp(Prefs.KEY_MINI), prefs.alpha(Prefs.KEY_MINI))
            miniMenu.refreshStates()
        } else {
            miniMenu.close(immediate = true)
            miniItem?.detach(); miniItem = null
        }
    }

    /** 서비스는 켜 둔 채로 플로팅 버튼만 숨기기/보이기 */
    fun setOverlayVisible(visible: Boolean) {
        prefs.overlayVisible = visible
        applySettings()
        if (!visible) {
            Toast.makeText(
                this, "모두 꺼짐 — 앱 화면이나 빠른 설정 타일에서 다시 켤 수 있어요", Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun resetPositions() {
        val screen = ScreenUtil.size(wm)
        scrollItem?.resetPosition(screen.x - dp(72), screen.y / 3)
        tapItem?.resetPosition(screen.x - dp(72), screen.y / 3 + dp(90))
        markerItem?.resetPosition(screen.x / 2 - dp(24), screen.y / 2 - dp(24))
        autoItem?.resetPosition(screen.x - dp(72), screen.y / 3 + dp(180))
        miniItem?.resetPosition(screen.x - dp(52), (screen.y * 0.6f).roundToInt())
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
        autoItem?.setTouchable(touchable)
        miniItem?.setTouchable(touchable)
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

    // ===================== 3. 자동 스크롤 =====================
    //
    // 손가락 하나를 화면에 대고 일정 속도로 천천히 끌어올리는 것을 흉내 낸다.
    //  - 200ms 짜리 드래그 조각을 continueStroke 로 계속 이어 붙인다 (손가락을 떼지 않음)
    //  - 화면 위쪽 한계에 닿으면 잠깐 멈췄다 떼고(관성 없음), 아래에서 다시 시작
    //  - 속도는 조각마다 새로 읽으므로 실행 중에 바꿔도 바로 반영된다

    /** 사용자가 원하는 상태 (버튼 ■ 표시) */
    private var autoWanted = false
    /** 주입 중인 손가락이 화면에 닿아 있는지 */
    private var autoFingerDown = false
    private var autoX = 0f
    private var autoY = 0f
    private var autoTopLimit = 0f

    private val autoResume = Runnable { if (autoWanted && !autoFingerDown) beginAutoStroke() }

    fun startAuto() {
        autoWanted = true
        autoView?.setActive(true)
        autoView?.setLabel("■")
        if (!autoFingerDown) beginAutoStroke()
        Toast.makeText(this, "자동 스크롤 시작 (${prefs.autoSpeedDp}dp/초)", Toast.LENGTH_SHORT).show()
    }

    /** 멈춤. 손가락은 진행 중인 조각이 끝날 때(최대 0.2초) 멈춤-떼기로 처리된다. */
    fun stopAuto() {
        autoWanted = false
        handler.removeCallbacks(autoResume)
        autoView?.setActive(false)
        autoView?.setLabel("↓")
    }

    private fun beginAutoStroke() {
        if (!autoWanted || autoFingerDown) return
        val s = ScreenUtil.size(wm)
        autoX = s.x / 2f
        val startY = s.y * 0.8f
        autoTopLimit = s.y * 0.2f
        // 첫 움직임은 '터치 슬롭'을 바로 넘겨서, 너무 느린 속도에서 길게 누르기로 인식되는 것을 막는다
        val kick = ViewConfiguration.get(this).scaledTouchSlop + 2f
        autoY = startY - kick
        val path = Path().apply { moveTo(autoX, startY); lineTo(autoX, autoY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 40, true)

        autoFingerDown = true
        // 시작 지점이 플로팅 버튼 위면 버튼이 터치를 가로채므로 시작 순간만 잠시 터치 불가
        setAllTouchable(false)
        handler.postDelayed({
            val ok = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(), autoCallback(stroke), null
            )
            handler.postDelayed({ if (!scrolling) setAllTouchable(true) }, 60)
            if (!ok) onAutoCancelled()
        }, TOUCHABLE_SETTLE_MS)
    }

    private fun autoCallback(stroke: GestureDescription.StrokeDescription) =
        object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = continueAuto(stroke)
            override fun onCancelled(g: GestureDescription?) = onAutoCancelled()
        }

    private fun continueAuto(prev: GestureDescription.StrokeDescription) {
        val pxPerSec = prefs.autoSpeedDp * resources.displayMetrics.density
        val step = (pxPerSec * AUTO_SEGMENT_MS / 1000f).coerceAtLeast(1f)
        if (!autoWanted || autoY - step < autoTopLimit) {
            liftAuto(prev)
            return
        }
        val newY = autoY - step
        val path = Path().apply { moveTo(autoX, autoY); lineTo(autoX, newY) }
        autoY = newY
        val next = prev.continueStroke(path, 0, AUTO_SEGMENT_MS, true)
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(next).build(), autoCallback(next), null
        )
        if (!ok) onAutoCancelled()
    }

    /** 제자리에서 잠깐 멈췄다가 손을 뗀다 → 앱이 속도 0으로 판단, 미끄러짐 없음 */
    private fun liftAuto(prev: GestureDescription.StrokeDescription) {
        val hold = prev.continueStroke(Path().apply { moveTo(autoX, autoY) }, 0, AUTO_LIFT_HOLD_MS, false)
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(hold).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    autoFingerDown = false
                    if (autoWanted) beginAutoStroke()  // 아래에서 다시 시작
                }
                override fun onCancelled(g: GestureDescription?) = onAutoCancelled()
            }, null
        )
        if (!ok) onAutoCancelled()
    }

    /**
     * 사용자가 화면을 만지거나 다른 기능(빠른 넘김/자동 터치)이 제스처를 보내면 진행 중 제스처가 취소된다.
     * 그때는 1초 쉬었다가 아래에서 다시 시작한다.
     */
    private fun onAutoCancelled() {
        autoFingerDown = false
        handler.removeCallbacks(autoResume)
        if (autoWanted) handler.postDelayed(autoResume, AUTO_RESUME_DELAY_MS)
    }
}
