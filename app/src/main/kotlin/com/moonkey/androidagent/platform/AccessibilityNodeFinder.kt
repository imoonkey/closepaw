package com.moonkey.androidagent.platform

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

internal object AccessibilityNodeFinder {
    /**
     * Find a clickable node at the given coordinates.
     * Used for ACTION_CLICK approach which works better with some apps.
     */
    fun findClickableNodeAtLocation(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()

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

    /**
     * Find a focused editable node in the tree.
     * Used when typing into the currently focused field.
     */
    fun findFocusedEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
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
    fun findNodeAtLocation(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()

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

        return search(root, shouldRecycle = false)
    }
}
