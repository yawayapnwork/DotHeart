package com.dotheart.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.dotheart.widget.state.WidgetStateStore
import com.dotheart.widget.work.WidgetSyncScheduler
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Alternative 4x2 widget: a Monday-to-Sunday day strip with a square dot
 * under every day that has plans, today inverted, and a one-line monospace
 * readout of the next upcoming event. Tapping the readout cycles through the
 * upcoming events.
 *
 * Like CoupleWidgetProvider it never does network I/O: events are fetched by
 * WidgetSyncWorker (which shares one periodic job between both widgets) and
 * cached in WidgetStateStore; this class only renders that cache.
 */
class CalendarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Render whatever is cached immediately, then let the shared worker
        // fetch fresh events. Not a user tap - no ping.
        for (appWidgetId in appWidgetIds) {
            render(context, appWidgetManager, appWidgetId)
        }
        WidgetSyncScheduler.schedule(context)
        WidgetSyncScheduler.enqueueManual(context, sendPing = false)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        render(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Keeps AppWidgetProvider's own dispatch of the standard widget
        // actions to onUpdate/onDeleted/etc. before handling our own action.
        super.onReceive(context, intent)

        if (intent.action == ACTION_CYCLE_EVENT) {
            // Purely local: advance to the next cached event and redraw.
            WidgetStateStore(context).advanceCalendarCursor()
            refreshAllWidgets(context)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetSyncScheduler.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Cancels the shared periodic job only if no couple widget needs it.
        WidgetSyncScheduler.cancelIfUnused(context)
    }

    companion object {
        const val ACTION_CYCLE_EVENT = "com.dotheart.widget.ACTION_CYCLE_CALENDAR_EVENT"

        private val LETTER_IDS = intArrayOf(
            R.id.cal_letter_1, R.id.cal_letter_2, R.id.cal_letter_3, R.id.cal_letter_4,
            R.id.cal_letter_5, R.id.cal_letter_6, R.id.cal_letter_7
        )
        private val NUM_IDS = intArrayOf(
            R.id.cal_num_1, R.id.cal_num_2, R.id.cal_num_3, R.id.cal_num_4,
            R.id.cal_num_5, R.id.cal_num_6, R.id.cal_num_7
        )
        private val DOT_IDS = intArrayOf(
            R.id.cal_dot_1, R.id.cal_dot_2, R.id.cal_dot_3, R.id.cal_dot_4,
            R.id.cal_dot_5, R.id.cal_dot_6, R.id.cal_dot_7
        )

        // Fixed English abbreviations: locale-dependent month names ("Sept")
        // would break the fixed-width monospace readout.
        private val MONTHS = arrayOf(
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
        )

        private const val COLOR_BLACK = 0xFF000000.toInt()
        private const val COLOR_WHITE = 0xFFFFFFFF.toInt()

        /** True while at least one calendar widget is placed on a home screen. */
        fun hasInstances(context: Context): Boolean =
            AppWidgetManager.getInstance(context).getAppWidgetIds(componentName(context)).isNotEmpty()

        /** Called by WidgetSyncWorker after new events are cached, and by the cycle tap. */
        fun refreshAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            for (id in appWidgetManager.getAppWidgetIds(componentName(context))) {
                render(context, appWidgetManager, id)
            }
        }

        private fun componentName(context: Context) =
            ComponentName(context, CalendarWidgetProvider::class.java)

        private fun render(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val store = WidgetStateStore(context)
            val views = RemoteViews(context.packageName, R.layout.widget_calendar)

            val today = LocalDate.now()
            val events = store.readCalendarEvents()
            val eventDates = events.mapTo(HashSet()) { it.date }
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

            for (i in 0 until 7) {
                val day = monday.plusDays(i.toLong())
                views.setTextViewText(NUM_IDS[i], day.dayOfMonth.toString().padStart(2, '0'))

                if (day == today) {
                    // Inverted block: the solid-white square drawable behind
                    // black text. setBackgroundResource(0) clears it again on
                    // the other days (RemoteViews reuses this view state).
                    views.setInt(NUM_IDS[i], "setBackgroundResource", R.drawable.indicator_square)
                    views.setTextColor(NUM_IDS[i], COLOR_BLACK)
                    views.setTextColor(LETTER_IDS[i], COLOR_WHITE)
                } else {
                    views.setInt(NUM_IDS[i], "setBackgroundResource", 0)
                    views.setTextColor(NUM_IDS[i], COLOR_WHITE)
                    views.setTextColor(LETTER_IDS[i], COLOR_WHITE)
                }

                views.setViewVisibility(
                    DOT_IDS[i],
                    if (day in eventDates) View.VISIBLE else View.INVISIBLE
                )
            }

            views.setTextViewText(R.id.cal_readout, buildReadout(store, today))

            val cycleIntent = Intent(context, CalendarWidgetProvider::class.java).apply {
                action = ACTION_CYCLE_EVENT
                // Unique data URI per widget id: PendingIntent identity is
                // component + action + data, not extras, so this keeps each
                // instance's tap target distinct.
                data = Uri.parse("dotheart-calendar://cycle/$appWidgetId")
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                cycleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.cal_readout, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * The next-milestone line, e.g.
         *   NEXT: 25 SEP // [VISITING] (T-6d)
         * For an event dated today the countdown reads (TODAY). When several
         * upcoming events exist, each tap on the readout moves to the next
         * one, wrapping around, and the label becomes "NEXT 2/3". With
         * nothing scheduled: "NEXT: -- // [NO PLANS]".
         */
        private fun buildReadout(store: WidgetStateStore, today: LocalDate): String {
            val upcoming = store.readCalendarEvents()
                .filter { !it.date.isBefore(today) }
                .sortedWith(compareBy({ it.date }, { it.title }))
            if (upcoming.isEmpty()) return "NEXT: -- // [NO PLANS]"

            val index = store.readCalendarCursor().mod(upcoming.size)
            val event = upcoming[index]
            val daysAway = ChronoUnit.DAYS.between(today, event.date)

            val label = if (index == 0) "NEXT" else "NEXT ${index + 1}/${upcoming.size}"
            val countdown = if (daysAway == 0L) "TODAY" else "T-${daysAway}d"
            val month = MONTHS[event.date.monthValue - 1]
            return "$label: ${event.date.dayOfMonth} $month // [${event.title.uppercase(Locale.ROOT)}] ($countdown)"
        }
    }
}
