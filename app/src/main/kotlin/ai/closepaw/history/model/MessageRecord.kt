package ai.closepaw.history.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A message in a session, can be user or agent.
 * 
 * This is the persisted representation of messages that can be serialized to JSON.
 * It maps to/from the UI's ChatMessage types.
 */
@Serializable
sealed interface MessageRecord {
    val id: String
    val timestamp: Long
    
    /**
     * User message.
     */
    @Serializable
    @SerialName("user")
    data class User(
        override val id: String,
        override val timestamp: Long,
        val text: String
    ) : MessageRecord
    
    /**
     * Agent message with content blocks.
     */
    @Serializable
    @SerialName("agent")
    data class Agent(
        override val id: String,
        override val timestamp: Long,
        val contentBlocks: List<ContentBlockRecord>,
        val isComplete: Boolean
    ) : MessageRecord
}

/**
 * Persisted content block (text or action).
 * 
 * This represents the interleaved content in an agent message:
 * text → action → text → action → etc.
 */
@Serializable
sealed interface ContentBlockRecord {
    /**
     * Text content from the LLM response.
     */
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlockRecord
    
    /**
     * An action card (tool execution).
     */
    @Serializable
    @SerialName("action")
    data class Action(
        val id: String,
        val toolName: String,
        val description: String,
        /** Action state: "proposed", "executing", "success", "failed", "skipped" */
        val state: String,
        val resultSummary: String? = null
    ) : ContentBlockRecord
}
