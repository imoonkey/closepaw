package ai.closepaw.platform

import android.accessibilityservice.AccessibilityService
import android.util.Log
import ai.closepaw.platform.virtualdisplay.ShizukuClient
import ai.closepaw.platform.virtualdisplay.VirtualDisplayConfig
import ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.trace.TraceRecorder
import ai.closepaw.ui.overlay.visualizer.ActionVisualizerManager

/**
 * PlatformFactory — Single decision point for platform selection.
 *
 * Decides whether to use AccessibilityPlatform (real screen) or VirtualDisplayPlatform (Shizuku
 * virtual display) based on:
 * 1. SessionConfig.platformMode (user preference)
 * 2. Shizuku availability (runtime check)
 *
 * If the user requests VIRTUAL_DISPLAY but Shizuku is unavailable, falls back to ACCESSIBILITY and
 * logs a warning.
 */
object PlatformFactory {

    private const val TAG = "PlatformFactory"

    /**
     * Create the appropriate AndroidPlatform for this session.
     *
     * @param config Session configuration (includes platformMode)
     * @param service AccessibilityService for both platform types
     * @param visualizer Optional visualizer for AccessibilityPlatform
     * @param traceRecorder Trace recorder for AccessibilityPlatform
     * @return The selected AndroidPlatform (not yet started)
     */
    fun create(
            config: SessionConfig,
            service: AccessibilityService,
            visualizer: ActionVisualizerManager? = null,
            traceRecorder: TraceRecorder,
            overlayTouchGate: OverlayTouchGate? = null,
            isPackageBlocked: (String?) -> Boolean = { false },
    ): AndroidPlatform {
        return when (config.platformMode) {
            PlatformMode.ACCESSIBILITY -> {
                Log.i(TAG, "Using AccessibilityPlatform (real screen)")
                AccessibilityPlatform(service, config, visualizer, traceRecorder, overlayTouchGate, isPackageBlocked)
            }
            PlatformMode.VIRTUAL_DISPLAY -> {
                createVirtualDisplayPlatform(config, service, visualizer, traceRecorder, isPackageBlocked)
                        ?: run {
                            Log.w(TAG, "Shizuku unavailable, falling back to AccessibilityPlatform")
                            AccessibilityPlatform(service, config, visualizer, traceRecorder, overlayTouchGate, isPackageBlocked)
                        }
            }
        }
    }

    private fun createVirtualDisplayPlatform(
            config: SessionConfig,
            service: AccessibilityService,
            visualizer: ActionVisualizerManager?,
            traceRecorder: TraceRecorder,
            isPackageBlocked: (String?) -> Boolean
    ): VirtualDisplayPlatform? {
        val shizuku = ShizukuClient()

        if (!shizuku.isAvailable()) {
            Log.w(TAG, "Shizuku is not available (not installed or not running)")
            return null
        }

        if (!shizuku.hasPermission()) {
            Log.w(TAG, "Shizuku permission not granted")
            return null
        }

        val displayConfig = VirtualDisplayConfig.fromPhysicalDisplay(service)
        Log.i(
                TAG,
                "Using VirtualDisplayPlatform: ${displayConfig.width}x${displayConfig.height}@${displayConfig.densityDpi}dpi"
        )

        return VirtualDisplayPlatform(
                service = service,
                shizuku = shizuku,
                config = displayConfig,
                sessionConfig = config,
                traceRecorder = traceRecorder,
                visualizer = visualizer,
                isPackageBlocked = isPackageBlocked
        )
    }
}
