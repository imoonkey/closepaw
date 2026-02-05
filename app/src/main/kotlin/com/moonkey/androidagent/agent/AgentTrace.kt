package com.moonkey.androidagent.agent

import com.moonkey.androidagent.agent.cognition.trace.CognitionTraceRedactor
import com.moonkey.androidagent.agent.cognition.trace.LlmInputItemsTraceSerializer
import com.moonkey.androidagent.agent.cognition.trace.ArbitrationDecision
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.protocol.SessionId
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.tool.ToolCallResult
import com.moonkey.androidagent.tool.ToolObservation
import com.moonkey.androidagent.trace.HistoryTraceSerializer
import com.moonkey.androidagent.trace.TraceArtifactRef
import com.moonkey.androidagent.trace.TraceJson
import com.moonkey.androidagent.trace.emit
import com.openai.models.responses.ResponseInputItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal class AgentTrace(
    private val sessionId: SessionId,
    private val services: SessionServices
) {
    private val trace = services.traceRecorder
    private var sessionStartedAtMs: Long = 0L
    private val runMetrics = RunMetrics()

    fun sessionStarted(config: AgentConfig) {
        sessionStartedAtMs = System.currentTimeMillis()
        trace.emit(
            sessionId = sessionId.value,
            type = "session_started",
            data =
                buildJsonObject {
                    put("goal", JsonPrimitive(config.goal))
                    put("task_id", JsonPrimitive(config.taskId))
                    put("agent_id", JsonPrimitive(config.agentId))
                    put("agent_role", JsonPrimitive(config.agentRole.name.lowercase()))
                    config.parentSessionId?.let { put("parent_session_id", JsonPrimitive(it.value)) }
                    config.delegationCallId?.let { put("delegation_call_id", JsonPrimitive(it)) }
                    put("max_turns", JsonPrimitive(config.maxTurns))
                    put("ui_settle_delay_ms", JsonPrimitive(config.uiSettleDelayMs))
                    put("llm_backend", JsonPrimitive(services.config.llmBackend.name))
                    put("model", JsonPrimitive(services.config.model))
                    put("approval_mode", JsonPrimitive(services.config.approvalMode.name))
                    put("debug_mode", JsonPrimitive(services.config.debugMode))
                    put("trace_enabled", JsonPrimitive(services.config.traceEnabled))
                    put("cognition_profile_id", JsonPrimitive(config.cognitionProfileId ?: "baseline"))
                }
        )
    }

    fun sessionStopped(reason: AgentStopReason, turnsExecuted: Int) {
        val summaryArtifact = writeRunSummary(reason, turnsExecuted)
        trace.emit(
            sessionId = sessionId.value,
            type = "session_stopped",
            data =
                buildJsonObject {
                    put("reason", JsonPrimitive(reason::class.simpleName ?: "unknown"))
                    put("turns_executed", JsonPrimitive(turnsExecuted))
                },
            artifacts = listOfNotNull(summaryArtifact)
        )
    }

    fun turnStarted(turnId: String, turnNumber: Int) {
        runMetrics.turnsStarted++
        trace.emit(
            sessionId = sessionId.value,
            type = "turn_started",
            turnId = turnId,
            turnNumber = turnNumber
        )
    }

    fun turnCompleted(turnId: String, turnNumber: Int) {
        runMetrics.turnsCompleted++
        trace.emit(
            sessionId = sessionId.value,
            type = "turn_completed",
            turnId = turnId,
            turnNumber = turnNumber
        )
    }

    fun turnError(turnId: String, turnNumber: Int, error: Throwable) {
        runMetrics.turnErrors++
        trace.emit(
            sessionId = sessionId.value,
            type = "turn_error",
            turnId = turnId,
            turnNumber = turnNumber,
            data =
                buildJsonObject {
                    put("message", JsonPrimitive(error.message ?: "Unknown error"))
                    put("type", JsonPrimitive(error::class.qualifiedName ?: "Exception"))
                }
        )
    }

    fun screenCaptured(turnId: String, turnNumber: Int, snapshot: ScreenSnapshot, packageName: String?) {
        val artifacts =
            buildList {
                snapshot.debug?.rawA11yTreePath?.let {
                    add(TraceArtifactRef(kind = "raw_a11y_tree", path = it, mimeType = "application/json"))
                }
                snapshot.debug?.sanitizedA11yTreePath?.let {
                    add(TraceArtifactRef(kind = "sanitized_a11y_tree", path = it, mimeType = "application/json"))
                }
                snapshot.debug?.screenshotPath?.let {
                    add(TraceArtifactRef(kind = "screenshot", path = it, mimeType = "image/jpeg"))
                }
            }

        trace.emit(
            sessionId = sessionId.value,
            type = "screen_captured",
            turnId = turnId,
            turnNumber = turnNumber,
            data =
                buildJsonObject {
                    put("elements", JsonPrimitive(snapshot.elements.size))
                    packageName?.let { put("package", JsonPrimitive(it)) }
                },
            artifacts = artifacts
        )
    }

    fun llmRequest(
        turnId: String,
        turnNumber: Int,
        snapshot: ScreenSnapshot,
        systemPrompt: String,
        userContextText: String,
        history: List<ResponseItem>,
        inputItems: List<ResponseInputItem>
    ) {
        if (!trace.enabled) return
        runMetrics.llmRequests++

        val historyJson = HistoryTraceSerializer.toJson(history)
        val historyArtifact =
            storeRedactedText(
                kind = "llm_history",
                filenameHint = "turn_${turnNumber}_history.json",
                content = encodeRedactedJson(historyJson),
                mimeType = "application/json"
            )

        val systemArtifact =
            storeRedactedText(
                kind = "llm_system_prompt",
                filenameHint = "turn_${turnNumber}_system.txt",
                content = systemPrompt,
                mimeType = "text/plain"
            )

        val contextArtifact =
            storeRedactedText(
                kind = "llm_user_context",
                filenameHint = "turn_${turnNumber}_user_context.txt",
                content = userContextText,
                mimeType = "text/plain"
            )

        val fullPromptArtifact =
            storeRedactedText(
                kind = "llm_full_prompt",
                filenameHint = "turn_${turnNumber}_full_prompt.txt",
                content =
                    """
                    === SYSTEM PROMPT ===
                    $systemPrompt

                    === USER CONTEXT ===
                    $userContextText
                    """.trimIndent(),
                mimeType = "text/plain"
            )

        val inputItemsArtifact =
            storeRedactedText(
                kind = "llm_input_items",
                filenameHint = "turn_${turnNumber}_llm_input_items.json",
                content = encodeRedactedJson(LlmInputItemsTraceSerializer.toJson(inputItems)),
                mimeType = "application/json"
            )

        val snapshotArtifacts =
            listOfNotNull(
                snapshot.debug?.sanitizedA11yTreePath?.let {
                    TraceArtifactRef(kind = "sanitized_a11y_tree", path = it, mimeType = "application/json")
                },
                snapshot.debug?.rawA11yTreePath?.let {
                    TraceArtifactRef(kind = "raw_a11y_tree", path = it, mimeType = "application/json")
                },
                snapshot.debug?.screenshotPath?.let {
                    TraceArtifactRef(kind = "screenshot", path = it, mimeType = "image/jpeg")
                }
            )

        trace.emit(
            sessionId = sessionId.value,
            type = "llm_request",
            turnId = turnId,
            turnNumber = turnNumber,
            data =
                buildJsonObject {
                    put("history_items", JsonPrimitive(history.size))
                    put("input_items", JsonPrimitive(inputItems.size))
                    put("model", JsonPrimitive(services.config.model))
                    put("screenshot_attached", JsonPrimitive(snapshot.image != null))
                },
            artifacts =
                listOfNotNull(
                    historyArtifact,
                    systemArtifact,
                    contextArtifact,
                    fullPromptArtifact,
                    inputItemsArtifact
                ) + snapshotArtifacts
        )
    }

    fun llmResponse(turnId: String, turnNumber: Int, result: TurnResult) {
        if (!trace.enabled) return
        runMetrics.llmResponses++

        val toolCallsJson =
            buildJsonArray {
                result.toolCalls.forEach { call ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(call.id))
                            put("name", JsonPrimitive(call.name))
                            put("arguments_json", JsonPrimitive(call.arguments.toString()))
                        }
                    )
                }
            }

        val responseTextArtifact =
            result.content?.let {
                storeRedactedText(
                    kind = "llm_response_text",
                    filenameHint = "turn_${turnNumber}_assistant.txt",
                    content = it,
                    mimeType = "text/plain"
                )
            }

        val toolCallsArtifact =
            storeRedactedText(
                kind = "llm_tool_calls",
                filenameHint = "turn_${turnNumber}_tool_calls.json",
                content = encodeRedactedJson(toolCallsJson),
                mimeType = "application/json"
            )

        trace.emit(
            sessionId = sessionId.value,
            type = "llm_response",
            turnId = turnId,
            turnNumber = turnNumber,
            data =
                buildJsonObject {
                    put("has_text", JsonPrimitive(result.content != null))
                    put("tool_calls", JsonPrimitive(result.toolCalls.size))
                    put("is_complete", JsonPrimitive(result.isComplete))
                },
            artifacts = listOfNotNull(responseTextArtifact, toolCallsArtifact)
        )
    }

    fun arbitrationDecision(turnId: String, turnNumber: Int, decision: ArbitrationDecision) {
        if (!trace.enabled) return
        trace.emit(
            sessionId = sessionId.value,
            type = "tool_arbitration",
            turnId = turnId,
            turnNumber = turnNumber,
            data =
                buildJsonObject {
                    put("policy_mode", JsonPrimitive(decision.policyMode.name))
                    put("original_tool_count", JsonPrimitive(decision.originalToolCount))
                    put("selected_tool_count", JsonPrimitive(decision.selectedToolCount))
                    put("selected_tool", JsonPrimitive(decision.selectedTool?.name ?: "none"))
                    put(
                        "dropped_tools",
                        buildJsonArray {
                            decision.droppedToolCalls.forEach { dropped ->
                                add(
                                    buildJsonObject {
                                        put("name", JsonPrimitive(dropped.toolName))
                                        put("reason", JsonPrimitive(dropped.reason.name))
                                    }
                                )
                            }
                        }
                    )
                }
        )
    }

    fun toolCall(turnId: String, turnNumber: Int, toolCall: ToolCallRequest) {
        if (!trace.enabled) return
        runMetrics.toolCalls++

        val argsArtifact =
            storeRedactedText(
                kind = "tool_call_args",
                filenameHint = "turn_${turnNumber}_${toolCall.name}_${toolCall.id}.json",
                content = toolCall.arguments.toString(2),
                mimeType = "application/json"
            )

        trace.emit(
            sessionId = sessionId.value,
            type = "tool_call",
            turnId = turnId,
            turnNumber = turnNumber,
            data =
                buildJsonObject {
                    put("id", JsonPrimitive(toolCall.id))
                    put("name", JsonPrimitive(toolCall.name))
                },
            artifacts = listOfNotNull(argsArtifact)
        )
    }

    fun toolResult(
        turnId: String,
        turnNumber: Int,
        toolCall: ToolCallRequest,
        toolResult: ToolCallResult,
        formattedResult: String,
        observation: ToolObservation,
        observedSnapshot: ScreenSnapshot?
    ) {
        if (!trace.enabled) return
        if (toolResult is ToolCallResult.Success) {
            runMetrics.toolSuccesses++
        } else {
            runMetrics.toolFailures++
        }

        val resultArtifact =
            storeRedactedText(
                kind = "tool_result",
                filenameHint = "turn_${turnNumber}_${toolCall.name}_${toolCall.id}_result.txt",
                content = formattedResult,
                mimeType = "text/plain"
            )

        val observationArtifact =
            when (observation) {
                is ToolObservation.ScreenState ->
                    storeRedactedText(
                        kind = "tool_observation_screen",
                        filenameHint = "turn_${turnNumber}_${toolCall.name}_${toolCall.id}_screen.json",
                        content = observation.accessibilityTree,
                        mimeType = "application/json"
                    )

                is ToolObservation.TextOutput ->
                    storeRedactedText(
                        kind = "tool_observation_text",
                        filenameHint = "turn_${turnNumber}_${toolCall.name}_${toolCall.id}_obs.txt",
                        content = observation.content,
                        mimeType = "text/plain"
                    )
            }

        val postArtifacts =
            buildList {
                addAll(listOfNotNull(resultArtifact, observationArtifact))
                observedSnapshot?.debug?.rawA11yTreePath?.let {
                    add(
                        TraceArtifactRef(
                            kind = "raw_a11y_tree",
                            path = it,
                            mimeType = "application/json",
                            description = "Post-action raw tree"
                        )
                    )
                }
                observedSnapshot?.debug?.sanitizedA11yTreePath?.let {
                    add(
                        TraceArtifactRef(
                            kind = "sanitized_a11y_tree",
                            path = it,
                            mimeType = "application/json",
                            description = "Post-action sanitized tree"
                        )
                    )
                }
                observedSnapshot?.debug?.screenshotPath?.let {
                    add(
                        TraceArtifactRef(
                            kind = "screenshot",
                            path = it,
                            mimeType = "image/jpeg",
                            description = "Post-action screenshot"
                        )
                    )
                }
            }

        trace.emit(
            sessionId = sessionId.value,
            type = "tool_result",
            turnId = turnId,
            turnNumber = turnNumber,
            data =
                buildJsonObject {
                    put("id", JsonPrimitive(toolCall.id))
                    put("name", JsonPrimitive(toolCall.name))
                    put("success", JsonPrimitive(toolResult is ToolCallResult.Success))
                },
            artifacts = postArtifacts
        )
    }

    private fun writeRunSummary(reason: AgentStopReason, turnsExecuted: Int): TraceArtifactRef? {
        if (!trace.enabled) return null
        val stoppedAtMs = System.currentTimeMillis()
        val summaryJson =
            buildJsonObject {
                put("session_id", JsonPrimitive(sessionId.value))
                trace.runId?.let { put("run_id", JsonPrimitive(it)) }
                put("started_at_ms", JsonPrimitive(sessionStartedAtMs))
                put("stopped_at_ms", JsonPrimitive(stoppedAtMs))
                put("duration_ms", JsonPrimitive((stoppedAtMs - sessionStartedAtMs).coerceAtLeast(0)))
                put("stop_reason", JsonPrimitive(reason::class.simpleName ?: "unknown"))
                put("turns_executed", JsonPrimitive(turnsExecuted))
                put("turns_started", JsonPrimitive(runMetrics.turnsStarted))
                put("turns_completed", JsonPrimitive(runMetrics.turnsCompleted))
                put("turn_errors", JsonPrimitive(runMetrics.turnErrors))
                put("llm_requests", JsonPrimitive(runMetrics.llmRequests))
                put("llm_responses", JsonPrimitive(runMetrics.llmResponses))
                put("tool_calls", JsonPrimitive(runMetrics.toolCalls))
                put("tool_successes", JsonPrimitive(runMetrics.toolSuccesses))
                put("tool_failures", JsonPrimitive(runMetrics.toolFailures))
            }
        return storeRedactedText(
            kind = "run_summary",
            filenameHint = "run_summary.json",
            content = encodeRedactedJson(summaryJson),
            mimeType = "application/json"
        )
    }

    private fun storeRedactedText(
        kind: String,
        filenameHint: String,
        content: String,
        mimeType: String
    ): TraceArtifactRef? {
        return trace.storeText(
            kind = kind,
            filenameHint = filenameHint,
            content = CognitionTraceRedactor.redactText(content),
            mimeType = mimeType
        )
    }

    private fun encodeRedactedJson(element: JsonElement): String {
        return TraceJson.instance.encodeToString(CognitionTraceRedactor.redactJson(element))
    }
}

private data class RunMetrics(
    var turnsStarted: Int = 0,
    var turnsCompleted: Int = 0,
    var turnErrors: Int = 0,
    var llmRequests: Int = 0,
    var llmResponses: Int = 0,
    var toolCalls: Int = 0,
    var toolSuccesses: Int = 0,
    var toolFailures: Int = 0
)
