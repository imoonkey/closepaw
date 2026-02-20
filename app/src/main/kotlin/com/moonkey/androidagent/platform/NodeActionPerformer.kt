package com.moonkey.androidagent.platform

import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.moonkey.androidagent.util.recycleCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared node-action executor for both Accessibility and VirtualDisplay platforms.
 *
 * The only platform-specific dependency is [rootProvider].
 */
class NodeActionPerformer(
        private val rootProvider: () -> AccessibilityNodeInfo?,
        private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
) {
    @Suppress("DEPRECATION")
    suspend fun performNodeClickAt(x: Int, y: Int): ActionResult {
        return performNodeActionAt(
                nodeFinder = { root -> AccessibilityNodeFinder.findClickableNodeAtLocation(root, x, y) },
                notFoundMessage = "No clickable node at ($x,$y)",
                action = AccessibilityNodeInfo.ACTION_CLICK,
                successMessage = "ACTION_CLICK at ($x,$y)",
                failureMessage = "ACTION_CLICK returned false at ($x,$y)"
        )
    }

    /**
     * Perform a scroll action on the scrollable node at (x, y).
     *
     * Direction is content direction: "down" = reveal content below.
     * Tries API 23+ directional action first, falls back to FORWARD/BACKWARD.
     */
    @Suppress("DEPRECATION")
    suspend fun performScrollAt(x: Int, y: Int, direction: String): ActionResult {
        return onMain {
            withRoot { root ->
                val node = AccessibilityNodeFinder.findScrollableNodeAtLocation(root, x, y)
                        ?: return@withRoot ActionResult.Failure("No scrollable node at ($x,$y)")
                try {
                    val (primaryId, fallbackId) = scrollActionIds(direction)
                    val ok = if (primaryId != null && sdkIntProvider() >= 23) {
                        node.performAction(primaryId) || node.performAction(fallbackId)
                    } else {
                        node.performAction(fallbackId)
                    }
                    if (ok) {
                        ActionResult.Success("Scrolled $direction at ($x,$y)")
                    } else {
                        ActionResult.Failure("Scroll $direction failed at ($x,$y)")
                    }
                } finally {
                    if (node !== root) node.recycleCompat()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    suspend fun performNodeLongClickAt(x: Int, y: Int): ActionResult {
        return onMain {
            withRoot { root ->
                val longClickableNode = AccessibilityNodeFinder.findLongClickableNodeAtLocation(root, x, y)
                if (longClickableNode != null) {
                    try {
                        val ok = longClickableNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                        return@withRoot if (ok) {
                            ActionResult.Success("ACTION_LONG_CLICK at ($x,$y)")
                        } else {
                            ActionResult.Failure("ACTION_LONG_CLICK returned false at ($x,$y)")
                        }
                    } finally {
                        if (longClickableNode !== root) longClickableNode.recycleCompat()
                    }
                }

                // Fallback: some views expose long-click action without marking isLongClickable.
                val clickableNode = AccessibilityNodeFinder.findClickableNodeAtLocation(root, x, y)
                        ?: return@withRoot ActionResult.Failure("No long-clickable node at ($x,$y)")
                try {
                    val ok = clickableNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    if (ok) {
                        ActionResult.Success("ACTION_LONG_CLICK at ($x,$y) via clickable fallback")
                    } else {
                        ActionResult.Failure("No long-clickable node at ($x,$y)")
                    }
                } finally {
                    if (clickableNode !== root) clickableNode.recycleCompat()
                }
            }
        }
    }

    suspend fun performSetTextOnNodeAt(x: Int, y: Int, text: String, clear: Boolean): ActionResult {
        return onMain {
            withRoot { root ->
                val node = AccessibilityNodeFinder.findNodeAtLocation(root, x, y)
                        ?: return@withRoot ActionResult.Failure("No text-input node at ($x,$y)")
                try {
                    setTextOnNode(node, text, clear)
                } finally {
                    if (node !== root) node.recycleCompat()
                }
            }
        }
    }

    suspend fun performSetTextOnFocused(text: String, clear: Boolean): ActionResult {
        return onMain {
            withRoot { root ->
                val node = AccessibilityNodeFinder.findFocusedEditableNode(root)
                        ?: return@withRoot ActionResult.Failure(
                                "No focused editable element found. Specify element_index to focus a field first."
                        )
                try {
                    setTextOnNode(node, text, clear)
                } finally {
                    if (node !== root) node.recycleCompat()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    suspend fun performEnterKey(): ActionResult {
        return onMain {
            withRoot { root ->
                val focusedEditable = AccessibilityNodeFinder.findFocusedEditableNode(root)
                        ?: return@withRoot ActionResult.Failure(
                                "No focused editable element to send Enter to"
                        )
                try {
                    val imeEnterActionId =
                            runCatching {
                                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
                                                .id
                                    }
                                    .getOrNull()
                    val imeResult =
                            if (sdkIntProvider() >= Build.VERSION_CODES.R && imeEnterActionId != null)
                                    focusedEditable.performAction(imeEnterActionId)
                            else false
                    if (imeResult) {
                        return@withRoot ActionResult.Success("Enter key pressed (IME action)")
                    }

                    val clickFallbackResult =
                            focusedEditable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clickFallbackResult) {
                        ActionResult.Success("Enter key pressed (click fallback)")
                    } else {
                        ActionResult.Failure(
                                "Failed to perform Enter action on focused editable element"
                        )
                    }
                } finally {
                    if (focusedEditable !== root) focusedEditable.recycleCompat()
                }
            }
        }
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, text: String, clear: Boolean): ActionResult {
        if (clear) {
            val clearArgs =
                    Bundle().apply {
                        putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                ""
                        )
                    }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        }

        val args =
                Bundle().apply {
                    putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text
                    )
                }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) {
            return ActionResult.Failure("ACTION_SET_TEXT failed")
        }

        return ActionResult.Success("Text entered: $text")
    }

    private suspend fun performNodeActionAt(
            nodeFinder: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?,
            notFoundMessage: String,
            action: Int,
            successMessage: String,
            failureMessage: String
    ): ActionResult {
        return onMain {
            withRoot { root ->
                val node = nodeFinder(root) ?: return@withRoot ActionResult.Failure(notFoundMessage)
                try {
                    val ok = node.performAction(action)
                    if (ok) {
                        ActionResult.Success(successMessage)
                    } else {
                        ActionResult.Failure(failureMessage)
                    }
                } finally {
                    if (node !== root) node.recycleCompat()
                }
            }
        }
    }

    private inline fun withRoot(block: (AccessibilityNodeInfo) -> ActionResult): ActionResult {
        val root = rootProvider() ?: return ActionResult.Failure("No a11y root available")
        return try {
            block(root)
        } finally {
            root.recycleCompat()
        }
    }

    private suspend inline fun onMain(crossinline block: () -> ActionResult): ActionResult {
        return withContext(Dispatchers.Main) { block() }
    }

    companion object {
        @Suppress("DEPRECATION")
        private fun scrollActionIds(direction: String): Pair<Int?, Int> {
            return when (direction) {
                "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id to
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id to
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id to
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id to
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else -> null to AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
        }
    }
}
