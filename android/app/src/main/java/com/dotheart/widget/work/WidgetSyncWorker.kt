package com.dotheart.widget.work

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import android.util.Log
import com.dotheart.widget.BuildConfig
import com.dotheart.widget.CoupleWidgetProvider
import com.dotheart.widget.R
import com.dotheart.widget.net.ImageFetchResult
import com.dotheart.widget.net.StateFetchResult
import com.dotheart.widget.net.WidgetRepository
import com.dotheart.widget.render.PixelArtRenderer
import com.dotheart.widget.state.WidgetStateStore
import com.dotheart.widget.util.NotificationChannels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single sync implementation shared by both the periodic background
 * poll and the manual tap-to-refresh path (CoupleWidgetProvider enqueues
 * this same worker class for both, so there is exactly one fetch/decode/
 * render pipeline to keep correct).
 *
 * Runs on Dispatchers.IO for its entire body: the OkHttp calls are
 * blocking I/O, and the BitmapFactory decode/scale step, while CPU-bound,
 * is short enough (2MB source cap, 240x240 output cap) that a second
 * context switch to Dispatchers.Default would only add dispatch overhead
 * without a measurable benefit.
 */
class WidgetSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val stateStore = WidgetStateStore(applicationContext)
    private val repository = WidgetRepository(BuildConfig.DOTHEART_BASE_URL)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val knownChecksum = stateStore.readChecksum()
            when (val stateResult = repository.fetchCurrentState(knownChecksum)) {
                is StateFetchResult.NotModified -> {
                    Log.i(TAG, "Widget state unchanged (checksum match); skipping render.")
                    stateStore.resetFailureCount()
                    Result.success()
                }
                is StateFetchResult.Updated -> {
                    handleUpdatedState(stateResult, knownChecksum)
                }
                is StateFetchResult.Failed -> {
                    handleFailure(stateResult.retryable, stateResult.reason)
                }
            }
        } catch (e: CancellationException) {
            // WorkManager cancelled this execution (constraints no longer
            // met, explicit cancellation, etc.) - must propagate, not be
            // treated as an application-level failure, so coroutine
            // cancellation stays cooperative.
            throw e
        } catch (e: Exception) {
            // A background sync worker must never crash the process. Any
            // other unexpected exception is treated as a retryable failure
            // rather than propagating.
            Log.e(TAG, "Unexpected error during widget sync.", e)
            handleFailure(retryable = true, reason = e.message ?: "Unexpected error")
        }
    }

    private suspend fun handleUpdatedState(
        result: StateFetchResult.Updated,
        knownChecksum: String?
    ): Result {
        val state = result.state
        var freshBitmap: Bitmap? = null

        if (state.checksum != knownChecksum) {
            when (val imageResult = repository.fetchImage(state.imageUrl, knownChecksum)) {
                is ImageFetchResult.Fetched -> {
                    freshBitmap = try {
                        PixelArtRenderer.decodeAndScale(
                            imageResult.bytes,
                            PixelArtRenderer.MAX_WIDGET_BITMAP_DIMENSION_PX,
                            PixelArtRenderer.MAX_WIDGET_BITMAP_DIMENSION_PX
                        )
                    } catch (e: PixelArtRenderer.DecodeException) {
                        Log.w(TAG, "Image decode/scale failed; keeping cached art.", e)
                        null
                    }
                }
                is ImageFetchResult.NotModified -> {
                    // The JSON checksum changed but the image byte-content
                    // didn't (a message-only edit on the backend) - reuse
                    // the bitmap already on disk, nothing to re-cache here.
                    freshBitmap = null
                }
                is ImageFetchResult.Failed -> {
                    if (imageResult.retryable) {
                        return handleFailure(true, imageResult.reason)
                    }
                    // Non-retryable image failure: still persist the new
                    // text state below so the note stays fresh even though
                    // the art didn't update this cycle.
                    Log.w(TAG, "Non-retryable image fetch failure: ${imageResult.reason}")
                }
            }
        }

        if (freshBitmap != null) {
            stateStore.saveBitmapToCache(freshBitmap)
        }
        stateStore.writeState(state.message, state.checksum, state.timestamp)
        stateStore.resetFailureCount()

        CoupleWidgetProvider.refreshAllWidgets(applicationContext, showError = false)
        return Result.success()
    }

    private fun handleFailure(retryable: Boolean, reason: String): Result {
        Log.w(TAG, "Widget sync failure (retryable=$retryable): $reason")
        CoupleWidgetProvider.refreshAllWidgets(applicationContext, showError = true)

        if (!retryable) {
            return Result.failure()
        }

        val failureCount = stateStore.incrementFailureCount()
        if (failureCount >= FAILURE_ALERT_THRESHOLD && !stateStore.hasSentFailureAlert()) {
            postFailureAlert()
            stateStore.markFailureAlertSent()
        }

        return if (runAttemptCount < MAX_WORKMANAGER_RETRIES) {
            Result.retry()
        } else {
            // Stop this cycle's retry chain here; the next periodic run
            // (or the next manual tap) starts a fresh attempt count.
            Result.failure()
        }
    }

    private fun postFailureAlert() {
        NotificationChannels.ensureAlertChannel(applicationContext)

        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; skipping failure alert.")
            return
        }

        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_sync_error_badge)
            .setContentTitle(applicationContext.getString(R.string.notification_stale_title))
            .setContentText(applicationContext.getString(R.string.notification_stale_body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(STALE_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "WidgetSyncWorker"
        private const val FAILURE_ALERT_THRESHOLD = 5
        private const val MAX_WORKMANAGER_RETRIES = 8
        private const val STALE_NOTIFICATION_ID = 1001
    }
}
