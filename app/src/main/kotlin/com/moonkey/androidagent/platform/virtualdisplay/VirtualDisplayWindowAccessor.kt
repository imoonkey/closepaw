package com.moonkey.androidagent.platform.virtualdisplay

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Window and root access helper for a virtual display.
 *
 * Centralizes display-filtered window queries from AccessibilityService.
 * Callers must recycle returned nodes; this class does not hold references.
 */
class VirtualDisplayWindowAccessor(
    private val service: AccessibilityService,
    private val displayIdProvider: () -> Int
) {
    companion object {
        private const val TAG = "VDWindowAccessor"
    }

    /** Get windows on the virtual display. Returns empty list if display invalid or unavailable. */
    fun getWindowsOnDisplay(): List<AccessibilityWindowInfo> {
        val displayId = displayIdProvider()
        if (displayId == Display.INVALID_DISPLAY) return emptyList()
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val allDisplayWindows = service.getWindowsOnAllDisplays()
                val displayWindows = allDisplayWindows.get(displayId)
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    if (displayWindows != null) {
                        val summary = displayWindows.joinToString(", ") {
                            "Window(id=${it.id}, display=${it.displayId}, title=${it.title}, type=${it.type}, layer=${it.layer})"
                        }
                        Log.d(TAG, "Windows on display $displayId: $summary")
                    } else {
                        val displayIds = (0 until allDisplayWindows.size()).map { allDisplayWindows.keyAt(it) }
                        Log.d(TAG, "No windows on display $displayId. Available displays: $displayIds")
                    }
                }
                displayWindows ?: emptyList()
            } else {
                val allWindows = service.windows
                if (Log.isLoggable(TAG, Log.DEBUG) && allWindows != null) {
                    val summary = allWindows.joinToString(", ") {
                        "Window(id=${it.id}, display=${it.displayId}, title=${it.title}, type=${it.type}, layer=${it.layer})"
                    }
                    Log.d(TAG, "All windows (legacy): $summary")
                }
                allWindows?.filter { it.displayId == displayId } ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get windows for display $displayId", e)
            emptyList()
        }
    }

    /**
     * Get the a11y root node from the virtual display's topmost app window. Caller must recycle.
     *
     * Picks the highest-layer TYPE_APPLICATION window, falling back to any highest-layer
     * non-overlay/non-IME window. This ensures node actions and getCurrentPackageName()
     * target the correct foreground window under dialogs and popups.
     */
    fun getRootOnDisplay(): AccessibilityNodeInfo? {
        val windows = getWindowsOnDisplay()
        return try {
            val eligible = windows
                .filter { w ->
                    w.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                        w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
                }
                .sortedByDescending { it.layer }

            val topWindow =
                eligible.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    ?: eligible.firstOrNull()
            topWindow?.root
        } finally {
            windows.forEach { window ->
                runCatching { window.recycle() }
                    .onFailure { e -> Log.w(TAG, "Window recycle failed (ignored)", e) }
            }
        }
    }

    /**
     * Get a11y roots from all relevant windows on the virtual display. Caller must recycle all.
     *
     * Excludes TYPE_ACCESSIBILITY_OVERLAY (our own overlay) and TYPE_INPUT_METHOD (keyboard).
     * Remaining windows are sorted by layer ascending for deterministic element ordering
     * across turns (background roots first, foreground roots last).
     */
    fun getRootsOnDisplay(): List<AccessibilityNodeInfo> {
        val windows = getWindowsOnDisplay()
        return try {
            windows
                .filter { w ->
                    w.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                        w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
                }
                .sortedBy { it.layer }
                .mapNotNull { it.root }
        } finally {
            windows.forEach { window ->
                runCatching { window.recycle() }
                    .onFailure { e -> Log.w(TAG, "Window recycle failed (ignored)", e) }
            }
        }
    }
}
