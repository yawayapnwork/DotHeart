package com.dotheart.widget

import android.app.Application
import com.dotheart.widget.work.WidgetSyncScheduler

/**
 * Ensures the periodic sync job is registered on every process start, not
 * only when a widget instance is first placed on a home screen. WorkManager
 * itself is already initialized by the time onCreate() runs here (its
 * androidx.startup ContentProvider runs before any Application.onCreate()),
 * so calling WorkManager.getInstance() inside WidgetSyncScheduler.schedule()
 * is always safe at this point.
 *
 * This is deliberately redundant with CoupleWidgetProvider.onEnabled(),
 * which also calls WidgetSyncScheduler.schedule() - both are safe to call
 * because enqueueUniquePeriodicWork uses ExistingPeriodicWorkPolicy.KEEP
 * (see WidgetSyncScheduler), so whichever entry point runs first wins and
 * the other is a no-op.
 */
class DotHeartApp : Application() {

    override fun onCreate() {
        super.onCreate()
        WidgetSyncScheduler.schedule(applicationContext)
    }
}
