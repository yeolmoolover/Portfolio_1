package com.portfolio.floatingtouch

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 빠른 설정(상단바 내려서 나오는 타일) 버튼.
 * 탭 한 번으로 플로팅 버튼을 보이기/숨기기. 접근성 서비스는 계속 켜진 상태로 둔다.
 * 서비스가 꺼져 있으면 접근성 설정 화면을 연다.
 */
class ToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val service = FloatingService.instance
        if (service == null) {
            openAccessibilitySettings()
            return
        }
        service.setOverlayVisible(!Prefs(this).overlayVisible)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = FloatingService.instance != null
        val visible = Prefs(this).overlayVisible
        tile.state = if (running && visible) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !running -> "서비스 꺼짐"
                visible -> "표시 중"
                else -> "숨김"
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** 타일 상태를 다시 그리도록 요청 (타일이 추가돼 있지 않으면 무시됨) */
        fun refresh(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, ToggleTileService::class.java))
            }
        }
    }
}
