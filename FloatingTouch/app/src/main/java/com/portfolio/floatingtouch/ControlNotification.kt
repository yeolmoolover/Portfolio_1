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
 *  [1 스크롤]  [2 자동터치]  [끄기]
 * 1·2 버튼은 각각 켜기/끄기 토글, 끄기는 모든 플로팅 버튼 숨김.
 * 접근성 서비스가 켜져 있는 동안만 표시된다.
 */
object ControlNotification {
    private const val CHANNEL_ID = "controls"
    private const val NOTIF_ID = 1001

    const val ACTION_SCROLL = "com.portfolio.floatingtouch.SCROLL"
    const val ACTION_TAP = "com.portfolio.floatingtouch.TAP"
    const val ACTION_OFF = "com.portfolio.floatingtouch.OFF"
    const val ACTION_REPOST = "com.portfolio.floatingtouch.REPOST"

    /** 현재 실제로 화면에 보이는지 */
    fun scrollOn(p: Prefs) = p.overlayVisible && p.scrollEnabled
    fun tapOn(p: Prefs) = p.overlayVisible && p.tapEnabled

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun show(context: Context, prefs: Prefs) {
        if (!canPost(context)) return
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

        val sOn = scrollOn(prefs)
        val tOn = tapOn(prefs)
        val rv = RemoteViews(context.packageName, R.layout.notification_controls).apply {
            setTextViewText(R.id.btn_scroll, (if (prefs.scrollDown) "▼" else "▲") + " 1 스크롤")
            setInt(R.id.btn_scroll, "setBackgroundResource",
                if (sOn) R.drawable.notif_btn_scroll_on else R.drawable.notif_btn_off)
            setTextColor(R.id.btn_scroll, if (sOn) 0xFFFFFFFF.toInt() else 0xFF424242.toInt())

            setInt(R.id.btn_tap, "setBackgroundResource",
                if (tOn) R.drawable.notif_btn_tap_on else R.drawable.notif_btn_off)
            setTextColor(R.id.btn_tap, if (tOn) 0xFFFFFFFF.toInt() else 0xFF424242.toInt())

            setTextColor(R.id.btn_off, 0xFF424242.toInt())

            setOnClickPendingIntent(R.id.btn_scroll, broadcast(context, ACTION_SCROLL, 1))
            setOnClickPendingIntent(R.id.btn_tap, broadcast(context, ACTION_TAP, 2))
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
            // Android 14+ 에서는 상주 알림도 밀어서 지울 수 있으므로, 지워지면 다시 띄운다
            .setDeleteIntent(broadcast(context, ACTION_REPOST, 4))
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
