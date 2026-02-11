package com.moonkey.androidagent.platform.virtualdisplay

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.moonkey.androidagent.platform.AccessibilityNodeFinder
import com.moonkey.androidagent.platform.ActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Performs node-based UI actions (click, long-click, set-text) on a virtual display.
 *
 * Uses AccessibilityNodeFinder and a11y performAction(); works across displays.
 * Aligns with AccessibilityPlatform's node-action handling structure.
 */
class VirtualDisplayNodeActionPerformer(
    private val windowAccessor: VirtualDisplayWindowAccessor
) {
    companion object {
        private const val TAG = "VirtualDisplayNodeActionPerformer"
    }

    @Suppress("DEPRECATION")
    suspend fun performNodeClickAt(x: Int, y: Int): ActionResult {
        return withContext(Dispatchers.Main) {
            val root = windowAccessor.getRootOnDisplay()
                ?: return@withContext ActionResult.Failure(
                    "No a11y root on display for click"
                )

            try {
                val node = AccessibilityNodeFinder.findClickableNodeAtLocation(root, x, y)
                    ?: return@withContext ActionResult.Failure("No clickable node at ($x,$y)")

                try {
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (ok) ActionResult.Success("ACTION_CLICK at ($x,$y)")
                    else ActionResult.Failure("ACTION_CLICK returned false at ($x,$y)")
                } finally {
                    if (node !== root) {
                        node.recycle()
                    }
                }
            } finally {
                root.recycle()
            }
        }
    }

    @Suppress("DEPRECATION")
    suspend fun performNodeLongClickAt(x: Int, y: Int): ActionResult {
        return withContext(Dispatchers.Main) {
            val root = windowAccessor.getRootOnDisplay()
                ?: return@withContext ActionResult.Failure(
                    "No a11y root on display for long-click"
                )

            try {
                val node = AccessibilityNodeFinder.findLongClickableNodeAtLocation(root, x, y)
                    ?: return@withContext ActionResult.Failure(
                        "No long-clickable node at ($x,$y)"
                    )

                try {
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    if (ok) ActionResult.Success("ACTION_LONG_CLICK at ($x,$y)")
                    else ActionResult.Failure("ACTION_LONG_CLICK returned false at ($x,$y)")
                } finally {
                    if (node !== root) {
                        node.recycle()
                    }
                }
            } finally {
                root.recycle()
            }
        }
    }

    suspend fun performSetTextOnNodeAt(
        x: Int,
        y: Int,
        text: String,
        clear: Boolean
    ): ActionResult {
        return withContext(Dispatchers.Main) {
            val root = windowAccessor.getRootOnDisplay()
                ?: return@withContext ActionResult.Failure(
                    "No a11y root on display for set-text"
                )

            try {
                val node = AccessibilityNodeFinder.findNodeAtLocation(root, x, y)
                    ?: return@withContext ActionResult.Failure(
                        "No text-input node at ($x,$y)"
                    )

                try {
                    setTextOnNode(node, text, clear)
                } finally {
                    if (node !== root) node.recycle()
                }
            } finally {
                root.recycle()
            }
        }
    }

    suspend fun performSetTextOnFocused(text: String, clear: Boolean): ActionResult {
        return withContext(Dispatchers.Main) {
            val root = windowAccessor.getRootOnDisplay()
                ?: return@withContext ActionResult.Failure(
                    "No a11y root on display for set-text"
                )

            try {
                val node = AccessibilityNodeFinder.findFocusedEditableNode(root)
                    ?: return@withContext ActionResult.Failure(
                        "No focused editable element on display"
                    )

                try {
                    setTextOnNode(node, text, clear)
                } finally {
                    if (node !== root) {
                        node.recycle()
                    }
                }
            } finally {
                root.recycle()
            }
        }
    }

    private fun setTextOnNode(
        node: AccessibilityNodeInfo,
        text: String,
        clear: Boolean
    ): ActionResult {
        if (clear) {
            val clearArgs = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    ""
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) {
            clearInputFocusAfterSetText(node)
            ActionResult.Success("Text entered: $text")
        } else {
            ActionResult.Failure("ACTION_SET_TEXT failed")
        }
    }

    /**
     * Pragmatic IME mitigation after successful ACTION_SET_TEXT.
     * Clearing focus avoids keeping IME/input connection active longer than necessary.
     */
    private fun clearInputFocusAfterSetText(node: AccessibilityNodeInfo) {
        runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
        }.onFailure { e ->
            Log.w(TAG, "Failed to clear focus after ACTION_SET_TEXT (non-fatal)", e)
        }
    }
}
