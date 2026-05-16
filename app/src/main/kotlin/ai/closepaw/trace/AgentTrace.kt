package ai.closepaw.trace

import ai.closepaw.agent.AgentExecutionConfig
import ai.closepaw.agent.AgentStopReason
import ai.closepaw.agent.ToolCallRequest
import ai.closepaw.agent.TurnResult
import ai.closepaw.history.ResponseItem
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.protocol.SessionId
import ai.closepaw.session.SessionServices
import ai.closepaw.tool.ToolCallResult
import ai.closepaw.tool.ToolObservation
import com.openai.models.responses.ResponseInputItem
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Writes structured trace events and artifacts for one agent session.
 *
 * This class is the single bridge between runtime events and trace recorder output:
 * - emits timeline events (turn/llm/tool/session)
 * - stores redacted text/json artifacts
 * - tracks lightweight run counters for final summary
 */
internal class AgentTrace(
    private val sessionId: SessionId,
    private val services: SessionServices
) {
    private val trace = services.traceRecorder
    private val artifacts = AgentTraceArtifacts(trace)
    private var sessionStartedAtMs: Long = 0L
    private val runMetrics = RunMetrics()

    /** Emits initial session metadata. */
    fun sessionStarted(config: AgentExecutionConfig) {
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
                    put("ui_settle_delay_ms", JsonPrimitive(config.uiSettleDelayMs))
                    put("llm_backend", JsonPrimitive(services.config.llm.backendType.name))
                    put("model", JsonPrimitive(config.modelName))
                    put("main_model", JsonPrimitive(services.config.mainModel))
                    put("approval_mode", JsonPrimitive(services.config.approvalMode.name))
                    put("debug_mode", JsonPrimitive(services.config.debugMode))
                    put("trace_enabled", JsonPrimitive(services.config.traceEnabled))
                }
        )
    }

    /** Emits final session event and writes run summary artifact. */
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
        val snapshotArtifacts = artifacts.snapshotArtifacts(snapshot, postAction = false)

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
            artifacts = snapshotArtifacts
        )
    }

    /** Emits LLM request event with prompt/history artifacts. */
    fun llmRequest(
        turnId: String,
        turnNumber: Int,
        snapshot: ScreenSnapshot,
        systemPrompt: String,
        userContextText: String,
        history: List<ResponseItem>,
        inputItems: List<ResponseInputItem>,
        modelName: String,
        modelId: String
    ) {
        if (!trace.enabled) return
        runMetrics.llmRequests++

        val llmRequestArtifacts =
            artifacts.llmRequestArtifacts(
                turnNumber = turnNumber,
                snapshot = snapshot,
                systemPrompt = systemPrompt,
                userContextText = userContextText,
                history = history,
                inputItems = inputItems
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
                    put("model", JsonPrimitive(modelName))
                    put("model_id", JsonPrimitive(modelId))
                    put("screenshot_attached", JsonPrimitive(snapshot.image != null))
                },
            artifacts = llmRequestArtifacts
        )
    }

    /** Emits LLM response event and tool-call/text artifacts. */
    fun llmResponse(turnId: String, turnNumber: Int, result: TurnResult) {
        if (!trace.enabled) return
        runMetrics.llmResponses++
        val llmResponseArtifacts = artifacts.llmResponseArtifacts(turnNumber, result)

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
            artifacts = llmResponseArtifacts
        )
    }

    /** Emits per-turn arbitration details (selected tools vs dropped tools). */
    fun arbitrationDecision(turnId: String, turnNumber: Int, decision: ArbitrationDecision) {
        if (!trace.enabled) return
        trace.emit(
            sessionId = sessionId.value,
            type = "tool_arbitration",
            turnId = turnId,
            turnNumber = turnNumber,
            data =
                buildJsonObject {
                    put("original_tool_count", JsonPrimitive(decision.originalToolCount))
                    put("selected_tool_count", JsonPrimitive(decision.selectedToolCount))
                    put(
                        "selected_tools",
                        buildJsonArray {
                            decision.selectedTools.forEach { selected ->
                                add(JsonPrimitive(selected.name))
                            }
                        }
                    )
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
            artifacts.storeRedactedText(
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

        val postArtifacts =
            artifacts.toolResultArtifacts(
                turnNumber = turnNumber,
                toolCall = toolCall,
                formattedResult = formattedResult,
                observation = observation,
                observedSnapshot = observedSnapshot
            )

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
        return artifacts.storeRedactedText(
            kind = "run_summary",
            filenameHint = "run_summary.json",
            content = artifacts.encodeRedactedJson(summaryJson),
            mimeType = "application/json"
        )
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
