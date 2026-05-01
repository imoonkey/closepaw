package ai.closepaw.agent

import android.util.Log
import ai.closepaw.agent.cognition.skills.ActivationResult
import ai.closepaw.agent.cognition.policy.ToolArbitrationResult
import ai.closepaw.agent.cognition.policy.TurnToolPolicy
import ai.closepaw.agent.cognition.prompt.PromptBuilder
import ai.closepaw.agent.cognition.prompt.TurnObservation
import ai.closepaw.history.MessageKind
import ai.closepaw.history.ResponseItem
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.protocol.TurnPhase
import ai.closepaw.protocol.compactThought
import ai.closepaw.session.SessionServices
import ai.closepaw.tool.ToolName
import ai.closepaw.trace.AgentTrace
import ai.closepaw.trace.ArbitrationDecision
import ai.closepaw.trace.DropReason
import ai.closepaw.trace.DroppedToolCall

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
        private val modelResolver =
                AgentModelResolver(
                        sessionLlmClient = services.llmClient,
                        modelCatalog = services.modelCatalog,
                        llmClientFactory = services.llmClientFactory
                )

        suspend fun runPlanningPhase(
                turnId: String,
                turnNumber: Int,
                snapshot: ScreenSnapshot,
                currentPackageName: String?,
                warnings: List<String>
        ): PlanningPhaseOutput {
                eventDispatcher.turnPhaseChanged(turnId, TurnPhase.PLANNING)
                eventDispatcher.status("🧠 Thinking...")

                val model = modelResolver.resolve(config.modelName)

                val turn =
                        Turn(
                                toolRegistry = services.toolRegistry,
                                llmClient = model.llmClient,
                                allowedToolNames = config.allowedToolNames
                        )
                val systemPrompt =
                        requireNotNull(config.systemPrompt) {
                                "System prompt must be provided by AgentRoleDef."
                        }

                // Activate any /skill-name mentions in the goal before prompt build.
                val activationResults = services.agentSkillManager.activateExplicitMentions(config.goal)
                val activatedSkillBodies = activationResults
                        .filterIsInstance<ActivationResult.Success>()
                        .joinToString("\n\n") { "## Skill: ${it.name}\n${it.body}" }
                        .takeIf { it.isNotEmpty() }

                // Catalog (one-liner descriptions) stays in system prompt.
                // Activated skill BODIES go into user-role messages (lower priority).
                val catalogSection = services.agentSkillManager.catalogPrompt()
                val fullSystemPrompt = buildString {
                        append(systemPrompt)
                        if (catalogSection != null) {
                                append("\n\n")
                                append(catalogSection)
                        }
                }

                // Canonical observation — computed once, consumed by prompt and history.
                val observation = TurnObservation.capture(
                        snapshot = snapshot,
                        perceptionConfig = services.config.perceptionConfig
                )

                val promptBuilder =
                        PromptBuilder(
                                historyManager = services.historyManager,
                                sessionState = services.sessionState,
                                supportsVision = model.supportsVision
                        )
                val appSkill = buildAppSkillMessage(currentPackageName)
                val recalledMemory = services.memoryRecaller.recall(currentPackageName)
                val inputItems =
                        promptBuilder.buildInputItems(
                                observation = observation,
                                warnings = warnings,
                                turnNumber = turnNumber,
                                maxTurns = config.maxTurns,
                                appSkill = appSkill,
                                recalledMemory = recalledMemory,
                                activatedAgentSkills = activatedSkillBodies
                        )

                // Record screen observation for future turns.
                // Uses the same canonical screenBlock — no ordering dependency.
                services.historyManager.addItem(
                        ResponseItem.Message(
                                kind = MessageKind.SCREEN_OBSERVATION,
                                content = observation.screenBlock.trim()
                        )
                )

                trace.llmRequest(
                        turnId = turnId,
                        turnNumber = turnNumber,
                        snapshot = snapshot,
                        systemPrompt = fullSystemPrompt,
                        userContextText = "(built by PromptBuilder)",
                        history = services.historyManager.forPrompt(),
                        inputItems = inputItems,
                        modelName = config.modelName,
                        modelId = model.modelId
                )

                var turnResult: TurnResult? = null
                var streamError: Throwable? = null
                turn.runStreaming(
                                systemPrompt = fullSystemPrompt,
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
                                ResponseItem.Message(kind = MessageKind.ASSISTANT_TEXT, content = content)
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

        private fun buildAppSkillMessage(currentPackageName: String?): String? {
                val packageName = currentPackageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val skillBody = services.appSkillRepository.load(packageName)
                Log.d(TAG, "App skill lookup: pkg=$packageName, found=${skillBody != null}")
                if (skillBody == null) return null
                return buildString {
                        appendLine("## App Skill")
                        appendLine("Package: $packageName")
                        appendLine()
                        append(skillBody)
                }.trim()
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

                val full = thought.trim()
                val compact = compactThought(full)
                Log.d(TAG, "Turn $turnNumber: agent_thought = $compact")
                eventDispatcher.thoughtUpdate(full = full, compact = compact)
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
