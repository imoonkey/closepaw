package com.moonkey.androidagent.tool.handlers

import com.moonkey.androidagent.model.ScreenSnapshot
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
            when (
                val outcome =
                    TargetingInvocationUtils.executeAttempt(
                        context = context,
                        action = attempt.action,
                        snapshotForAction = null,
                        snapshotForUiChange = attempt.preSnapshotForChange,
                        requireUiChange = true,
                        logTag = TAG
                    )
            ) {
                is TargetingInvocationUtils.AttemptOutcome.Success -> {
                    return ToolExecutionResult.Success(
                        output = "${outcome.message} via ${attempt.label}",
                        observation = outcome.observation
                    )
                }
                is TargetingInvocationUtils.AttemptOutcome.Retry -> {
                    attemptLogs.add("${attempt.label}: ${outcome.reason}")
                }
                is TargetingInvocationUtils.AttemptOutcome.Cancelled -> {
                    return ToolExecutionResult.Cancelled(outcome.reason)
                }
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
        val selectorAttempts =
            MultiSelectorTargeting.attemptsFromParams(
                params = params,
                textKey = "text",
                textIndexKey = "text_index",
                textLabel = "text",
                selectorOrder = MultiSelectorTargeting.CLICK_FALLBACK_ORDER
            )

        for (selectorAttempt in selectorAttempts) {
            val selector = selectorAttempt.selector
            val label = selectorAttempt.label
            when (selector) {
                is MultiSelectorTargeting.Selector.Point -> {
                    addCoordinateAttempts(
                        attempts = attempts,
                        dedupe = dedupe,
                        x = selector.x,
                        y = selector.y,
                        label = label,
                        preSnapshotForChange = snapshot
                    )
                }
                is MultiSelectorTargeting.Selector.Text -> {
                    if (snapshot == null) {
                        attemptLogs.add("$label: Snapshot required for text lookup")
                        continue
                    }

                    val element =
                        MultiSelectorTargeting.resolveElementByTextOrDescription(
                            snapshot = snapshot,
                            text = selector.text,
                            index = selector.index
                        )
                    if (element == null) {
                        val count =
                            MultiSelectorTargeting.matchCountByTextOrDescription(
                                snapshot,
                                selector.text
                            )
                        attemptLogs.add(
                            "text=\"${selector.text}\" index ${selector.index} out of range (found $count)"
                        )
                        continue
                    }

                    addCoordinateAttempts(
                        attempts = attempts,
                        dedupe = dedupe,
                        x = element.center.x,
                        y = element.center.y,
                        label = label,
                        preSnapshotForChange = snapshot
                    )
                }
                is MultiSelectorTargeting.Selector.ElementIndex -> {
                    if (snapshot == null) {
                        attemptLogs.add("$label: Snapshot required for element_index click")
                        continue
                    }

                    val element =
                        MultiSelectorTargeting.resolveElement(snapshot, selector.elementIndex)
                    if (element == null) {
                        attemptLogs.add(
                            TargetingInvocationUtils.buildElementNotFoundMessage(
                                selector.elementIndex,
                                snapshot
                            )
                        )
                        continue
                    }

                    addCoordinateAttempts(
                        attempts = attempts,
                        dedupe = dedupe,
                        x = element.center.x,
                        y = element.center.y,
                        label = label,
                        preSnapshotForChange = snapshot
                    )
                }
            }
        }

        return attempts
    }

    private fun addCoordinateAttempts(
        attempts: MutableList<ClickAttempt>,
        dedupe: MutableSet<String>,
        x: Int,
        y: Int,
        label: String,
        preSnapshotForChange: ScreenSnapshot?
    ) {
        addAttemptIfNew(
            attempts = attempts,
            dedupe = dedupe,
            dedupeKey = "ClickNodeAt:$x,$y",
            action = UIAction.ClickNodeAt(x = x, y = y),
            label = "$label -> ACTION_CLICK",
            preSnapshotForChange = preSnapshotForChange
        )

        addAttemptIfNew(
            attempts = attempts,
            dedupe = dedupe,
            dedupeKey = "TapAt:$x,$y",
            action = UIAction.TapAt(x = x, y = y),
            label = "$label -> gesture tap",
            preSnapshotForChange = preSnapshotForChange
        )
    }

    private fun addAttemptIfNew(
        attempts: MutableList<ClickAttempt>,
        dedupe: MutableSet<String>,
        dedupeKey: String,
        action: UIAction,
        label: String,
        preSnapshotForChange: ScreenSnapshot?
    ) {
        if (dedupe.add(dedupeKey)) {
            attempts.add(
                ClickAttempt(
                    label = label,
                    action = action,
                    preSnapshotForChange = preSnapshotForChange
                )
            )
        }
    }
}
