package ai.closepaw.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.theme.BubbleShapeAgent
import ai.closepaw.ui.theme.BubbleShapeUser
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * MessageBubble - Displays a single message in the conversation.
 *
 * Handles both User and Agent message types with appropriate styling.
 */
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
        is ChatMessage.Agent -> AgentBubble(
            message = message,
            maxWidth = screenWidth * 0.9f,
            modifier = modifier
        )
    }
}

/**
 * UserBubble - Right-aligned user message.
 *
 * Uses light gray bubble for a clean, modern look (not dark/heavy).
 */
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
                color = ai.closepaw.ui.theme.UserBubble  // Light gray bubble
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = ai.closepaw.ui.theme.UserBubbleText,  // Dark text
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            // Timestamp
            Text(
                text = formatTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * AgentBubble - Left-aligned agent message with streaming support.
 *
 * Renders content blocks in order to support interleaved text and actions:
 * "I'll click Chrome" → [Click Action Card] → "Now I see the homepage"
 */
@Composable
private fun AgentBubble(
    message: ChatMessage.Agent,
    maxWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier.widthIn(max = maxWidth).testTag("qa-agent-bubble"),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = BubbleShapeAgent,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Handle thinking state (no content yet)
                    if (message.state == AgentMessageState.Thinking && message.contentBlocks.isEmpty()) {
                        ThinkingIndicator()
                    } else {
                        // Render content blocks in order (interleaved text and actions)
                        val isLastBlockText = message.contentBlocks.lastOrNull() is ContentBlock.Text

                        message.contentBlocks.forEachIndexed { index, block ->
                            val isLastBlock = index == message.contentBlocks.lastIndex

                            when (block) {
                                is ContentBlock.Text -> {
                                    if (block.text.isNotEmpty()) {
                                        // Show streaming cursor only on the last text block while streaming
                                        val showCursor = isLastBlock &&
                                            message.state == AgentMessageState.Streaming

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
                                is ContentBlock.Action -> {
                                    ActionCard(
                                        data = block.data,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        // If streaming and last block is NOT text (e.g., just added an action),
                        // show a thinking indicator for the next text
                        if (message.state == AgentMessageState.Streaming && !isLastBlockText) {
                            // Could add a mini "..." indicator here if desired
                        }
                    }
                }
            }

            // Timestamp (only show when complete)
            if (message.state == AgentMessageState.Complete) {
                Text(
                    text = formatTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

// DateTimeFormatter is thread-safe unlike SimpleDateFormat
private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

/**
 * Format timestamp for display.
 */
private fun formatTime(timestamp: Long): String {
    val dateTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    return dateTime.format(timeFormatter)
}
