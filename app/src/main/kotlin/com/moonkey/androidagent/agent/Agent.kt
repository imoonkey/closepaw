package com.moonkey.androidagent.agent

import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.session.SessionServices
import kotlinx.coroutines.CompletableDeferred

/**
 * Agent - Public facade for running a single ReAct agent session.
 *
 * Implementation lives in [AgentRuntime] to keep files small and focused.
 */
class Agent(
    private val config: AgentConfig,
    private val services: SessionServices,
    private val eventEmitter: suspend (AgentEvent) -> Unit,
    private val cancellationSignal: CompletableDeferred<AgentStopReason>
) {
    private val runtime = AgentRuntime(config, services, eventEmitter, cancellationSignal)

    suspend fun run(): AgentStopReason = runtime.run()

    suspend fun pause() = runtime.pause()

    suspend fun resume() = runtime.resume()

    fun stop() = runtime.stop()
}

