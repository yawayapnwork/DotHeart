package com.dotheart.widget.net

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.delay
import okhttp3.Request

sealed class StateFetchResult {
    object NotModified : StateFetchResult()
    data class Updated(val state: WidgetState) : StateFetchResult()
    data class Failed(val retryable: Boolean, val reason: String) : StateFetchResult()
}

sealed class ImageFetchResult {
    object NotModified : ImageFetchResult()
    data class Fetched(val bytes: ByteArray) : ImageFetchResult()
    data class Failed(val retryable: Boolean, val reason: String) : ImageFetchResult()
}

/**
 * Talks to the DotHeart FastAPI backend. Every fetch is ETag/checksum aware
 * (If-None-Match) so an unchanged server state costs one small conditional
 * round trip instead of a full JSON + image re-download - see
 * app/main.py's GET /api/v1/widget/current and GET /static/{filename}.
 */
class WidgetRepository(private val baseUrl: String) {

    private val client = HttpClientProvider.client

    suspend fun fetchCurrentState(knownChecksum: String?): StateFetchResult = try {
        withRetry {
            val request = Request.Builder()
                .url("$baseUrl/api/v1/widget/current")
                .apply { knownChecksum?.let { header("If-None-Match", "\"$it\"") } }
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> StateFetchResult.NotModified
                    response.isSuccessful -> parseStateBody(response.body?.string(), knownChecksum)
                    response.code in RETRYABLE_HTTP_CODES ->
                        StateFetchResult.Failed(retryable = true, reason = "HTTP ${response.code}")
                    else ->
                        StateFetchResult.Failed(retryable = false, reason = "HTTP ${response.code}")
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

    private fun parseStateBody(body: String?, knownChecksum: String?): StateFetchResult {
        if (body.isNullOrBlank()) {
            return StateFetchResult.Failed(retryable = true, reason = "Empty response body.")
        }
        return try {
            val state = WidgetState.fromJson(body)
            if (knownChecksum != null && state.checksum == knownChecksum) {
                StateFetchResult.NotModified
            } else {
                StateFetchResult.Updated(state)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Malformed JSON in /current response.", e)
            StateFetchResult.Failed(retryable = true, reason = "Malformed response body.")
        }
    }

    /**
     * Fast in-call retry with exponential backoff (1s, then 2s), layered
     * *underneath* WorkManager's own between-run BackoffPolicy.EXPONENTIAL
     * (configured in CoupleWidgetProvider.schedulePeriodicSync). A single
     * dropped packet or slow DNS lookup self-heals within this doWork()
     * execution instead of costing an entire extra scheduling cycle; a
     * sustained outage still exhausts these attempts and surfaces as an
     * IOException, which the caller maps to a retryable Failed result for
     * WorkManager's slower, battery-conscious backoff to take over from.
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
        private const val MAX_INTERNAL_ATTEMPTS = 3
        private const val INITIAL_BACKOFF_MS = 1000L
        private val RETRYABLE_HTTP_CODES: Set<Int> = (500..599).toSet() + 429
    }
}
