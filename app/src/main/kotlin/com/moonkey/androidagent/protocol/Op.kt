package com.moonkey.androidagent.protocol

import com.moonkey.androidagent.llm.LocalLLMConfig
import com.moonkey.androidagent.perception.PerceptionConfig

/**
 * Op - Operations sent from the UI layer to the agent session.
 * 
 * This defines the "Submission Queue" (SQ) in the Codex pattern.
 * All user intents are expressed as operations submitted to the session.
 * 
 * Operations are:
 * - Immutable data classes
 * - Thread-safe to create and pass around
 * - Processed asynchronously by the session
 */
sealed interface Op {
    
    // ===== Session Lifecycle =====
    
    /**
     * Start the agent with a goal.
     * 
     * @deprecated Use UserInput to start a task instead. Kept for backward compatibility.
     * Maps to UserInput(goal).
     * 
     * Valid in: Created state
     * Transitions to: Running state
     */
    @Deprecated("Use UserInput instead", ReplaceWith("UserInput(goal)"))
    data class Start(
        /** The user's goal/instruction for the agent */
        val goal: String
    ) : Op
    
    /**
     * Pause execution cooperatively.
     * 
     * The agent will complete its current action, then pause.
     * Valid in: Running state
     * Transitions to: Paused state
     */
    data object Pause : Op
    
    /**
     * Resume from pause.
     * 
     * Valid in: Paused state
     * Transitions to: Running state
     */
    data object Resume : Op
    
    /**
     * Interrupt the current turn.
     * 
     * Note: Interrupt is cooperative - the agent will complete its current
     * action before stopping. True cancellation of in-flight LLM calls is
     * not supported.
     * 
     * Valid in: Running state
     * Stays in: Running state (ready for next turn)
     */
    data object Interrupt : Op
    
    /**
     * Shutdown the session gracefully.
     * 
     * Valid in: Any state
     * Transitions to: Shutdown state
     */
    data object Shutdown : Op
    
    // ===== User Interaction =====
    
    /**
     * User provides input to the agent.
     * 
     * - If session is idle, starts a new Task.
     * - If session is running, provides input to the current Task (if supported).
     * 
     * This is the primary way to interact with the agent in Chat Mode.
     */
    data class UserInput(
        val text: String
    ) : Op
    
    /**
     * User responds to an approval request.
     */
    data class Approve(
        /** ID of the action being approved/denied */
        val actionId: String,
        
        /** User's decision */
        val decision: ApprovalDecision
    ) : Op
}

/**
 * SessionConfig - Configuration for an agent session.
 * 
 * Contains all settings that affect session behavior.
 * Immutable after session creation.
 */
data class SessionConfig(
    /** Maximum number of turns before auto-stopping */
    val maxTurns: Int = 50,
    
    /** Delay between actions in milliseconds (for UI to settle) */
    val actionDelayMs: Long = 2000,
    
    /** Approval mode for tool execution */
    val approvalMode: ApprovalMode = ApprovalMode.SMART,
    
    /** LLM model to use (for cloud backends) — kept for backward compat with UI.
     * @deprecated Use [mainModel] instead. Will be removed when UI migrates.
     */
    @Deprecated("Use mainModel instead", replaceWith = ReplaceWith("mainModel"))
    val model: String = "gpt-5.2",

    /** LLM backend type (cloud or local) — kept for backward compat with UI.
     * @deprecated Will be removed when local LLM path migrates to ModelCatalog.
     */
    @Deprecated("Will be removed when local models migrate to catalog")
    val llmBackend: LLMBackendType = LLMBackendType.OPENAI,

    /** Execution mode for main agent orchestration */
    val agentMode: AgentMode = AgentMode.PRO,

    /** Local LLM configuration (used when llmBackend is LOCAL) — kept for backward compat.
     * @deprecated Will be removed when local LLM path migrates to ModelCatalog.
     */
    @Deprecated("Will be removed when local models migrate to catalog")
    val localLLMConfig: LocalLLMConfig? = null,
    
    /** Enable verbose debug logging */
    val debugMode: Boolean = false,

    /** Persist a full JSONL trace (for inspection_tool) */
    val traceEnabled: Boolean = false,

    /** Trace run id (folder name) for correlating host/device artifacts */
    val traceRunId: String? = null,

    /** Controls which perception modalities (a11y tree, screenshot, both) are active */
    val perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT,

    /**
     * Primary model name (key in llm_models.json) for standalone/planner agents.
     *
     * Defaults to [model] for backward compatibility: when the UI sets only
     * [model], [mainModel] inherits the same value. When constructing configs
     * programmatically, prefer setting [mainModel] directly.
     */
    @Suppress("DEPRECATION")
    val mainModel: String = model,

    /**
     * Model name (key in llm_models.json) for executor agents in planner/executor mode.
     * When null, executor agents fall back to [mainModel].
     *
     * Typical usage: set a cheaper/faster model here while [mainModel] uses a
     * more capable model for planning.
     */
    val executorModel: String? = null
)

/**
 * Agent execution mode.
 */
enum class AgentMode {
    /** Single standalone agent with direct UI tools. */
    BASIC,

    /** Planner + delegated executor flow. */
    PRO
}

/**
 * LLM backend type - determines which LLM client to use.
 */
enum class LLMBackendType {
    /** Use OpenAI cloud API */
    OPENAI,
    
    /** Use local on-device LLM via Leap SDK */
    LOCAL
}

/**
 * ApprovalMode - How tool execution approvals are handled.
 */
enum class ApprovalMode {
    /** Always ask user before executing any tool */
    ALWAYS_ASK,
    
    /** Never ask, auto-approve all tools */
    AUTO_APPROVE,
    
    /** Smart mode: auto-approve low-risk, ask for high-risk */
    SMART
}
