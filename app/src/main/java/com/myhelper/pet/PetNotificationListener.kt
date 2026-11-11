package com.myhelper.pet

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class PetNotificationListener : NotificationListenerService() {

    private val skipCategories = setOf(
        "call", "transport", "progress", "service", "alarm", "navigation", "status", "sys", "stopwatch"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val svc = OverlayService.instance ?: return
        val n = sbn.notification ?: return
        val pkg = sbn.packageName
        if (pkg == packageName) return

        // 음악 재생, 통화 중, 진행 중 알림 등은 제외
        val skipFlags = Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_FOREGROUND_SERVICE or
            Notification.FLAG_GROUP_SUMMARY
        if ((n.flags and skipFlags) != 0) return
        if (n.category != null && n.category in skipCategories) return
        val template = n.extras?.getString(Notification.EXTRA_TEMPLATE) ?: ""
        if (template.contains("MediaStyle") || template.contains("CallStyle")) return

        val muted = Cfg.prefs(this).getStringSet("muted", emptySet()) ?: emptySet()
        if (pkg in muted) return

        val label = Apps.label(this, pkg)
        Handler(Looper.getMainLooper()).post { svc.onNotification(pkg, label) }
    }
}