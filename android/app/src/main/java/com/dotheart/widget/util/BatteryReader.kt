package com.dotheart.widget.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class BatteryStatus(val levelPercent: Int, val isCharging: Boolean)

/**
 * Reads the battery level and charging state from the sticky
 * ACTION_BATTERY_CHANGED broadcast. Passing a null receiver to
 * registerReceiver returns the last broadcast Intent immediately and
 * registers nothing, so there is no persistent listener to leak or to wake
 * the process - the cost is one synchronous binder call.
 */
object BatteryReader {

    /** Below this local battery percent the widget skips all graphic reprocessing. */
    const val LOW_BATTERY_PERCENT = 15

    /** True only when a valid reading exists and it is strictly below [LOW_BATTERY_PERCENT]. */
    fun isLow(context: Context): Boolean {
        val status = read(context) ?: return false
        return status.levelPercent < LOW_BATTERY_PERCENT
    }

    /** Returns null if the system has no battery broadcast or it lacks a usable level. */
    fun read(context: Context): BatteryStatus? {
        val intent: Intent = context.applicationContext
            .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null

        val percent = (level * 100 / scale).coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return BatteryStatus(levelPercent = percent, isCharging = isCharging)
    }
}
