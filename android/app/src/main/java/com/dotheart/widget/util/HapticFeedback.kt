package com.dotheart.widget.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Understated haptic signatures for widget events. Fire-and-forget: the
 * vibration is handed to the system service and the call returns
 * immediately, so it costs one binder call and holds no wake lock, timer or
 * thread of its own.
 *
 * Every failure path is silent by design - a missing VIBRATE permission,
 * a device with no vibrator, or the user having vibration/touch feedback
 * turned off must never affect the widget. (The system itself drops the
 * effect when the user has disabled vibration; there is nothing to detect
 * or work around on our side.)
 */
object HapticFeedback {

    private const val TAG = "HapticFeedback"

    enum class Signature(
        /** Alternating off/on durations in ms, starting with an initial off delay. */
        internal val timings: LongArray,
        /** Per-segment amplitude (0 = off) for devices with amplitude control. */
        internal val amplitudes: IntArray
    ) {
        /** Single sharp 15ms click: the tap on the widget was received. */
        PING_ACK(
            timings = longArrayOf(0, 15),
            amplitudes = intArrayOf(0, 255)
        ),

        /** Double-pulse click, 10ms on / 30ms off / 15ms on: new artwork arrived. */
        PAYLOAD_RECEIVED(
            timings = longArrayOf(0, 10, 30, 15),
            amplitudes = intArrayOf(0, 255, 0, 255)
        )
    }

    /** Plays [signature]; does nothing if there is no usable vibrator or permission. */
    fun play(context: Context, signature: Signature) {
        try {
            val vibrator = vibratorFor(context) ?: return
            if (!vibrator.hasVibrator()) return

            val effect = if (vibrator.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(signature.timings, signature.amplitudes, NO_REPEAT)
            } else {
                // Timing-only fallback: the on segments still buzz at the
                // motor's fixed strength.
                VibrationEffect.createWaveform(signature.timings, NO_REPEAT)
            }
            vibrator.vibrate(effect)
        } catch (e: SecurityException) {
            // android.permission.VIBRATE missing or revoked.
            Log.w(TAG, "Vibration not permitted; skipping haptic.", e)
        } catch (e: RuntimeException) {
            // Vibrator service died / unavailable mid-call: haptics are
            // strictly cosmetic, never let them propagate.
            Log.w(TAG, "Vibration failed; skipping haptic.", e)
        }
    }

    private const val NO_REPEAT = -1

    private fun vibratorFor(context: Context): Vibrator? {
        val appContext = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
