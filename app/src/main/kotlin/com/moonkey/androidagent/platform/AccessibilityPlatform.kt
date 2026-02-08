package com.moonkey.androidagent.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.model.ScreenImage
import com.moonkey.androidagent.model.ScreenImageSource
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.model.ScreenSnapshotDebug
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.trace.A11yTreeDumper
import com.moonkey.androidagent.trace.NoopTraceRecorder
import com.moonkey.androidagent.trace.TraceJson
import com.moonkey.androidagent.trace.TraceRecorder
import com.moonkey.androidagent.ui.overlay.visualizer.ActionVisualizerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * AccessibilityPlatform - Real implementation of AndroidPlatform using AccessibilityService.
 * 
 * This wraps the existing Perceptor for screen capture and provides
 * action execution via the accessibility service APIs.
 * 
 * Visualization Support:
 * Optionally accepts an ActionVisualizerManager to display visual feedback
 * (ripples, trails) when performing gestures. This helps users see where
 * and how the agent is interacting with the screen.
 */
class AccessibilityPlatform(
    private val service: AccessibilityService,
    private val config: SessionConfig,
    private val visualizer: ActionVisualizerManager? = null,
    private val traceRecorder: TraceRecorder = NoopTraceRecorder
) : AndroidPlatform {
    
    companion object {
        private const val TAG = "AccessibilityPlatform"
        private const val DEFAULT_GESTURE_DURATION_MS = 100L
        private const val SWIPE_GESTURE_DURATION_MS = 300L
        /** Timeout for gesture callbacks - prevents indefinite hang if callback never fires */
        private const val GESTURE_TIMEOUT_MS = 5000L
    }
    
    override suspend fun captureScreen(): ScreenSnapshot {
        val root = withContext(Dispatchers.Main) { service.rootInActiveWindow }
        val windowId = root?.windowId

        val rawTreeArtifact =
            if (traceRecorder.enabled) {
                val dump = withContext(Dispatchers.Default) { A11yTreeDumper.dump(root) }
                val json = TraceJson.instance.encodeToString(dump)
                traceRecorder.storeText(
                    kind = "raw_a11y_tree",
                    filenameHint = "raw_${System.currentTimeMillis()}.json",
                    content = json,
                    mimeType = "application/json"
                )
            } else {
                null
            }

        val snapshot = Perceptor.snapshot(root)

        val sanitizedTreeArtifact =
            if (traceRecorder.enabled) {
                val json = Perceptor.toPromptJson(snapshot)
                traceRecorder.storeText(
                    kind = "sanitized_a11y_tree",
                    filenameHint = "sanitized_${snapshot.timestamp}.json",
                    content = json,
                    mimeType = "application/json"
                )
            } else {
                null
            }

        val shouldCaptureScreenshot = config.enableScreenshotInput || traceRecorder.enabled
        val screenshotCapture = captureScreenshotIfEnabled(windowId, enabled = shouldCaptureScreenshot)
        val image = if (config.enableScreenshotInput) screenshotCapture?.image else null
        Log.d(TAG, "Captured screen: ${snapshot.elements.size} elements, package: ${root?.packageName}")
        val debug =
            if (traceRecorder.enabled) {
                ScreenSnapshotDebug(
                    rawA11yTreePath = rawTreeArtifact?.path,
                    sanitizedA11yTreePath = sanitizedTreeArtifact?.path,
                    screenshotPath = screenshotCapture?.tracePath
                )
            } else {
                null
            }

        return snapshot.copy(image = image, debug = debug)
    }

    private data class ScreenshotCapture(
        val image: ScreenImage,
        val tracePath: String?
    )

    private suspend fun captureScreenshotIfEnabled(windowId: Int?, enabled: Boolean): ScreenshotCapture? {
        if (!enabled) {
            return null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }

        val result = takeScreenshotResult(windowId) ?: return null
        return compressScreenshot(result)
    }

    private suspend fun takeScreenshotResult(
        windowId: Int?
    ): AccessibilityService.ScreenshotResult? {
        val windowResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            windowId?.let { takeWindowScreenshot(it) }
        } else {
            null
        }
        return windowResult ?: takeDisplayScreenshot()
    }

    private suspend fun takeDisplayScreenshot(): AccessibilityService.ScreenshotResult? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            if (continuation.isActive) {
                                continuation.resume(screenshot)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "takeScreenshot failed: ${formatScreenshotError(errorCode)}")
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun takeWindowScreenshot(
        windowId: Int
    ): AccessibilityService.ScreenshotResult? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                service.takeScreenshotOfWindow(windowId, service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            if (continuation.isActive) {
                                continuation.resume(screenshot)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "takeScreenshotOfWindow failed: ${formatScreenshotError(errorCode)}")
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }
                )
            }
        }
    }

    private suspend fun compressScreenshot(
        screenshot: AccessibilityService.ScreenshotResult
    ): ScreenshotCapture? = withContext(Dispatchers.Default) {
        val hardwareBuffer = screenshot.hardwareBuffer
        try {
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                ?: return@withContext null

            val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            hardwareBitmap.recycle()
            if (softwareBitmap == null) {
                return@withContext null
            }

            val scaledBitmap = scaleBitmapIfNeeded(softwareBitmap, config.screenshotMaxDimension)
            val width = scaledBitmap.width
            val height = scaledBitmap.height

            val jpegBytes = compressJpeg(scaledBitmap, config.screenshotJpegQuality)

            if (scaledBitmap !== softwareBitmap) {
                softwareBitmap.recycle()
            }
            scaledBitmap.recycle()

            jpegBytes?.let {
                if (config.debugMode) {
                    persistDebugScreenshot(it, width, height)
                }
                val tracePath =
                    if (traceRecorder.enabled) {
                        traceRecorder.storeBytes(
                            kind = "screenshot",
                            filenameHint = "screenshot_${System.currentTimeMillis()}_${width}x${height}.jpg",
                            bytes = it,
                            mimeType = "image/jpeg"
                        )?.path
                    } else {
                        null
                    }
                ScreenImage(
                    width = width,
                    height = height,
                    mimeType = "image/jpeg",
                    bytes = it,
                    source = ScreenImageSource.ACCESSIBILITY_SCREENSHOT
                ).let { image -> ScreenshotCapture(image = image, tracePath = tracePath) }
            }
        } finally {
            hardwareBuffer.close()
        }
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val safeMax = maxDimension.coerceAtLeast(1)
        val currentMax = maxOf(bitmap.width, bitmap.height)
        if (currentMax <= safeMax) {
            return bitmap
        }

        val scale = safeMax.toFloat() / currentMax.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        val safeQuality = quality.coerceIn(1, 100)
        val output = ByteArrayOutputStream()
        val success = bitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, output)
        return if (success) output.toByteArray() else null
    }

    private fun persistDebugScreenshot(bytes: ByteArray, width: Int, height: Int) {
        val dir = service.getExternalFilesDir("debug-output") ?: return
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create debug-output directory")
            return
        }
        val filename = "llm_screenshot_${System.currentTimeMillis()}_${width}x${height}.jpg"
        val file = File(dir, filename)
        try {
            file.outputStream().use { it.write(bytes) }
            Log.d(TAG, "Saved LLM screenshot: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save LLM screenshot: ${e.message}")
        }
    }

    private fun formatScreenshotError(errorCode: Int): String {
        return when (errorCode) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "interval too short"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal error"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "secure window"
            else -> "error code $errorCode"
        }
    }
    
    override suspend fun performAction(action: UIAction, snapshot: ScreenSnapshot?): ActionResult {
        return when (action) {
            is UIAction.Click -> performClick(action, snapshot)
            is UIAction.ClickAt -> performClickAt(action.x, action.y)
            is UIAction.ClickNodeAt -> performNodeClickAt(action.x, action.y)
            is UIAction.TapAt -> performTapAt(action.x, action.y)
            is UIAction.LongClick -> performLongClick(action, snapshot)
            is UIAction.LongClickAt -> performLongClickAt(action)
            is UIAction.Type -> performType(action, snapshot)
            is UIAction.Swipe -> performSwipe(action)
            is UIAction.SystemButton -> performSystemButton(action)
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
    
    // ===== Action Implementations =====
    
    /**
     * Perform click action using multiple strategies.
     * 
     * Strategy 1: Try ACTION_CLICK on the accessibility node first (most reliable for complex apps)
     * Strategy 2: Fall back to gesture-based tap if ACTION_CLICK fails
     * 
     * ACTION_CLICK is preferred because:
     * - It's more reliable for apps with complex ViewGroups (YouTube, Notion, Flutter apps)
     * - The accessibility framework routes the click to the correct target
     * - Gesture taps can report "success" even when the UI doesn't respond
     * 
     * Gesture tap is used as fallback for apps that don't handle ACTION_CLICK well.
     */
    private suspend fun performClick(action: UIAction.Click, snapshot: ScreenSnapshot?): ActionResult {
        if (snapshot == null) {
            return ActionResult.Failure("Snapshot required for element-based click")
        }
        
        val element = snapshot.elements.getOrNull(action.elementIndex)
            ?: return ActionResult.ElementNotFound(action.elementIndex)
        
        val centerX = element.center.x
        val centerY = element.center.y
        
        Log.d(TAG, "Clicking element ${action.elementIndex} at ($centerX, $centerY)")

        // Strategy 1: Try ACTION_CLICK on the accessibility node first
        // This is more reliable for apps with complex ViewGroups like YouTube
        val actionClickResult = performNodeClickAt(centerX, centerY)
        
        if (actionClickResult is ActionResult.Success) {
            return actionClickResult
        }

        // Strategy 2: Fall back to gesture-based tap
        Log.d(TAG, "ACTION_CLICK failed or unavailable, falling back to gesture tap at ($centerX, $centerY)")
        val tapResult = performTapAt(centerX, centerY)
        if (tapResult is ActionResult.Success) {
            return ActionResult.Success(
                if (element.isEditable) {
                    "Tapped editable element ${action.elementIndex}"
                } else {
                    "Tapped element ${action.elementIndex}"
                }
            )
        }
        
        return tapResult
    }
    
    private suspend fun performClickAt(x: Int, y: Int): ActionResult {
        return performTapAt(x, y)
    }

    private suspend fun performNodeClickAt(x: Int, y: Int): ActionResult {
        return withContext(Dispatchers.Main) {
            val root = service.rootInActiveWindow
            if (root == null) {
                Log.d(TAG, "Root is null, cannot try ACTION_CLICK")
                return@withContext ActionResult.Failure("Cannot access active window for ACTION_CLICK")
            }

            val clickableNode = AccessibilityNodeFinder.findClickableNodeAtLocation(root, x, y)
            if (clickableNode == null) {
                Log.d(TAG, "No clickable node found at ($x, $y)")
                return@withContext ActionResult.Failure("No clickable node found at ($x,$y)")
            }

            try {
                Log.d(TAG, "Trying ACTION_CLICK on node at ($x, $y)")
                visualizer?.showClick(x.toFloat(), y.toFloat())
                val success = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) {
                    Log.d(TAG, "ACTION_CLICK succeeded at ($x, $y)")
                    ActionResult.Success("ACTION_CLICK succeeded at ($x,$y)")
                } else {
                    Log.d(TAG, "ACTION_CLICK returned false at ($x, $y)")
                    ActionResult.Failure("ACTION_CLICK returned false at ($x,$y)")
                }
            } finally {
                clickableNode.recycle()
            }
        }
    }

    private suspend fun performTapAt(x: Int, y: Int): ActionResult {
        return performTap(x.toFloat(), y.toFloat())
    }
    
    /**
     * Perform type action by re-querying the accessibility tree.
     * 
     * Re-queries the tree to find a fresh node at the target location,
     * avoiding stale AccessibilityNodeInfo references.
     */
    private suspend fun performType(action: UIAction.Type, snapshot: ScreenSnapshot?): ActionResult {
        Log.d(TAG, "performType: text='${action.text}', elementIndex=${action.elementIndex}, clear=${action.clear}")
        
        return withContext(Dispatchers.Main) {
            val root = service.rootInActiveWindow
            if (root == null) {
                Log.e(TAG, "performType: Cannot access screen (root is null)")
                return@withContext ActionResult.Failure("Cannot access screen for text input")
            }
            
            // Determine target location - either from element or use currently focused
            val targetNode: AccessibilityNodeInfo? = if (action.elementIndex != null) {
                // Element index provided - tap to focus first
                if (snapshot == null) {
                    Log.e(TAG, "performType: Snapshot is null but element_index provided")
                    return@withContext ActionResult.Failure("Snapshot required when element_index is provided")
                }
                
                val element = snapshot.elements.getOrNull(action.elementIndex)
                if (element == null) {
                    Log.e(TAG, "performType: Element ${action.elementIndex} not found (snapshot has ${snapshot.elements.size} elements)")
                    return@withContext ActionResult.ElementNotFound(action.elementIndex)
                }
                
                val centerX = element.center.x
                val centerY = element.center.y
                Log.d(TAG, "performType: Tapping element ${action.elementIndex} at ($centerX, $centerY) to focus")
                
                // Tap to focus the element
                val tapResult = performClickAt(centerX, centerY)
                if (tapResult is ActionResult.Failure) {
                    Log.e(TAG, "performType: Tap to focus failed: ${tapResult.reason}")
                    return@withContext tapResult
                }
                
                // Brief delay for focus to take effect
                kotlinx.coroutines.delay(100)
                
                // Re-query tree and find node at location
                val freshRoot = service.rootInActiveWindow
                if (freshRoot == null) {
                    Log.e(TAG, "performType: Lost screen access after tap")
                    return@withContext ActionResult.Failure("Lost screen access after tap")
                }
                
                val node = AccessibilityNodeFinder.findNodeAtLocation(freshRoot, centerX, centerY)
                Log.d(TAG, "performType: findNodeAtLocation returned ${if (node != null) "a node" else "null"}")
                node
            } else {
                // No element index - find currently focused node
                Log.d(TAG, "performType: No element_index, finding focused editable node")
                AccessibilityNodeFinder.findFocusedEditableNode(root)
            }
            
            if (targetNode != null) {
                Log.d(TAG, "performType: Found target node, setting text (clear=${action.clear})")
                if (action.clear) {
                    val clearArgs = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            ""
                        )
                    }
                    val clearResult = targetNode.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        clearArgs
                    )
                    Log.d(TAG, "performType: Clear result=$clearResult")
                }
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        action.text
                    )
                }
                val result = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                
                // Recycle the node after use (don't recycle root - owned by service)
                if (targetNode !== root) {
                    targetNode.recycle()
                }
                
                if (result) {
                    Log.d(TAG, "performType: Text entered successfully")
                    ActionResult.Success("Text entered: ${action.text}")
                } else {
                    Log.e(TAG, "performType: ACTION_SET_TEXT failed")
                    ActionResult.Failure("Failed to set text on element (ACTION_SET_TEXT failed)")
                }
            } else {
                val msg = if (action.elementIndex != null) {
                    "Could not find text-input element at specified location"
                } else {
                    "No focused editable element found. Specify element_index to focus a field first."
                }
                Log.e(TAG, "performType: $msg")
                ActionResult.Failure(msg)
            }
        }
    }
    
    private suspend fun performSwipe(action: UIAction.Swipe): ActionResult {
        val display = getDisplayInfo()
        val maxX = (display.widthPixels - 1).coerceAtLeast(0)
        val maxY = (display.heightPixels - 1).coerceAtLeast(0)
        val startX = action.startX.coerceIn(0, maxX)
        val startY = action.startY.coerceIn(0, maxY)
        val endX = action.endX.coerceIn(0, maxX)
        val endY = action.endY.coerceIn(0, maxY)

        if (startX != action.startX || startY != action.startY || endX != action.endX || endY != action.endY) {
            Log.w(TAG, "Swipe coordinates clamped to screen bounds")
        }

        Log.d(TAG, "Swipe: (${startX},${startY}) -> (${endX},${endY}), duration=${action.durationMs}ms")
        return performSwipeGesture(
            startX.toFloat(),
            startY.toFloat(),
            endX.toFloat(),
            endY.toFloat(),
            action.durationMs
        )
    }
    
    private suspend fun performSystemButton(action: UIAction.SystemButton): ActionResult {
        return withContext(Dispatchers.Main) {
            // ENTER is a key event, not a global action - handle separately
            if (action.button == SystemButtonType.ENTER) {
                return@withContext performEnterKey()
            }
            
            val globalAction = when (action.button) {
                SystemButtonType.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                SystemButtonType.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                SystemButtonType.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
                SystemButtonType.ENTER -> return@withContext ActionResult.Failure("ENTER handled above")
            }
            
            Log.d(TAG, "Performing global action: ${action.button} -> $globalAction")
            val result = service.performGlobalAction(globalAction)
            Log.d(TAG, "Global action result: $result")
            
            if (result) {
                ActionResult.Success("System button: ${action.button}")
            } else {
                ActionResult.Failure("Failed to perform system action: ${action.button}")
            }
        }
    }
    
    /**
     * Perform ENTER key press on the currently focused element.
     * 
     * Uses AccessibilityAction.ACTION_IME_ENTER (API 30+) for proper IME action,
     * with fallback to ACTION_CLICK for older devices.
     */
    @Suppress("DEPRECATION")
    private fun performEnterKey(): ActionResult {
        val root = service.rootInActiveWindow ?: return ActionResult.Failure("No active window")

        // Prefer a focused editable node. root.findFocus(FOCUS_INPUT) can point to
        // a focused WebView container and report success without submitting input.
        val focusedEditable =
            AccessibilityNodeFinder.findFocusedEditableNode(root)
                ?: return ActionResult.Failure(
                    "No focused editable element to send Enter to"
                )

        try {
            val imeResult =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    focusedEditable.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    )
                } else {
                    false
                }

            if (imeResult) {
                return ActionResult.Success("Enter key pressed")
            }

            val clickFallbackResult = focusedEditable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return if (clickFallbackResult) {
                ActionResult.Success("Enter key pressed (click fallback)")
            } else {
                ActionResult.Failure("Failed to perform Enter action on focused editable element")
            }
        } finally {
            focusedEditable.recycle()
        }
    }
    
    private suspend fun performWait(action: UIAction.Wait): ActionResult {
        kotlinx.coroutines.delay(action.durationMs)
        return ActionResult.Success("Waited ${action.durationMs}ms")
    }
    
    // ===== Gesture Helpers =====
    
    private suspend fun performTap(x: Float, y: Float): ActionResult {
        // Show visualization BEFORE the action
        visualizer?.showClick(x, y)
        
        val path = Path().apply {
            moveTo(x, y)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, DEFAULT_GESTURE_DURATION_MS))
            .build()
        
        return dispatchGesture(gesture)
    }
    
    private suspend fun performSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): ActionResult {
        // Show visualization BEFORE the action
        visualizer?.showSwipe(startX, startY, endX, endY, durationMs)
        
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        
        return dispatchGesture(gesture)
    }
    
    /**
     * Dispatch a gesture with timeout protection.
     * 
     * Some devices may not invoke the gesture callback in edge cases,
     * which would cause the coroutine to hang forever. Adding timeout
     * ensures the agent can recover from such situations.
     */
    private suspend fun dispatchGesture(gesture: GestureDescription): ActionResult {
        return withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resume(ActionResult.Success("Gesture completed"))
                        }
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resume(ActionResult.Cancelled("Gesture cancelled"))
                        }
                    }
                }
                
                val dispatched = service.dispatchGesture(gesture, callback, null)
                if (!dispatched) {
                    continuation.resume(ActionResult.Failure("Failed to dispatch gesture"))
                }
            }
        } ?: ActionResult.Failure("Gesture timed out after ${GESTURE_TIMEOUT_MS}ms")
    }
    
    // ===== Long Click Implementation =====
    
    /**
     * Perform long press using gesture with extended duration.
     */
    private suspend fun performLongClick(action: UIAction.LongClick, snapshot: ScreenSnapshot?): ActionResult {
        if (snapshot == null) {
            return ActionResult.Failure("Snapshot required for element-based long click")
        }
        
        val element = snapshot.elements.getOrNull(action.elementIndex)
            ?: return ActionResult.ElementNotFound(action.elementIndex)
        
        val x = element.center.x.toFloat()
        val y = element.center.y.toFloat()
        
        Log.d(TAG, "Long click element ${action.elementIndex} at ($x, $y) for ${action.durationMs}ms")
        
        // Show visualization (longPress ripple effect)
        visualizer?.showClick(x, y, longPress = true)
        
        // Long press is a stationary gesture held for duration
        val path = Path().apply {
            moveTo(x, y)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, action.durationMs))
            .build()
        
        return dispatchGesture(gesture)
    }

    private suspend fun performLongClickAt(action: UIAction.LongClickAt): ActionResult {
        val x = action.x.toFloat()
        val y = action.y.toFloat()

        Log.d(TAG, "Long click at ($x, $y) for ${action.durationMs}ms")

        visualizer?.showClick(x, y, longPress = true)

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, action.durationMs))
            .build()

        return dispatchGesture(gesture)
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
                val pm = service.packageManager
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                
                val resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                
                resolveInfos.mapNotNull { resolveInfo ->
                    val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                    val packageName = activityInfo.packageName
                    val label = resolveInfo.loadLabel(pm)?.toString() ?: packageName
                    val isSystem = (activityInfo.applicationInfo.flags and 
                        android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    
                    AppInfo(
                        packageName = packageName,
                        label = label,
                        isSystemApp = isSystem
                    )
                }.distinctBy { it.packageName }  // Remove duplicates
                    .sortedBy { it.label.lowercase() }
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
                ActionResult.Failure("Failed to launch $packageName: ${e.message}", e)
            }
        }
    }
}
