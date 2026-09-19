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
import androidx.work.workDataOf
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

    /**
     * Base delay for WorkManager's exponential backoff after a transient
     * failure (Result.retry()): 30s, 60s, 120s, ... capped by WorkManager
     * at 5 hours. Long enough for a Render free-tier container to finish
     * booting between attempts, short enough that a blip self-heals quickly.
     */
    private const val BACKOFF_DELAY_SECONDS = 30L

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
                BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS
            )
            .addTag(SYNC_WORK_TAG)
            .build()

        // UPDATE (not KEEP): installs that already have the periodic job
        // registered with the old 10s backoff pick up the new criteria on
        // the next process start. Like KEEP, re-entry from either caller
        // above is idempotent - it never enqueues a duplicate - and UPDATE
        // preserves the job's existing schedule/period timing.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    const val INPUT_KEY_SEND_PING = "send_ping"

    /**
     * [sendPing] distinguishes a user-initiated tap (true - also POSTs a
     * presence ping for the local user before fetching state, per the
     * tap-to-refresh contract) from an internal manual re-enqueue that
     * shouldn't itself count as a ping (false - e.g. WidgetSyncWorker never
     * re-enqueues itself this way today, but onUpdate's initial placement
     * sync below does, and that isn't a user tap).
     */
    fun enqueueManual(context: Context, sendPing: Boolean) {
        // Deliberately no NetworkType constraint here, unlike schedule()
        // above: a user-initiated manual refresh should attempt immediately
        // and fail fast/bounded (governed by WidgetRepository's own
        // 15-20s OkHttp timeouts) rather than sit deferred indefinitely if
        // the system's cached network-available signal is stale.
        val request = OneTimeWorkRequestBuilder<WidgetSyncWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .addTag(SYNC_WORK_TAG)
            .setInputData(workDataOf(INPUT_KEY_SEND_PING to sendPing))
            .build()

        // REPLACE: if the user taps repeatedly before a prior manual sync
        // finished, only the latest tap's request runs - avoids a backlog
        // of redundant fetches queuing up behind each other. A risk this
        // accepts: a rapid second tap can replace (and thus lose) a still-
        // pending first tap's ping before it sends - acceptable for a
        // presence indicator that only cares about recent activity, not an
        // exact tap count.
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_SYNC_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
