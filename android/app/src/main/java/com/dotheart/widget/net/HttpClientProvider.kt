package com.dotheart.widget.net

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Single process-wide OkHttpClient. Reused across every request so its
 * connection pool avoids paying a fresh TCP/TLS handshake - and the radio
 * wake cost that comes with it - on every WorkManager execution.
 *
 * Timeout values are sized around Render's free-tier cold start: a free web
 * service spins down after ~15 minutes idle and takes 30-50s to answer the
 * first request afterwards (see RELEASE.md). Depending on the moment, that
 * wait surfaces as a slow TCP connect, a slow response read, or a quick
 * 502/503/504 from Render's routing layer while the container boots, so all
 * three are covered:
 *  - connectTimeout 45s: a cold edge may not accept the connection promptly.
 *  - readTimeout 60s: the edge may accept, then hold the response.
 *  - [GatewayRetryInterceptor]: re-issues a request that got a gateway error.
 *  - callTimeout 150s: the overall ceiling across every attempt, sleep and
 *    read, sized for three attempts (see [MAX_GATEWAY_RETRIES]).
 */
object HttpClientProvider {

    private const val MAX_GATEWAY_RETRIES = 2
    private const val GATEWAY_BACKOFF_STEP_MS = 2_000L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // callTimeout spans the WHOLE call including application
            // interceptors, so it must cover the retries below or a slow
            // first attempt would cancel the retry that follows it. It
            // deliberately stays finite so one doWork() call is bounded.
            .callTimeout(150, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(GatewayRetryInterceptor(MAX_GATEWAY_RETRIES, GATEWAY_BACKOFF_STEP_MS))
            .build()
    }

    /**
     * Application interceptor that retries a request up to [maxRetries]
     * extra times when the server answers 502, 503 or 504 (a proxy in front
     * of a container that is still booting or briefly unreachable).
     *
     * Backoff is linear: [backoffStepMs] before the first retry,
     * 2 * [backoffStepMs] before the second (2s, then 4s by default).
     *
     * Only ever re-sends a request whose body is re-readable
     * (RequestBody.isOneShot() == false), which covers this app's GETs and
     * its small JSON ping; a one-shot body is returned as-is rather than
     * risking a corrupted second send. Each discarded response is closed
     * before the next attempt so no connection leaks. After the last retry
     * the final gateway response is returned unchanged, so callers still see
     * the real status code.
     *
     * The sleep blocks the calling thread, which is fine here: every call
     * is made from a Dispatchers.IO worker via the synchronous execute().
     * It aborts promptly if the call is cancelled.
     */
    private class GatewayRetryInterceptor(
        private val maxRetries: Int,
        private val backoffStepMs: Long
    ) : Interceptor {

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val canResend = request.body?.isOneShot() != true

            var response = chain.proceed(request)
            var retriesUsed = 0
            while (canResend && response.code in GATEWAY_ERROR_CODES && retriesUsed < maxRetries) {
                retriesUsed++
                response.close()
                sleepOrAbort(chain, backoffStepMs * retriesUsed)
                response = chain.proceed(request)
            }
            return response
        }

        private fun sleepOrAbort(chain: Interceptor.Chain, millis: Long) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            try {
                Thread.sleep(millis)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted during gateway retry backoff")
            }
        }

        private companion object {
            val GATEWAY_ERROR_CODES = setOf(502, 503, 504)
        }
    }
}
