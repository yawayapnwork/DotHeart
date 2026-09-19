package com.dotheart.widget.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * Decodes raw image bytes fetched from the backend and produces a
 * widget-safe bitmap: nearest-neighbor scaled (no bilinear blur, so
 * hand-drawn pixel art keeps hard edges) and hard-capped in both pixel
 * dimensions and raw byte size so a single RemoteViews update never risks
 * android.os.TransactionTooLargeException on the ~1MB Binder transaction
 * budget shared by every widget instance updating from this process.
 */
object PixelArtRenderer {

    /**
     * Refuse to decode a source image reporting larger bounds than this.
     * The backend caps upload *file size* at 2MB (app/security.py) but that
     * alone does not bound *decoded pixel dimensions* for a pathologically
     * constructed PNG - this is a decompression-bomb guard independent of
     * that server-side check.
     */
    const val MAX_SOURCE_DIMENSION_PX = 4096

    /**
     * Per-instance widget bitmap dimension ceiling. 240 * 240 * 4 bytes
     * (ARGB_8888) = 230,400 bytes, comfortably under both MAX_OUTPUT_BYTES
     * below and the shared Binder transaction budget even when two widget
     * instances update independently in close succession.
     */
    const val MAX_WIDGET_BITMAP_DIMENSION_PX = 240

    /**
     * Hard output-size ceiling in bytes (250KB), enforced regardless of the
     * requested target dimensions, so a future change to
     * MAX_WIDGET_BITMAP_DIMENSION_PX (or an unusually shaped/large
     * AppWidgetOptions size request) can never silently exceed Android's
     * IPC budget - see clampToByteBudget below.
     */
    const val MAX_OUTPUT_BYTES = 250 * 1024

    class DecodeException(message: String) : Exception(message)

    /**
     * Decodes [sourceBytes] and produces a bitmap scaled to at most
     * [targetWidthPx] x [targetHeightPx], clamped to
     * [MAX_WIDGET_BITMAP_DIMENSION_PX] per side and [MAX_OUTPUT_BYTES]
     * total. Throws [DecodeException] on malformed data, oversized source
     * bounds, or (defensively) if the byte budget still cannot be met -
     * callers must catch this and fall back to the last cached bitmap
     * rather than let it propagate as a crash.
     */
    fun decodeAndScale(sourceBytes: ByteArray, targetWidthPx: Int, targetHeightPx: Int): Bitmap {
        require(sourceBytes.isNotEmpty()) { "Source image bytes must not be empty." }

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, boundsOptions)
        val sourceWidth = boundsOptions.outWidth
        val sourceHeight = boundsOptions.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw DecodeException("Unable to determine source image bounds; data may be corrupted.")
        }
        if (sourceWidth > MAX_SOURCE_DIMENSION_PX || sourceHeight > MAX_SOURCE_DIMENSION_PX) {
            throw DecodeException(
                "Source image dimensions ${sourceWidth}x$sourceHeight exceed the " +
                    "$MAX_SOURCE_DIMENSION_PX px sanity ceiling."
            )
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sourceBitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, decodeOptions)
            ?: throw DecodeException("BitmapFactory failed to decode image bytes despite valid bounds.")

        try {
            val clampedWidth = targetWidthPx.coerceIn(1, MAX_WIDGET_BITMAP_DIMENSION_PX)
            val clampedHeight = targetHeightPx.coerceIn(1, MAX_WIDGET_BITMAP_DIMENSION_PX)
            val (outputWidth, outputHeight) = clampToByteBudget(clampedWidth, clampedHeight)

            val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint().apply {
                // The entire nearest-neighbor mechanism: filterBitmap=false
                // disables bilinear interpolation, so each destination pixel
                // takes the exact color of its nearest source pixel instead
                // of a blended average - preserving hard pixel-art edges at
                // any (non-)integer scale factor.
                isFilterBitmap = false
                isAntiAlias = false
                isDither = false
            }
            val srcRect = Rect(0, 0, sourceBitmap.width, sourceBitmap.height)
            val dstRect = Rect(0, 0, outputWidth, outputHeight)
            canvas.drawBitmap(sourceBitmap, srcRect, dstRect, paint)

            val actualBytes = output.byteCount
            if (actualBytes > MAX_OUTPUT_BYTES) {
                output.recycle()
                throw DecodeException(
                    "Scaled bitmap size $actualBytes bytes exceeds the $MAX_OUTPUT_BYTES byte budget " +
                        "even after clamping; refusing to hand it to RemoteViews."
                )
            }
            return output
        } finally {
            sourceBitmap.recycle()
        }
    }

    /** 4x4 Bayer threshold matrix (values 0..15) for the 1-bit ordered dither. */
    private val BAYER_4X4 = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5
    )

    /** Dither cells with a Bayer value below this get darkened: 6/16 = 37.5% of pixels. */
    private const val DITHER_DENSITY = 6

    /**
     * Returns a new bitmap of identical dimensions with the "carrier lost"
     * decay look: contrast halved around mid-gray (c' = 128 + (c - 128) / 2)
     * and a 4x4 Bayer ordered dither that halves the brightness of a fixed
     * 37.5% of pixels in a regular lattice.
     *
     * Purely per-pixel: no resampling, so the nearest-neighbor edges produced
     * by [decodeAndScale] stay exactly as sharp as the input. Alpha is
     * preserved and fully transparent pixels are skipped. [source] is never
     * modified (it may be the immutable cached bitmap) and the caller owns
     * the returned bitmap. Cost is one pass over at most
     * MAX_WIDGET_BITMAP_DIMENSION_PX^2 pixels.
     */
    fun applyDecay(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            val rowOffset = y * width
            val bayerRow = (y and 3) * 4
            for (x in 0 until width) {
                val argb = pixels[rowOffset + x]
                val alpha = argb ushr 24
                if (alpha == 0) continue

                var r = 128 + (((argb shr 16) and 0xFF) - 128) / 2
                var g = 128 + (((argb shr 8) and 0xFF) - 128) / 2
                var b = 128 + ((argb and 0xFF) - 128) / 2

                if (BAYER_4X4[bayerRow + (x and 3)] < DITHER_DENSITY) {
                    r = r shr 1
                    g = g shr 1
                    b = b shr 1
                }
                pixels[rowOffset + x] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Proportionally shrinks (width, height) until the ARGB_8888 byte cost
     * fits under [MAX_OUTPUT_BYTES]. A pure function of the dimension
     * ceiling, independent of the [MAX_WIDGET_BITMAP_DIMENSION_PX] constant,
     * so the byte budget is enforced strictly rather than only by
     * coincidence of the current constant's value.
     */
    private fun clampToByteBudget(width: Int, height: Int, bytesPerPixel: Int = 4): Pair<Int, Int> {
        var w = width
        var h = height
        while (w.toLong() * h.toLong() * bytesPerPixel > MAX_OUTPUT_BYTES && w > 1 && h > 1) {
            w = (w * 0.9).toInt().coerceAtLeast(1)
            h = (h * 0.9).toInt().coerceAtLeast(1)
        }
        return w to h
    }
}
