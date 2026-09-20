package com.dotheart.widget.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores the periodic widget sync after a reboot or an app update.
 *
 * WorkManager persists its jobs and normally re-arms them itself, but
 * re-running [WidgetSyncScheduler.schedule] here guarantees the job exists
 * even if its database was cleared or the OS dropped alarms. schedule() uses
 * ExistingPeriodicWorkPolicy.KEEP, so this is a cheap no-op when the job is
 * already registered.
 *
 * Work is only a WorkManager enqueue (no I/O of our own, no UI), so it is
 * safe to run inline on the receiver's main-thread callback.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> WidgetSyncScheduler.schedule(context)
        }
    }
}
