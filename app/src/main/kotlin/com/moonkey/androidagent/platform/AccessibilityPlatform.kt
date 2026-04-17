package com.moonkey.androidagent.platform

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.model.ScreenSnapshotDebug
import com.moonkey.androidagent.perception.PerceptorBoundsDiagnostics
import com.moonkey.androidagent.perception.PerceptorDiagnosticsCollector
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.trace.A11yTreeDumper
import com.moonkey.androidagent.trace.NoopTraceRecorder
import com.moonkey.androidagent.trace.TraceJson
import com.moonkey.androidagent.trace.TraceRecorder
import com.moonkey.androidagent.ui.overlay.visualizer.ActionVisualizerManager
import com.moonkey.androidagent.util.recycleCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * AccessibilityPlatform - Real implementation of AndroidPlatform using AccessibilityService.
 *
 * This wraps the existing Perceptor for screen capture and provides action execution via the
 * accessibility service APIs.
 *
 * Visualization Support: Optionally accepts an ActionVisualizerManager to display visual feedback
 * (ripples, trails) when performing gestures. This helps users see where and how the agent is
 * interacting with the screen.
 */
class AccessibilityPlatform(
        private val service: AccessibilityService,
        private val config: SessionConfig,
        private val visualizer: ActionVisualizerManager? = null,
        private val traceRecorder: TraceRecorder = NoopTraceRecorder,
        private val overlayTouchGate: OverlayTouchGate? = null,
        private val isPackageBlocked: (String?) -> Boolean = { false },
) : AndroidPlatform {

    companion object {
        private const val TAG = "AccessibilityPlatform"
    }

    private val nodeActionPerformer = NodeActionPerformer(rootProvider = { service.rootInActiveWindow })

    private val gestureInjector = AccessibilityGestureInjector(service, visualizer, overlayTouchGate)

    private val screenshotCapturer = AccessibilityScreenshotCapturer(service, config, traceRecorder)
    private val outOfBoundsActionTargetCount = AtomicInteger(0)

    override suspend fun captureScreen(): ScreenSnapshot {
        val currentPkg = getCurrentPackageName()

        // Null package = unknown foreground app — skip capture to avoid leaking artifacts
        if (currentPkg == null) {
            return ScreenSnapshot(
                timestamp = System.currentTimeMillis(),
                elements = emptyList(),
                image = null
            )
        }

        // Privacy gate: BLOCKED apps get masked snapshot — no artifacts created
        if (isPackageBlocked(currentPkg)) {
            return ScreenSnapshot(
                timestamp = System.currentTimeMillis(),
                elements = emptyList(),
                image = null
            )
        }

        val pc = config.perceptionConfig
        val timestamp = System.currentTimeMillis()

        // 1. Always capture accessibility tree (for change detection, node finding, trace)
        val a11yResult = captureAccessibilityTree()

        // 2. Screenshot capture (when config requires it OR trace is enabled for debugging)
        val shouldCaptureScreenshot = pc.capturesScreenshot || traceRecorder.enabled
        val windowId = a11yResult.windowId
        val screenshotCapture =
                screenshotCapturer.captureIfEnabled(windowId, enabled = shouldCaptureScreenshot)

        // 3. Only include screenshot in the snapshot if the perception config wants it
        val image = if (pc.capturesScreenshot) screenshotCapture?.image else null

        // 4. Build debug info
        val debug =
                if (traceRecorder.enabled) {
                    ScreenSnapshotDebug(
                            rawA11yTreePath = a11yResult.rawTreeArtifactPath,
                            sanitizedA11yTreePath = a11yResult.sanitizedTreeArtifactPath,
                            screenshotPath = screenshotCapture?.tracePath
                    )
                } else {
                    null
                }

        val elements = a11yResult.elements
        Log.d(
                TAG,
                "Captured screen [${pc::class.simpleName}]: ${elements.size} elements, screenshot=${image != null}"
        )

        return ScreenSnapshot(
                timestamp = timestamp,
                elements = elements,
                image = image,
                debug = debug,
                keyboardVisible = a11yResult.keyboardVisible
        )
    }

    private data class A11yCaptureResult(
            val elements: List<PerceptionElement>,
            val windowId: Int?,
            val keyboardVisible: Boolean,
            val rawTreeArtifactPath: String?,
            val sanitizedTreeArtifactPath: String?,
            val captureQualityArtifactPath: String?
    )

    private data class CaptureQuality(
            val attempts: Int,
            val elementCount: Int,
            val capturedAt: Long,
            val emptyReason: String?,
            val boundsDiagnostics: PerceptorBoundsDiagnostics,
            val outOfBoundsActionTargetCount: Int
    )

    private suspend fun captureAccessibilityTree(): A11yCaptureResult {
        val display = withContext(Dispatchers.Main) { getDisplayInfo() }
        var windowRoots = withContext(Dispatchers.Main) { collectRootsOnActiveDisplay() }
        var attempts = 1
        while (windowRoots.roots.isEmpty() && attempts < 3) {
            delay(150)
            attempts += 1
            windowRoots = withContext(Dispatchers.Main) { collectRootsOnActiveDisplay() }
        }
        val capturedAt = System.currentTimeMillis()
        val roots = windowRoots.roots
        val keyboardVisible = windowRoots.keyboardVisible
        // Use the topmost window (last in ascending-layer-sorted list) for screenshot targeting
        val windowId = roots.lastOrNull()?.windowId

        if (roots.isEmpty()) {
            val quality = CaptureQuality(
                    attempts = attempts,
                    elementCount = 0,
                    capturedAt = capturedAt,
                    emptyReason = "null_root",
                    boundsDiagnostics = PerceptorBoundsDiagnostics(),
                    outOfBoundsActionTargetCount = outOfBoundsActionTargetCount.get()
            )
            val qualityPath = storeCaptureQualityArtifact(quality)
            return A11yCaptureResult(
                    elements = emptyList(),
                    windowId = null,
                    keyboardVisible = keyboardVisible,
                    rawTreeArtifactPath = null,
                    sanitizedTreeArtifactPath = null,
                    captureQualityArtifactPath = qualityPath
            )
        }

        try {
            val rawTreeArtifactPath = if (traceRecorder.enabled) {
                val dump = withContext(Dispatchers.Default) { roots.map { A11yTreeDumper.dump(it) } }
                val json = TraceJson.instance.encodeToString(dump)
                traceRecorder.storeText(
                                kind = "raw_a11y_tree",
                                filenameHint = "raw_${System.currentTimeMillis()}.json",
                                content = json,
                                mimeType = "application/json"
                        )
                        ?.path
            } else null

            val diagnosticsCollector = PerceptorDiagnosticsCollector()
            val snapshot = Perceptor.snapshot(
                    roots = roots,
                    screenWidthPx = display.widthPixels,
                    screenHeightPx = display.heightPixels,
                    diagnosticsCollector = diagnosticsCollector
            )

            val sanitizedTreeArtifactPath = if (traceRecorder.enabled) {
                val json = Perceptor.toPromptJson(snapshot)
                traceRecorder.storeText(
                                kind = "sanitized_a11y_tree",
                                filenameHint = "sanitized_${snapshot.timestamp}.json",
                                content = json,
                                mimeType = "application/json"
                        )
                        ?.path
            } else null

            val quality = CaptureQuality(
                    attempts = attempts,
                    elementCount = snapshot.elements.size,
                    capturedAt = capturedAt,
                    emptyReason = if (snapshot.elements.isEmpty()) "zero_visible_elements" else null,
                    boundsDiagnostics = diagnosticsCollector.snapshot(),
                    outOfBoundsActionTargetCount = outOfBoundsActionTargetCount.get()
            )
            val qualityPath = storeCaptureQualityArtifact(quality)

            return A11yCaptureResult(
                    elements = snapshot.elements,
                    windowId = windowId,
                    keyboardVisible = keyboardVisible,
                    rawTreeArtifactPath = rawTreeArtifactPath,
                    sanitizedTreeArtifactPath = sanitizedTreeArtifactPath,
                    captureQualityArtifactPath = qualityPath
            )
        } finally {
            roots.forEach { it.recycleCompat() }
        }
    }

    private data class WindowRoots(
            val roots: List<AccessibilityNodeInfo>,
            val keyboardVisible: Boolean
    )

    /**
     * Collect a11y roots from all relevant windows on the active display.
     *
     * Excludes TYPE_ACCESSIBILITY_OVERLAY (our own overlay) and TYPE_INPUT_METHOD (keyboard).
     * Sorted by layer for deterministic element ordering across turns.
     * Falls back to rootInActiveWindow if window enumeration fails.
     *
     * Also detects keyboard visibility from the window type list (TYPE_INPUT_METHOD present),
     * since keyboard nodes are filtered from roots and won't be seen by Perceptor.
     */
    private fun collectRootsOnActiveDisplay(): WindowRoots {
        val windows = try {
            service.windows
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get windows, falling back to rootInActiveWindow", e)
            null
        }
        if (windows.isNullOrEmpty()) {
            return WindowRoots(
                roots = listOfNotNull(service.rootInActiveWindow),
                keyboardVisible = false
            )
        }
        return try {
            val keyboardVisible = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val roots = windows
                .filter { w ->
                    w.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                        w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
                }
                .sortedBy { it.layer }
                .mapNotNull { it.root }
            WindowRoots(
                roots = roots.ifEmpty { listOfNotNull(service.rootInActiveWindow) },
                keyboardVisible = keyboardVisible
            )
        } finally {
            windows.forEach { window ->
                runCatching { window.recycle() }
                    .onFailure { err -> Log.w(TAG, "Window recycle failed (ignored)", err) }
            }
        }
    }

    override suspend fun performAction(action: UIAction): ActionResult {
        return when (action) {
            is UIAction.ClickNodeAt -> {
                recordOutOfBoundsActionTarget("click_node", action.x, action.y)
                visualizer?.showClick(action.x.toFloat(), action.y.toFloat())
                nodeActionPerformer.performNodeClickAt(action.x, action.y, action.semanticHint)
            }
            is UIAction.TapAt -> {
                recordOutOfBoundsActionTarget("tap", action.x, action.y)
                gestureInjector.injectTap(action.x, action.y)
            }
            is UIAction.LongClickNodeAt -> {
                recordOutOfBoundsActionTarget("long_click_node", action.x, action.y)
                visualizer?.showClick(action.x.toFloat(), action.y.toFloat(), longPress = true)
                nodeActionPerformer.performNodeLongClickAt(action.x, action.y, action.semanticHint)
            }
            is UIAction.LongPressAt -> {
                recordOutOfBoundsActionTarget("long_press", action.x, action.y)
                gestureInjector.injectLongPress(
                        x = action.x.toFloat(),
                        y = action.y.toFloat(),
                        durationMs = action.durationMs
                )
            }
            is UIAction.SetTextOnNodeAt -> {
                recordOutOfBoundsActionTarget("set_text_node", action.x, action.y)
                nodeActionPerformer.performSetTextOnNodeAt(
                        x = action.x,
                        y = action.y,
                        text = action.text,
                        clear = action.clear
                )
            }
            is UIAction.SetTextOnFocused ->
                    nodeActionPerformer.performSetTextOnFocused(
                            text = action.text,
                            clear = action.clear
                    )
            is UIAction.ScrollNodeAt -> {
                recordOutOfBoundsActionTarget("scroll_node", action.x, action.y)
                nodeActionPerformer.performScrollAt(action.x, action.y, action.direction)
            }
            is UIAction.Swipe -> performSwipe(action)
            is UIAction.SystemButton ->
                    when (action.button) {
                        SystemButtonType.ENTER -> nodeActionPerformer.performEnterKey()
                        else -> gestureInjector.injectSystemButton(action.button)
                    }
            is UIAction.Wait -> performWait(action)
        }
    }

    override fun hasRequiredPermissions(): Boolean {
        // TODO: Consider checking Settings.canDrawOverlays() for overlay permission.
        //       However, overlay permission should be verified at MainActivity level,
        //       not here. Current check is sufficient for AccessibilityPlatform's scope.
        return service.serviceInfo != null
    }

    override fun getCurrentPackageName(): String? {
        return try {
            val windows = service.windows
            if (windows.isNullOrEmpty()) {
                // Fallback: no window enumeration available
                val root = service.rootInActiveWindow ?: return null
                return try { root.packageName?.toString() } finally { root.recycleCompat() }
            }
            try {
                // Pick the topmost non-overlay/non-IME TYPE_APPLICATION window
                val eligible = windows
                    .filter { w ->
                        w.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                            w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    }
                    .sortedByDescending { it.layer }
                val topWindow =
                    eligible.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                        ?: eligible.firstOrNull()
                val root = topWindow?.root ?: return null
                try { root.packageName?.toString() } finally { root.recycleCompat() }
            } finally {
                windows.forEach { w ->
                    runCatching { w.recycle() }
                        .onFailure { err -> Log.w(TAG, "Window recycle failed (ignored)", err) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get package name", e)
            null
        }
    }

    /**
     * Return the REAL display dimensions (full screen including nav bar and cutout).
     *
     * Accessibility nodes report bounds in full-screen coordinates (getBoundsInScreen),
     * so the display info used for visibility filtering must match. Using
     * Resources.displayMetrics.heightPixels gives only the app content area, which
     * causes elements near the bottom (e.g., bottom toolbar buttons) to be incorrectly
     * filtered as off-screen.
     */
    override fun getDisplayInfo(): DisplayInfo {
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = service.resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bounds = wm.maximumWindowMetrics.bounds
            return DisplayInfo(
                    widthPixels = bounds.width(),
                    heightPixels = bounds.height(),
                    density = density
            )
        }
        @Suppress("DEPRECATION")
        val realMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(realMetrics)
        return DisplayInfo(
                widthPixels = realMetrics.widthPixels,
                heightPixels = realMetrics.heightPixels,
                density = density
        )
    }

    // ===== Action Helpers =====
    private suspend fun performSwipe(action: UIAction.Swipe): ActionResult {
        val display = withContext(Dispatchers.Main) { getDisplayInfo() }
        val maxX = (display.widthPixels - 1).coerceAtLeast(0)
        val maxY = (display.heightPixels - 1).coerceAtLeast(0)

        val startX = action.startX.coerceIn(0, maxX)
        val startY = action.startY.coerceIn(0, maxY)
        val endX = action.endX.coerceIn(0, maxX)
        val endY = action.endY.coerceIn(0, maxY)

        if (startX != action.startX ||
                        startY != action.startY ||
                        endX != action.endX ||
                        endY != action.endY
        ) {
            outOfBoundsActionTargetCount.incrementAndGet()
            Log.w(TAG, "Swipe coordinates clamped to screen bounds")
        }

        Log.d(
                TAG,
                "Swipe: (${startX},${startY}) -> (${endX},${endY}), duration=${action.durationMs}ms"
        )
        return gestureInjector.injectSwipe(
                startX = startX.toFloat(),
                startY = startY.toFloat(),
                endX = endX.toFloat(),
                endY = endY.toFloat(),
                durationMs = action.durationMs
        )
    }

    private suspend fun performWait(action: UIAction.Wait): ActionResult {
        delay(action.durationMs)
        return ActionResult.Success("Waited ${action.durationMs}ms")
    }

    private fun recordOutOfBoundsActionTarget(actionName: String, x: Int, y: Int) {
        // Quick check: non-negative coordinates are very likely in-bounds; skip display query.
        // getDisplayInfo() reads displayMetrics which is thread-safe on Android.
        val display = getDisplayInfo()
        val isOutOfBounds = x < 0 || y < 0 || x >= display.widthPixels || y >= display.heightPixels
        if (!isOutOfBounds) return

        val count = outOfBoundsActionTargetCount.incrementAndGet()
        Log.w(TAG, "Out-of-bounds target for $actionName at ($x,$y)")
        if (!traceRecorder.enabled) return

        val payload = JSONObject().apply {
            put("action", actionName)
            put("x", x)
            put("y", y)
            put("display_width", display.widthPixels)
            put("display_height", display.heightPixels)
            put("count_so_far", count)
        }
        traceRecorder.storeText(
                kind = "action_bounds_outlier",
                filenameHint = "action_bounds_${System.currentTimeMillis()}.json",
                content = payload.toString(2),
                mimeType = "application/json"
        )
    }

    private fun storeCaptureQualityArtifact(quality: CaptureQuality): String? {
        if (!traceRecorder.enabled) return null
        val payload = JSONObject().apply {
            put("attempts", quality.attempts)
            put("element_count", quality.elementCount)
            put("captured_at", quality.capturedAt)
            put("empty_reason", quality.emptyReason)
            put(
                    "bounds_diagnostics",
                    JSONObject().apply {
                        put("right_out_of_bounds_count", quality.boundsDiagnostics.rightOutOfBoundsCount)
                        put("bottom_out_of_bounds_count", quality.boundsDiagnostics.bottomOutOfBoundsCount)
                        put("negative_coordinate_count", quality.boundsDiagnostics.negativeCoordinateCount)
                    }
            )
            put("out_of_bounds_action_target_count", quality.outOfBoundsActionTargetCount)
        }
        return traceRecorder.storeText(
                        kind = "capture_quality",
                        filenameHint = "capture_quality_${quality.capturedAt}.json",
                        content = payload.toString(2),
                        mimeType = "application/json"
                )
                ?.path
    }

    // ===== App Management Implementation =====

    /**
     * Get list of installed launchable apps.
     *
     * Uses PackageManager to query apps that have a launcher activity.
     */
    override suspend fun getInstalledApps(): List<AppInfo> {
        return withContext(Dispatchers.IO) {
            try {
                AppManager.getInstalledApps(service.packageManager)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get installed apps", e)
                emptyList()
            }
        }
    }

    /**
     * Launch an app by package name.
     *
     * Uses PackageManager.getLaunchIntentForPackage to get the launch intent.
     */
    override suspend fun launchApp(packageName: String): ActionResult {
        return withContext(Dispatchers.Main) {
            try {
                val pm = service.packageManager
                val launchIntent = pm.getLaunchIntentForPackage(packageName)

                if (launchIntent == null) {
                    return@withContext ActionResult.Failure(
                            "App not found or not launchable: $packageName"
                    )
                }

                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(launchIntent)

                Log.d(TAG, "Launched app: $packageName")
                ActionResult.Success("Launched $packageName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch app: $packageName", e)
                ActionResult.Failure("Failed to launch $packageName: ${e.message}")
            }
        }
    }
}
