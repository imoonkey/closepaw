package ai.closepaw.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import ai.closepaw.ui.chat.model.ActionCardData
import ai.closepaw.ui.chat.model.ActionState
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.chat.model.RowState
import ai.closepaw.ui.theme.closePaw

/**
 * AgentRow — D2/D3 layout. Three vertical regions:
 *   1. Trace (collapsible) — Thought + Action + mid-stream Text blocks.
 *   2. CollapsePill (toggle) — only on Complete rows that have a trace.
 *   3. Final region (always visible) — the FinalText block, if present.
 *
 * Live/Waiting/Error rows render trace open with no pill (locked open). The row
 * root is no longer clickable; the pill owns the toggle and Role.Button.
 */
@Composable
internal fun AgentRow(
    message: ChatMessage.Agent,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.closePaw.spacing
    val traceBlocks = message.contentBlocks.filter { it !is ContentBlock.FinalText }
    val finalText = message.contentBlocks.filterIsInstance<ContentBlock.FinalText>().lastOrNull()
    val hasTrace = traceBlocks.isNotEmpty()

    val collapsible = message.rowState == RowState.Complete && hasTrace
    var userCollapsed by remember(message.id) { mutableStateOf<Boolean?>(null) }
    val collapsed = if (collapsible) (userCollapsed ?: true) else false
    val expanded = !collapsed

    val rowDescription = when (message.rowState) {
        RowState.Live -> "live"
        RowState.Waiting -> "waiting"
        RowState.Complete -> if (collapsed) "collapsed" else "expanded"
        RowState.Error -> "error"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("qa-agent-bubble")
            .semantics { stateDescription = rowDescription },
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (message.state == AgentMessageState.Thinking && message.contentBlocks.isEmpty()) {
            ThinkingIndicator()
            return@Column
        }

        if (hasTrace) {
            AnimatedVisibility(visible = expanded) {
                ExpandedTrace(traceBlocks, message.state)
            }
            if (collapsible) {
                CollapsePill(
                    summary = outcomeFooter(message),
                    expanded = expanded,
                    onToggle = { userCollapsed = !collapsed },
                )
            }
        }

        if (finalText != null) {
            FinalAnswer(text = finalText.text)
        }
    }
}

@Composable
private fun FinalAnswer(text: String) {
    SelectionContainer(modifier = Modifier.fillMaxWidth().testTag("qa-final-answer")) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Chronological trace: Thought + Action + mid-stream Text. FinalText is handled
 * separately by [AgentRow]. UXFB-4 will restructure into ThoughtGroups; this
 * commit preserves the existing flat layout.
 */
@Composable
private fun ExpandedTrace(blocks: List<ContentBlock>, state: AgentMessageState) {
    val spacing = MaterialTheme.closePaw.spacing
    val isStreaming = state == AgentMessageState.Streaming
    val lastTextIndex = blocks.indexOfLast { it is ContentBlock.Text }
    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is ContentBlock.Thought -> ThoughtItem(block.text)
                is ContentBlock.Action -> ActionRow(data = block.data)
                is ContentBlock.Text -> {
                    val streamingTail = isStreaming && index == lastTextIndex
                    if (streamingTail) {
                        StreamingText(
                            text = block.text,
                            isStreaming = true,
                            textColor = MaterialTheme.colorScheme.onSurface,
                        )
                    } else if (block.text.isNotEmpty()) {
                        SelectionContainer(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = block.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                is ContentBlock.FinalText -> Unit
            }
        }
    }
}

@Composable
private fun ThoughtItem(text: String) {
    val spacing = MaterialTheme.closePaw.spacing
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "✱",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(spacing.sm))
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.closePaw.bodyItalic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * ActionRow — inline trace row for an action (aligned spec §6.2 / Track A §4.2).
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
                color = MaterialTheme.closePaw.inkFaint,
            )
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatToolCall(data),
                    style = mono,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val subtitle = data.resultSummary
                    ?: data.description.takeIf { it.isNotEmpty() && data.state != ActionState.Success }
                if (!subtitle.isNullOrBlank() && subtitle != data.description) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(spacing.sm))
            Text(
                text = statusGlyph,
                style = mono,
                color = statusColor,
                modifier = Modifier.semantics { stateDescription = statusDescription },
            )
        }
        val expanded = data.expandedContent
        if (!expanded.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.xs),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = expanded,
                    style = MaterialTheme.closePaw.monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(spacing.sm),
                )
            }
        }
    }
}

private fun formatToolCall(data: ActionCardData): String {
    val args = data.description.takeIf { it.isNotBlank() } ?: ""
    return "${data.toolName}($args)"
}
