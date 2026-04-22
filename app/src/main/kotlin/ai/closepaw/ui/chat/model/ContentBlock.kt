package ai.closepaw.ui.chat.model

/**
 * ContentBlock - A unit of content in an agent message.
 *
 * Enables interleaved display of text, thoughts, and action cards in the order
 * they occurred. [FinalText] is a tagged variant of [Text] reserved for the
 * agent loop's concluding answer (per Turn.kt:205-209 stop criteria) — either
 * the `complete_task.answer` argument or the last text block in a tool-less
 * turn. It renders in a separate, always-visible "final" region of the row.
 */
sealed interface ContentBlock {
    /** Streaming text from the LLM mid-turn (narrative, may be interrupted). */
    data class Text(val text: String) : ContentBlock

    /** The row's concluding answer. Promoted from [Text] by the reducer. */
    data class FinalText(val text: String) : ContentBlock

    /** Agent reasoning emitted via `ThoughtUpdate`. One block per update. */
    data class Thought(val text: String) : ContentBlock

    /** An action card (tool execution). */
    data class Action(val data: ActionCardData) : ContentBlock
}
