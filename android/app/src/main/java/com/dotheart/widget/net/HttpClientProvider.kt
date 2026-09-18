package com.dotheart.widget.net

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Single process-wide OkHttpClient. Reused across every request so its
 * connection pool avoids paying a fresh TCP/TLS handshake - and the radio
 * wake cost that comes with it - on every WorkManager execution.
 *
 * Timeout values are sized around Render's free-tier cold start, not just
 * "a reasonable default": a free web service spins down after ~15 minutes
 * idle, and Render's routing layer accepts the TCP connection immediately
 * but then holds the response while the container boots - observed up to
 * ~50s in the worst case (see RELEASE.md). That delay shows up as time
 * spent waiting to *read* the response, not the initial connect, so
 * readTimeout/callTimeout are set well above it while connectTimeout stays
 * short (Render's edge always accepts the connection promptly, cold
 * container or not - a slow connect there is a genuine network problem,
 * not a cold start, and should fail fast).
 */
object HttpClientProvider {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Overall ceiling across connect+write+server-processing+read.
            // Must exceed readTimeout with headroom, not equal it, or it
            // would cut the read wait short before readTimeout itself gets
            // a chance to - 65s here vs. a 60s readTimeout leaves that
            // headroom while still bounding worst-case execution.
            .callTimeout(65, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
