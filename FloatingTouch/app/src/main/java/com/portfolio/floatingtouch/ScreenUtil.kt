package com.portfolio.floatingtouch

import android.graphics.Point
import android.os.Build
import android.view.WindowManager

object ScreenUtil {
    /** 시스템 바를 포함한 실제 화면 크기(px). 제스처 좌표계와 같다. */
    fun size(wm: WindowManager): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

    /** 접근성 오버레이 창을 위한 공통 LayoutParams (화면 전체 좌표 기준, 화면 밖 배치 허용). */
    fun overlayParams(sizePx: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.fitInsetsTypes = 0
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    fun setTouchable(lp: WindowManager.LayoutParams, touchable: Boolean) {
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
    }
}
