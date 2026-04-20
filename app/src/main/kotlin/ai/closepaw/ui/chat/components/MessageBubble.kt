package ai.closepaw.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.closepaw.ui.chat.model.ActionCardData
import ai.closepaw.ui.chat.model.ActionState
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.chat.model.RowState
import ai.closepaw.ui.theme.closePaw
import android.content.Context
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    when (message) {
        is ChatMessage.User -> UserBubble(
            message = message,
            maxWidth = screenWidth * 0.85f,
            modifier = modifier
        )
        is ChatMessage.Agent -> AgentRow(
            message = message,
            modifier = modifier
        )
    }
}

@Composable
private fun UserBubble(
    message: ChatMessage.User,
    maxWidth: Dp,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.closePaw.spacing
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier.widthIn(max = maxWidth).testTag("qa-user-bubble"),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)
                    )
                }
            }
            Text(
                text = formatTime(LocalContext.current, message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * AgentRow — Track A spec §4. One row per turn, chronological trace inside.
 *
 * Disclosure (single axis, row-level only):
 *  - Live/Waiting/Error: locked open. No collapse control.
 *  - Complete: tappable header toggles collapsed ↔ expanded; default collapsed.
 *
 * The user's manual toggle wins over the default. Default is re-evaluated when
 * the row transitions Live → Complete (so a row that was watched live then
 * completes folds itself away, per spec §5).
 */
@Composable
private fun AgentRow(
    message: ChatMessage.Agent,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.closePaw.spacing
    val collapsible = message.rowState == RowState.Complete
    // Tri-state: null = follow default, true/false = user override.
    var userCollapsed by remember(message.id) { mutableStateOf<Boolean?>(null) }
    val collapsed = if (collapsible) (userCollapsed ?: true) else false
    val expanded = !collapsed

    val rowDescription = when (message.rowState) {
        RowState.Live -> "live"
        RowState.Waiting -> "waiting"
        RowState.Complete -> if (collapsed) "collapsed" else "expanded"
        RowState.Error -> "error"
    }

    val headerModifier = Modifier
        .fillMaxWidth()
        .testTag("qa-agent-bubble")
        .semantics { stateDescription = rowDescription }
        .then(
            if (collapsible) Modifier.clickable { userCollapsed = !collapsed } else Modifier
        )

    Column(
        modifier = modifier.then(headerModifier),
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        // Thinking indicator: nothing has been emitted yet for this turn.
        if (message.state == AgentMessageState.Thinking && message.contentBlocks.isEmpty()) {
            ThinkingIndicator()
            return@Column
        }

        if (expanded) {
            ExpandedTrace(message)
            // §4.5: Complete rows get the success footer. Error rows already
            // surface the ⚠️ text block inline (rendered by ExpandedTrace), so
            // a duplicate footer would just restate it.
            if (message.rowState == RowState.Complete) {
                OutcomeFooter(message)
            }
        } else {
            CollapsedHeader(message)
        }
    }
}

@Composable
private fun CollapsedHeader(message: ChatMessage.Agent) {
    val spacing = MaterialTheme.closePaw.spacing
    val summary = collapsedSummary(message)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.sm)
    ) {
        // Aligned visual spec §6.2: no claw left tick. Use neutral check in inkFaint.
        Text(
            text = "✓",
            style = MaterialTheme.closePaw.monoBody,
            color = MaterialTheme.closePaw.inkFaint
        )
        Spacer(Modifier.width(spacing.sm))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "▸",
            style = MaterialTheme.closePaw.monoBody,
            color = MaterialTheme.closePaw.inkFaint
        )
    }
}

/**
 * Outcome footer — Track A spec §4.5: `✓ N actions · elapsed` on Complete rows.
 */
@Composable
private fun OutcomeFooter(message: ChatMessage.Agent) {
    val text = outcomeFooter(message)
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.closePaw.inkFaint
    )
}

/**
 * Track A model: `Trace* + (optional) Final`.
 * Trace items are chronological Thought + Action blocks. Final is the closing
 * assistant prose — the concatenation of every Text block streamed in (older
 * Text fragments interrupted by a Thought/Action are merged back together so
 * the user sees one coherent answer, per spec §4.4).
 */
@Composable
private fun ExpandedTrace(message: ChatMessage.Agent) {
    val spacing = MaterialTheme.closePaw.spacing
    Column(
        // Track A spec §4.6 (D1-aligned): trace items separated by sm (8dp).
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier = Modifier.fillMaxWidth()
    ) {
        message.contentBlocks.forEach { block ->
            when (block) {
                is ContentBlock.Thought -> ThoughtItem(block.text)
                is ContentBlock.Action -> ActionRow(data = block.data)
                is ContentBlock.Text -> Unit // Text → Final, rendered below.
            }
        }

        // Render each Text block as its own composable so independent emissions
        // (streaming prose + completion summary + error text) keep visual
        // separation. The enclosing Column's spacedBy(sm) handles spacing.
        val finalTexts = message.contentBlocks
            .filterIsInstance<ContentBlock.Text>()
            .filter { it.text.isNotEmpty() }
        if (finalTexts.isNotEmpty()) {
            FinalSeparator()
            val isStreaming = message.state == AgentMessageState.Streaming
            finalTexts.forEachIndexed { index, block ->
                val isLastStreaming = isStreaming && index == finalTexts.lastIndex
                if (isLastStreaming) {
                    StreamingText(
                        text = block.text,
                        isStreaming = true,
                        textColor = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = block.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FinalSeparator() {
    // Aligned spec §4.4 / §7: hairline rule between Trace and Final at InkGhost (8% Ink).
    val spacing = MaterialTheme.closePaw.spacing
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun ThoughtItem(text: String) {
    val spacing = MaterialTheme.closePaw.spacing
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "✱",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(spacing.sm))
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.closePaw.bodyItalic,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * ActionRow — inline trace row for an action (aligned spec §6.2 / Track A §4.2).
 * Replaces the legacy bordered ActionCard surface form (retired in D2-4). One
 * disclosure axis only — the row owns expand state, not the action.
 */
@Composable
private fun ActionRow(data: ActionCardData) {
    val spacing = MaterialTheme.closePaw.spacing
    val mono = MaterialTheme.closePaw.monoBody
    val statusGlyph = when (data.state) {
        ActionState.Proposed -> "⏳"
        ActionState.Executing -> "⏳"
        ActionState.Success -> "✓"
        ActionState.Failed -> "✕"
        ActionState.Skipped -> "⊘"
    }
    val statusDescription = when (data.state) {
        ActionState.Proposed -> "Proposed"
        ActionState.Executing -> "Executing"
        ActionState.Success -> "Success"
        ActionState.Failed -> "Failed"
        ActionState.Skipped -> "Skipped"
    }
    val statusColor = when (data.state) {
        ActionState.Success -> MaterialTheme.colorScheme.secondary
        ActionState.Failed -> MaterialTheme.colorScheme.error
        ActionState.Skipped -> MaterialTheme.closePaw.inkFaint
        ActionState.Proposed, ActionState.Executing -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "→",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.closePaw.inkFaint
            )
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatToolCall(data),
                    style = mono,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val subtitle = data.resultSummary
                    ?: data.description.takeIf { it.isNotEmpty() && data.state != ActionState.Success }
                if (!subtitle.isNullOrBlank() && subtitle != data.description) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(spacing.sm))
            Text(
                text = statusGlyph,
                style = mono,
                color = statusColor,
                modifier = Modifier.semantics { stateDescription = statusDescription }
            )
        }
        val expanded = data.expandedContent
        if (!expanded.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.xs),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = expanded,
                    style = MaterialTheme.closePaw.monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(spacing.sm)
                )
            }
        }
    }
}

private fun formatToolCall(data: ActionCardData): String {
    val args = data.description.takeIf { it.isNotBlank() } ?: ""
    return "${data.toolName}($args)"
}

/**
 * Collapsed-row summary (spec §5.2): `headline · N actions · elapsed`.
 *
 * Headline ladder (first non-empty wins): first thought → first action → first
 * text → "(no activity)". Step 1 of the spec ladder (the prior user prompt)
 * lives outside this row; we fall back to the in-row signals.
 */
internal fun collapsedSummary(message: ChatMessage.Agent): String {
    val headline = collapsedHeadline(message)
    val actionCount = countActions(message)
    val parts = buildList {
        add(headline)
        if (actionCount > 0) add("$actionCount action${if (actionCount == 1) "" else "s"}")
        formatElapsed(message)?.let { add(it) }
    }
    return parts.joinToString(separator = " · ")
}

/**
 * Outcome-footer text (spec §4.5): single-line `✓ N actions · elapsed` on
 * Complete rows. Error rows surface `⚠️ <message>` inline as a Text block, so
 * no footer is rendered for them — gating happens at the call site.
 */
internal fun outcomeFooter(message: ChatMessage.Agent): String {
    val actionCount = countActions(message)
    val elapsed = formatElapsed(message)
    val parts = buildList {
        if (actionCount > 0) add("$actionCount action${if (actionCount == 1) "" else "s"}")
        elapsed?.let { add(it) }
    }
    return if (parts.isEmpty()) "✓" else "✓  ${parts.joinToString(separator = " · ")}"
}

private fun countActions(message: ChatMessage.Agent): Int =
    message.contentBlocks.count { it is ContentBlock.Action }

private fun formatElapsed(message: ChatMessage.Agent): String? {
    val end = message.completedTimestamp ?: return null
    val deltaMs = (end - message.timestamp).coerceAtLeast(0)
    return when {
        deltaMs < 10_000 -> String.format(Locale.US, "%.1fs", deltaMs / 1000.0)
        else -> "${deltaMs / 1000}s"
    }
}

/**
 * Collapsed-row headline ladder (spec §5.2): user prompt → first thought →
 * first action → first text → "(no activity)". User prompt is truncated to
 * ~6 words with an ellipsis for compactness.
 */
internal fun collapsedHeadline(message: ChatMessage.Agent): String {
    val prompt = message.userPrompt
    if (!prompt.isNullOrBlank()) return truncateWords(prompt, 6)

    val firstThought = message.contentBlocks
        .filterIsInstance<ContentBlock.Thought>()
        .firstOrNull()?.text
    if (!firstThought.isNullOrBlank()) return firstThought

    val firstAction = message.contentBlocks
        .filterIsInstance<ContentBlock.Action>()
        .firstOrNull()?.data?.description
    if (!firstAction.isNullOrBlank()) return firstAction

    val firstText = message.contentBlocks
        .filterIsInstance<ContentBlock.Text>()
        .firstOrNull()?.text
    if (!firstText.isNullOrBlank()) return firstText

    return "(no activity)"
}

private fun truncateWords(text: String, maxWords: Int): String {
    val words = text.trim().split(Regex("\\s+"))
    return if (words.size <= maxWords) text.trim()
    else words.take(maxWords).joinToString(" ") + "…"
}

// Use the platform's time format (respects user's 12/24h preference and
// locale). On 24h-default locales (e.g. zh-CN) this avoids the AM/PM marker
// entirely instead of forcing a hard Locale.US pin.
private fun formatTime(context: Context, timestamp: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(Date(timestamp))
