package com.moonkey.androidagent.app

import android.view.Display
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.ui.overlay.model.CapsuleContext
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode

internal enum class OverlayUserLocation {
    MAIN_APP,
    VD_VIEWER,
    OTHER_APP
}

internal enum class ShowPreference {
    CAPSULE,
    ISLAND
}

internal data class OverlayVisibilityDecision(
    val showCapsule: Boolean,
    val showIsland: Boolean,
    val showGlow: Boolean,
    val normalizedShowPreference: ShowPreference,
)

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
    displayId: Int? = null,
): OverlayUserLocation? {
    if (!isActivityWindowClass(className)) return null
    if (packageName == null) return null
    // Ignore non-default display activity windows to prevent VD app windows from
    // flipping location while user stays in MainActivity on the real screen.
    if (displayId != null && displayId != Display.DEFAULT_DISPLAY) return null
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

internal fun deriveOverlayVisibility(
    platformMode: PlatformMode,
    location: OverlayUserLocation,
    mode: CapsuleMode,
    hasActiveTask: Boolean,
    showPreference: ShowPreference,
): OverlayVisibilityDecision {
    val isActive = hasActiveTask || mode is CapsuleMode.Done || mode is CapsuleMode.Error
    val normalizedShowPreference = when {
        location == OverlayUserLocation.MAIN_APP || !isActive -> showPreference
        mode is CapsuleMode.WaitingForInput ||
            mode is CapsuleMode.WaitingForAction ||
            mode is CapsuleMode.WaitingForApproval ||
            mode is CapsuleMode.Error -> ShowPreference.CAPSULE
        else -> showPreference
    }

    return when (platformMode) {
        PlatformMode.ACCESSIBILITY -> {
            val isOverlayContext = location != OverlayUserLocation.MAIN_APP && isActive
            val showCapsule = isOverlayContext && normalizedShowPreference == ShowPreference.CAPSULE
            OverlayVisibilityDecision(
                showCapsule = showCapsule,
                showIsland = isOverlayContext && normalizedShowPreference == ShowPreference.ISLAND,
                showGlow = location != OverlayUserLocation.MAIN_APP && isActive,
                normalizedShowPreference = normalizedShowPreference,
            )
        }
        PlatformMode.VIRTUAL_DISPLAY -> {
            val needsUserAttention = mode is CapsuleMode.WaitingForApproval ||
                mode is CapsuleMode.WaitingForInput ||
                mode is CapsuleMode.WaitingForAction ||
                mode is CapsuleMode.Error

            if (!isActive) {
                OverlayVisibilityDecision(
                    showCapsule = false,
                    showIsland = false,
                    showGlow = false,
                    normalizedShowPreference = normalizedShowPreference,
                )
            } else if (location == OverlayUserLocation.MAIN_APP && !needsUserAttention) {
                // In VD mode, main app UI handles normal interaction — hide overlay
                OverlayVisibilityDecision(
                    showCapsule = false,
                    showIsland = false,
                    showGlow = false,
                    normalizedShowPreference = normalizedShowPreference,
                )
            } else {
                // Show overlay in VD_VIEWER, OTHER_APP, or MAIN_APP when user attention needed.
                // For needsUserAttention modes, always force capsule regardless of preference —
                // in MAIN_APP the default preference is ISLAND, which won't show without this.
                val forceCapsule = needsUserAttention
                OverlayVisibilityDecision(
                    showCapsule = forceCapsule || normalizedShowPreference == ShowPreference.CAPSULE,
                    showIsland = !forceCapsule && normalizedShowPreference == ShowPreference.ISLAND,
                    showGlow = (location == OverlayUserLocation.VD_VIEWER || needsUserAttention) && hasActiveTask,
                    normalizedShowPreference = normalizedShowPreference,
                )
            }
        }
    }
}

/**
 * Whether user touch interaction with the underlying screen should be blocked.
 *
 * - A11y OTHER_APP: block while agent owns control (non-terminal, non-takeover modes).
 * - VD_VIEWER: block until takeover is confirmed.
 */
internal fun shouldLockUserInteraction(
    platformMode: PlatformMode,
    location: OverlayUserLocation,
    mode: CapsuleMode,
): Boolean {
    val userOwnsControl = mode is CapsuleMode.Takeover
    val nonInteractiveState = mode is CapsuleMode.Hidden ||
        mode is CapsuleMode.Done ||
        mode is CapsuleMode.Error
    if (userOwnsControl || nonInteractiveState) return false

    return when (platformMode) {
        PlatformMode.ACCESSIBILITY -> location == OverlayUserLocation.OTHER_APP
        PlatformMode.VIRTUAL_DISPLAY -> location == OverlayUserLocation.VD_VIEWER
    }
}

/**
 * Whether the capsule overlay window should be touchable (i.e. NOT have FLAG_NOT_TOUCHABLE).
 *
 * Only [CapsuleMode.Hidden] passes touches through — all other modes need user interaction
 * (buttons, input fields, or the full-screen touch shield during Running).
 */
internal fun shouldCapsuleOverlayBeTouchable(mode: CapsuleMode): Boolean =
    mode !is CapsuleMode.Hidden
