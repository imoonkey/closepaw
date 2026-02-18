package com.moonkey.androidagent.util

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

private const val API_LEVEL_CHECKED_STATE = 36

/**
 * Keep compatibility across SDK levels while avoiding deprecated call sites.
 */
internal fun AccessibilityNodeInfo.isCheckedCompat(): Boolean {
    return if (Build.VERSION.SDK_INT >= API_LEVEL_CHECKED_STATE) {
        getChecked() == AccessibilityNodeInfo.CHECKED_STATE_TRUE
    } else {
        @Suppress("DEPRECATION")
        isChecked
    }
}

/**
 * Object pooling for AccessibilityNodeInfo was removed in API 33.
 * Keep this for older SDK behavior in one suppressed place.
 */
@Suppress("DEPRECATION")
internal fun AccessibilityNodeInfo.recycleCompat() {
    recycle()
}
