package com.moonkey.androidagent.ui.chat.model

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * ChatMessage - UI data classes for the chat conversation.
 * 
 * These classes represent the visual representation of messages in the chat interface.
 * They map from AgentEvents emitted by the session to UI-friendly data structures.
 */
sealed interface ChatMessage {
    val id: String
    val timestamp: Long
    
    /**
     * User message bubble.
     */
    data class User(
        override val id: String,
        override val timestamp: Long,
        val text: String
    ) : ChatMessage
    
    /**
     * Agent message bubble with streaming support and interleaved content.
     * 
     * Content blocks allow text and actions to be displayed in the order they occurred,
     * supporting the natural flow: "I'll click this" → [Action Card] → "Now I see..."
     */
    data class Agent(
        override val id: String,
        override val timestamp: Long,
        val contentBlocks: List<ContentBlock>,
        val state: AgentMessageState
    ) : ChatMessage {
        /** 
         * Convenience: get all text content concatenated (for backward compatibility).
         */
        val content: String 
            get() = contentBlocks
                .filterIsInstance<ContentBlock.Text>()
                .joinToString("") { it.text }
        
        /**
         * Convenience: get all actions (for backward compatibility).
         */
        val actions: List<ActionCardData>
            get() = contentBlocks
                .filterIsInstance<ContentBlock.Action>()
                .map { it.data }
    }
}

/**
 * ContentBlock - A unit of content in an agent message.
 * 
 * Enables interleaved display of text and action cards in the order they occurred.
 */
sealed interface ContentBlock {
    /**
     * Text content from the LLM response.
     */
    data class Text(val text: String) : ContentBlock
    
    /**
     * An action card (tool execution).
     */
    data class Action(val data: ActionCardData) : ContentBlock
}

/**
 * AgentMessageState - The visual state of an agent message.
 */
enum class AgentMessageState {
    /** Agent is processing before first delta (shows thinking indicator) */
    Thinking,
    
    /** Agent is streaming text (shows blinking cursor) */
    Streaming,
    
    /** Message is complete (no cursor) */
    Complete
}

/**
 * ActionCardData - Data for displaying a tool execution in the chat.
 */
data class ActionCardData(
    val id: String,
    val toolName: String,
    val toolIcon: ImageVector? = null,
    val description: String,
    val state: ActionState,
    val resultSummary: String? = null,
    val expandedContent: String? = null
)

/**
 * ActionState - The execution state of an action.
 */
enum class ActionState {
    /** Action has been proposed but not yet executed */
    Proposed,
    
    /** Action is currently executing */
    Executing,
    
    /** Action completed successfully */
    Success,
    
    /** Action failed */
    Failed,
    
    /** Action was skipped (e.g., denied by user or policy) */
    Skipped
}

/**
 * TaskBannerState - State for the task context banner.
 */
sealed interface TaskBannerState {
    val dotColor: Long
    val title: String
    val subtitle: String?
    
    /**
     * No active task - banner is hidden.
     */
    data object Idle : TaskBannerState {
        override val dotColor = 0x00000000L
        override val title = ""
        override val subtitle: String? = null
    }
    
    /**
     * Task is running.
     */
    data class Working(
        val taskTitle: String,
        val phase: String? = null
    ) : TaskBannerState {
        override val dotColor = 0xFF2563EBL // Primary blue
        override val title = "Working on: $taskTitle"
        override val subtitle = phase
    }
    
    /**
     * Task completed successfully.
     */
    data class Completed(
        val summary: String
    ) : TaskBannerState {
        override val dotColor = 0xFF0D9488L // Success teal
        override val title = "✓ $summary"
        override val subtitle: String? = null
    }
    
    /**
     * Task encountered an error.
     */
    data class Error(
        val message: String
    ) : TaskBannerState {
        override val dotColor = 0xFFDC2626L // Error red
        override val title = "⚠ $message"
        override val subtitle: String? = null
    }
}

/**
 * InputState - State of the input dock.
 */
enum class InputState {
    /** Ready for user input */
    Idle,
    
    /** Agent is working, input disabled */
    Working
}

/**
 * ChatUiState - Overall UI state for the chat screen.
 */
data class ChatUiState(
    val inputState: InputState = InputState.Idle,
    val showEmptyState: Boolean = true
)
