package com.portfolio.floatingtouch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews

/**
 * 알림표시줄에 상주하는 컨트롤 알림.
 *  [1 빠른넘김]  [2 자동터치]  [3 자동스크롤]  [끄기]
 * 1·2·3 버튼은 각각 켜기/끄기 토글, 끄기는 모든 플로팅 버튼 숨김.
 * 접근성 서비스가 켜져 있는 동안만 표시된다.
 */
object ControlNotification {
    private const val CHANNEL_ID = "controls"
    private const val NOTIF_ID = 1001

    const val ACTION_SCROLL = "com.portfolio.floatingtouch.SCROLL"
    const val ACTION_TAP = "com.portfolio.floatingtouch.TAP"
    const val ACTION_AUTO = "com.portfolio.floatingtouch.AUTO"
    const val ACTION_OFF = "com.portfolio.floatingtouch.OFF"

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun show(context: Context, prefs: Prefs) {
        if (!canPost(context)) return
        // 모두 꺼진 상태(= 앱 끔)면 알림도, 상태바 아이콘도 없앤다.
        // 다시 켜려면 앱 화면이나 빠른 설정 타일에서 기능을 켜면 알림이 다시 나타난다.
        if (!prefs.overlayVisible) {
            cancel(context)
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "플로팅 버튼 컨트롤", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "알림표시줄에서 1번/2번 기능을 켜고 끕니다"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }

        val rv = RemoteViews(context.packageName, R.layout.notification_controls).apply {
            fun style(id: Int, on: Boolean, onBg: Int) {
                setInt(id, "setBackgroundResource", if (on) onBg else R.drawable.notif_btn_off)
                setTextColor(id, if (on) 0xFFFFFFFF.toInt() else 0xFF424242.toInt())
            }
            setTextViewText(R.id.btn_scroll, (if (prefs.scrollDown) "1 ▼" else "1 ▲") + "\n빠른넘김")
            style(R.id.btn_scroll, Controls.isOn(prefs, Feature.SCROLL), R.drawable.notif_btn_scroll_on)
            style(R.id.btn_tap, Controls.isOn(prefs, Feature.TAP), R.drawable.notif_btn_tap_on)
            style(R.id.btn_auto, Controls.isOn(prefs, Feature.AUTO), R.drawable.notif_btn_auto_on)
            setTextColor(R.id.btn_off, 0xFF424242.toInt())

            setOnClickPendingIntent(R.id.btn_scroll, broadcast(context, ACTION_SCROLL, 1))
            setOnClickPendingIntent(R.id.btn_tap, broadcast(context, ACTION_TAP, 2))
            setOnClickPendingIntent(R.id.btn_auto, broadcast(context, ACTION_AUTO, 5))
            setOnClickPendingIntent(R.id.btn_off, broadcast(context, ACTION_OFF, 3))
        }

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val n = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(rv)
            .setCustomBigContentView(rv)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    private fun broadcast(context: Context, action: String, req: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, req,
            Intent(context, ControlReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}

/** 알림 버튼 클릭 수신 → 서비스에 전달 */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = FloatingService.instance ?: return  // 서비스가 꺼져 있으면 알림도 없어야 정상
        service.handleControl(intent.action ?: return)
    }
}
