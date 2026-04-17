package com.moonkey.androidagent.session

import android.util.Log
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.history.SessionRecordingService
import com.moonkey.androidagent.history.model.CheckpointState
import com.moonkey.androidagent.history.model.ConversationConfigSnapshot
import com.moonkey.androidagent.history.model.HistoryItemConverter
import com.moonkey.androidagent.history.model.SessionRuntimeSnapshot
import com.moonkey.androidagent.history.model.TodoSnapshot
import com.moonkey.androidagent.llm.LocalLLMConfig
import com.moonkey.androidagent.perception.PerceptionConfig
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.ApprovalMode
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionLlmConfig
import com.moonkey.androidagent.protocol.SessionState

/**
 * Coordinates building and persisting LLM context snapshots.
 *
 * Owns the mapping from runtime state → [SessionRuntimeSnapshot].
 * Delegates actual I/O to [SessionRecordingService].
 */
internal class SessionCheckpointCoordinator(
    private val sessionId: String,
    private val config: SessionConfig,
    private val historyManager: HistoryManager,
    private val sessionState: AgentSessionState,
    private val recordingService: SessionRecordingService
) {
    companion object {
        private const val TAG = "CheckpointCoordinator"
    }

    fun scheduleCheckpoint(sessionState: SessionState) {
        val checkpointState = when (sessionState) {
            SessionState.Idle -> CheckpointState.IDLE_READY
            else -> CheckpointState.RUNNING_DIRTY
        }
        recordingService.scheduleCheckpoint { buildSnapshot(checkpointState) }
    }

    suspend fun flushIdleReady(): Boolean {
        val snapshot = buildSnapshot(CheckpointState.IDLE_READY)
        val success = recordingService.forceCheckpoint(snapshot)
        if (success) {
            Log.d(TAG, "Flushed IDLE_READY checkpoint for $sessionId")
        } else {
            Log.e(TAG, "Failed to flush IDLE_READY checkpoint for $sessionId")
        }
        return success
    }

    suspend fun flushClosed(): Boolean {
        val snapshot = buildSnapshot(CheckpointState.CLOSED)
        val success = recordingService.forceCheckpoint(snapshot)
        if (success) {
            Log.d(TAG, "Flushed CLOSED checkpoint for $sessionId")
        } else {
            Log.e(TAG, "Failed to flush CLOSED checkpoint for $sessionId")
        }
        return success
    }

    private fun buildSnapshot(state: CheckpointState): SessionRuntimeSnapshot {
        val items = historyManager.getAll()
        val todos = sessionState.todos.get()
        val scratchpadJson = sessionState.scratchpad.toJsonObject().toString()

        return SessionRuntimeSnapshot(
            sessionId = sessionId,
            config = config.toConfigSnapshot(),
            historyItems = HistoryItemConverter.toRecords(items),
            todos = todos.map { TodoSnapshot(description = it.description, status = it.status.name) },
            scratchpadJson = scratchpadJson,
            checkpointState = state,
            lastCheckpointAt = System.currentTimeMillis()
        )
    }
}

internal fun SessionConfig.toConfigSnapshot() = ConversationConfigSnapshot(
    mainModel = mainModel,
    executorModel = executorModel,
    agentMode = agentMode.name,
    maxTurns = maxTurns,
    perceptionMode = perceptionConfig.toModeString(),
    platformMode = platformMode.name,
    llmBackendType = llm.backendType.name,
    localModelSlug = llm.localConfig?.modelSlug,
    localQuantizationSlug = llm.localConfig?.quantizationSlug,
    actionDelayMs = actionDelayMs,
    approvalMode = approvalMode.name,
    debugMode = debugMode,
    traceEnabled = traceEnabled,
    traceRunId = traceRunId,
    excludedTools = excludedTools.toList()
)

private fun PerceptionConfig.toModeString(): String = when (this) {
    is PerceptionConfig.AccessibilityOnly -> "accessibility_only"
    is PerceptionConfig.ScreenshotOnly -> "screenshot_only"
    is PerceptionConfig.Hybrid -> "hybrid"
}

internal fun ConversationConfigSnapshot.toSessionConfig(): SessionConfig = SessionConfig(
    mainModel = mainModel,
    executorModel = executorModel,
    agentMode = try { AgentMode.valueOf(agentMode) } catch (_: Exception) { AgentMode.PRO },
    maxTurns = maxTurns,
    perceptionConfig = when (perceptionMode) {
        "screenshot_only" -> PerceptionConfig.ScreenshotOnly()
        "hybrid" -> PerceptionConfig.Hybrid()
        else -> PerceptionConfig.AccessibilityOnly
    },
    platformMode = try { PlatformMode.valueOf(platformMode) } catch (_: Exception) { PlatformMode.ACCESSIBILITY },
    llm = SessionLlmConfig(
        backendType = try {
            LLMBackendType.valueOf(llmBackendType)
        } catch (_: Exception) {
            LLMBackendType.OPENAI
        },
        localConfig = if (llmBackendType == LLMBackendType.LOCAL.name) {
            LocalLLMConfig(
                modelSlug = localModelSlug ?: LocalLLMConfig().modelSlug,
                quantizationSlug = localQuantizationSlug ?: LocalLLMConfig().quantizationSlug
            )
        } else {
            null
        }
    ),
    actionDelayMs = actionDelayMs,
    approvalMode = try { ApprovalMode.valueOf(approvalMode) } catch (_: Exception) { ApprovalMode.SMART },
    debugMode = debugMode,
    traceEnabled = traceEnabled,
    traceRunId = traceRunId,
    excludedTools = excludedTools.toSet()
)
