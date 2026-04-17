package ai.closepaw.history.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionRuntimeSnapshot(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val config: ConversationConfigSnapshot,
    val historyItems: List<PersistedHistoryItem>,
    val todos: List<TodoSnapshot>,
    val scratchpadJson: String = "{}",
    // Legacy field: old checkpoints stored scratchpad as Map<String, String>.
    // Kept for backward-compatible deserialization. New writes use scratchpadJson only.
    val scratchpad: Map<String, String>? = null,
    val checkpointState: CheckpointState,
    val lastCheckpointAt: Long,
    /** Name of the last TaskOutcome recorded in this session (or null if no task has completed). */
    val lastTaskOutcome: String? = null
)

/**
 * Serializable mirror of [ai.closepaw.history.ResponseItem] for checkpoint persistence.
 *
 * Exists because [ResponseItem.FunctionCall.arguments] is a [org.json.JSONObject] (not
 * kotlinx-serializable). This type stores it as [FunctionCall.argumentsRawJson] instead.
 * Conversion is handled by [HistoryItemConverter] (two call-sites: save and reload).
 */
@Serializable
sealed interface PersistedHistoryItem {
    @Serializable
    data class Message(
        val kind: String? = null,
        val content: String,
        val name: String? = null,
        // Legacy fields for backward compatibility with pre-MessageKind checkpoints.
        // New writes always set `kind` and omit these. HistoryItemConverter.fromRecord
        // uses role+isScreenObservation to infer kind when kind is absent.
        val role: String? = null,
        val isScreenObservation: Boolean = false
    ) : PersistedHistoryItem

    @Serializable
    data class FunctionCall(
        val id: String,
        val name: String,
        val argumentsRawJson: String
    ) : PersistedHistoryItem

    @Serializable
    data class FunctionCallOutput(
        val callId: String,
        val content: String,
        val success: Boolean = true,
        val truncated: Boolean = false
    ) : PersistedHistoryItem
}

@Serializable
data class ConversationConfigSnapshot(
    val mainModel: String,
    val executorModel: String? = null,
    val agentMode: String,
    val maxTurns: Int,
    val perceptionMode: String,
    val platformMode: String,
    val llmBackendType: String = "OPENAI",
    val localModelSlug: String? = null,
    val localQuantizationSlug: String? = null,
    val actionDelayMs: Long = 2000,
    val approvalMode: String = "SMART",
    val debugMode: Boolean = false,
    val traceEnabled: Boolean = false,
    val traceRunId: String? = null,
    val excludedTools: List<String> = emptyList()
)

@Serializable
data class TodoSnapshot(
    val description: String,
    val status: String
)

@Serializable
enum class CheckpointState {
    IDLE_READY,
    RUNNING_DIRTY,
    CLOSED
}

/** IDLE_READY and CLOSED snapshots are safe entry points for follow-up reload. */
fun CheckpointState.isReloadable(): Boolean =
    this == CheckpointState.IDLE_READY || this == CheckpointState.CLOSED
