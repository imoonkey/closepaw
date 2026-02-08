package com.moonkey.androidagent.tool.handlers

import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import org.json.JSONObject

/**
 * Invocation for click action using explicit selector + API fallback.
 *
 * Fallback order:
 * - selector: element_index -> text -> coordinates
 * - API per selector: ACTION_CLICK -> gesture tap
 */
class ClickTargetInvocation(
    override val params: JSONObject,
    private val description: String
) : ToolInvocation {

    companion object {
        private const val TAG = "ClickTargetInvocation"
    }

    private sealed interface AttemptOutcome {
        data class Success(val result: ToolExecutionResult.Success) : AttemptOutcome
        data class Retry(val reason: String) : AttemptOutcome
        data class Cancelled(val reason: String) : AttemptOutcome
    }

    private data class ClickAttempt(
        val label: String,
        val action: UIAction,
        val preSnapshotForChange: ScreenSnapshot?
    )

    override val toolName: String = "mobile_action"

    override fun getDescription(): String {
        val agentThought = params.optString("agent_thought", "").trim()
        return if (agentThought.isNotEmpty()) {
            "$description (reason: $agentThought)"
        } else {
            description
        }
    }

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) {
            return ToolExecutionResult.Cancelled("Cancelled before execution")
        }

        val snapshot = context.currentSnapshot
        val attemptLogs = mutableListOf<String>()
        val attempts = buildAttemptPlan(snapshot, attemptLogs)

        for (attempt in attempts) {
            when (val outcome = executeAttempt(context, attempt)) {
                is AttemptOutcome.Success -> return outcome.result
                is AttemptOutcome.Retry -> attemptLogs.add("${attempt.label}: ${outcome.reason}")
                is AttemptOutcome.Cancelled -> return ToolExecutionResult.Cancelled(outcome.reason)
            }
        }

        val details = if (attemptLogs.isNotEmpty()) {
            " Attempts: ${attemptLogs.joinToString("; ")}"
        } else {
            ""
        }
        return ToolExecutionResult.Failure("Failed to click element.$details")
    }

    private fun buildAttemptPlan(
        snapshot: ScreenSnapshot?,
        attemptLogs: MutableList<String>
    ): List<ClickAttempt> {
        val attempts = mutableListOf<ClickAttempt>()
        val dedupe = mutableSetOf<String>()

        val elementIndex = if (params.has("element_index")) params.optInt("element_index", -1) else null
        if (elementIndex != null) {
            if (elementIndex < 0) {
                attemptLogs.add("element_index must be >= 0")
            } else {
                val element = resolveElement(snapshot, elementIndex)
                if (element == null) {
                    if (snapshot != null) {
                        attemptLogs.add(TargetingInvocationUtils.buildElementNotFoundMessage(elementIndex, snapshot))
                    } else {
                        attemptLogs.add("element_index $elementIndex requires snapshot")
                    }
                } else {
                    addCoordinateAttempts(
                        attempts = attempts,
                        dedupe = dedupe,
                        x = element.center.x,
                        y = element.center.y,
                        label = "element_index $elementIndex",
                        preSnapshotForChange = snapshot
                    )
                }
            }
        }

        val text = params.optString("text", "").trim()
        if (text.isNotEmpty()) {
            if (snapshot == null) {
                attemptLogs.add("text=\"$text\": Snapshot required for text lookup")
            } else {
                val textIndex = params.optInt("text_index", 0)
                val resolvedIndex =
                    MultiSelectorTargeting.findElementIndexByTextOrDescription(
                        snapshot = snapshot,
                        text = text,
                        index = textIndex
                    )
                if (resolvedIndex == null) {
                    val count = MultiSelectorTargeting.matchCountByTextOrDescription(snapshot, text)
                    attemptLogs.add("text=\"$text\" index $textIndex out of range (found $count)")
                } else {
                    val element = resolveElement(snapshot, resolvedIndex)
                    if (element == null) {
                        attemptLogs.add("text=\"$text\" resolved to missing element index $resolvedIndex")
                    } else {
                        addCoordinateAttempts(
                            attempts = attempts,
                            dedupe = dedupe,
                            x = element.center.x,
                            y = element.center.y,
                            label = "text=\"$text\" index $textIndex",
                            preSnapshotForChange = snapshot
                        )
                    }
                }
            }
        }

        val hasPoint = params.has("x") && params.has("y")
        if (hasPoint) {
            val x = params.optInt("x", -1)
            val y = params.optInt("y", -1)
            addCoordinateAttempts(
                attempts = attempts,
                dedupe = dedupe,
                x = x,
                y = y,
                label = "coordinates ($x,$y)",
                preSnapshotForChange = snapshot
            )
        }

        return attempts
    }

    private suspend fun executeAttempt(
        context: ToolExecutionContext,
        attempt: ClickAttempt
    ): AttemptOutcome {
        val result = context.platform.performAction(attempt.action, snapshot = null)
        return when (result) {
            is ActionResult.Success -> {
                val observation = TargetingInvocationUtils.capturePostActionObservation(context, TAG)
                val uiChange = TargetingInvocationUtils.detectUiChange(attempt.preSnapshotForChange, observation)
                if (!uiChange.changed) {
                    AttemptOutcome.Retry(uiChange.reason)
                } else {
                    AttemptOutcome.Success(
                        ToolExecutionResult.Success(
                            output = "${result.message} via ${attempt.label}",
                            observation = observation
                        )
                    )
                }
            }
            is ActionResult.Failure -> AttemptOutcome.Retry(result.reason)
            is ActionResult.ElementNotFound -> {
                val snap = attempt.preSnapshotForChange
                val reason = if (snap != null) {
                    TargetingInvocationUtils.buildElementNotFoundMessage(result.elementIndex, snap)
                } else {
                    "Element not found: index ${result.elementIndex} (no snapshot available)"
                }
                AttemptOutcome.Retry(reason)
            }
            is ActionResult.Cancelled -> AttemptOutcome.Cancelled(result.reason)
        }
    }

    private fun addCoordinateAttempts(
        attempts: MutableList<ClickAttempt>,
        dedupe: MutableSet<String>,
        x: Int,
        y: Int,
        label: String,
        preSnapshotForChange: ScreenSnapshot?
    ) {
        val actionClick = UIAction.ClickNodeAt(x = x, y = y)
        addAttemptIfNew(
            attempts = attempts,
            dedupe = dedupe,
            action = actionClick,
            label = "$label -> ACTION_CLICK",
            preSnapshotForChange = preSnapshotForChange
        )

        val gestureTap = UIAction.TapAt(x = x, y = y)
        addAttemptIfNew(
            attempts = attempts,
            dedupe = dedupe,
            action = gestureTap,
            label = "$label -> gesture tap",
            preSnapshotForChange = preSnapshotForChange
        )
    }

    private fun addAttemptIfNew(
        attempts: MutableList<ClickAttempt>,
        dedupe: MutableSet<String>,
        action: UIAction,
        label: String,
        preSnapshotForChange: ScreenSnapshot?
    ) {
        val key = when (action) {
            is UIAction.ClickNodeAt -> "ClickNodeAt:${action.x},${action.y}"
            is UIAction.TapAt -> "TapAt:${action.x},${action.y}"
            else -> return
        }
        if (dedupe.add(key)) {
            attempts.add(
                ClickAttempt(
                    label = label,
                    action = action,
                    preSnapshotForChange = preSnapshotForChange
                )
            )
        }
    }

    private fun resolveElement(snapshot: ScreenSnapshot?, index: Int): PerceptionElement? {
        if (snapshot == null) return null
        return snapshot.elements.firstOrNull { it.index == index }
            ?: snapshot.elements.getOrNull(index)
    }
}
