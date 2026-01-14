package com.moonkey.androidagent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.moonkey.androidagent.domain.models.AgentAction
import com.moonkey.androidagent.domain.models.ScreenSnapshot

class ActionDispatcher(private val service: AccessibilityService) {

    fun perform(action: AgentAction, snapshot: ScreenSnapshot) {
        if (action !is AgentAction.AtomicAction) return

        when (action.type) {
            "click" -> {
                val node = getNode(action.elementId, snapshot) ?: return
                clickNode(node)
            }
            "type" -> {
                val node = getNode(action.elementId, snapshot) ?: return
                inputText(node, action.text ?: "")
            }
            "scroll" -> {
                scroll(action.direction ?: "down")
            }
            "system" -> {
                performSystemAction(action.button)
            }
            "wait" -> {
                // Handled by delay in orchestration
            }
        }
    }

    private fun getNode(id: Int?, snapshot: ScreenSnapshot): AccessibilityNodeInfo? {
        if (id == null) return null
        return snapshot.rawMap[id]
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        // Try native click first
        if (node.isClickable) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) return
        }

        // Fallback to gesture
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private fun inputText(node: AccessibilityNodeInfo, text: String) {
        // Try setting text directly
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (!result) {
            // Click then type logic could be added here if needed
            clickNode(node) // focus
            // In a real agent, we might need to input key events via shell or other means if
            // SET_TEXT fails
        }
    }

    private fun scroll(direction: String) {
        val displayMetrics = service.resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        val startX = width / 2f
        val startY: Float
        val endY: Float

        if (direction == "down") {
            startY = height * 0.7f
            endY = height * 0.3f
        } else { // up
            startY = height * 0.3f
            endY = height * 0.7f
        }

        swipe(startX, startY, startX, endY)
    }

    private fun performSystemAction(button: String?) {
        when (button?.lowercase()) {
            "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "enter" -> {
                /* Enter key not directly supported via global action */
            }
        }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture =
                GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                        .build()
        service.dispatchGesture(gesture, null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        val gesture =
                GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
                        .build()
        service.dispatchGesture(gesture, null, null)
    }
}
