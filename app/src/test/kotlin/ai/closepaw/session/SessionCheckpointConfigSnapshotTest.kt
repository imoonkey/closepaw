package ai.closepaw.session

import com.google.common.truth.Truth.assertThat
import ai.closepaw.llm.LocalLLMConfig
import ai.closepaw.perception.PerceptionConfig
import ai.closepaw.protocol.AgentMode
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionLlmConfig
import org.junit.Test

class SessionCheckpointConfigSnapshotTest {

    @Test
    fun `config snapshot round trip preserves llm routing`() {
        val config =
            SessionConfig(
                mainModel = "main-model",
                executorModel = "executor-model",
                agentMode = AgentMode.BASIC,
                maxTurns = 42,
                perceptionConfig = PerceptionConfig.Hybrid(),
                platformMode = PlatformMode.VIRTUAL_DISPLAY,
                llm =
                    SessionLlmConfig(
                        backendType = LLMBackendType.LOCAL,
                        localConfig =
                            LocalLLMConfig(
                                modelSlug = "LFM2.5-1.2B-Instruct",
                                quantizationSlug = "Q5_K_M"
                            )
                    )
            )

        val snapshot = config.toConfigSnapshot()
        val restored = snapshot.toSessionConfig()

        assertThat(restored.mainModel).isEqualTo("main-model")
        assertThat(restored.executorModel).isEqualTo("executor-model")
        assertThat(restored.agentMode).isEqualTo(AgentMode.BASIC)
        assertThat(restored.maxTurns).isEqualTo(42)
        assertThat(restored.perceptionConfig).isInstanceOf(PerceptionConfig.Hybrid::class.java)
        assertThat(restored.platformMode).isEqualTo(PlatformMode.VIRTUAL_DISPLAY)
        assertThat(restored.llm.backendType).isEqualTo(LLMBackendType.LOCAL)
        assertThat(restored.llm.localConfig?.modelSlug).isEqualTo("LFM2.5-1.2B-Instruct")
        assertThat(restored.llm.localConfig?.quantizationSlug).isEqualTo("Q5_K_M")
    }

    @Test
    fun `config snapshot round trip preserves runtime affecting fields`() {
        val config =
            SessionConfig(
                actionDelayMs = 1234L,
                approvalMode = ApprovalMode.ALWAYS_ASK,
                debugMode = true,
                traceEnabled = true,
                traceRunId = "run-xyz",
                excludedTools = setOf("open_app", "shell")
            )

        val snapshot = config.toConfigSnapshot()
        val restored = snapshot.toSessionConfig()

        assertThat(restored.actionDelayMs).isEqualTo(1234L)
        assertThat(restored.approvalMode).isEqualTo(ApprovalMode.ALWAYS_ASK)
        assertThat(restored.debugMode).isTrue()
        assertThat(restored.traceEnabled).isTrue()
        assertThat(restored.traceRunId).isEqualTo("run-xyz")
        assertThat(restored.excludedTools).containsExactly("open_app", "shell")
    }
}

