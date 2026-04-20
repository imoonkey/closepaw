package ai.closepaw.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.chat.model.RowState
import ai.closepaw.ui.theme.BubbleShapeUser
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
    maxWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier.widthIn(max = maxWidth).testTag("qa-user-bubble"),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                shape = BubbleShapeUser,
                color = ai.closepaw.ui.theme.UserBubble
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = ai.closepaw.ui.theme.UserBubbleText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
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
 * Per-action expand intentionally absent (spec §4.2 / §9).
 */
@Composable
private fun AgentRow(
    message: ChatMessage.Agent,
    modifier: Modifier = Modifier
) {
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
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun CollapsedHeader(message: ChatMessage.Agent) {
    val headline = collapsedHeadline(message)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        Text(text = "✓", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            text = headline,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(text = "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExpandedTrace(message: ChatMessage.Agent) {
    Column(
        // Track A spec §4.6: trace items separated by 8dp vertical spacing
        // (cross-spec sync with Track D1 — supersedes the 6dp draft note).
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        message.contentBlocks.forEachIndexed { index, block ->
            val isLast = index == message.contentBlocks.lastIndex
            when (block) {
                is ContentBlock.Thought -> ThoughtItem(block.text)
                is ContentBlock.Action -> ActionCard(
                    data = block.data,
                    modifier = Modifier.fillMaxWidth()
                )
                is ContentBlock.Text -> {
                    if (block.text.isNotEmpty()) {
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
}

@Composable
private fun ThoughtItem(text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Text(text = "✱", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Collapsed-row headline ladder (spec §5.2).
 *
 * v1 implements steps 2–4 (first thought / first action / fallback). The
 * preferred user-prompt headline (step 1) requires the previous user bubble
 * and is left to the d2-4 restyle pass.
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
