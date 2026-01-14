package com.moonkey.androidagent.protocol

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
     * Valid in: Created state
     * Transitions to: Running state
     */
    data class Start(
        /** The user's goal/instruction for the agent */
        val goal: String,
        
        /** Session configuration */
        val config: SessionConfig = SessionConfig()
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
     * Aborts the current action but keeps the session alive.
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
     * User provides additional input during execution.
     * 
     * Can be used for clarification or guidance.
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
    
    /** LLM model to use */
    val model: String = "gpt-4o",
    
    /** Enable verbose debug logging */
    val debugMode: Boolean = false
)

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

