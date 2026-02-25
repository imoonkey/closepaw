package com.moonkey.androidagent.protocol

import com.moonkey.androidagent.llm.LocalLLMConfig
import com.moonkey.androidagent.perception.PerceptionConfig

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
        /** Execution mode for main agent orchestration */
        val agentMode: AgentMode = AgentMode.PRO,
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
         * Primary model name (key in llm_models.json) for standalone/planner agents.
         *
         * Construct configs with [mainModel] directly.
         */
        val mainModel: String = "glm-5",
        /**
         * Model name (key in llm_models.json) for executor agents in planner/executor mode.
         * When null, executor agents fall back to [mainModel].
         *
         * Typical usage: set a cheaper/faster model here while [mainModel] uses a
         * more capable model for planning.
         */
        val executorModel: String? = null,
        /** Platform mode: real screen (accessibility) or virtual display (Shizuku) */
        val platformMode: PlatformMode = PlatformMode.ACCESSIBILITY,
        /** Tool names to exclude from the agent's allowed tool set (e.g. for eval) */
        val excludedTools: Set<String> = emptySet()
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

/** Agent execution mode. */
enum class AgentMode {
        /** Single standalone agent with direct UI tools. */
        BASIC,
        /** Planner + delegated executor flow. */
        PRO
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
