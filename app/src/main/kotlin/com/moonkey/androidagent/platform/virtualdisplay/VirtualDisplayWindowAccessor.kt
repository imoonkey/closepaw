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
                            "Window(id=${it.id}, display=${it.displayId}, title=${it.title}, type=${it.type})"
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
                        "Window(id=${it.id}, display=${it.displayId}, title=${it.title}, type=${it.type})"
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
     * Get the a11y root node from the virtual display's app window. Caller must recycle.
     *
     * We recycle all fetched AccessibilityWindowInfo instances immediately after extracting the root.
     */
    fun getRootOnDisplay(): AccessibilityNodeInfo? {
        val windows = getWindowsOnDisplay()
        return try {
            val appWindow =
                windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    ?: windows.firstOrNull()
            appWindow?.root
        } finally {
            windows.forEach { window ->
                runCatching { window.recycle() }
                    .onFailure { e -> Log.w(TAG, "Window recycle failed (ignored)", e) }
            }
        }
    }
}
