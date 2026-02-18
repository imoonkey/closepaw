package com.moonkey.androidagent.perception

import android.graphics.Rect

data class PerceptorBoundsDiagnostics(
    val rightOutOfBoundsCount: Int = 0,
    val bottomOutOfBoundsCount: Int = 0,
    val negativeCoordinateCount: Int = 0
)

/**
 * Collects suspicious bounds counters while traversing a11y nodes.
 *
 * Counters are best-effort diagnostics and intentionally cheap to compute.
 *
 * **Threading contract**: Instances are created and used within a single
 * [Perceptor.snapshot] call, which runs synchronously on a single thread.
 * Do not share instances across coroutines or threads.
 */
class PerceptorDiagnosticsCollector {
    private var rightOutOfBoundsCount: Int = 0
    private var bottomOutOfBoundsCount: Int = 0
    private var negativeCoordinateCount: Int = 0

    fun recordNodeBounds(rect: Rect, screenWidthPx: Int?, screenHeightPx: Int?) {
        if (rect.left < 0 || rect.top < 0 || rect.right < 0 || rect.bottom < 0) {
            negativeCoordinateCount += 1
        }

        val width = screenWidthPx?.takeIf { it > 0 }
        val height = screenHeightPx?.takeIf { it > 0 }
        if (width != null && rect.right > width) rightOutOfBoundsCount += 1
        if (height != null && rect.bottom > height) bottomOutOfBoundsCount += 1
    }

    fun snapshot(): PerceptorBoundsDiagnostics {
        return PerceptorBoundsDiagnostics(
            rightOutOfBoundsCount = rightOutOfBoundsCount,
            bottomOutOfBoundsCount = bottomOutOfBoundsCount,
            negativeCoordinateCount = negativeCoordinateCount
        )
    }
}
