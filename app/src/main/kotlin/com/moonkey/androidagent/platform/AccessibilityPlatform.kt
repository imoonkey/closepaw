package com.moonkey.androidagent.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.moonkey.androidagent.data.perception.Perceptor
import com.moonkey.androidagent.domain.models.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * AccessibilityPlatform - Real implementation of AndroidPlatform using AccessibilityService.
 * 
 * This wraps the existing Perceptor for screen capture and provides
 * action execution via the accessibility service APIs.
 */
class AccessibilityPlatform(
    private val service: AccessibilityService
) : AndroidPlatform {
    
    companion object {
        private const val TAG = "AccessibilityPlatform"
        private const val DEFAULT_GESTURE_DURATION_MS = 100L
        private const val SWIPE_GESTURE_DURATION_MS = 300L
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
            is UIAction.Type -> performType(action, snapshot)
            is UIAction.Scroll -> performScroll(action)
            is UIAction.Swipe -> performSwipe(action)
            is UIAction.SystemButton -> performSystemButton(action)
            is UIAction.Wait -> performWait(action)
        }
    }
    
    override fun hasRequiredPermissions(): Boolean {
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
     * Perform click action using stored bounds (H5 fix).
     * 
     * Uses gesture-based tap which is more reliable than ACTION_CLICK
     * and doesn't require storing AccessibilityNodeInfo references.
     */
    private suspend fun performClick(action: UIAction.Click, snapshot: ScreenSnapshot?): ActionResult {
        if (snapshot == null) {
            return ActionResult.Failure("Snapshot required for element-based click")
        }
        
        val element = snapshot.elements.getOrNull(action.elementIndex)
            ?: return ActionResult.ElementNotFound(action.elementIndex)
        
        // H5 fix: Use stored center coordinates for gesture-based click
        // This is actually more reliable than ACTION_CLICK on many devices
        val centerX = element.center[0]
        val centerY = element.center[1]
        
        Log.d(TAG, "Clicking element ${action.elementIndex} at ($centerX, $centerY)")
        return performClickAt(centerX, centerY)
    }
    
    private suspend fun performClickAt(x: Int, y: Int): ActionResult {
        return performTap(x.toFloat(), y.toFloat())
    }
    
    /**
     * Perform type action by re-querying the accessibility tree (H5 fix).
     * 
     * Re-queries the tree to find a fresh node at the target location,
     * avoiding stale AccessibilityNodeInfo references.
     */
    private suspend fun performType(action: UIAction.Type, snapshot: ScreenSnapshot?): ActionResult {
        if (snapshot == null) {
            return ActionResult.Failure("Snapshot required for type action")
        }
        
        val element = snapshot.elements.getOrNull(action.elementIndex)
            ?: return ActionResult.ElementNotFound(action.elementIndex)
        
        // H5 fix: Re-query the accessibility tree for a fresh node
        // This avoids stale node issues from stored references
        val centerX = element.center[0]
        val centerY = element.center[1]
        
        return withContext(Dispatchers.Main) {
            // First, tap to focus the element
            val tapResult = performClickAt(centerX, centerY)
            if (tapResult is ActionResult.Failure) {
                return@withContext tapResult
            }
            
            // Brief delay for focus to take effect
            kotlinx.coroutines.delay(100)
            
            // Re-query the tree to find the focused/target node
            val root = service.rootInActiveWindow
            if (root == null) {
                return@withContext ActionResult.Failure("Cannot access screen for text input")
            }
            
            // Find node at the target location (by bounds overlap)
            val targetNode = findNodeAtLocation(root, centerX, centerY)
            
            if (targetNode != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                }
                val result = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                
                // PR fix: Recycle the node after use (don't recycle root - owned by service)
                if (targetNode !== root) {
                    targetNode.recycle()
                }
                
                if (result) {
                    ActionResult.Success("Text entered: ${action.text}")
                } else {
                    ActionResult.Failure("Failed to set text on element (ACTION_SET_TEXT failed)")
                }
            } else {
                ActionResult.Failure("Could not find text-input element at location ($centerX, $centerY)")
            }
        }
    }
    
    /**
     * Find a text-input capable node at the given screen coordinates.
     * Helper for performType() to re-query the accessibility tree.
     * 
     * PR fixes applied:
     * - P1: Properly recycles intermediate nodes during DFS traversal
     * - P2: Checks for ACTION_SET_TEXT support, not just isEditable (supports WebView/custom widgets)
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
    
    private suspend fun performScroll(action: UIAction.Scroll): ActionResult {
        val display = getDisplayInfo()
        val centerX = display.widthPixels / 2f
        
        // Use safe regions to avoid triggering system gestures
        // Status bar is typically top ~100px, nav bar is bottom ~150px
        // Avoid top 0.35 (status bar pull-down zone) and bottom 0.15 (nav gestures)
        val (startY, endY) = when (action.direction) {
            ScrollDirection.DOWN -> {
                // Swipe up to scroll down: start from lower middle, end at upper middle
                display.heightPixels * 0.75f to display.heightPixels * 0.35f
            }
            ScrollDirection.UP -> {
                // Swipe down to scroll up: start from upper middle (but not too high!), end at lower
                display.heightPixels * 0.45f to display.heightPixels * 0.85f
            }
            ScrollDirection.LEFT, ScrollDirection.RIGHT -> {
                // Horizontal scroll - use center Y
                display.heightPixels * 0.5f to display.heightPixels * 0.5f
            }
        }
        
        val (startX, endX) = when (action.direction) {
            ScrollDirection.LEFT -> {
                // Swipe right to left to scroll left
                display.widthPixels * 0.8f to display.widthPixels * 0.2f
            }
            ScrollDirection.RIGHT -> {
                // Swipe left to right to scroll right
                display.widthPixels * 0.2f to display.widthPixels * 0.8f
            }
            else -> centerX to centerX
        }
        
        Log.d(TAG, "Performing scroll ${action.direction}: ($startX, $startY) -> ($endX, $endY)")
        return performSwipeGesture(startX, startY, endX, endY, SWIPE_GESTURE_DURATION_MS)
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
            val globalAction = when (action.button) {
                SystemButtonType.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                SystemButtonType.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                SystemButtonType.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
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
    
    private suspend fun performWait(action: UIAction.Wait): ActionResult {
        kotlinx.coroutines.delay(action.durationMs)
        return ActionResult.Success("Waited ${action.durationMs}ms")
    }
    
    // ===== Gesture Helpers =====
    
    private suspend fun performTap(x: Float, y: Float): ActionResult {
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
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        
        return dispatchGesture(gesture)
    }
    
    private suspend fun dispatchGesture(gesture: GestureDescription): ActionResult {
        return suspendCancellableCoroutine { continuation ->
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
    }
}

