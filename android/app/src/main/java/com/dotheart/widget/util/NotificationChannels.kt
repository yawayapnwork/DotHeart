package com.dotheart.widget.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Owns the single low-priority notification channel used for the
 * "DotHeart hasn't updated in a while" self-healing-detection alert
 * (WidgetSyncWorker.postFailureAlert). Idempotent - safe to call on every
 * worker execution.
 */
object NotificationChannels {

    const val CHANNEL_ALERTS = "dotheart_alerts"

    fun ensureAlertChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ALERTS) != null) return

        val channel = NotificationChannel(
            CHANNEL_ALERTS,
            "DotHeart sync alerts",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifies you when DotHeart hasn't been able to refresh for a while."
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
