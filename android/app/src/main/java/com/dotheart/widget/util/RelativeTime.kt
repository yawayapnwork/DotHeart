package com.dotheart.widget.util

import java.util.concurrent.TimeUnit

/**
 * Terse relative-time formatting for the widget's monospace status line
 * (e.g. "14M AGO"). Deliberately computed once per render call, never
 * scheduled on a recurring tick: the widget only re-renders on an actual
 * sync/tap/resize event (see CoupleWidgetProvider), so a self-updating
 * "live clock" here would mean introducing a new periodic Handler/AlarmManager
 * purely to keep one label fresh between those events - directly against
 * this project's Doze/battery-awareness philosophy (PLAN.md §1.1) for a
 * label whose staleness between real syncs is a few minutes at worst.
 */
object RelativeTime {

    /**
     * [nowMillis] and [eventMillis] are both epoch milliseconds. Returns
     * "NEVER" if [eventMillis] is non-positive (the event hasn't happened
     * yet - e.g. a user who has never pinged).
     */
    fun formatAgo(nowMillis: Long, eventMillis: Long): String {
        if (eventMillis <= 0L) return "NEVER"
        val deltaMs = (nowMillis - eventMillis).coerceAtLeast(0L)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(deltaMs)
        return when {
            seconds < 60 -> "NOW"
            seconds < 3600 -> "${seconds / 60}m AGO"
            seconds < 86400 -> "${seconds / 3600}h AGO"
            else -> "${seconds / 86400}d AGO"
        }
    }
}
