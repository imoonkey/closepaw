package com.moonkey.androidagent.tool.action

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.ScreenImage
import com.moonkey.androidagent.model.ScreenSnapshot

/**
 * Detects UI changes by comparing snapshot fingerprints.
 *
 * Primary signal: FNV-1a hash of accessibility elements (fast, stable).
 * Fallback signal: 8×8 perceptual hash of screenshot (for empty a11y trees).
 *
 * Key design decision: Unverifiable is a DISTINCT outcome — not silently
 * treated as Changed. Callers decide how to handle it.
 */
object UiChangeDetector {

    enum class ChangeResult { Changed, Unchanged, Unverifiable }

    fun compare(pre: ScreenSnapshot?, post: ScreenSnapshot?): ChangeResult {
        if (pre == null || post == null) return ChangeResult.Unverifiable
        val preHash = fingerprint(pre)
        val postHash = fingerprint(post)
        return if (preHash != postHash) ChangeResult.Changed else ChangeResult.Unchanged
    }

    /** Detects scroll boundary: pre/post content identical after swipe. */
    fun detectScrollBoundary(pre: ScreenSnapshot?, post: ScreenSnapshot?): String? {
        if (pre == null || post == null) return null

        val preTexts = pre.elements
            .filter { it.text.isNotBlank() || it.description.isNotBlank() }
            .map { "${it.text}|${it.description}|${it.bounds}" }
            .sorted()

        val postTexts = post.elements
            .filter { it.text.isNotBlank() || it.description.isNotBlank() }
            .map { "${it.text}|${it.description}|${it.bounds}" }
            .sorted()

        return if (preTexts == postTexts && preTexts.isNotEmpty()) {
            "Screen content unchanged after swipe - may have reached scroll boundary"
        } else {
            null
        }
    }

    /**
     * Composite fingerprint: a11y elements when available, screenshot fallback when empty.
     *
     * This ensures change detection works even when the accessibility tree returns zero
     * elements (games, custom GL views, WebView content, etc.).
     */
    private fun fingerprint(snapshot: ScreenSnapshot): Long {
        var hash = FNV_OFFSET_BASIS
        hash = mix(hash, snapshot.keyboardVisible.hashCode().toLong())
        hash = mix(hash, snapshot.textEnriched.hashCode().toLong())
        val elements = snapshot.elements
        if (elements.isNotEmpty()) {
            return fingerprintFromElements(elements, hash)
        }
        // Fallback: perceptual hash of screenshot (for empty a11y trees)
        return snapshot.image?.let { mix(hash, fingerprintFromImage(it)) } ?: hash
    }

    /**
     * FNV-1a hash over sorted elements' stable fields.
     * Includes: resourceId, className, text, description, bounds, isFocused, isEnabled.
     */
    private fun fingerprintFromElements(elements: List<PerceptionElement>, seed: Long): Long {
        var hash = seed
        for (element in elements.sortedBy { it.index }) {
            hash = mix(hash, element.index.toLong())
            hash = mix(hash, element.resourceId.hashCode().toLong())
            hash = mix(hash, element.className.hashCode().toLong())
            hash = mix(hash, element.text.hashCode().toLong())
            hash = mix(hash, element.description.hashCode().toLong())
            hash = mix(hash, element.hintText.hashCode().toLong())
            hash = mix(hash, element.bounds.left.toLong())
            hash = mix(hash, element.bounds.top.toLong())
            hash = mix(hash, element.bounds.right.toLong())
            hash = mix(hash, element.bounds.bottom.toLong())
            hash = mix(hash, element.isFocused.hashCode().toLong())
            hash = mix(hash, element.isEnabled.hashCode().toLong())
            hash = mix(hash, element.isSelected.hashCode().toLong())
            hash = mix(hash, element.isChecked.hashCode().toLong())
            hash = mix(hash, element.isCheckable.hashCode().toLong())
            element.rangeInfo?.let { range ->
                hash = mix(hash, range.current.toBits().toLong())
                hash = mix(hash, range.min.toBits().toLong())
                hash = mix(hash, range.max.toBits().toLong())
            }
        }
        return hash
    }

    /**
     * 8×8 average perceptual hash of a screenshot.
     *
     * Algorithm:
     * 1. Decode JPEG → scale to 8×8 grayscale
     * 2. Compute average luminance across 64 pixels
     * 3. Threshold each pixel: above average = 1, below = 0
     * 4. Encode as 64-bit hash
     *
     * Properties:
     * - Sensitive to page navigation and layout changes
     * - Insensitive to clock/signal/battery icon changes (too small at 8×8)
     * - Fast: single BitmapFactory decode + 64 pixel comparisons
     */
    private fun fingerprintFromImage(image: ScreenImage): Long {
        val grayscale = decodeToGrayscale8x8(image.bytes) ?: return 0L
        val average = grayscale.sum() / grayscale.size.toDouble()
        var hash = 0L
        for (i in grayscale.indices) {
            if (grayscale[i] > average) {
                hash = hash or (1L shl i)
            }
        }
        return hash
    }

    /**
     * Decode JPEG bytes to an 8×8 grayscale array (64 luminance values).
     * Returns null if decoding fails.
     */
    private fun decodeToGrayscale8x8(jpegBytes: ByteArray): IntArray? {
        // Subsample during decode for efficiency: request nearest power-of-2 reduction
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
        val origWidth = options.outWidth
        val origHeight = options.outHeight
        if (origWidth <= 0 || origHeight <= 0) return null

        // Calculate subsample ratio: we only need 8×8
        val sampleSize = (minOf(origWidth, origHeight) / 8).coerceAtLeast(1)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit(sampleSize)
        }
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions)
            ?: return null

        return try {
            // Scale to exactly 8×8
            val scaled = Bitmap.createScaledBitmap(decoded, 8, 8, true)
            val grayscale = IntArray(64)
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val pixel = scaled.getPixel(x, y)
                    // ITU-R BT.601 luminance
                    grayscale[y * 8 + x] = (
                        0.299 * Color.red(pixel) +
                        0.587 * Color.green(pixel) +
                        0.114 * Color.blue(pixel)
                    ).toInt()
                }
            }
            if (scaled !== decoded) scaled.recycle()
            grayscale
        } finally {
            decoded.recycle()
        }
    }

    private const val FNV_OFFSET_BASIS = 1469598103934665603L

    private fun mix(current: Long, value: Long): Long {
        return (current xor value) * 1099511628211L // FNV prime
    }
}
