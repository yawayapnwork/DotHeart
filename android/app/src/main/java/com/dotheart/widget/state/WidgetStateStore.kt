package com.dotheart.widget.state

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
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

    fun writeLinkStatus(statusCode: Int) {
        prefs.edit().putInt(KEY_LINK_STATUS, statusCode).apply()
    }

    fun writeState(message: String, checksum: String, artUpdatedAt: Long, lastPingA: Long, lastPingB: Long) {
        prefs.edit()
            .putString(KEY_MESSAGE, message)
            .putString(KEY_CHECKSUM, checksum)
            .putLong(KEY_ART_UPDATED_AT, artUpdatedAt)
            .putLong(KEY_LAST_PING_A, lastPingA)
            .putLong(KEY_LAST_PING_B, lastPingB)
            .apply()
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
        private const val KEY_LINK_STATUS = "link_status"
        private const val KEY_FAILURE_COUNT = "failure_count"
        private const val KEY_ALERT_SENT = "failure_alert_sent"

        const val LINK_STATUS_UNKNOWN = 0
        const val LINK_STATUS_UNREACHABLE = -1
    }
}
