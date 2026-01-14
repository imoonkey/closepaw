package com.moonkey.androidagent.orchestration

import com.moonkey.androidagent.orchestration.v3.MobileV3Orchestration
import com.moonkey.androidagent.session.SessionServices

/**
 * OrchestrationFactory - Creates orchestration instances.
 * 
 * This factory allows swapping orchestration strategies without
 * changing the Session implementation. Different factories can
 * create different orchestration types.
 */
fun interface OrchestrationFactory {
    
    /**
     * Create an orchestration instance.
     * 
     * @param config Orchestration configuration
     * @param services Session services (tools, agents, history, etc.)
     * @param eventEmitter Function to emit events
     * @param cancellationSignal Signal that completes when cancellation is requested
     * @return A new AgentOrchestration instance
     */
    fun create(
        config: OrchestrationConfig,
        services: SessionServices,
        eventEmitter: EventEmitter,
        cancellationSignal: CancellationSignal
    ): AgentOrchestration
}

/**
 * Factory for creating MobileV3Orchestration instances.
 * 
 * This is the primary orchestration for Mobile-Agent-v3 style execution
 * with Manager, Executor, and Reflector agents.
 */
class MobileV3OrchestrationFactory : OrchestrationFactory {
    
    override fun create(
        config: OrchestrationConfig,
        services: SessionServices,
        eventEmitter: EventEmitter,
        cancellationSignal: CancellationSignal
    ): AgentOrchestration {
        return MobileV3Orchestration(
            config = config,
            services = services,
            eventEmitter = eventEmitter,
            cancellationSignal = cancellationSignal
        )
    }
}
