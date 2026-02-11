package com.moonkey.androidagent.platform

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.moonkey.androidagent.platform.virtualdisplay.ShizukuClient
import com.moonkey.androidagent.platform.virtualdisplay.VirtualDisplayConfig
import com.moonkey.androidagent.platform.virtualdisplay.VirtualDisplayPlatform
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.trace.TraceRecorder
import com.moonkey.androidagent.ui.overlay.visualizer.ActionVisualizerManager

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
            traceRecorder: TraceRecorder
    ): AndroidPlatform {
        return when (config.platformMode) {
            PlatformMode.ACCESSIBILITY -> {
                Log.i(TAG, "Using AccessibilityPlatform (real screen)")
                AccessibilityPlatform(service, config, visualizer, traceRecorder)
            }
            PlatformMode.VIRTUAL_DISPLAY -> {
                createVirtualDisplayPlatform(config, service, traceRecorder)
                        ?: run {
                            Log.w(TAG, "Shizuku unavailable, falling back to AccessibilityPlatform")
                            AccessibilityPlatform(service, config, visualizer, traceRecorder)
                        }
            }
        }
    }

    private fun createVirtualDisplayPlatform(
            config: SessionConfig,
            service: AccessibilityService,
            traceRecorder: TraceRecorder
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
                traceRecorder = traceRecorder
        )
    }
}
