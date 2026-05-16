package ai.closepaw.protocol

import ai.closepaw.llm.LocalLLMConfig
import ai.closepaw.perception.PerceptionConfig

/**
 * SessionConfig - Configuration for an agent session.
 *
 * Contains all settings that affect session behavior.
 * Immutable after session creation.
 */
data class SessionConfig(
        /** Delay between actions in milliseconds (for UI to settle) */
        val actionDelayMs: Long = 2000,
        /** Approval mode for tool execution */
        val approvalMode: ApprovalMode = ApprovalMode.SMART,
        /**
         * Canonical LLM runtime routing config (backend + local model params).
         */
        val llm: SessionLlmConfig =
                SessionLlmConfig(
                        backendType = LLMBackendType.OPENAI,
                        localConfig = null
                ),
        /** Enable verbose debug logging */
        val debugMode: Boolean = false,
        /** Persist a full JSONL trace (for inspection_tool) */
        val traceEnabled: Boolean = false,
        /** Trace run id (folder name) for correlating host/device artifacts */
        val traceRunId: String? = null,
        /** Controls which perception modalities (a11y tree, screenshot, both) are active */
        val perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT,
        /**
         * Primary model name (key in llm_models.json) for the main agent.
         * Subagents inherit this model — there is no separate subagent model.
         */
        val mainModel: String = "glm-5",
        /** Platform mode: real screen (accessibility) or virtual display (Shizuku) */
        val platformMode: PlatformMode = PlatformMode.ACCESSIBILITY,
        /** Tool names to exclude from the agent's allowed tool set (e.g. for eval) */
        val excludedTools: Set<String> = emptySet(),
        /**
         * Eval-only safety net plumbed to [ai.closepaw.agent.AgentExecutionConfig.evalTurnBudget].
         * Production leaves this null; eval bridge sets it from yaml `max_turns:`.
         */
        val evalTurnBudget: Int? = null
)

/** Canonical LLM routing config used at runtime. */
data class SessionLlmConfig(
        val backendType: LLMBackendType = LLMBackendType.OPENAI,
        val localConfig: LocalLLMConfig? = null
)

/** Platform mode — which display the agent operates on. */
enum class PlatformMode {
        /** Agent operates on the real screen via AccessibilityService. */
        ACCESSIBILITY,
        /** Agent operates on a Shizuku-powered virtual display. */
        VIRTUAL_DISPLAY
}

/** LLM backend type - determines which LLM client to use. */
enum class LLMBackendType {
        /** Use OpenAI cloud API */
        OPENAI,
        /** Use local on-device LLM via Leap SDK */
        LOCAL
}

/** ApprovalMode - How tool execution approvals are handled. */
enum class ApprovalMode {
        /** Always ask user before executing any tool */
        ALWAYS_ASK,
        /** Never ask, auto-approve all tools */
        AUTO_APPROVE,
        /** Smart mode: auto-approve low-risk, ask for high-risk */
        SMART
}
