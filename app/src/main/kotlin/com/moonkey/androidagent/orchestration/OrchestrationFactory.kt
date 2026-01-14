package com.moonkey.androidagent.orchestration

import android.accessibilityservice.AccessibilityService
import com.moonkey.androidagent.orchestration.legacy.LegacyOrchestrationAdapter
import com.moonkey.androidagent.orchestration.v3.MobileV3Orchestration
import com.moonkey.androidagent.session.SessionServices
import kotlinx.coroutines.CoroutineScope

/**
 * OrchestrationFactory - Creates orchestration instances.
 * 
 * This factory allows swapping orchestration strategies without
 * changing the Session implementation. Different factories can
 * create different orchestration types:
 * 
 * - MobileV3OrchestrationFactory: Multi-agent (Manager, Executor, Reflector)
 * - LegacyOrchestrationFactory: Wraps existing AgentOrchestrator
 * - SingleAgentOrchestrationFactory: Simple single-agent loop
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

/**
 * Factory for creating LegacyOrchestrationAdapter instances.
 * 
 * This wraps the existing AgentOrchestrator as an AgentOrchestration,
 * allowing for gradual migration and A/B testing.
 * 
 * @param service AccessibilityService required for legacy orchestrator
 * @param scope CoroutineScope for the legacy orchestrator
 */
class LegacyOrchestrationFactory(
    private val service: AccessibilityService,
    private val scope: CoroutineScope
) : OrchestrationFactory {
    
    override fun create(
        config: OrchestrationConfig,
        services: SessionServices,
        eventEmitter: EventEmitter,
        cancellationSignal: CancellationSignal
    ): AgentOrchestration {
        return LegacyOrchestrationAdapter(
            goal = config.goal,
            service = service,
            scope = scope,
            eventEmitter = eventEmitter,
            sessionId = config.sessionId
        )
    }
}

/**
 * Factory that selects orchestration based on configuration.
 * 
 * Uses SessionConfig.useNewOrchestration to decide which factory to use.
 * 
 * @param service AccessibilityService for legacy fallback
 * @param scope CoroutineScope for operations
 */
class AutoSelectingOrchestrationFactory(
    private val service: AccessibilityService,
    private val scope: CoroutineScope
) : OrchestrationFactory {
    
    private val v3Factory = MobileV3OrchestrationFactory()
    private val legacyFactory = LegacyOrchestrationFactory(service, scope)
    
    override fun create(
        config: OrchestrationConfig,
        services: SessionServices,
        eventEmitter: EventEmitter,
        cancellationSignal: CancellationSignal
    ): AgentOrchestration {
        val useNew = services.config.useNewOrchestration
        
        return if (useNew) {
            v3Factory.create(config, services, eventEmitter, cancellationSignal)
        } else {
            legacyFactory.create(config, services, eventEmitter, cancellationSignal)
        }
    }
}


