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
import com.dotheart.widget.state.WidgetStateStore
import com.dotheart.widget.work.WidgetSyncScheduler

/**
 * AppWidgetProvider for both placed DotHeart widget instances (they are two
 * instances of this same provider, reflecting one shared backend state -
 * see PLAN.md/WORKFLOW.md). Owns the widget lifecycle, the manual tap
 * broadcast, and WorkManager scheduling. Never performs network I/O
 * directly - all fetch/decode/render work lives in WidgetSyncWorker, which
 * this class only enqueues.
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
            render(context, appWidgetManager, appWidgetId, showRefreshing = false, showError = false)
        }
        WidgetSyncScheduler.schedule(context)
        WidgetSyncScheduler.enqueueManual(context)
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
        render(context, appWidgetManager, appWidgetId, showRefreshing = false, showError = false)
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
        // cached state (so the tap feels immediately responsive) showing a
        // "refreshing" spinner, and enqueuing the worker, then returns.
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            render(context, appWidgetManager, appWidgetId, showRefreshing = true, showError = false)
        } else {
            for (id in appWidgetManager.getAppWidgetIds(componentName(context))) {
                render(context, appWidgetManager, id, showRefreshing = true, showError = false)
            }
        }
        WidgetSyncScheduler.enqueueManual(context)
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

        /**
         * Called by WidgetSyncWorker after every sync cycle (success or
         * failure) to push the latest cached state to every placed widget
         * instance. Kept here, shared, so the provider and the worker never
         * maintain two copies of the RemoteViews-construction logic.
         */
        fun refreshAllWidgets(context: Context, showError: Boolean) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(componentName(context))
            for (id in ids) {
                render(context, appWidgetManager, id, showRefreshing = false, showError = showError)
            }
        }

        private fun componentName(context: Context) =
            ComponentName(context, CoupleWidgetProvider::class.java)

        private fun render(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            showRefreshing: Boolean,
            showError: Boolean
        ) {
            val stateStore = WidgetStateStore(context)
            val views = RemoteViews(context.packageName, R.layout.widget_couple)

            val cachedBitmap: Bitmap? = stateStore.loadCachedBitmap()
            if (cachedBitmap != null) {
                views.setImageViewBitmap(R.id.widget_image, cachedBitmap)
            } else {
                views.setImageViewResource(R.id.widget_image, R.drawable.placeholder_heart)
            }

            val message = stateStore.readMessage()
            views.setTextViewText(
                R.id.widget_message,
                message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.widget_default_message)
            )

            views.setViewVisibility(
                R.id.widget_refreshing,
                if (showRefreshing) View.VISIBLE else View.GONE
            )
            views.setViewVisibility(
                R.id.widget_error_badge,
                if (showError && !showRefreshing) View.VISIBLE else View.GONE
            )

            // Unique per-widget-instance PendingIntent: PendingIntent
            // identity is determined by component + action + data +
            // categories (NOT extras), so without a distinct data Uri and
            // request code here, every instance's tap target would resolve
            // to the same PendingIntent and only ever refresh whichever
            // instance was created first.
            val refreshIntent = Intent(context, CoupleWidgetProvider::class.java).apply {
                action = ACTION_MANUAL_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
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
    }
}
