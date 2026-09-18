package com.dotheart.widget.net

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Single process-wide OkHttpClient. Reused across every request so its
 * connection pool avoids paying a fresh TCP/TLS handshake - and the radio
 * wake cost that comes with it - on every WorkManager execution.
 */
object HttpClientProvider {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // Overall ceiling across connect+write+server-processing+read,
            // so a single call can never hang a WorkManager execution slot
            // indefinitely even if per-phase timeouts are each individually
            // satisfied in small increments.
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
