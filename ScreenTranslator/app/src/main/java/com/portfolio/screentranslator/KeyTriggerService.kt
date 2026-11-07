package com.portfolio.screentranslator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service whose only job is to see hardware volume keys while translation
 * is running:
 *   volume DOWN long-press  -> translate the screen now
 *   volume UP   long-press  -> hide / show the current translation
 *   short press             -> normal volume change (we re-issue it ourselves, because
 *                              filtering a key means the system no longer handles it)
 * When the capture service is not running every key is passed through untouched.
 */
class KeyTriggerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var downAt = 0L
    private var longFired = false
    private var pendingLong: Runnable? = null

    override fun onServiceConnected() {
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!CaptureService.running) return false
        val prefs = Prefs(this)
        if (!prefs.volumeTrigger) return false
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_UP) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                downAt = System.currentTimeMillis(); longFired = false
                pendingLong?.let(handler::removeCallbacks)
                pendingLong = Runnable {
                    longFired = true
                    val action = if (code == KeyEvent.KEYCODE_VOLUME_DOWN) CaptureService.ACTION_TRANSLATE
                                 else CaptureService.ACTION_TOGGLE_ORIGINAL
                    startService(Intent(this, CaptureService::class.java).setAction(action))
                }.also { handler.postDelayed(it, LONG_PRESS_MS) }
            }
            KeyEvent.ACTION_UP -> {
                pendingLong?.let(handler::removeCallbacks); pendingLong = null
                if (!longFired) {
                    // Short press: do what the system would have done.
                    val am = getSystemService(AudioManager::class.java)
                    val dir = if (code == KeyEvent.KEYCODE_VOLUME_DOWN) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
                    runCatching { am.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI) }
                }
            }
        }
        return true
    }

    companion object { const val LONG_PRESS_MS = 450L }
}
