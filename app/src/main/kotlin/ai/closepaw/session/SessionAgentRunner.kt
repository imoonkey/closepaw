package ai.closepaw.session

import android.content.res.Resources
import android.os.Build
import android.util.Log
import ai.closepaw.agent.Agent
import ai.closepaw.agent.AgentEventDispatcher
import ai.closepaw.agent.AgentExecutionConfig
import ai.closepaw.agent.AgentExecutionRole
import ai.closepaw.agent.AgentStopReason
import ai.closepaw.agent.definition.AgentDefRegistry
import ai.closepaw.agent.definition.ResolvedAgentRole
import ai.closepaw.agent.subagent.IsolatedSubAgentRunner
import ai.closepaw.protocol.AgentEvent
import ai.closepaw.protocol.SessionId
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.tool.ToolName
import ai.closepaw.tool.impl.DelegateTaskTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

internal class SessionAgentRunner(
    private val scope: CoroutineScope,
    private val services: SessionServices,
    private val sessionId: SessionId,
    private val config: SessionConfig,
    private val emitEvent: suspend (AgentEvent) -> Unit,
) {
    companion object {
        private const val TAG = "SessionAgentRunner"
    }

    private data class RunnerState(
        val agent: Agent?,
        val agentJob: Job?,
        val cancellationSignal: CompletableDeferred<AgentStopReason>?
    )

    private val stateLock = Any()
    private var state = RunnerState(agent = null, agentJob = null, cancellationSignal = null)
    private val eventDispatcher = AgentEventDispatcher(sessionId = sessionId, eventEmitter = emitEvent)

    /**
     * Completion signals from the runner.
     * AgentSession must consume these through its serialized lifecycle path —
     * the runner never mutates session state directly.
     */
    val completions = Channel<AgentStopReason>(capacity = Channel.BUFFERED)

    fun start(taskInput: String, taskId: String) {
        val agentDef = AgentDefRegistry.main
        // Read excludedTools from services.config — SessionServices.create stamps the user-pref
        // tool gates (e.g. browser_script when off) into that copy. The local `config` field is
        // the original, pre-merge SessionConfig and would re-expose the gated tool to the LLM.
        val resolvedAgentDef: ResolvedAgentRole = agentDef.resolve(
            snapshot = services.termuxSnapshot,
            excludedTools = services.config.excludedTools.toToolNames()
        )

        if (ToolName.DelegateTask.raw in resolvedAgentDef.allowedToolNames) {
            ensureDelegationToolRegistered()
        }
        ensureAskUserToolRegistered()

        val signal = CompletableDeferred<AgentStopReason>()

        // Resolve model name based on agent role
        val modelName = when (resolvedAgentDef.executionRole) {
            AgentExecutionRole.STANDALONE,
            AgentExecutionRole.PLANNER -> config.mainModel
            AgentExecutionRole.EXECUTOR -> config.executorModel ?: config.mainModel
        }

        val agentConfig = AgentExecutionConfig(
            goal = taskInput,
            sessionId = sessionId,
            taskId = taskId,
            maxTurns = config.maxTurns,
            uiSettleDelayMs = config.actionDelayMs,
            debugMode = config.debugMode,
            systemPrompt = resolvePromptTemplates(resolvedAgentDef.systemPrompt),
            allowedToolNames = resolvedAgentDef.allowedToolNames,
            agentId = sessionId.value,
            agentRole = resolvedAgentDef.executionRole,
            modelName = modelName
        )

        val newAgent = Agent(
            config = agentConfig,
            services = services,
            eventEmitter = { event -> emitEvent(event) },
            cancellationSignal = signal
        )

        // Pre-register state so cancel/stop sees the agent even if the coroutine
        // starts before we capture the Job reference.
        synchronized(stateLock) {
            state = RunnerState(agent = newAgent, agentJob = null, cancellationSignal = signal)
        }

        val newAgentJob = scope.launch {
            try {
                val result = newAgent.run()
                deliverCompletion(result)
            } catch (e: CancellationException) {
                if (signal.isCompleted) {
                    Log.d(TAG, "Agent cancelled by user request")
                    deliverCompletion(AgentStopReason.UserRequested)
                } else {
                    Log.e(TAG, "Agent cancelled unexpectedly", e)
                    deliverCompletion(AgentStopReason.Error(e.message ?: "Agent cancelled"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Agent error", e)
                deliverCompletion(AgentStopReason.Error(e.message ?: "Unknown error"))
            }
        }
        synchronized(stateLock) {
            state = state.copy(agentJob = newAgentJob)
        }

        Log.d(TAG, "Started agent for task $taskId")
    }

    private fun deliverCompletion(reason: AgentStopReason) {
        completions.trySend(reason)
    }

    private fun resolvePromptTemplates(prompt: String): String {
        val dm = try { Resources.getSystem().displayMetrics } catch (_: Exception) { null }
        val today = LocalDate.now()
        val dayOfWeek = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return prompt
            .replace("{{device_model}}", Build.MODEL ?: "unknown")
            .replace("{{device_manufacturer}}", Build.MANUFACTURER ?: "unknown")
            .replace("{{screen_width}}", (dm?.widthPixels ?: 0).toString())
            .replace("{{screen_height}}", (dm?.heightPixels ?: 0).toString())
            .replace("{{current_date}}", "$today, $dayOfWeek")
    }

    private fun ensureDelegationToolRegistered() {
        if (services.toolRegistry.contains("delegate_task")) return

        val delegatableRoles = AgentDefRegistry.delegatableRoles()
        val delegateTool = DelegateTaskTool(
            delegatableRoles = delegatableRoles,
            runnerFactory = { roleDef ->
                IsolatedSubAgentRunner(
                    roleDef = roleDef,
                    parentServices = services,
                    parentSessionId = sessionId,
                    eventDispatcher = eventDispatcher
                )
            },
            eventDispatcher = eventDispatcher
        )
        services.toolRegistry.register(delegateTool)
    }

    private fun ensureAskUserToolRegistered() {
        if (services.toolRegistry.contains("ask_user")) return

        val askUserTool = ai.closepaw.tool.impl.AskUserTool(
            responseChannel = services.userResponseChannel,
            eventDispatcher = eventDispatcher
        )
        services.toolRegistry.register(askUserTool)
    }

    private fun Set<String>.toToolNames(): Set<ToolName> = map { ToolName.from(it) }.toSet()

    suspend fun pause(): Deferred<Unit> {
        val currentAgent = synchronized(stateLock) { state.agent }
        return currentAgent?.pause() ?: CompletableDeferred<Unit>().also { it.complete(Unit) }
    }

    suspend fun resume() {
        val currentAgent = synchronized(stateLock) { state.agent }
        currentAgent?.resume()
    }

    fun stop() {
        val currentAgent = synchronized(stateLock) { state.agent }
        currentAgent?.stop()
    }

    /** Cancel the agent job immediately (for interrupt during tool suspension). */
    fun cancelJob() {
        val snapshot = synchronized(stateLock) { state }
        // Complete signal before cancel so the catch block sees isCompleted == true
        snapshot.cancellationSignal?.complete(AgentStopReason.UserRequested)
        snapshot.agentJob?.cancel()
    }

    fun shutdown() {
        val snapshot =
            synchronized(stateLock) {
                val current = state
                state = RunnerState(agent = null, agentJob = null, cancellationSignal = null)
                current
            }
        snapshot.agent?.stop()
        // Complete signal before cancel so the catch block sees isCompleted == true
        snapshot.cancellationSignal?.complete(AgentStopReason.UserRequested)
        snapshot.agentJob?.cancel()
    }

    fun clear() {
        synchronized(stateLock) {
            state = RunnerState(agent = null, agentJob = null, cancellationSignal = null)
        }
    }
}
