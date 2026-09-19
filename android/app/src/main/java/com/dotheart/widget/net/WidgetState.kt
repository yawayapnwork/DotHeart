package com.dotheart.widget.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * Mirrors the backend's GET /api/v1/widget/current response schema
 * (see app/main.py::get_current_widget in the FastAPI service):
 *   { "message": str, "image_url": str, "timestamp": int, "checksum": str,
 *     "last_ping_a": int, "last_ping_b": int,
 *     "peer_battery_level": int|null, "peer_is_charging": bool|null,
 *     "notes": [ { "message": str, "timestamp": int }, ... ] }  (newest first)
 *
 * last_ping_a/last_ping_b are Unix epoch seconds, 0 if that user has never
 * pinged (see app/storage.py::PingState - the ping_state row always exists
 * from backend startup, defaulting both to 0).
 *
 * peer_battery_level/peer_is_charging describe the *other* user relative to
 * the user_id sent with the request; both are null until that peer has
 * reported a reading, mapped here to PEER_BATTERY_UNKNOWN / false.
 *
 * Parsed manually via the platform's built-in org.json rather than adding a
 * JSON library dependency (Gson/Moshi) - the schema is small and stable, and
 * every byte here counts against the <15MB APK budget.
 */
/** One entry of the backend's rolling note log. */
data class WidgetNote(val message: String, val timestamp: Long)

data class WidgetState(
    val message: String,
    val imageUrl: String,
    val timestamp: Long,
    val checksum: String,
    val lastPingA: Long,
    val lastPingB: Long,
    val peerBatteryLevel: Int = PEER_BATTERY_UNKNOWN,
    val peerIsCharging: Boolean = false,
    val notes: List<WidgetNote> = emptyList()
) {
    companion object {
        const val PEER_BATTERY_UNKNOWN = -1

        @Throws(org.json.JSONException::class)
        fun fromJson(raw: String): WidgetState {
            val json = JSONObject(raw)
            // optInt/optBoolean return the fallback for both a missing key
            // and an explicit JSON null.
            return WidgetState(
                message = json.getString("message"),
                imageUrl = json.getString("image_url"),
                timestamp = json.getLong("timestamp"),
                checksum = json.getString("checksum"),
                lastPingA = json.optLong("last_ping_a", 0L),
                lastPingB = json.optLong("last_ping_b", 0L),
                peerBatteryLevel = json.optInt("peer_battery_level", PEER_BATTERY_UNKNOWN),
                peerIsCharging = json.optBoolean("peer_is_charging", false),
                notes = parseNotes(json.optJSONArray("notes"))
            )
        }

        private fun parseNotes(array: JSONArray?): List<WidgetNote> {
            if (array == null) return emptyList()
            val notes = ArrayList<WidgetNote>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                notes.add(WidgetNote(item.optString("message", ""), item.optLong("timestamp", 0L)))
            }
            return notes
        }
    }
}
