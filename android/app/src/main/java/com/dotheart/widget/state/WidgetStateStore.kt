package com.dotheart.widget.state

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.dotheart.widget.net.WidgetNote
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * SharedPreferences + internal-storage wrapper holding the widget's last
 * known state: message/checksum/art-timestamp/ping-timestamps mirroring the
 * backend's current-state schema, the last observed link (HTTP) status, the
 * failure-streak counter driving the "hasn't updated in a while" alert, and
 * a persisted copy of the last successfully rendered bitmap so a fresh
 * process start can render last-known-good pixels before any network
 * attempt.
 *
 * Uses context.filesDir (internal storage) for the bitmap cache
 * specifically so no WRITE_EXTERNAL_STORAGE / scoped-storage permission is
 * ever required.
 */
class WidgetStateStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cacheFile: File
        get() = File(appContext.filesDir, CACHE_FILENAME)

    fun readChecksum(): String? = prefs.getString(KEY_CHECKSUM, null)

    fun readMessage(): String? = prefs.getString(KEY_MESSAGE, null)

    /** Epoch seconds the art/message was last updated on the backend. */
    fun readArtUpdatedAt(): Long = prefs.getLong(KEY_ART_UPDATED_AT, 0L)

    /** Epoch seconds of each user's last recorded ping; 0 if never. */
    fun readLastPingA(): Long = prefs.getLong(KEY_LAST_PING_A, 0L)

    fun readLastPingB(): Long = prefs.getLong(KEY_LAST_PING_B, 0L)

    /**
     * Last observed link status: an HTTP status code (200 = healthy), or
     * LINK_STATUS_UNREACHABLE if the most recent attempt never got a
     * response at all, or LINK_STATUS_UNKNOWN before any sync has ever
     * completed (fresh install).
     */
    fun readLinkStatus(): Int = prefs.getInt(KEY_LINK_STATUS, LINK_STATUS_UNKNOWN)

    /**
     * True while the most recent sync failed in a way that is expected to
     * heal by itself: no response at all (timeout / cold start / offline),
     * a 5xx gateway or server error, or 429. The widget then keeps the last
     * bitmap on screen and shows "[RETRYING LINK...]" instead of an error.
     * Permanent failures (e.g. 401/404) are not "retrying" and keep showing
     * their real LINK code.
     */
    fun isLinkRetrying(): Boolean {
        val status = readLinkStatus()
        return status == LINK_STATUS_UNREACHABLE || status in 500..599 || status == 429
    }

    fun writeLinkStatus(statusCode: Int) {
        prefs.edit().putInt(KEY_LINK_STATUS, statusCode).apply()
    }

    /** Peer's last reported battery percent, or -1 if unknown. */
    fun readPeerBatteryLevel(): Int = prefs.getInt(KEY_PEER_BATTERY_LEVEL, -1)

    fun readPeerIsCharging(): Boolean = prefs.getBoolean(KEY_PEER_IS_CHARGING, false)

    /**
     * Cached note log, newest first, exactly as the backend returned it
     * (at most 5). Empty if none has synced yet or the stored JSON is corrupt.
     */
    fun readNotes(): List<WidgetNote> {
        val raw = prefs.getString(KEY_NOTES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let {
                    WidgetNote(it.optString("message", ""), it.optLong("timestamp", 0L))
                }
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Discarding corrupt cached notes.", e)
            emptyList()
        }
    }

    /** Current display mode: DISPLAY_MODE_CANVAS (default) or DISPLAY_MODE_LOG. */
    fun readDisplayMode(): String =
        prefs.getString(KEY_DISPLAY_MODE, DISPLAY_MODE_CANVAS) ?: DISPLAY_MODE_CANVAS

    /** Flips CANVAS <-> LOG and returns the new mode. */
    fun toggleDisplayMode(): String {
        val next = if (readDisplayMode() == DISPLAY_MODE_LOG) DISPLAY_MODE_CANVAS else DISPLAY_MODE_LOG
        prefs.edit().putString(KEY_DISPLAY_MODE, next).apply()
        return next
    }

    /** Epoch millis of the previous widget-body tap, for double-tap detection. */
    fun readLastTapMillis(): Long = prefs.getLong(KEY_LAST_TAP_MS, 0L)

    fun writeLastTapMillis(millis: Long) {
        prefs.edit().putLong(KEY_LAST_TAP_MS, millis).apply()
    }

    fun writeState(
        message: String,
        checksum: String,
        artUpdatedAt: Long,
        lastPingA: Long,
        lastPingB: Long,
        peerBatteryLevel: Int,
        peerIsCharging: Boolean,
        notes: List<WidgetNote>
    ) {
        prefs.edit()
            .putString(KEY_MESSAGE, message)
            .putString(KEY_CHECKSUM, checksum)
            .putLong(KEY_ART_UPDATED_AT, artUpdatedAt)
            .putLong(KEY_LAST_PING_A, lastPingA)
            .putLong(KEY_LAST_PING_B, lastPingB)
            .putInt(KEY_PEER_BATTERY_LEVEL, peerBatteryLevel)
            .putBoolean(KEY_PEER_IS_CHARGING, peerIsCharging)
            .putString(KEY_NOTES, notesToJson(notes))
            .apply()
    }

    private fun notesToJson(notes: List<WidgetNote>): String {
        val array = JSONArray()
        for (note in notes) {
            array.put(JSONObject().put("message", note.message).put("timestamp", note.timestamp))
        }
        return array.toString()
    }

    fun saveBitmapToCache(bitmap: Bitmap) {
        try {
            FileOutputStream(cacheFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to persist widget bitmap cache.", e)
        }
    }

    fun loadCachedBitmap(): Bitmap? {
        if (!cacheFile.exists()) return null
        return try {
            BitmapFactory.decodeFile(cacheFile.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode cached widget bitmap.", e)
            null
        }
    }

    fun incrementFailureCount(): Int {
        val next = prefs.getInt(KEY_FAILURE_COUNT, 0) + 1
        prefs.edit().putInt(KEY_FAILURE_COUNT, next).apply()
        return next
    }

    fun resetFailureCount() {
        prefs.edit()
            .putInt(KEY_FAILURE_COUNT, 0)
            .putBoolean(KEY_ALERT_SENT, false)
            .apply()
    }

    fun hasSentFailureAlert(): Boolean = prefs.getBoolean(KEY_ALERT_SENT, false)

    fun markFailureAlertSent() {
        prefs.edit().putBoolean(KEY_ALERT_SENT, true).apply()
    }

    companion object {
        private const val TAG = "WidgetStateStore"
        private const val PREFS_NAME = "dotheart_widget_state"
        private const val CACHE_FILENAME = "widget_cache.png"
        private const val KEY_CHECKSUM = "checksum"
        private const val KEY_MESSAGE = "message"
        private const val KEY_ART_UPDATED_AT = "art_updated_at"
        private const val KEY_LAST_PING_A = "last_ping_a"
        private const val KEY_LAST_PING_B = "last_ping_b"
        private const val KEY_PEER_BATTERY_LEVEL = "peer_battery_level"
        private const val KEY_PEER_IS_CHARGING = "peer_is_charging"
        private const val KEY_NOTES = "notes"
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_LAST_TAP_MS = "last_tap_ms"
        private const val KEY_LINK_STATUS = "link_status"
        private const val KEY_FAILURE_COUNT = "failure_count"
        private const val KEY_ALERT_SENT = "failure_alert_sent"

        const val DISPLAY_MODE_CANVAS = "CANVAS"
        const val DISPLAY_MODE_LOG = "LOG"

        const val LINK_STATUS_UNKNOWN = 0
        const val LINK_STATUS_UNREACHABLE = -1
    }
}
