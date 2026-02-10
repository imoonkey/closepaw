package com.moonkey.androidagent.platform

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * BitmapUtils — Shared bitmap processing for platform implementations.
 *
 * Extracted from AccessibilityPlatform to avoid duplication with
 * VirtualDisplayPlatform. Pure functions, no state.
 */
object BitmapUtils {

    /**
     * Scale a bitmap so its largest dimension does not exceed [maxDimension].
     * Returns the original bitmap unmodified if it's already small enough.
     */
    fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val safeMax = maxDimension.coerceAtLeast(1)
        val currentMax = maxOf(bitmap.width, bitmap.height)
        if (currentMax <= safeMax) {
            return bitmap
        }
        val scale = safeMax.toFloat() / currentMax.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /**
     * Compress a bitmap to JPEG bytes.
     *
     * @param quality JPEG quality 1-100
     * @return compressed bytes, or null if compression failed
     */
    fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        val safeQuality = quality.coerceIn(1, 100)
        val output = ByteArrayOutputStream()
        val success = bitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, output)
        return if (success) output.toByteArray() else null
    }
}
