package com.moonkey.androidagent.platform

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
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
    companion object {
        private const val TAG = "NodeActionPerformer"
    }

    @Suppress("DEPRECATION")
    suspend fun performNodeClickAt(x: Int, y: Int): ActionResult {
        return onMain {
            withRoot { root ->
                val node = AccessibilityNodeFinder.findClickableNodeAtLocation(root, x, y)
                        ?: return@withRoot ActionResult.Failure("No clickable node at ($x,$y)")
                try {
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (ok) ActionResult.Success("ACTION_CLICK at ($x,$y)")
                    else ActionResult.Failure("ACTION_CLICK returned false at ($x,$y)")
                } finally {
                    if (node !== root) node.recycle()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    suspend fun performNodeLongClickAt(x: Int, y: Int): ActionResult {
        return onMain {
            withRoot { root ->
                val node = AccessibilityNodeFinder.findLongClickableNodeAtLocation(root, x, y)
                        ?: return@withRoot ActionResult.Failure(
                                "No long-clickable node at ($x,$y)"
                        )
                try {
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    if (ok) ActionResult.Success("ACTION_LONG_CLICK at ($x,$y)")
                    else ActionResult.Failure("ACTION_LONG_CLICK returned false at ($x,$y)")
                } finally {
                    if (node !== root) node.recycle()
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
                    if (node !== root) node.recycle()
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
                    if (node !== root) node.recycle()
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
                    if (focusedEditable !== root) focusedEditable.recycle()
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

        clearInputFocusAfterSetText(node)
        return ActionResult.Success("Text entered: $text")
    }

    private fun clearInputFocusAfterSetText(node: AccessibilityNodeInfo) {
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) }
                .onFailure { Log.w(TAG, "Failed to clear input focus (non-fatal)", it) }
    }

    private inline fun withRoot(block: (AccessibilityNodeInfo) -> ActionResult): ActionResult {
        val root = rootProvider() ?: return ActionResult.Failure("No a11y root available")
        return try {
            block(root)
        } finally {
            root.recycle()
        }
    }

    private suspend inline fun onMain(crossinline block: () -> ActionResult): ActionResult {
        return withContext(Dispatchers.Main) { block() }
    }
}
