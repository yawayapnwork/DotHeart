package com.dotheart.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import com.dotheart.widget.state.WidgetStateStore
import com.dotheart.widget.util.RelativeTime
import com.dotheart.widget.work.WidgetSyncScheduler
import kotlin.math.abs

/**
 * AppWidgetProvider for both placed DotHeart widget instances (they are two
 * instances of this same provider, reflecting one shared backend state -
 * see PLAN.md/WORKFLOW.md). Owns the widget lifecycle, the manual tap
 * broadcast, and WorkManager scheduling. Never performs network I/O
 * directly - all fetch/decode/render/ping work lives in WidgetSyncWorker,
 * which this class only enqueues.
 */
class CoupleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // A widget instance was just placed, or the process was restarted
        // and the OS re-delivered this to re-establish state. Render
        // whatever is cached on disk immediately - never show a blank
        // widget while the first network fetch is still pending.
        for (appWidgetId in appWidgetIds) {
            render(context, appWidgetManager, appWidgetId, showSyncing = false)
        }
        WidgetSyncScheduler.schedule(context)
        // Initial placement sync, not a user tap - no ping.
        WidgetSyncScheduler.enqueueManual(context, sendPing = false)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // Pure re-render of the already-downloaded, already-scaled cached
        // bitmap at the new widget size - no network call, must be instant.
        render(context, appWidgetManager, appWidgetId, showSyncing = false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Preserves AppWidgetProvider's own dispatch of the standard
        // ACTION_APPWIDGET_UPDATE / _DELETED / _OPTIONS_CHANGED / _ENABLED /
        // _DISABLED actions to onUpdate/onDeleted/etc below, before we
        // additionally handle our own custom action.
        super.onReceive(context, intent)

        if (intent.action != ACTION_MANUAL_REFRESH) return

        // onReceive runs on the main thread with a strict OS-enforced
        // execution budget - it must never perform network I/O itself. It
        // does exactly two cheap things: an instant local re-render from
        // cached state (so the tap feels immediately responsive) showing
        // "[SYNCING...]" in the status line, and enqueuing the worker
        // (which sends the presence ping, then fetches/renders fresh
        // state), then returns.
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            render(context, appWidgetManager, appWidgetId, showSyncing = true)
        } else {
            for (id in appWidgetManager.getAppWidgetIds(componentName(context))) {
                render(context, appWidgetManager, id, showSyncing = true)
            }
        }
        WidgetSyncScheduler.enqueueManual(context, sendPing = true)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetSyncScheduler.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Last widget instance removed - cancel the periodic job so no
        // orphaned background work keeps polling and draining battery for
        // a widget that no longer exists.
        WidgetSyncScheduler.cancel(context)
    }

    companion object {
        const val ACTION_MANUAL_REFRESH = "com.dotheart.widget.ACTION_MANUAL_REFRESH"

        /** Both users' pings count as "linked" if this close together. */
        private const val LINK_WINDOW_SECONDS = 600L // 10 minutes

        /**
         * Called by WidgetSyncWorker after every sync cycle (success or
         * failure) to push the latest persisted state to every placed
         * widget instance. Kept here, shared, so the provider and the
         * worker never maintain two copies of the RemoteViews-construction
         * logic.
         */
        fun refreshAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(componentName(context))
            for (id in ids) {
                render(context, appWidgetManager, id, showSyncing = false)
            }
        }

        private fun componentName(context: Context) =
            ComponentName(context, CoupleWidgetProvider::class.java)

        private fun render(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            showSyncing: Boolean
        ) {
            val store = WidgetStateStore(context)
            val views = RemoteViews(context.packageName, R.layout.widget_couple)

            val cachedBitmap: Bitmap? = store.loadCachedBitmap()
            if (cachedBitmap != null) {
                views.setImageViewBitmap(R.id.widget_image, cachedBitmap)
            } else {
                // No art has ever synced yet - fully transparent, not a
                // decorative placeholder graphic. The status line below
                // ("NEVER"/"[SYNCING...]") already communicates the state.
                views.setImageViewResource(R.id.widget_image, android.R.color.transparent)
            }

            views.setTextViewText(
                R.id.widget_status,
                buildStatusLine(store, showSyncing)
            )

            val pingA = store.readLastPingA()
            val pingB = store.readLastPingB()
            val linked = pingA > 0L && pingB > 0L && abs(pingA - pingB) <= LINK_WINDOW_SECONDS
            // setInt(...,"setImageAlpha",...) is RemoteViews' documented
            // mechanism for calling an arbitrary single-int-arg setter
            // reflectively - used here instead of a second drawable/
            // visibility toggle so the indicator is one view with two
            // brightness states, not two views or two drawables.
            views.setInt(R.id.link_indicator, "setImageAlpha", if (linked) 255 else DIM_ALPHA)

            val refreshIntent = Intent(context, CoupleWidgetProvider::class.java).apply {
                action = ACTION_MANUAL_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                // Unique data URI per widget id: PendingIntent identity is
                // determined by component + action + data + categories
                // (NOT extras), so without this every instance's tap target
                // would resolve to the same PendingIntent and only ever
                // refresh whichever instance was created first.
                data = Uri.parse("dotheart-widget://refresh/$appWidgetId")
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                refreshIntent,
                // FLAG_IMMUTABLE is mandatory behavior to declare explicitly
                // on API 23+ (enforced as a hard requirement on API 31+):
                // this PendingIntent's Intent can never be modified by the
                // receiving launcher process.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * Builds the single-line monospace telemetry readout, e.g.:
         *   \[SYNC 14M AGO // LINK 200\] > out for milk
         * or, mid-tap:
         *   \[SYNCING...\] > out for milk
         */
        private fun buildStatusLine(store: WidgetStateStore, showSyncing: Boolean): String {
            val header = if (showSyncing) {
                "[SYNCING...]"
            } else {
                val now = System.currentTimeMillis()
                val ago = RelativeTime.formatAgo(now, store.readArtUpdatedAt() * 1000L)
                val link = when (val status = store.readLinkStatus()) {
                    WidgetStateStore.LINK_STATUS_UNKNOWN -> "?"
                    WidgetStateStore.LINK_STATUS_UNREACHABLE -> "ERR"
                    else -> status.toString()
                }
                "[SYNC $ago // LINK $link]"
            }

            val message = store.readMessage()
            return if (!message.isNullOrBlank()) {
                "$header > $message"
            } else {
                header
            }
        }

        /** Dimmed-but-not-invisible alpha for an out-of-window link indicator - a deliberate "present but not lit" state, not a hidden view. */
        private const val DIM_ALPHA = 40
    }
}
