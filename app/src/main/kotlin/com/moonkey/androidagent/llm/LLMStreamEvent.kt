package com.moonkey.androidagent.llm

/**
 * LLMStreamEvent - Unified streaming event type for all LLM backends.
 * 
 * This abstraction allows both OpenAI and local LLM implementations to emit
 * events in a consistent format, enabling seamless switching between backends.
 * 
 * Event Types:
 * - Created: Response stream started
 * - ThinkingDelta: Reasoning/thinking content from thinking models (o1, o3, LFM-Thinking)
 * - TextDelta: Normal text content delta
 * - ToolCallComplete: A complete tool call has been parsed
 * - Completed: Stream finished successfully
 * - Failed: An error occurred
 * 
 * ## Thinking Model Support
 * 
 * For models that produce reasoning/thinking content (OpenAI o1/o3, LFM2.5-Thinking):
 * - ThinkingDelta events contain the internal reasoning process
 * - TextDelta events contain the final user-visible response
 * - Some models may emit thinking before text, or interleaved
 * 
 * ## OpenAI Reasoning Models
 * 
 * OpenAI's o1/o3 models may return reasoning content via:
 * - `reasoning_content` field in the Responses API (when available)
 * - Separate "reasoning" output item type
 * 
 * Note: Availability of reasoning content varies by model and API tier.
 * When not available, only TextDelta events will be emitted.
 * 
 * ## Local Thinking Models
 * 
 * LFM2.5-Thinking produces thinking content that may be wrapped in
 * special tokens or structured output. The LFMLLMClient parses this
 * and emits appropriate ThinkingDelta events.
 */
sealed interface LLMStreamEvent {
    
    /**
     * Response stream has started.
     * 
     * @property responseId Unique identifier for this response
     */
    data class Created(val responseId: String) : LLMStreamEvent
    
    /**
     * A delta of thinking/reasoning content from the model.
     * 
     * This represents the model's internal reasoning process, which may be:
     * - Chain-of-thought reasoning
     * - Planning and deliberation
     * - Problem decomposition
     * 
     * UI can optionally display this to show the model's thought process,
     * or hide it and only show the final TextDelta content.
     * 
     * @property delta The incremental thinking text chunk
     * @property isComplete Whether this is the final thinking delta
     */
    data class ThinkingDelta(
        val delta: String,
        val isComplete: Boolean = false
    ) : LLMStreamEvent
    
    /**
     * A delta of text content from the model.
     * 
     * This is the main user-visible response content. Collect all TextDelta
     * events and concatenate to build the complete response.
     * 
     * @property delta The incremental text chunk
     */
    data class TextDelta(val delta: String) : LLMStreamEvent
    
    /**
     * A complete tool call has been received.
     * 
     * The tool call is ready to be executed. Contains all information
     * needed to invoke the tool via the ToolRouter.
     * 
     * @property callId Unique identifier for this tool call (from OpenAI or generated locally)
     * @property name The name of the tool to invoke
     * @property arguments JSON string of the tool arguments
     */
    data class ToolCallComplete(
        val callId: String,
        val name: String,
        val arguments: String
    ) : LLMStreamEvent
    
    /**
     * Response stream completed successfully.
     * 
     * All content has been received. The consumer can now process
     * the accumulated text and tool calls.
     */
    data object Completed : LLMStreamEvent
    
    /**
     * An error occurred during streaming.
     * 
     * @property error Description of the error
     * @property cause The underlying exception (optional)
     */
    data class Failed(
        val error: String,
        val cause: Throwable? = null
    ) : LLMStreamEvent
}

/**
 * Extension to check if the stream has a terminal event.
 */
fun LLMStreamEvent.isTerminal(): Boolean = when (this) {
    is LLMStreamEvent.Completed -> true
    is LLMStreamEvent.Failed -> true
    else -> false
}
