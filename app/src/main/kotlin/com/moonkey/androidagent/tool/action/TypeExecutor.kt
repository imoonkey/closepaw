package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.UIAction
import kotlinx.coroutines.delay

/**
 * Type executor: set text on target node, with tap-to-focus fallback.
 *
 * Fallback table:
 *   With target:
 *     Attempt 1: SetTextOnNodeAt(x, y, text, clear)
 *     Attempt 2: TapAt(x, y) → delay → SetTextOnFocused(text, clear)
 *   Without target:
 *     Attempt 1: SetTextOnFocused(text, clear)
 *
 * Type success = ACTION_SET_TEXT returns true. UI change detection is supplementary.
 */
class TypeExecutor(
    private val targetResolver: TargetResolver = TargetResolver
) {
    companion object {
        private const val FOCUS_DELAY_MS = 150L
        private const val UI_SETTLE_DELAY_MS = 300L
    }

    suspend fun execute(
        target: Target?,
        inputText: String,
        clear: Boolean,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        isCancelled: () -> Boolean
    ): ActionOutcome {
        val attemptTrail = mutableListOf<String>()

        if (target == null) {
            return typeOnFocused(inputText, clear, snapshot, platform, attemptTrail)
        }

        val point = targetResolver.resolve(target, snapshot)
            ?: return ActionOutcome.Failed(
                reason = targetResolver.describeFailure(target, snapshot),
                attemptTrail = emptyList()
            )

        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before type")

        // Attempt 1: SetTextOnNodeAt
        val directResult = platform.performAction(
            UIAction.SetTextOnNodeAt(point.x, point.y, inputText, clear)
        )
        if (directResult is ActionResult.Success) {
            attemptTrail.add("SetTextOnNodeAt: success")
            delay(UI_SETTLE_DELAY_MS)
            val post = runCatching { platform.captureScreen() }.getOrNull()
            val observation = post?.let { buildObservation(it, platform) }
            return ActionOutcome.Success(
                message = "Typed into element at (${point.x},${point.y})",
                observation = observation,
                attemptTrail = attemptTrail,
                verified = true
            )
        }
        attemptTrail.add("SetTextOnNodeAt: ${(directResult as? ActionResult.Failure)?.reason ?: "failed"}")

        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled between type attempts")

        // Attempt 2: Tap to focus, then SetTextOnFocused.
        // Skipped in VD mode — tap triggers IME on the wrong display.
        if (!platform.allowTapToFocus()) {
            attemptTrail.add("TapToFocus: skipped (VD mode)")
            return ActionOutcome.Failed(
                reason = "SetTextOnNodeAt failed and tap-to-focus disabled in VD mode",
                attemptTrail = attemptTrail
            )
        }

        val tapResult = platform.performAction(UIAction.TapAt(point.x, point.y))
        if (tapResult is ActionResult.Failure) {
            attemptTrail.add("TapToFocus: ${tapResult.reason}")
            return ActionOutcome.Failed(
                reason = "Type at (${point.x},${point.y}) failed after all attempts",
                attemptTrail = attemptTrail
            )
        }

        delay(FOCUS_DELAY_MS)

        val focusedResult = platform.performAction(UIAction.SetTextOnFocused(inputText, clear))
        if (focusedResult is ActionResult.Success) {
            attemptTrail.add("TapToFocus+SetTextOnFocused: success")
            delay(UI_SETTLE_DELAY_MS)
            val post = runCatching { platform.captureScreen() }.getOrNull()
            val observation = post?.let { buildObservation(it, platform) }
            return ActionOutcome.Success(
                message = "Typed via tap-to-focus at (${point.x},${point.y})",
                observation = observation,
                attemptTrail = attemptTrail,
                verified = true
            )
        }
        attemptTrail.add("SetTextOnFocused: ${(focusedResult as? ActionResult.Failure)?.reason ?: "failed"}")

        return ActionOutcome.Failed(
            reason = "Type at (${point.x},${point.y}) failed after all attempts",
            attemptTrail = attemptTrail
        )
    }

    private suspend fun typeOnFocused(
        inputText: String,
        clear: Boolean,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        attemptTrail: MutableList<String>
    ): ActionOutcome {
        val result = platform.performAction(UIAction.SetTextOnFocused(inputText, clear))
        if (result is ActionResult.Success) {
            attemptTrail.add("SetTextOnFocused: success")
            delay(UI_SETTLE_DELAY_MS)
            val post = runCatching { platform.captureScreen() }.getOrNull()
            val observation = post?.let { buildObservation(it, platform) }
            return ActionOutcome.Success(
                message = "Typed into focused field",
                observation = observation,
                attemptTrail = attemptTrail,
                verified = true
            )
        }
        attemptTrail.add("SetTextOnFocused: ${(result as? ActionResult.Failure)?.reason ?: "failed"}")
        return ActionOutcome.Failed(
            reason = "No focused editable element found",
            attemptTrail = attemptTrail
        )
    }
}
