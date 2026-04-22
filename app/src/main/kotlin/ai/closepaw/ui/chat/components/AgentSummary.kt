package ai.closepaw.ui.chat.components

import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock

private val WHITESPACE = Regex("\\s+")

/**
 * Outcome-footer text (Track A spec §4.5): single-line `✓ N actions · elapsed`
 * on Complete rows. The CollapsePill uses this as its summary; standalone
 * footer rendering is gone in D2 (the pill replaces it).
 */
internal fun outcomeFooter(message: ChatMessage.Agent): String {
    val actionCount = countActions(message)
    val elapsed = formatElapsed(message)
    val parts = buildList {
        if (actionCount > 0) add("$actionCount action${if (actionCount == 1) "" else "s"}")
        elapsed?.let { add(it) }
    }
    return if (parts.isEmpty()) "✓" else "✓ ${parts.joinToString(separator = " · ")}"
}

/**
 * Collapsed-row summary (legacy spec §5.2): `headline · N actions · elapsed`.
 * Retained as a fallback for any future surface that still wants a textual
 * row digest; the pill itself uses [outcomeFooter] only.
 */
internal fun collapsedSummary(message: ChatMessage.Agent): String {
    val actionCount = countActions(message)
    val parts = buildList {
        firstHeadline(message)?.let { add(it) }
        if (actionCount > 0) add("$actionCount action${if (actionCount == 1) "" else "s"}")
        formatElapsed(message)?.let { add(it) }
    }
    return parts.joinToString(separator = " · ")
}

internal fun countActions(message: ChatMessage.Agent): Int =
    message.contentBlocks.count { it is ContentBlock.Action }

internal fun formatElapsed(message: ChatMessage.Agent): String? {
    val end = message.completedTimestamp ?: return null
    val deltaMs = (end - message.timestamp).coerceAtLeast(0)
    return when {
        deltaMs < 10_000 -> String.format("%.1fs", deltaMs / 1000.0)
        else -> "${deltaMs / 1000}s"
    }
}

private fun firstHeadline(message: ChatMessage.Agent): String? {
    message.userPrompt?.takeIf { it.isNotBlank() }?.let { return truncateWords(it, 6) }
    message.contentBlocks.firstNotNullOfOrNull { block ->
        when (block) {
            is ContentBlock.Thought -> block.text.takeIf { it.isNotBlank() }
            is ContentBlock.Action -> block.data.description.takeIf { it.isNotBlank() }
            is ContentBlock.Text -> block.text.takeIf { it.isNotBlank() }
            is ContentBlock.FinalText -> block.text.takeIf { it.isNotBlank() }
        }
    }?.let { return it }
    return null
}

private fun truncateWords(text: String, maxWords: Int): String {
    val words = text.trim().split(WHITESPACE)
    return if (words.size <= maxWords) text.trim()
    else words.take(maxWords).joinToString(" ") + "…"
}
