package com.moonkey.androidagent.app

import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.ui.overlay.model.CapsuleContext
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode

internal enum class OverlayUserLocation {
    MAIN_APP,
    VD_VIEWER,
    OTHER_APP
}

internal fun isActivityWindowClass(className: String?): Boolean {
    val normalizedClassName = className?.substringBefore('$') ?: return false
    return normalizedClassName.endsWith("Activity") ||
        normalizedClassName.contains("Activity") ||
        normalizedClassName.contains("Launcher") ||
        normalizedClassName.contains(".app.") ||
        normalizedClassName.contains("Home")
}

internal fun resolveUserLocation(
    appPackage: String,
    packageName: String?,
    className: String?,
): OverlayUserLocation? {
    if (!isActivityWindowClass(className)) return null
    if (packageName == null) return null
    if (packageName != appPackage) return OverlayUserLocation.OTHER_APP

    val normalizedClassName = className?.substringBefore('$') ?: return null
    return if (normalizedClassName.contains("VirtualDisplayViewerActivity")) {
        OverlayUserLocation.VD_VIEWER
    } else {
        OverlayUserLocation.MAIN_APP
    }
}

internal fun resolveCapsuleContext(
    platformMode: PlatformMode,
    location: OverlayUserLocation,
): CapsuleContext =
    when (platformMode) {
        PlatformMode.ACCESSIBILITY -> {
            if (location == OverlayUserLocation.MAIN_APP) CapsuleContext.MAIN_APP
            else CapsuleContext.SCREEN_VIEWING
        }
        PlatformMode.VIRTUAL_DISPLAY -> {
            when (location) {
                OverlayUserLocation.MAIN_APP -> CapsuleContext.MAIN_APP
                OverlayUserLocation.VD_VIEWER -> CapsuleContext.SCREEN_VIEWING
                OverlayUserLocation.OTHER_APP -> CapsuleContext.BACKGROUND
            }
        }
    }

internal fun shouldOpenAppWhenIslandTapped(
    hasActiveTask: Boolean,
    mode: CapsuleMode,
): Boolean = !hasActiveTask && mode !is CapsuleMode.Done && mode !is CapsuleMode.Error
