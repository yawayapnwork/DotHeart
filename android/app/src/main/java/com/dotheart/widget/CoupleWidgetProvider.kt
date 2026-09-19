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
import android.view.View
import android.widget.RemoteViews
import com.dotheart.widget.render.PixelArtRenderer
import com.dotheart.widget.state.WidgetStateStore
import com.dotheart.widget.util.BatteryReader
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

        if (intent.action == ACTION_TOGGLE_VIEW) {
            // Status-bar tap: purely local CANVAS <-> LOG flip from cached
            // state. No network, no worker, no ping.
            WidgetStateStore(context).toggleDisplayMode()
            refreshAllWidgets(context)
            return
        }

        if (intent.action != ACTION_MANUAL_REFRESH) return

        // RemoteViews cannot detect gestures, so a double-tap is emulated: a
        // second body tap within DOUBLE_TAP_WINDOW_MS of the first toggles the
        // view instead of refreshing. (The first tap of the pair has already
        // started a refresh, which is harmless.)
        val tapStore = WidgetStateStore(context)
        val tapNow = System.currentTimeMillis()
        val elapsed = tapNow - tapStore.readLastTapMillis()
        if (elapsed in 1..DOUBLE_TAP_WINDOW_MS) {
            tapStore.writeLastTapMillis(0L)
            tapStore.toggleDisplayMode()
            refreshAllWidgets(context)
            return
        }
        tapStore.writeLastTapMillis(tapNow)

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
        const val ACTION_TOGGLE_VIEW = "com.dotheart.widget.ACTION_TOGGLE_VIEW"

        /** Two body taps closer together than this count as a double-tap. */
        private const val DOUBLE_TAP_WINDOW_MS = 400L

        /** Notes shown in LOG mode. */
        private const val LOG_LINE_COUNT = 3

        /** Peer silent for at least this long: ticker shows [SIGNAL WEAK]. */
        private const val STALE_THRESHOLD_MS = 2L * 60 * 60 * 1000

        /** Peer silent for more than this long: [CARRIER LOST] and decayed canvas. */
        private const val DECAYED_THRESHOLD_MS = 24L * 60 * 60 * 1000

        private enum class Signal { ACTIVE, STALE, DECAYED }

        /**
         * Classifies the peer's connection by time since their last ping.
         * A peer who has never pinged (timestamp 0) is ACTIVE: there is no
         * baseline to call stale, and a fresh install must not look dead.
         */
        private fun evaluateSignal(store: WidgetStateStore, now: Long): Signal {
            val peerPingSeconds = if (BuildConfig.DOTHEART_LOCAL_USER_ID == "a") {
                store.readLastPingB()
            } else {
                store.readLastPingA()
            }
            if (peerPingSeconds <= 0L) return Signal.ACTIVE
            val timeSinceLastPing = now - peerPingSeconds * 1000L
            return when {
                timeSinceLastPing > DECAYED_THRESHOLD_MS -> Signal.DECAYED
                timeSinceLastPing >= STALE_THRESHOLD_MS -> Signal.STALE
                else -> Signal.ACTIVE
            }
        }

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

            val signal = evaluateSignal(store, System.currentTimeMillis())
            val logMode = store.readDisplayMode() == WidgetStateStore.DISPLAY_MODE_LOG
            if (logMode) {
                // LOG mode never touches the bitmap: no cache decode, no decay
                // pass - just three short strings.
                views.setViewVisibility(R.id.iv_pixel_art, View.GONE)
                views.setViewVisibility(R.id.ll_log_history, View.VISIBLE)
                bindLogLines(views, store)
            } else {
                views.setViewVisibility(R.id.ll_log_history, View.GONE)
                views.setViewVisibility(R.id.iv_pixel_art, View.VISIBLE)
                val cachedBitmap: Bitmap? = store.loadCachedBitmap()
                if (cachedBitmap != null) {
                    // Below 15% local battery, skip the per-pixel decay pass and
                    // show the cached art as-is: no extra CPU work on a dying phone.
                    val shown = if (signal == Signal.DECAYED && !BatteryReader.isLow(context)) {
                        PixelArtRenderer.applyDecay(cachedBitmap)
                    } else {
                        cachedBitmap
                    }
                    views.setImageViewBitmap(R.id.iv_pixel_art, shown)
                } else {
                    // No art has ever synced yet - fully transparent, not a
                    // decorative placeholder graphic. The status line below
                    // ("NEVER"/"[SYNCING...]") already communicates the state.
                    views.setImageViewResource(R.id.iv_pixel_art, android.R.color.transparent)
                }
            }

            views.setTextViewText(
                R.id.widget_status,
                buildStatusLine(store, showSyncing, signal)
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

            // The status bar is a separate, more specific tap target: it flips
            // CANVAS <-> LOG locally instead of refreshing. Its own data URI
            // keeps its PendingIntent distinct from the refresh one.
            val toggleIntent = Intent(context, CoupleWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_VIEW
                data = Uri.parse("dotheart-widget://toggle/$appWidgetId")
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_status, togglePendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * Fills the three terminal lines with the last [LOG_LINE_COUNT] notes,
         * oldest on top and newest at the bottom (the backend returns newest
         * first), e.g.:
         *   > left studio
         *   > train delayed
         *   > coffee
         * Fewer notes are bottom-aligned with blank lines above; no notes
         * at all shows "> NO LOG".
         */
        private fun bindLogLines(views: RemoteViews, store: WidgetStateStore) {
            val lineIds = intArrayOf(R.id.tv_log_line_1, R.id.tv_log_line_2, R.id.tv_log_line_3)
            val notes = store.readNotes().take(LOG_LINE_COUNT).reversed()
            val lines = if (notes.isEmpty()) {
                listOf("> NO LOG")
            } else {
                notes.map { "> ${it.message}" }
            }
            val blankPrefix = LOG_LINE_COUNT - lines.size
            for (slot in 0 until LOG_LINE_COUNT) {
                val text = if (slot < blankPrefix) "" else lines[slot - blankPrefix]
                views.setTextViewText(lineIds[slot], text)
            }
        }

        /**
         * Builds the single-line monospace telemetry readout, e.g.:
         *   PEER 84% [CHRG] // SYNC 14m AGO // LINK 200 > out for milk
         *   PEER 42% [BAT] // SYNC 2m AGO // LINK 200 > out for milk
         *   PEER --% [---] // SYNC NEVER // LINK ? (peer battery not yet reported)
         * or, mid-tap:
         *   [SYNCING...] > out for milk
         */
        private fun buildStatusLine(store: WidgetStateStore, showSyncing: Boolean, signal: Signal): String {
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
                val base = "PEER ${peerPowerReadout(store)} // SYNC $ago // LINK $link"
                when (signal) {
                    Signal.ACTIVE -> base
                    Signal.STALE -> "$base [SIGNAL WEAK]"
                    Signal.DECAYED -> "$base [CARRIER LOST]"
                }
            }

            val message = store.readMessage()
            return if (!message.isNullOrBlank()) {
                "$header > $message"
            } else {
                header
            }
        }

        private fun peerPowerReadout(store: WidgetStateStore): String {
            val level = store.readPeerBatteryLevel()
            if (level < 0) return "--% [---]"
            return "$level% ${if (store.readPeerIsCharging()) "[CHRG]" else "[BAT]"}"
        }

        /** Dimmed-but-not-invisible alpha for an out-of-window link indicator - a deliberate "present but not lit" state, not a hidden view. */
        private const val DIM_ALPHA = 40
    }
}
