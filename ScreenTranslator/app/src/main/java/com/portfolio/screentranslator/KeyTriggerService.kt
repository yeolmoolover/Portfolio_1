package com.portfolio.screentranslator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service whose only job is to see hardware volume keys while translation
 * is running:
 *   volume DOWN -> translate the screen now
 *   volume UP   -> hide / show the current translation
 * The keys are consumed, so they do NOT change the volume while translation is running.
 * When the capture service is not running every key is passed through untouched.
 */
class KeyTriggerService : AccessibilityService() {

    override fun onServiceConnected() {
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!CaptureService.running) return false
        if (!Prefs(this).volumeTrigger) return false
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_UP) return false

        // Fire on the first DOWN; swallow auto-repeats and the UP so nothing reaches the system.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val action = if (code == KeyEvent.KEYCODE_VOLUME_DOWN) CaptureService.ACTION_TRANSLATE
                         else CaptureService.ACTION_TOGGLE_ORIGINAL
            startService(Intent(this, CaptureService::class.java).setAction(action))
        }
        return true
    }
}
