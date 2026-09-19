package com.dotheart.widget.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.dotheart.widget.BuildConfig
import com.dotheart.widget.CoupleWidgetProvider
import com.dotheart.widget.R
import com.dotheart.widget.net.ImageFetchResult
import com.dotheart.widget.net.PingResult
import com.dotheart.widget.net.StateFetchResult
import com.dotheart.widget.net.WidgetRepository
import com.dotheart.widget.render.PixelArtRenderer
import com.dotheart.widget.state.WidgetStateStore
import com.dotheart.widget.util.BatteryReader
import com.dotheart.widget.util.HapticFeedback
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
    private val lowBattery: Boolean
        get() = BatteryReader.isLow(applicationContext)
    private val repository = WidgetRepository(BuildConfig.DOTHEART_BASE_URL, BuildConfig.DOTHEART_PING_TOKEN)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Low-battery protection: below 15% the periodic poll does
            // nothing at all (no radio, no decode, no widget redraw) so it
            // can't hold the CPU awake. A manual tap is user-initiated and
            // still runs, but handleUpdatedState skips all graphics work.
            val userInitiated = inputData.getBoolean(WidgetSyncScheduler.INPUT_KEY_SEND_PING, false)
            if (!userInitiated && lowBattery) {
                Log.i(TAG, "Local battery below ${BatteryReader.LOW_BATTERY_PERCENT}%; skipping periodic sync.")
                return@withContext Result.success()
            }

            maybeSendPing()

            val knownChecksum = stateStore.readChecksum()
            when (val stateResult = repository.fetchCurrentState(knownChecksum, BuildConfig.DOTHEART_LOCAL_USER_ID)) {
                is StateFetchResult.NotModified -> {
                    Log.i(TAG, "Widget state unchanged (checksum match); skipping render.")
                    // NotModified only ever originates from a successful
                    // (2xx, or a literal 304) HTTP response - see
                    // WidgetRepository.fetchCurrentState - so the link is
                    // confirmed healthy even though nothing else changed.
                    stateStore.writeLinkStatus(200)
                    stateStore.resetFailureCount()
                    CoupleWidgetProvider.refreshAllWidgets(applicationContext)
                    Result.success()
                }
                is StateFetchResult.Updated -> {
                    handleUpdatedState(stateResult, knownChecksum)
                }
                is StateFetchResult.Failed -> {
                    handleFailure(stateResult.retryable, stateResult.reason, stateResult.httpCode)
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
            handleFailure(retryable = true, reason = e.message ?: "Unexpected error", httpCode = null)
        }
    }

    /**
     * Only sends a ping when this execution was enqueued for a user tap
     * (see WidgetSyncScheduler.enqueueManual/INPUT_KEY_SEND_PING) - the
     * periodic background poll never pings on its own, since a ping is a
     * presence signal ("I looked at this just now"), not a sync heartbeat.
     * Best-effort: a failed/unconfigured ping is logged and otherwise
     * ignored, never blocking the state fetch that follows - the user
     * still wants to see the latest art/note even if the ping itself
     * couldn't be delivered this time.
     */
    private suspend fun maybeSendPing() {
        val shouldPing = inputData.getBoolean(WidgetSyncScheduler.INPUT_KEY_SEND_PING, false)
        if (!shouldPing) return

        val userId = BuildConfig.DOTHEART_LOCAL_USER_ID
        when (val result = repository.sendPing(userId, BatteryReader.read(applicationContext))) {
            is PingResult.Success ->
                Log.i(TAG, "Ping sent: user_id=$userId timestamp=${result.timestamp}")
            is PingResult.Failed ->
                Log.w(TAG, "Ping not delivered (retryable=${result.retryable}): ${result.reason}")
        }
    }

    private suspend fun handleUpdatedState(
        result: StateFetchResult.Updated,
        knownChecksum: String?
    ): Result {
        val state = result.state
        var freshBitmap: Bitmap? = null

        // Read once so the decision and the checksum written below agree.
        val skipGraphics = lowBattery
        if (skipGraphics) {
            // Skip image fetch/decode/scale entirely and keep the cached art.
            // The stored checksum is left unchanged so the art is fetched on
            // the first sync after battery recovers; text/ping/peer-battery
            // state is still persisted below.
            Log.i(TAG, "Local battery low; bypassing graphic reprocessing.")
        } else if (state.checksum != knownChecksum) {
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
                        return handleFailure(true, imageResult.reason, httpCode = null)
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
        stateStore.writeState(
            message = state.message,
            checksum = if (skipGraphics) (knownChecksum ?: "") else state.checksum,
            artUpdatedAt = state.timestamp,
            lastPingA = state.lastPingA,
            lastPingB = state.lastPingB,
            peerBatteryLevel = state.peerBatteryLevel,
            peerIsCharging = state.peerIsCharging,
            notes = state.notes
        )
        stateStore.writeLinkStatus(200)
        stateStore.resetFailureCount()

        CoupleWidgetProvider.refreshAllWidgets(applicationContext)

        // Hook point: only a genuinely new payload buzzes - checksum differs
        // from the one cached before this sync. Skipped when the low-battery
        // path bypassed the art fetch (the art did not actually arrive).
        if (!skipGraphics && state.checksum != knownChecksum) {
            HapticFeedback.play(applicationContext, HapticFeedback.Signature.PAYLOAD_RECEIVED)
        }
        return Result.success()
    }

    private fun handleFailure(retryable: Boolean, reason: String, httpCode: Int?): Result {
        Log.w(TAG, "Widget sync failure (retryable=$retryable, httpCode=$httpCode): $reason")
        stateStore.writeLinkStatus(httpCode ?: WidgetStateStore.LINK_STATUS_UNREACHABLE)
        CoupleWidgetProvider.refreshAllWidgets(applicationContext)

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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notification_stale_title))
            .setContentText(
                applicationContext.getString(R.string.notification_stale_body, FAILURE_ALERT_THRESHOLD)
            )
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
