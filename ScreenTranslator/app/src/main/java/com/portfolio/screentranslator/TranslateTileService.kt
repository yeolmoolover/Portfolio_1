package com.portfolio.screentranslator

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick-settings tile: tap = translate now (when running) or open the app to start.
 */
class TranslateTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = if (CaptureService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.app_name)
            updateTile()
        }
    }

    // The Intent overload is the only one available below API 34; it is guarded by the SDK check.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (CaptureService.running) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_TRANSLATE))
        } else {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }
}
