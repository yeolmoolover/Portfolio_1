package com.portfolio.floatingtouch

import android.content.Context

enum class Feature { SCROLL, TAP, AUTO }

/**
 * 기능 1·2·3 켜기/끄기 공통 로직. 앱 화면, 빠른 설정 타일, 서비스가 모두 이것을 쓴다.
 * "켜짐" = 플로팅 버튼이 실제로 화면에 보이는 상태 (전체 보이기 && 해당 기능 활성).
 */
object Controls {
    fun enabled(p: Prefs, f: Feature) = when (f) {
        Feature.SCROLL -> p.scrollEnabled
        Feature.TAP -> p.tapEnabled
        Feature.AUTO -> p.autoEnabled
    }

    private fun setEnabled(p: Prefs, f: Feature, v: Boolean) = when (f) {
        Feature.SCROLL -> p.scrollEnabled = v
        Feature.TAP -> p.tapEnabled = v
        Feature.AUTO -> p.autoEnabled = v
    }

    fun isOn(p: Prefs, f: Feature) = p.overlayVisible && enabled(p, f)

    /** 해당 기능만 켜기/끄기를 뒤집고, 나머지는 지금 보이는 그대로 유지 */
    fun toggle(context: Context, f: Feature) {
        val p = Prefs(context)
        val now = Feature.values().associateWith { isOn(p, it) }
        Feature.values().forEach { setEnabled(p, it, if (it == f) !now.getValue(it) else now.getValue(it)) }
        p.overlayVisible = true
        // 모두 꺼졌으면 '전체 숨김' 상태로 정리 (빠른 설정 타일로 다시 켜면 전부 나타남)
        if (Feature.values().none { enabled(p, it) }) {
            p.overlayVisible = false
            Feature.values().forEach { setEnabled(p, it, true) }
        }
        FloatingService.instance?.applySettings()
    }

    fun allOff(context: Context) {
        Prefs(context).overlayVisible = false
        FloatingService.instance?.applySettings()
    }
}
