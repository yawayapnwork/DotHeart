package com.dotheart.widget.net

import org.json.JSONObject

/**
 * Mirrors the backend's GET /api/v1/widget/current response schema
 * (see app/main.py::get_current_widget in the FastAPI service):
 *   { "message": str, "image_url": str, "timestamp": int, "checksum": str,
 *     "last_ping_a": int, "last_ping_b": int }
 *
 * last_ping_a/last_ping_b are Unix epoch seconds, 0 if that user has never
 * pinged (see app/storage.py::PingState - the ping_state row always exists
 * from backend startup, defaulting both to 0).
 *
 * Parsed manually via the platform's built-in org.json rather than adding a
 * JSON library dependency (Gson/Moshi) - the schema is small and stable, and
 * every byte here counts against the <15MB APK budget.
 */
data class WidgetState(
    val message: String,
    val imageUrl: String,
    val timestamp: Long,
    val checksum: String,
    val lastPingA: Long,
    val lastPingB: Long
) {
    companion object {
        @Throws(org.json.JSONException::class)
        fun fromJson(raw: String): WidgetState {
            val json = JSONObject(raw)
            return WidgetState(
                message = json.getString("message"),
                imageUrl = json.getString("image_url"),
                timestamp = json.getLong("timestamp"),
                checksum = json.getString("checksum"),
                lastPingA = json.optLong("last_ping_a", 0L),
                lastPingB = json.optLong("last_ping_b", 0L)
            )
        }
    }
}
