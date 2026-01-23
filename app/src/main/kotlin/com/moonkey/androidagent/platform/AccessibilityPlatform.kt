package com.moonkey.androidagent.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.ui.overlay.visualizer.ActionVisualizerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val visualizer: ActionVisualizerManager? = null
) : AndroidPlatform {
    
    companion object {
        private const val TAG = "AccessibilityPlatform"
        private const val DEFAULT_GESTURE_DURATION_MS = 100L
        private const val SWIPE_GESTURE_DURATION_MS = 300L
        /** Timeout for gesture callbacks - prevents indefinite hang if callback never fires */
        private const val GESTURE_TIMEOUT_MS = 5000L
    }
    
    override suspend fun captureScreen(): ScreenSnapshot {
        return withContext(Dispatchers.Main) {
            val root = service.rootInActiveWindow
            val snapshot = Perceptor.snapshot(root)
            Log.d(TAG, "Captured screen: ${snapshot.elements.size} elements, package: ${root?.packageName}")
            snapshot
        }
    }
    
    override suspend fun performAction(action: UIAction, snapshot: ScreenSnapshot?): ActionResult {
        return when (action) {
            is UIAction.Click -> performClick(action, snapshot)
            is UIAction.ClickAt -> performClickAt(action.x, action.y)
            is UIAction.LongClick -> performLongClick(action, snapshot)
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
     * Strategy 1: Try ACTION_CLICK on the accessibility node (works better with some apps like Notion)
     * Strategy 2: Fall back to gesture-based tap (works better with native Android apps)
     * 
     * Some cross-platform apps (Notion, Flutter apps, etc.) respond better to accessibility
     * node clicks than raw gestures, while native apps often work better with gestures.
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
        
        // Strategy 1: Try ACTION_CLICK on the accessibility node
        // This works better with cross-platform apps like Notion, Flutter, React Native
        return withContext(Dispatchers.Main) {
            val root = service.rootInActiveWindow
            if (root != null) {
                val clickableNode = findClickableNodeAtLocation(root, centerX, centerY)
                if (clickableNode != null) {
                    Log.d(TAG, "Trying ACTION_CLICK on node at ($centerX, $centerY)")
                    visualizer?.showClick(centerX.toFloat(), centerY.toFloat())
                    
                    val success = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    clickableNode.recycle()
                    
                    if (success) {
                        Log.d(TAG, "ACTION_CLICK succeeded")
                        return@withContext ActionResult.Success("Clicked element ${action.elementIndex}")
                    } else {
                        Log.d(TAG, "ACTION_CLICK failed, falling back to gesture")
                    }
                } else {
                    Log.d(TAG, "No clickable node found at location, using gesture")
                }
            }
            
            // Strategy 2: Fall back to gesture-based tap
            performClickAt(centerX, centerY)
        }
    }
    
    /**
     * Find a clickable node at the given coordinates.
     * Used for ACTION_CLICK approach which works better with some apps.
     */
    private fun findClickableNodeAtLocation(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect()
        
        fun search(node: AccessibilityNodeInfo, shouldRecycle: Boolean): AccessibilityNodeInfo? {
            node.getBoundsInScreen(bounds)
            
            if (!bounds.contains(x, y)) {
                if (shouldRecycle) node.recycle()
                return null
            }
            
            // Check children first (prefer deeper matches)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = search(child, shouldRecycle = true)
                if (found != null) {
                    if (shouldRecycle) node.recycle()
                    return found
                }
            }
            
            // If this node is clickable, return it
            if (node.isClickable) {
                return node
            }
            
            if (shouldRecycle) node.recycle()
            return null
        }
        
        return search(root, shouldRecycle = false)
    }
    
    private suspend fun performClickAt(x: Int, y: Int): ActionResult {
        return performTap(x.toFloat(), y.toFloat())
    }
    
    /**
     * Perform type action by re-querying the accessibility tree.
     * 
     * Re-queries the tree to find a fresh node at the target location,
     * avoiding stale AccessibilityNodeInfo references.
     */
    private suspend fun performType(action: UIAction.Type, snapshot: ScreenSnapshot?): ActionResult {
        Log.d(TAG, "performType: text='${action.text}', elementIndex=${action.elementIndex}")
        
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
                
                val node = findNodeAtLocation(freshRoot, centerX, centerY)
                Log.d(TAG, "performType: findNodeAtLocation returned ${if (node != null) "a node" else "null"}")
                node
            } else {
                // No element index - find currently focused node
                Log.d(TAG, "performType: No element_index, finding focused editable node")
                findFocusedEditableNode(root)
            }
            
            if (targetNode != null) {
                Log.d(TAG, "performType: Found target node, setting text")
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
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
    
    /**
     * Find a focused editable node in the tree.
     * Used when typing into the currently focused field.
     */
    private fun findFocusedEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // First, try to find the input-focused node
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            // Check if it supports text input
            if (focused.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
                return focused
            }
            focused.recycle()
        }
        
        // Fallback: DFS for any editable node that has focus
        return findEditableWithFocus(root)
    }
    
    /**
     * DFS to find an editable node with focus.
     */
    private fun findEditableWithFocus(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableWithFocus(child)
            if (result != null) {
                if (result !== child) child.recycle()
                return result
            }
            child.recycle()
        }
        
        return null
    }
    
    /**
     * Find a text-input capable node at the given screen coordinates.
     * Helper for performType() to re-query the accessibility tree.
     * 
     * - Properly recycles intermediate nodes during DFS traversal
     * - Checks for ACTION_SET_TEXT support, not just isEditable (supports WebView/custom widgets)
     */
    private fun findNodeAtLocation(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect()
        
        /**
         * Check if a node can accept text input.
         * P2 fix: Check for ACTION_SET_TEXT action support in addition to isEditable,
         * which handles custom widgets and WebView inputs that support text but don't set isEditable.
         */
        fun canAcceptTextInput(node: AccessibilityNodeInfo): Boolean {
            if (node.isEditable) return true
            // P2 fix: Also check if node supports ACTION_SET_TEXT action
            val actions = node.actionList
            return actions?.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } == true
        }
        
        /**
         * DFS to find deepest text-input node containing the point.
         * P1 fix: Properly recycles intermediate nodes obtained during traversal.
         * 
         * @param node Current node to search
         * @param shouldRecycle Whether this node should be recycled if not returned
         *                      (false for root which is system-owned)
         */
        fun search(node: AccessibilityNodeInfo, shouldRecycle: Boolean): AccessibilityNodeInfo? {
            node.getBoundsInScreen(bounds)
            
            if (!bounds.contains(x, y)) {
                if (shouldRecycle) {
                    node.recycle()
                }
                return null
            }
            
            // Check children first (prefer deeper matches)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = search(child, shouldRecycle = true)
                if (found != null) {
                    // Found a match in subtree
                    // Recycle current node if allowed (AccessibilityNodeInfo from getChild() are independent)
                    if (shouldRecycle) {
                        node.recycle()
                    }
                    return found
                }
                // Child subtree had no match - child was already recycled in search()
            }
            
            // If this node can accept text input and contains the point, return it
            // (don't recycle - caller will handle it)
            if (canAcceptTextInput(node)) {
                return node
            }
            
            // No match in this subtree - recycle this node if allowed
            if (shouldRecycle) {
                node.recycle()
            }
            return null
        }
        
        // Start search from root (don't recycle root - it's owned by the system)
        return search(root, shouldRecycle = false)
    }
    
    private suspend fun performSwipe(action: UIAction.Swipe): ActionResult {
        return performSwipeGesture(
            action.startX.toFloat(),
            action.startY.toFloat(),
            action.endX.toFloat(),
            action.endY.toFloat(),
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
     * Uses AccessibilityNodeInfo.ACTION_IME_ENTER (API 30+) for proper IME action,
     * with fallback to ACTION_CLICK for older devices.
     */
    private fun performEnterKey(): ActionResult {
        val root = service.rootInActiveWindow ?: return ActionResult.Failure("No active window")
        
        // Find the focused element
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            // Try ACTION_IME_ENTER first (API 30+) - properly triggers IME actions like Done/Go/Search
            val imeResult = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                focused.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
            } else {
                // Fallback for older devices: try ACTION_CLICK which sometimes works for submit
                focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            focused.recycle()
            
            return if (imeResult) {
                ActionResult.Success("Enter key pressed")
            } else {
                ActionResult.Failure("Failed to perform Enter action")
            }
        }
        
        return ActionResult.Failure("No focused element to send Enter to")
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

