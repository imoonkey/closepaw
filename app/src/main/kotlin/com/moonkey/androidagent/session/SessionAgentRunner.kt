package com.moonkey.androidagent.session

import android.util.Log
import com.moonkey.androidagent.agent.Agent
import com.moonkey.androidagent.agent.AgentExecutionConfig
import com.moonkey.androidagent.agent.AgentExecutionRole
import com.moonkey.androidagent.agent.AgentStopReason
import com.moonkey.androidagent.agent.definition.AgentDefRegistry
import com.moonkey.androidagent.agent.subagent.AgentRegistry
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
import kotlinx.coroutines.launch

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

    private var agent: Agent? = null
    private var agentJob: Job? = null
    private var cancellationSignal: CompletableDeferred<AgentStopReason>? = null

    fun start(taskInput: String, taskId: String) {
        val agentDef = AgentDefRegistry.mainFor(config.agentMode)
        if (agentDef.requiresDelegationToolRegistration) {
            ensureDelegationToolRegistered()
        }
        ensureAskUserToolRegistered()

        val signal = CompletableDeferred<AgentStopReason>()
        cancellationSignal = signal

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
            systemPrompt = agentDef.systemPrompt,
            allowedToolNames = agentDef.allowedTools,
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
        agent = newAgent

        agentJob = scope.launch {
            try {
                val result = newAgent.run()
                onComplete(result)
            } catch (e: CancellationException) {
                Log.d(TAG, "Agent cancelled")
                onComplete(AgentStopReason.UserRequested)
            } catch (e: Exception) {
                Log.e(TAG, "Agent error", e)
                onComplete(AgentStopReason.Error(e.message ?: "Unknown error"))
            }
        }

        Log.d(TAG, "Started agent for task $taskId")
    }

    private fun ensureDelegationToolRegistered() {
        if (services.toolRegistry.contains("delegate_task")) return

        val registry = AgentRegistry.createDefault()
        val delegateTool = DelegateTaskTool(
            sessionId = sessionId,
            registry = registry,
            runnerFactory = { definition ->
                IsolatedSubAgentRunner(
                    definition = definition,
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
        return agent?.pause() ?: CompletableDeferred<Unit>().also { it.complete(Unit) }
    }

    suspend fun resume() {
        agent?.resume()
    }

    fun stop() {
        agent?.stop()
    }

    fun shutdown() {
        agent?.stop()
        agent = null
        agentJob?.cancel()
        agentJob = null
        cancellationSignal?.complete(AgentStopReason.UserRequested)
        cancellationSignal = null
    }

    fun clear() {
        agent = null
        agentJob = null
        cancellationSignal = null
    }
}
