package com.dotheart.widget.net

import android.util.Log
import com.dotheart.widget.util.BatteryStatus
import java.io.IOException
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

sealed class StateFetchResult {
    object NotModified : StateFetchResult()
    data class Updated(val state: WidgetState) : StateFetchResult()

    /**
     * [httpCode] is the real HTTP status when the server actually responded
     * (used to drive the widget's "LINK <code>" telemetry), or null when
     * the failure never got a response at all (DNS/timeout/connection
     * refused) - callers should treat a null code as "unreachable", not as
     * "unknown code".
     */
    data class Failed(val retryable: Boolean, val reason: String, val httpCode: Int? = null) : StateFetchResult()
}

sealed class ImageFetchResult {
    object NotModified : ImageFetchResult()
    data class Fetched(val bytes: ByteArray) : ImageFetchResult()
    data class Failed(val retryable: Boolean, val reason: String) : ImageFetchResult()
}

sealed class PingResult {
    data class Success(val timestamp: Long) : PingResult()
    data class Failed(val retryable: Boolean, val reason: String) : PingResult()
}

/**
 * Talks to the DotHeart FastAPI backend. Every fetch is ETag/checksum aware
 * (If-None-Match) so an unchanged server state costs one small conditional
 * round trip instead of a full JSON + image re-download - see
 * app/main.py's GET /api/v1/widget/current and GET /static/{filename}.
 *
 * [pingToken] authenticates POST /api/v1/widget/ping (Authorization: Bearer)
 * - it is the same shared secret as the backend's WIDGET_TOKEN, embedded in
 * this APK at build time via BuildConfig.DOTHEART_PING_TOKEN (see
 * android/app/build.gradle.kts). This is a deliberate, accepted tradeoff for
 * a private, sideloaded, two-person app with no server-backed user accounts
 * and no in-app settings UI to collect a secret at runtime instead: anyone
 * who decompiles the APK recovers this token. Do not reuse it for anything
 * more sensitive than "which of two trusted people tapped a home screen
 * widget."
 */
class WidgetRepository(private val baseUrl: String, private val pingToken: String) {

    private val client = HttpClientProvider.client

    /**
     * [userId] ("a"/"b") tells the backend who is asking so it can return
     * the *peer's* battery state (peer_battery_level / peer_is_charging).
     */
    suspend fun fetchCurrentState(knownChecksum: String?, userId: String): StateFetchResult = try {
        withRetry {
            val url = "$baseUrl/api/v1/widget/current".toHttpUrl().newBuilder()
                .addQueryParameter("user_id", userId)
                .build()
            val request = Request.Builder()
                .url(url)
                .apply { knownChecksum?.let { header("If-None-Match", "\"$it\"") } }
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> StateFetchResult.NotModified
                    response.isSuccessful -> parseStateBody(response.body?.string(), response.code)
                    response.code in RETRYABLE_HTTP_CODES ->
                        StateFetchResult.Failed(retryable = true, reason = "HTTP ${response.code}", httpCode = response.code)
                    else ->
                        StateFetchResult.Failed(retryable = false, reason = "HTTP ${response.code}", httpCode = response.code)
                }
            }
        }
    } catch (e: IOException) {
        Log.w(TAG, "fetchCurrentState exhausted retries.", e)
        StateFetchResult.Failed(retryable = true, reason = e.message ?: "Network error")
    }

    suspend fun fetchImage(imageUrl: String, knownChecksum: String?): ImageFetchResult = try {
        withRetry {
            val fullUrl = if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
                imageUrl
            } else {
                "$baseUrl$imageUrl"
            }
            val request = Request.Builder()
                .url(fullUrl)
                .apply { knownChecksum?.let { header("If-None-Match", "\"$it\"") } }
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> ImageFetchResult.NotModified
                    response.isSuccessful -> {
                        val bytes = response.body?.bytes()
                        if (bytes == null || bytes.isEmpty()) {
                            ImageFetchResult.Failed(
                                retryable = true,
                                reason = "Empty image body on HTTP ${response.code}."
                            )
                        } else {
                            ImageFetchResult.Fetched(bytes)
                        }
                    }
                    response.code in RETRYABLE_HTTP_CODES ->
                        ImageFetchResult.Failed(retryable = true, reason = "HTTP ${response.code}")
                    else ->
                        ImageFetchResult.Failed(retryable = false, reason = "HTTP ${response.code}")
                }
            }
        }
    } catch (e: IOException) {
        Log.w(TAG, "fetchImage exhausted retries.", e)
        ImageFetchResult.Failed(retryable = true, reason = e.message ?: "Network error")
    }

    /**
     * [battery] is sent as `battery_level` / `is_charging` JSON fields when
     * non-null; a null reading just sends the bare presence ping.
     */
    suspend fun sendPing(userId: String, battery: BatteryStatus? = null): PingResult {
        if (pingToken.isBlank()) {
            return PingResult.Failed(retryable = false, reason = "Ping token not configured.")
        }
        return try {
            withRetry {
                val jsonBody = JSONObject().put("user_id", userId).apply {
                    if (battery != null) {
                        put("battery_level", battery.levelPercent)
                        put("is_charging", battery.isCharging)
                    }
                }.toString()
                val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/widget/ping")
                    .header("Authorization", "Bearer $pingToken")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> parsePingBody(response.body?.string())
                        response.code in RETRYABLE_HTTP_CODES ->
                            PingResult.Failed(retryable = true, reason = "HTTP ${response.code}")
                        else ->
                            PingResult.Failed(retryable = false, reason = "HTTP ${response.code}")
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "sendPing exhausted retries.", e)
            PingResult.Failed(retryable = true, reason = e.message ?: "Network error")
        }
    }

    private fun parsePingBody(body: String?): PingResult {
        // The HTTP status already confirmed success server-side; a
        // malformed/empty body here only costs us the exact echoed
        // timestamp, not correctness, so this degrades to Success(0)
        // with a log line rather than treating it as a failure.
        if (body.isNullOrBlank()) return PingResult.Success(0L)
        return try {
            PingResult.Success(JSONObject(body).optLong("timestamp", 0L))
        } catch (e: Exception) {
            Log.w(TAG, "Malformed JSON in /ping response.", e)
            PingResult.Success(0L)
        }
    }

    private fun parseStateBody(body: String?, httpCode: Int): StateFetchResult {
        if (body.isNullOrBlank()) {
            return StateFetchResult.Failed(retryable = true, reason = "Empty response body.", httpCode = httpCode)
        }
        return try {
            // Always Updated, even on a checksum match: peer battery and ping
            // fields change independently of the art, and the worker skips
            // the image download itself when the checksum is unchanged.
            StateFetchResult.Updated(WidgetState.fromJson(body))
        } catch (e: Exception) {
            Log.w(TAG, "Malformed JSON in /current response.", e)
            StateFetchResult.Failed(retryable = true, reason = "Malformed response body.", httpCode = httpCode)
        }
    }

    /**
     * Fast in-call retry with a 1s backoff, layered *underneath*
     * WorkManager's own between-run BackoffPolicy.EXPONENTIAL (configured
     * in WidgetSyncScheduler). A single dropped packet or slow DNS lookup
     * self-heals within this doWork() execution instead of costing an
     * entire extra scheduling cycle; a sustained outage still exhausts
     * these attempts and surfaces as an IOException, which the caller maps
     * to a retryable Failed result for WorkManager's slower,
     * battery-conscious backoff to take over from.
     *
     * Deliberately only 2 attempts, not 3: HttpClientProvider's readTimeout/
     * callTimeout are already sized (60s/65s) to let a *single* attempt
     * survive a Render free-tier cold start on its own, so a second attempt
     * here exists only to catch a genuine transient blip (the container is
     * warm by then regardless) - not to retry the cold-start wait itself.
     * At 65s per attempt, 3 attempts would allow a single doWork() call to
     * run for up to ~3 minutes worst case, which is excessive.
     */
    private suspend fun <T> withRetry(block: () -> T): T {
        var lastError: IOException? = null
        repeat(MAX_INTERNAL_ATTEMPTS) { attemptIndex ->
            try {
                return block()
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "Network attempt ${attemptIndex + 1}/$MAX_INTERNAL_ATTEMPTS failed: ${e.message}")
                if (attemptIndex < MAX_INTERNAL_ATTEMPTS - 1) {
                    delay(INITIAL_BACKOFF_MS * (1L shl attemptIndex))
                }
            }
        }
        throw lastError ?: IOException("Unknown network failure.")
    }

    companion object {
        private const val TAG = "WidgetRepository"
        private const val MAX_INTERNAL_ATTEMPTS = 2
        private const val INITIAL_BACKOFF_MS = 1000L
        private val RETRYABLE_HTTP_CODES: Set<Int> = (500..599).toSet() + 429
    }
}
