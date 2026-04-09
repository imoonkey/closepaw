package com.moonkey.androidagent.session

import android.content.res.Resources
import android.os.Build
import android.util.Log
import com.moonkey.androidagent.agent.Agent
import com.moonkey.androidagent.agent.AgentExecutionConfig
import com.moonkey.androidagent.agent.AgentExecutionRole
import com.moonkey.androidagent.agent.AgentStopReason
import com.moonkey.androidagent.agent.definition.AgentDefRegistry
import com.moonkey.androidagent.agent.subagent.IsolatedSubAgentRunner
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.SessionId
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.tool.impl.DelegateTaskTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

internal class SessionAgentRunner(
    private val scope: CoroutineScope,
    private val services: SessionServices,
    private val sessionId: SessionId,
    private val config: SessionConfig,
    private val emitEvent: suspend (AgentEvent) -> Unit,
    private val onComplete: suspend (AgentStopReason) -> Unit
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

    fun start(taskInput: String, taskId: String) {
        val agentDef = AgentDefRegistry.mainFor(config.agentMode)
        if ("delegate_task" in agentDef.allowedTools) {
            ensureDelegationToolRegistered()
        }
        ensureAskUserToolRegistered()

        val signal = CompletableDeferred<AgentStopReason>()

        // Resolve model name based on agent role
        val modelName = when (agentDef.executionRole) {
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
            systemPrompt = resolvePromptTemplates(agentDef.systemPrompt),
            allowedToolNames = agentDef.allowedTools - config.excludedTools,
            agentId = sessionId.value,
            agentRole = agentDef.executionRole,
            modelName = modelName
        )

        val newAgent = Agent(
            config = agentConfig,
            services = services,
            eventEmitter = { event -> emitEvent(event) },
            cancellationSignal = signal
        )

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
            state = RunnerState(agent = newAgent, agentJob = newAgentJob, cancellationSignal = signal)
        }

        Log.d(TAG, "Started agent for task $taskId")
    }

    private suspend fun deliverCompletion(reason: AgentStopReason) {
        withContext(NonCancellable) {
            onComplete(reason)
        }
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
            sessionId = sessionId,
            delegatableRoles = delegatableRoles,
            runnerFactory = { roleDef ->
                IsolatedSubAgentRunner(
                    roleDef = roleDef,
                    parentServices = services,
                    parentSessionId = sessionId,
                    eventEmitter = emitEvent
                )
            },
            eventEmitter = emitEvent
        )
        services.toolRegistry.register(delegateTool)
    }

    private fun ensureAskUserToolRegistered() {
        if (services.toolRegistry.contains("ask_user")) return

        val dispatcher = com.moonkey.androidagent.agent.AgentEventDispatcher(
            sessionId = sessionId,
            eventEmitter = emitEvent
        )
        val askUserTool = com.moonkey.androidagent.tool.impl.AskUserTool(
            responseChannel = services.userResponseChannel,
            eventDispatcher = dispatcher
        )
        services.toolRegistry.register(askUserTool)
    }

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

    fun shutdown() {
        val snapshot =
            synchronized(stateLock) {
                val current = state
                state = RunnerState(agent = null, agentJob = null, cancellationSignal = null)
                current
            }
        snapshot.agent?.stop()
        snapshot.agentJob?.cancel()
        snapshot.cancellationSignal?.complete(AgentStopReason.UserRequested)
    }

    fun clear() {
        synchronized(stateLock) {
            state = RunnerState(agent = null, agentJob = null, cancellationSignal = null)
        }
    }
}
