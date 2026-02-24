package com.moonkey.androidagent.platform

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.moonkey.androidagent.util.recycleCompat

object AccessibilityNodeFinder {
    /**
     * Find the clickable node whose center is closest to (x, y).
     *
     * Matching strategy:
     * 1. Collect all clickable+visible nodes whose bounds contain (x, y)
     * 2. Return the candidate whose center is nearest to (x, y)
     *
     * This correctly resolves overlapping nodes: the click point is the intended
     * element's center by construction (distance = 0), so it always wins over
     * any accidentally overlapping sibling.
     */
    fun findClickableNodeAtLocation(
            root: AccessibilityNodeInfo,
            x: Int,
            y: Int
    ): AccessibilityNodeInfo? =
            findActionableNodeAtLocation(root, x, y) { node -> node.isClickable && node.isVisibleToUser }

    /**
     * Find the top-most long-clickable node whose center is closest to (x, y).
     * Mirrors findClickableNodeAtLocation but checks isLongClickable.
     */
    fun findLongClickableNodeAtLocation(
            root: AccessibilityNodeInfo,
            x: Int,
            y: Int
    ): AccessibilityNodeInfo? =
            findActionableNodeAtLocation(root, x, y) { node ->
                node.isVisibleToUser && (
                        node.isLongClickable ||
                                node.actionList?.any {
                                    it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK
                                } == true
                        )
            }

    private fun findActionableNodeAtLocation(
            root: AccessibilityNodeInfo,
            x: Int,
            y: Int,
            predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Long>>()

        fun collect(node: AccessibilityNodeInfo, shouldRecycle: Boolean) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.contains(x, y)) {
                if (shouldRecycle) node.recycleCompat()
                return
            }
            for (i in node.childCount - 1 downTo 0) {
                val child = node.getChild(i) ?: continue
                collect(child, shouldRecycle = true)
            }
            if (predicate(node)) {
                val cx = (bounds.left + bounds.right) / 2
                val cy = (bounds.top + bounds.bottom) / 2
                val dist = (x - cx).toLong() * (x - cx) + (y - cy).toLong() * (y - cy)
                candidates.add(node to dist)
            } else if (shouldRecycle) {
                node.recycleCompat()
            }
        }

        collect(root, shouldRecycle = false)

        val winner = candidates.minByOrNull { it.second }?.first
        for ((node, _) in candidates) {
            if (node !== winner && node !== root) node.recycleCompat()
        }
        return winner
    }

    /**
     * Find a focused editable node in the tree. Used when typing into the currently focused field.
     */
    fun findFocusedEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // First, try to find the input-focused node
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            // Check if it supports text input
            if (focused.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
                return focused
            }
            focused.recycleCompat()
        }

        // Fallback: DFS for any editable node that has focus
        return findEditableWithFocus(root)
    }

    /** DFS to find an editable node with focus. */
    private fun findEditableWithFocus(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        ) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableWithFocus(child)
            if (result != null) {
                if (result !== child) child.recycleCompat()
                return result
            }
            child.recycleCompat()
        }

        return null
    }

    /**
     * Find the scrollable node at the given coordinates.
     * Used for scroll action dispatch — finds the container to perform ACTION_SCROLL on.
     */
    fun findScrollableNodeAtLocation(
            root: AccessibilityNodeInfo,
            x: Int,
            y: Int
    ): AccessibilityNodeInfo? =
            findActionableNodeAtLocation(root, x, y) { node ->
                node.isScrollable && node.isVisibleToUser
            }

    /**
     * Find a text-input capable node at the given screen coordinates. Helper for performType() to
     * re-query the accessibility tree.
     *
     * - Properly recycles intermediate nodes during DFS traversal
     * - Checks for ACTION_SET_TEXT support, not just isEditable (supports WebView/custom widgets)
     */
    fun findNodeAtLocation(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()

        /**
         * Check if a node can accept text input. P2 fix: Check for ACTION_SET_TEXT action support
         * in addition to isEditable, which handles custom widgets and WebView inputs that support
         * text but don't set isEditable.
         */
        fun canAcceptTextInput(node: AccessibilityNodeInfo): Boolean {
            if (node.isEditable) return true
            // P2 fix: Also check if node supports ACTION_SET_TEXT action
            val actions = node.actionList
            return actions?.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } == true
        }

        /**
         * DFS to find deepest text-input node containing the point. P1 fix: Properly recycles
         * intermediate nodes obtained during traversal.
         *
         * @param node Current node to search
         * @param shouldRecycle Whether this node should be recycled if not returned
         * ```
         *                      (false for root which is system-owned)
         * ```
         */
        fun search(node: AccessibilityNodeInfo, shouldRecycle: Boolean): AccessibilityNodeInfo? {
            node.getBoundsInScreen(bounds)

            if (!bounds.contains(x, y)) {
                if (shouldRecycle) {
                    node.recycleCompat()
                }
                return null
            }

            // Check children first in reverse order (prefer visual top-most match).
            for (i in node.childCount - 1 downTo 0) {
                val child = node.getChild(i) ?: continue
                val found = search(child, shouldRecycle = true)
                if (found != null) {
                    // Found a match in subtree
                    // Recycle current node if allowed (AccessibilityNodeInfo from getChild() are
                    // independent)
                    if (shouldRecycle) {
                        node.recycleCompat()
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
                node.recycleCompat()
            }

            return null
        }

        return search(root, shouldRecycle = false)
    }
}
