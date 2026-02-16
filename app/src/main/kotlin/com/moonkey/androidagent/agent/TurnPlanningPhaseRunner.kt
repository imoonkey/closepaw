package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.agent.cognition.policy.ToolArbitrationResult
import com.moonkey.androidagent.agent.cognition.policy.TurnToolPolicy
import com.moonkey.androidagent.agent.cognition.prompt.PromptBuilder
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.TurnPhase
import com.moonkey.androidagent.protocol.sanitizeThought
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.tool.ToolName
import com.moonkey.androidagent.trace.AgentTrace
import com.moonkey.androidagent.trace.ArbitrationDecision
import com.moonkey.androidagent.trace.DropReason
import com.moonkey.androidagent.trace.DroppedToolCall

internal data class PlanningPhaseOutput(
        val turnResult: TurnResult,
        val arbitration: ToolArbitrationResult
)

internal class TurnPlanningPhaseRunner(
        private val config: AgentExecutionConfig,
        private val services: SessionServices,
        private val eventDispatcher: AgentEventDispatcher,
        private val trace: AgentTrace,
        private val turnPolicyEngine: TurnToolPolicy
) {
        companion object {
                private const val TAG = "TurnPlanningPhase"
        }

        private data class ModelResolution(
                val llmClient: LLMClient,
                val modelId: String,
                val supportsVision: Boolean
        )

        suspend fun runPlanningPhase(
                turnId: String,
                turnNumber: Int,
                snapshot: ScreenSnapshot,
                warnings: List<String>
        ): PlanningPhaseOutput {
                eventDispatcher.turnPhaseChanged(turnId, TurnPhase.PLANNING)
                eventDispatcher.status("🧠 Thinking...")

                val model = resolveTurnModel()

                val turn =
                        Turn(
                                toolRegistry = services.toolRegistry,
                                llmClient = model.llmClient,
                                allowedToolNames = config.allowedToolNames
                        )
                val systemPrompt =
                        requireNotNull(config.systemPrompt) {
                                "System prompt must be provided by AgentDef."
                        }
                val promptBuilder =
                        PromptBuilder(
                                historyManager = services.historyManager,
                                sessionState = services.sessionState,
                                supportsVision = model.supportsVision,
                                perceptionConfig = services.config.perceptionConfig
                        )
                val inputItems =
                        promptBuilder.buildInputItems(
                                snapshot = snapshot,
                                image = snapshot.image,
                                warnings = warnings
                        )

                // Record screen observation for future turns (after prompt built)
                recordScreenObservation(snapshot)

                trace.llmRequest(
                        turnId = turnId,
                        turnNumber = turnNumber,
                        snapshot = snapshot,
                        systemPrompt = systemPrompt,
                        userContextText = "(built by PromptBuilder)",
                        history = services.historyManager.forPrompt(),
                        inputItems = inputItems,
                        modelName = config.modelName,
                        modelId = model.modelId
                )

                var turnResult: TurnResult? = null
                var streamError: Throwable? = null
                turn.runStreaming(
                                systemPrompt = systemPrompt,
                                inputItems = inputItems,
                                model = model.modelId
                        )
                        .collect { event ->
                                when (event) {
                                        is TurnStreamEvent.TextDelta ->
                                                eventDispatcher.messageDelta(turnId, event.text)
                                        is TurnStreamEvent.ToolCallReceived ->
                                                Log.d(
                                                        TAG,
                                                        "Turn $turnNumber: Received tool call: ${event.toolCall.name}"
                                                )
                                        is TurnStreamEvent.Complete -> {
                                                turnResult = event.result
                                                Log.d(
                                                        TAG,
                                                        "Turn $turnNumber: Stream complete, isComplete=${event.result.isComplete}"
                                                )
                                        }
                                        is TurnStreamEvent.Error -> {
                                                streamError = event.error
                                                Log.e(
                                                        TAG,
                                                        "Turn $turnNumber: Stream error",
                                                        event.error
                                                )
                                        }
                                }
                        }

                streamError?.let { throw it }
                val result = turnResult ?: throw RuntimeException("Stream completed without result")

                Log.d(TAG, "Turn $turnNumber: LLM response: ${result.content?.take(200)}...")
                Log.d(TAG, "Turn $turnNumber: Tool calls: ${result.toolCalls.map { it.name }}")

                trace.llmResponse(turnId, turnNumber, result)
                result.content?.let { content ->
                        services.historyManager.addItem(
                                ResponseItem.Message(role = "assistant", content = content)
                        )
                }

                val arbitration = turnPolicyEngine.arbitrateToolCalls(result.toolCalls)
                trace.arbitrationDecision(
                        turnId = turnId,
                        turnNumber = turnNumber,
                        decision = buildArbitrationDecision(result.toolCalls, arbitration)
                )
                emitArbitrationWarnings(turnNumber, arbitration)

                // Extract agent_thought from the first selected tool call for capsule display.
                emitAgentThought(arbitration.selectedToolCalls, turnNumber)

                return PlanningPhaseOutput(turnResult = result, arbitration = arbitration)
        }

        private fun resolveTurnModel(): ModelResolution {
                return when (services.config.llmBackend) {
                        LLMBackendType.LOCAL ->
                                ModelResolution(
                                        llmClient = services.llmClient,
                                        modelId = config.modelName,
                                        supportsVision = false
                                )
                        LLMBackendType.OPENAI -> {
                                val modelEntry = services.modelCatalog.resolve(config.modelName)
                                ModelResolution(
                                        llmClient = services.llmClientFactory.create(config.modelName),
                                        modelId = modelEntry.modelId,
                                        supportsVision = modelEntry.supportsVision
                                )
                        }
                }
        }

        /**
         * Record the current screen observation into history so future turns can see what this turn
         * saw. Called after prompt is built but before the LLM call, so the prompt doesn't
         * duplicate the current screen.
         *
         * In screenshot-only mode the a11y tree is omitted from history to keep the context
         * consistent with what the LLM actually sees.
         */
        private fun recordScreenObservation(snapshot: ScreenSnapshot) {
                val pc = services.config.perceptionConfig
                val text =
                        if (pc.capturesAccessibility) {
                                val screenJson = Perceptor.toPromptJson(snapshot)
                                buildString {
                                        appendLine(
                                                "Screen state (${snapshot.elements.size} elements):"
                                        )
                                        appendLine("```json")
                                        appendLine(screenJson)
                                        append("```")
                                }
                        } else {
                                "(Screenshot-only mode — accessibility tree omitted from history)"
                        }
                services.historyManager.addItem(
                        ResponseItem.Message(
                                role = "user",
                                content = text.trim(),
                                isScreenObservation = true
                        )
                )
        }

        private suspend fun emitArbitrationWarnings(
                turnNumber: Int,
                arbitration: ToolArbitrationResult
        ) {
                val droppedCount = arbitration.droppedToolCalls.size
                if (droppedCount > 0) {
                        val keptNames = arbitration.selectedToolCalls.map { it.name }
                        val droppedNames = arbitration.droppedToolCalls.map { it.name }
                        Log.w(
                                TAG,
                                "Turn $turnNumber: Kept $keptNames, dropped $droppedNames"
                        )
                        eventDispatcher.status(
                                "⚠️ Dropped $droppedCount tool call(s): $droppedNames"
                        )
                }
                if (arbitration.hasCompletionTool && arbitration.hasScreenAction) {
                        Log.w(
                                TAG,
                                "Turn $turnNumber: complete_task returned with screen action; completion deferred"
                        )
                        eventDispatcher.status(
                                "⚠️ Completion returned with screen action; executing action first"
                        )
                }
        }

        /**
         * Extract agent_thought from the first selected tool call and emit it
         * as a ThoughtUpdate event for the Smart Capsule.
         *
         * Fallback chain: agent_thought → tool action description → nothing.
         */
        private suspend fun emitAgentThought(
                selectedToolCalls: List<ToolCallRequest>,
                turnNumber: Int
        ) {
                val firstCall = selectedToolCalls.firstOrNull() ?: return
                val agentThought =
                        firstCall.arguments
                                .optString("agent_thought", "")
                                .trim()
                                .takeIf { it.isNotEmpty() }

                val thought =
                        agentThought
                                ?: ActionDescriptionFormatter.format(firstCall).takeIf {
                                        it.isNotEmpty()
                                }
                                ?: return

                val sanitized = sanitizeThought(thought)
                Log.d(TAG, "Turn $turnNumber: agent_thought = $sanitized")
                eventDispatcher.thoughtUpdate(sanitized)
        }

        private fun buildArbitrationDecision(
                originalCalls: List<ToolCallRequest>,
                arbitration: ToolArbitrationResult
        ): ArbitrationDecision {
                val originalNameCounts = originalCalls.groupingBy { it.name }.eachCount()
                val selectedToolIds = arbitration.selectedToolCalls.map { it.id }.toSet()
                val dropped =
                        originalCalls.filterNot { it.id in selectedToolIds }.map { call ->
                                val reason =
                                        when {
                                                call.name == ToolName.CompleteTask.raw &&
                                                        arbitration.hasScreenAction ->
                                                        DropReason.COMPLETE_TASK_DEFERRED
                                                (originalNameCounts[call.name] ?: 0) > 1 ->
                                                        DropReason.DUPLICATE_TOOL
                                                arbitration.selectedToolCalls.isNotEmpty() ->
                                                        DropReason.MAX_TOOLS_EXCEEDED
                                                else -> DropReason.POLICY_REJECTION
                                        }
                                DroppedToolCall(toolName = call.name, reason = reason)
                        }

                return ArbitrationDecision(
                        selectedTools = arbitration.selectedToolCalls,
                        droppedToolCalls = dropped,
                        selectedToolCount = arbitration.selectedToolCalls.size,
                        originalToolCount = originalCalls.size
                )
        }
}
