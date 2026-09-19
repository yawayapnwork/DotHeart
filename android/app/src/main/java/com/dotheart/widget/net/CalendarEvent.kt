package com.dotheart.widget.net

import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONObject

/**
 * One shared-calendar event, mirroring the backend's event object
 * (app/storage.py::CalendarStorage):
 *   { "id": "3fa9c2d1", "date": "2026-09-25", "title": "visiting",
 *     "author": "a", "updated_at": int }
 * updated_at is not needed by the widget and is not kept.
 */
data class CalendarEvent(
    val id: String,
    val date: LocalDate,
    val title: String,
    val author: String
) {
    companion object {
        /**
         * Parses a JSON array of event objects. Entries with a missing id or
         * an unparseable date are skipped rather than failing the whole
         * list, so one bad row can never blank the calendar.
         */
        fun fromJsonArray(array: JSONArray?): List<CalendarEvent> {
            if (array == null) return emptyList()
            val events = ArrayList<CalendarEvent>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                if (id.isEmpty()) continue
                val date = try {
                    LocalDate.parse(item.optString("date", ""))
                } catch (e: DateTimeParseException) {
                    continue
                }
                events.add(
                    CalendarEvent(
                        id = id,
                        date = date,
                        title = item.optString("title", ""),
                        author = item.optString("author", "")
                    )
                )
            }
            return events
        }

        fun toJsonArray(events: List<CalendarEvent>): JSONArray {
            val array = JSONArray()
            for (event in events) {
                array.put(
                    JSONObject()
                        .put("id", event.id)
                        .put("date", event.date.toString())
                        .put("title", event.title)
                        .put("author", event.author)
                )
            }
            return array
        }
    }
}

sealed class CalendarFetchResult {
    data class Success(val events: List<CalendarEvent>) : CalendarFetchResult()
    data class Failed(val retryable: Boolean, val reason: String) : CalendarFetchResult()
}
