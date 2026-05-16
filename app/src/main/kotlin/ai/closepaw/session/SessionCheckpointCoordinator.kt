package ai.closepaw.session

import android.util.Log
import ai.closepaw.history.HistoryManager
import ai.closepaw.history.SessionRecordingService
import ai.closepaw.history.model.CheckpointState
import ai.closepaw.history.model.ConversationConfigSnapshot
import ai.closepaw.history.model.HistoryItemConverter
import ai.closepaw.history.model.SessionRuntimeSnapshot
import ai.closepaw.history.model.TodoSnapshot
import ai.closepaw.llm.LocalLLMConfig
import ai.closepaw.perception.PerceptionConfig
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionLlmConfig
import ai.closepaw.protocol.SessionState

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
            schemaVersion = 2,
            sessionId = sessionId,
            config = config.toConfigSnapshot(),
            historyItems = HistoryItemConverter.toRecords(items),
            todos = todos.map { TodoSnapshot(description = it.description, status = it.status.name) },
            scratchpadJson = scratchpadJson,
            checkpointState = state,
            lastCheckpointAt = System.currentTimeMillis(),
            lastTaskOutcome = recordingService.getLastTaskOutcome()?.name
        )
    }
}

internal fun SessionConfig.toConfigSnapshot() = ConversationConfigSnapshot(
    mainModel = mainModel,
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
    perceptionConfig = when (perceptionMode) {
        "screenshot_only" -> PerceptionConfig.ScreenshotOnly()
        "hybrid" -> PerceptionConfig.Hybrid()
        else -> PerceptionConfig.AccessibilityOnly
    },
    platformMode = try { PlatformMode.valueOf(platformMode) } catch (_: Exception) {
        Log.w(SNAPSHOT_TAG, "Unknown PlatformMode in snapshot: $platformMode"); PlatformMode.ACCESSIBILITY
    },
    llm = SessionLlmConfig(
        backendType = try {
            LLMBackendType.valueOf(llmBackendType)
        } catch (_: Exception) {
            Log.w(SNAPSHOT_TAG, "Unknown LLMBackendType in snapshot: $llmBackendType")
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
    approvalMode = try { ApprovalMode.valueOf(approvalMode) } catch (_: Exception) {
        Log.w(SNAPSHOT_TAG, "Unknown ApprovalMode in snapshot: $approvalMode"); ApprovalMode.SMART
    },
    debugMode = debugMode,
    traceEnabled = traceEnabled,
    traceRunId = traceRunId,
    excludedTools = excludedTools.toSet()
)

private const val SNAPSHOT_TAG = "ConfigSnapshot"
