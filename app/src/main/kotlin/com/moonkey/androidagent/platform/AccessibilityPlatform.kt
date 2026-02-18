package com.moonkey.androidagent.platform

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
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
        private val traceRecorder: TraceRecorder = NoopTraceRecorder
) : AndroidPlatform {

    companion object {
        private const val TAG = "AccessibilityPlatform"
    }

    private val nodeActionPerformer = NodeActionPerformer(rootProvider = { service.rootInActiveWindow })

    private val gestureInjector = AccessibilityGestureInjector(service, visualizer)

    private val screenshotCapturer = AccessibilityScreenshotCapturer(service, config, traceRecorder)
    private val outOfBoundsActionTargetCount = AtomicInteger(0)

    override suspend fun captureScreen(): ScreenSnapshot {
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
                            screenshotPath = screenshotCapture?.tracePath,
                            captureQualityPath = a11yResult.captureQualityArtifactPath
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
                debug = debug
        )
    }

    private data class A11yCaptureResult(
            val elements: List<PerceptionElement>,
            val windowId: Int?,
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
        var root = withContext(Dispatchers.Main) { service.rootInActiveWindow }
        var attempts = 1
        while (root == null && attempts < 3) {
            delay(150)
            attempts += 1
            root = withContext(Dispatchers.Main) { service.rootInActiveWindow }
        }
        val capturedAt = System.currentTimeMillis()
        val windowId = root?.windowId

        if (root == null) {
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
                    rawTreeArtifactPath = null,
                    sanitizedTreeArtifactPath = null,
                    captureQualityArtifactPath = qualityPath
            )
        }

        val rawTreeArtifactPath = if (traceRecorder.enabled) {
            val dump = withContext(Dispatchers.Default) { A11yTreeDumper.dump(root) }
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
                root = root,
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
                rawTreeArtifactPath = rawTreeArtifactPath,
                sanitizedTreeArtifactPath = sanitizedTreeArtifactPath,
                captureQualityArtifactPath = qualityPath
        )
    }

    override suspend fun performAction(action: UIAction): ActionResult {
        return when (action) {
            is UIAction.ClickNodeAt -> {
                recordOutOfBoundsActionTarget("click_node", action.x, action.y)
                visualizer?.showClick(action.x.toFloat(), action.y.toFloat())
                nodeActionPerformer.performNodeClickAt(action.x, action.y)
            }
            is UIAction.TapAt -> {
                recordOutOfBoundsActionTarget("tap", action.x, action.y)
                gestureInjector.injectTap(action.x, action.y)
            }
            is UIAction.LongClickNodeAt -> {
                recordOutOfBoundsActionTarget("long_click_node", action.x, action.y)
                visualizer?.showClick(action.x.toFloat(), action.y.toFloat(), longPress = true)
                nodeActionPerformer.performNodeLongClickAt(action.x, action.y)
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
            service.rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get package name", e)
            null
        }
    }

    override fun getDisplayInfo(): DisplayInfo {
        val displayMetrics = service.resources.displayMetrics
        return DisplayInfo(
                widthPixels = displayMetrics.widthPixels,
                heightPixels = displayMetrics.heightPixels,
                density = displayMetrics.density
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

    private suspend fun recordOutOfBoundsActionTarget(actionName: String, x: Int, y: Int) {
        val display = withContext(Dispatchers.Main) { getDisplayInfo() }
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
