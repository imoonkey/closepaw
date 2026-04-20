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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
                text = formatTime(message.timestamp),
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
 * Per-action expand intentionally absent (spec §4.2 / §9). D2 restyle: tokens
 * + typography only. No structural changes.
 */
@Composable
private fun AgentRow(
    message: ChatMessage.Agent,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.closePaw.spacing
    val collapsible = message.rowState == RowState.Complete
    var collapsed by remember(message.id) { mutableStateOf(collapsible) }
    val expanded = !collapsible || !collapsed

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
            if (collapsible) Modifier.clickable { collapsed = !collapsed } else Modifier
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
        } else {
            CollapsedHeader(message)
        }

        if (message.rowState == RowState.Complete && message.state == AgentMessageState.Complete) {
            Text(
                text = formatTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.closePaw.inkFaint
            )
        }
    }
}

@Composable
private fun CollapsedHeader(message: ChatMessage.Agent) {
    val spacing = MaterialTheme.closePaw.spacing
    val headline = collapsedHeadline(message)
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
            text = headline,
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

@Composable
private fun ExpandedTrace(message: ChatMessage.Agent) {
    val spacing = MaterialTheme.closePaw.spacing
    Column(
        // Track A spec §4.6 (D1-aligned): trace items separated by sm (8dp).
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier = Modifier.fillMaxWidth()
    ) {
        // Find index of the first Final (Text) block — used to draw an InkGhost
        // hairline above it per Track A §4.4.
        val finalIndex = message.contentBlocks.indexOfFirst {
            it is ContentBlock.Text && it.text.isNotEmpty()
        }

        message.contentBlocks.forEachIndexed { index, block ->
            val isLast = index == message.contentBlocks.lastIndex
            when (block) {
                is ContentBlock.Thought -> ThoughtItem(block.text)
                is ContentBlock.Action -> ActionRow(data = block.data)
                is ContentBlock.Text -> {
                    if (block.text.isEmpty()) return@forEachIndexed
                    if (index == finalIndex) {
                        FinalSeparator()
                    }
                    val showCursor = isLast && message.state == AgentMessageState.Streaming
                    if (showCursor) {
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
 * Collapsed-row headline ladder (spec §5.2).
 *
 * v1 implements steps 2–4 (first thought / first action / fallback). The
 * preferred user-prompt headline (step 1) requires the previous user bubble
 * and is left to a later pass.
 */
private fun collapsedHeadline(message: ChatMessage.Agent): String {
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

private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

private fun formatTime(timestamp: Long): String {
    val dateTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    return dateTime.format(timeFormatter)
}
