package com.moonkey.androidagent.platform

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.model.ScreenSnapshotDebug
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.trace.A11yTreeDumper
import com.moonkey.androidagent.trace.NoopTraceRecorder
import com.moonkey.androidagent.trace.TraceJson
import com.moonkey.androidagent.trace.TraceRecorder
import com.moonkey.androidagent.ui.overlay.visualizer.ActionVisualizerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

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
                debug = debug
        )
    }

    private data class A11yCaptureResult(
            val elements: List<PerceptionElement>,
            val windowId: Int?,
            val rawTreeArtifactPath: String?,
            val sanitizedTreeArtifactPath: String?
    )

    private suspend fun captureAccessibilityTree(): A11yCaptureResult {
        val root = withContext(Dispatchers.Main) { service.rootInActiveWindow }
        val windowId = root?.windowId

        val rawTreeArtifactPath =
                if (traceRecorder.enabled) {
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

        val snapshot = Perceptor.snapshot(root)

        val sanitizedTreeArtifactPath =
                if (traceRecorder.enabled) {
                    val json = Perceptor.toPromptJson(snapshot)
                    traceRecorder.storeText(
                                    kind = "sanitized_a11y_tree",
                                    filenameHint = "sanitized_${snapshot.timestamp}.json",
                                    content = json,
                                    mimeType = "application/json"
                            )
                            ?.path
                } else null

        return A11yCaptureResult(
                elements = snapshot.elements,
                windowId = windowId,
                rawTreeArtifactPath = rawTreeArtifactPath,
                sanitizedTreeArtifactPath = sanitizedTreeArtifactPath
        )
    }

    override suspend fun performAction(action: UIAction): ActionResult {
        return when (action) {
            is UIAction.ClickNodeAt -> {
                visualizer?.showClick(action.x.toFloat(), action.y.toFloat())
                nodeActionPerformer.performNodeClickAt(action.x, action.y)
            }
            is UIAction.TapAt -> gestureInjector.injectTap(action.x, action.y)
            is UIAction.LongClickNodeAt -> {
                visualizer?.showClick(action.x.toFloat(), action.y.toFloat(), longPress = true)
                nodeActionPerformer.performNodeLongClickAt(action.x, action.y)
            }
            is UIAction.LongPressAt -> {
                gestureInjector.injectLongPress(
                        x = action.x.toFloat(),
                        y = action.y.toFloat(),
                        durationMs = action.durationMs
                )
            }
            is UIAction.SetTextOnNodeAt ->
                    nodeActionPerformer.performSetTextOnNodeAt(
                            x = action.x,
                            y = action.y,
                            text = action.text,
                            clear = action.clear
                    )
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
        val display = getDisplayInfo()
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
        kotlinx.coroutines.delay(action.durationMs)
        return ActionResult.Success("Waited ${action.durationMs}ms")
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
