package com.dotheart.widget.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for every WidgetSyncWorker WorkManager request:
 * the periodic constraints/backoff/interval and the manual one-off policy.
 * Called from two independent entry points that must never drift out of
 * sync with each other:
 *
 *  - DotHeartApp.onCreate(), so the periodic job is (re-)registered on
 *    every process start, independent of widget placement timing.
 *  - CoupleWidgetProvider.onEnabled(), so a widget instance placed through
 *    a path that doesn't necessarily run Application.onCreate() first on
 *    every OS/launcher (e.g. a widget restored from an app-data backup)
 *    still ends up scheduled.
 *
 * enqueueUniquePeriodicWork with KEEP makes calling schedule() redundantly
 * from both entry points a safe no-op rather than a duplicate/reset job.
 */
object WidgetSyncScheduler {

    const val PERIODIC_WORK_NAME = "dotheart_periodic_sync"
    const val MANUAL_SYNC_WORK_NAME = "dotheart_manual_sync"
    const val SYNC_WORK_TAG = "dotheart_sync"
    private const val REFRESH_INTERVAL_MINUTES = 30L

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<WidgetSyncWorker>(
            REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(SYNC_WORK_TAG)
            .build()

        // KEEP: re-entry from either caller above must not reset
        // accumulated backoff state or enqueue a duplicate periodic job.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    fun enqueueManual(context: Context) {
        // Deliberately no NetworkType constraint here, unlike schedule()
        // above: a user-initiated manual refresh should attempt immediately
        // and fail fast/bounded (governed by WidgetRepository's own
        // 15-20s OkHttp timeouts) rather than sit deferred indefinitely if
        // the system's cached network-available signal is stale.
        val request = OneTimeWorkRequestBuilder<WidgetSyncWorker>()
            .addTag(SYNC_WORK_TAG)
            .build()

        // REPLACE: if the user taps repeatedly before a prior manual sync
        // finished, only the latest tap's request runs - avoids a backlog
        // of redundant fetches queuing up behind each other.
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_SYNC_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
