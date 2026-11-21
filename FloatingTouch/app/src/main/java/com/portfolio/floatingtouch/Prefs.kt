package com.portfolio.floatingtouch

import android.content.Context

/** 모든 설정값 저장소 (SharedPreferences). 범위 밖 값은 항상 보정해서 반환한다. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("floating_touch", Context.MODE_PRIVATE)

    var scrollDurationMs: Long
        get() = sp.getLong("scroll_ms", MIN_SCROLL_MS).coerceIn(MIN_SCROLL_MS, MAX_SCROLL_MS)
        set(v) = sp.edit().putLong("scroll_ms", v.coerceIn(MIN_SCROLL_MS, MAX_SCROLL_MS)).apply()

    /** true = 아래 내용 보기(손가락을 아래→위로 쓸어올림), false = 위로 */
    var scrollDown: Boolean
        get() = sp.getBoolean("scroll_down", true)
        set(v) = sp.edit().putBoolean("scroll_down", v).apply()

    /** 한 번에 스와이프할 거리 (화면 높이의 %, 5.0 ~ 90.0) */
    var scrollDistancePct: Float
        get() = sp.getFloat("scroll_dist", DEFAULT_SCROLL_PCT).coerceIn(MIN_SCROLL_PCT, MAX_SCROLL_PCT)
        set(v) = sp.edit().putFloat("scroll_dist", v.coerceIn(MIN_SCROLL_PCT, MAX_SCROLL_PCT)).apply()

    /**
     * 스와이프 끝에서 손가락을 떼기 전에 멈춰 있는 시간(ms). 0 이면 관성(미끄러짐) 허용.
     * 안드로이드 VelocityTracker 는 마지막 움직임 후 일정 시간(약 40ms) 이상 멈추면
     * 속도를 0으로 보므로, 손을 뗄 때 플링(주루룩 미끄러짐)이 생기지 않는다.
     */
    var scrollHoldMs: Long
        get() = sp.getLong("scroll_hold", DEFAULT_HOLD_MS).coerceIn(0L, MAX_HOLD_MS)
        set(v) = sp.edit().putLong("scroll_hold", v.coerceIn(0L, MAX_HOLD_MS)).apply()

    var tapIntervalMs: Long
        get() = sp.getLong("tap_ms", MIN_TAP_MS).coerceIn(MIN_TAP_MS, MAX_TAP_MS)
        set(v) = sp.edit().putLong("tap_ms", v.coerceIn(MIN_TAP_MS, MAX_TAP_MS)).apply()

    /** 플로팅 버튼 전체 보이기/숨기기 (서비스는 켜 둔 채로) */
    var overlayVisible: Boolean
        get() = sp.getBoolean("overlay_visible", true)
        set(v) = sp.edit().putBoolean("overlay_visible", v).apply()

    var scrollEnabled: Boolean
        get() = sp.getBoolean("scroll_enabled", true)
        set(v) = sp.edit().putBoolean("scroll_enabled", v).apply()

    var tapEnabled: Boolean
        get() = sp.getBoolean("tap_enabled", true)
        set(v) = sp.edit().putBoolean("tap_enabled", v).apply()

    fun sizeDp(key: String): Int =
        sp.getInt("${key}_size", defaultSize(key)).coerceIn(minSize(key), MAX_SIZE_DP)

    fun setSizeDp(key: String, v: Int) =
        sp.edit().putInt("${key}_size", v.coerceIn(minSize(key), MAX_SIZE_DP)).apply()

    /** 0.2 ~ 1.0 */
    fun alpha(key: String): Float =
        sp.getFloat("${key}_alpha", if (key == KEY_TARGET) 0.9f else DEFAULT_ALPHA).coerceIn(MIN_ALPHA, 1f)

    fun setAlpha(key: String, v: Float) =
        sp.edit().putFloat("${key}_alpha", v.coerceIn(MIN_ALPHA, 1f)).apply()

    fun posX(key: String): Int = sp.getInt("${key}_x", Int.MIN_VALUE)
    fun posY(key: String): Int = sp.getInt("${key}_y", Int.MIN_VALUE)

    /** -1 = 왼쪽 엣지에 숨김, 1 = 오른쪽 엣지에 숨김, 0 = 숨기지 않음 */
    fun hiddenSide(key: String): Int = sp.getInt("${key}_hidden", 0)

    fun savePosition(key: String, x: Int, y: Int, hiddenSide: Int) {
        sp.edit()
            .putInt("${key}_x", x)
            .putInt("${key}_y", y)
            .putInt("${key}_hidden", hiddenSide)
            .apply()
    }

    fun resetPosition(key: String) {
        sp.edit().remove("${key}_x").remove("${key}_y").remove("${key}_hidden").apply()
    }

    companion object {
        const val KEY_SCROLL = "scroll"
        const val KEY_TAP = "tap"
        const val KEY_TARGET = "target"

        const val MIN_SCROLL_MS = 1L        // 0.001초
        const val MAX_SCROLL_MS = 10_000L   // 10초
        const val MIN_SCROLL_PCT = 5f
        const val MAX_SCROLL_PCT = 90f      // 하단 제스처 바/상단 상태바를 피하기 위해 90% 까지
        const val DEFAULT_SCROLL_PCT = 60f
        const val DEFAULT_HOLD_MS = 150L
        const val MAX_HOLD_MS = 500L
        const val MIN_TAP_MS = 1_000L       // 1초
        const val MAX_TAP_MS = 10_000L      // 10초

        const val MIN_SIZE_DP = 32
        /** 조준점은 화면을 덜 가리도록 더 작게 줄일 수 있게 */
        const val MIN_TARGET_SIZE_DP = 16

        fun minSize(key: String) = if (key == KEY_TARGET) MIN_TARGET_SIZE_DP else MIN_SIZE_DP
        fun defaultSize(key: String) = if (key == KEY_TARGET) 48 else DEFAULT_SIZE_DP
        const val MAX_SIZE_DP = 120
        const val DEFAULT_SIZE_DP = 56
        const val MIN_ALPHA = 0.2f
        const val DEFAULT_ALPHA = 0.85f
    }
}
