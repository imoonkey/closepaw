package ai.closepaw.tool.action

import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.platform.UIAction
import ai.closepaw.tool.AppClassifier
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
        isCancelled: () -> Boolean,
        appClassifier: AppClassifier? = null
    ): ActionOutcome {
        val attemptTrail = mutableListOf<String>()

        if (target == null) {
            return typeOnFocused(inputText, clear, snapshot, platform, attemptTrail, appClassifier)
        }

        val resolvedTarget = targetResolver.resolve(target, snapshot)
        val point = when (resolvedTarget) {
            is TargetResolver.ResolveResult.Resolved -> resolvedTarget.point
            is TargetResolver.ResolveResult.NotFound -> {
                return ActionOutcome.Failed(
                    reason = resolvedTarget.reason,
                    attemptTrail = emptyList()
                )
            }
            is TargetResolver.ResolveResult.Ambiguous -> {
                return ActionOutcome.Failed(
                    reason = resolvedTarget.reason,
                    attemptTrail = emptyList()
                )
            }
        }

        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before type")

        // Attempt 1: SetTextOnNodeAt
        val directResult = platform.performAction(
            UIAction.SetTextOnNodeAt(point.x, point.y, inputText, clear)
        )
        if (directResult is ActionResult.Success) {
            attemptTrail.add("SetTextOnNodeAt: success")
            val analysis = capturePostActionAnalysis(snapshot, platform, UI_SETTLE_DELAY_MS, appClassifier)
            return ActionOutcome.Success(
                message = formatActionMessage(
                    "Typed into element at (${point.x},${point.y})",
                    analysis.warnings
                ),
                observation = analysis.observation,
                attemptTrail = attemptTrail,
                verified = analysis.changeResult == UiChangeDetector.ChangeResult.Changed
            )
        }
        if (directResult is ActionResult.Cancelled) {
            return ActionOutcome.Cancelled("Type at (${point.x},${point.y}) cancelled: ${directResult.reason}")
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
        if (tapResult is ActionResult.Cancelled) {
            return ActionOutcome.Cancelled("Type tap-to-focus at (${point.x},${point.y}) cancelled: ${tapResult.reason}")
        }

        delay(FOCUS_DELAY_MS)

        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled after tap-to-focus")

        val focusedResult = platform.performAction(UIAction.SetTextOnFocused(inputText, clear))
        if (focusedResult is ActionResult.Success) {
            attemptTrail.add("TapToFocus+SetTextOnFocused: success")
            val analysis = capturePostActionAnalysis(snapshot, platform, UI_SETTLE_DELAY_MS, appClassifier)
            return ActionOutcome.Success(
                message = formatActionMessage(
                    "Typed via tap-to-focus at (${point.x},${point.y})",
                    analysis.warnings
                ),
                observation = analysis.observation,
                attemptTrail = attemptTrail,
                verified = analysis.changeResult == UiChangeDetector.ChangeResult.Changed
            )
        }
        if (focusedResult is ActionResult.Cancelled) {
            return ActionOutcome.Cancelled("Type focused-set at (${point.x},${point.y}) cancelled: ${focusedResult.reason}")
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
        attemptTrail: MutableList<String>,
        appClassifier: AppClassifier? = null
    ): ActionOutcome {
        val result = platform.performAction(UIAction.SetTextOnFocused(inputText, clear))
        if (result is ActionResult.Success) {
            attemptTrail.add("SetTextOnFocused: success")
            val analysis = capturePostActionAnalysis(snapshot, platform, UI_SETTLE_DELAY_MS, appClassifier)
            return ActionOutcome.Success(
                message = formatActionMessage("Typed into focused field", analysis.warnings),
                observation = analysis.observation,
                attemptTrail = attemptTrail,
                verified = analysis.changeResult == UiChangeDetector.ChangeResult.Changed
            )
        }
        if (result is ActionResult.Cancelled) {
            return ActionOutcome.Cancelled("Type on focused cancelled: ${result.reason}")
        }
        attemptTrail.add("SetTextOnFocused: ${(result as? ActionResult.Failure)?.reason ?: "failed"}")
        return ActionOutcome.Failed(
            reason = "No focused editable element found",
            attemptTrail = attemptTrail
        )
    }
}
