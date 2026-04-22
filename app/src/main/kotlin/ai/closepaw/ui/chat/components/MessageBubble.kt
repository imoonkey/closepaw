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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.theme.closePaw
import android.content.Context
import java.util.Date

/**
 * MessageBubble — entry-point dispatcher. User vs Agent rendering lives in
 * [UserBubble] / [AgentRow].
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    when (message) {
        is ChatMessage.User -> UserBubble(
            message = message,
            maxWidth = screenWidth * 0.85f,
            modifier = modifier,
        )
        is ChatMessage.Agent -> AgentRow(
            message = message,
            modifier = modifier,
        )
    }
}

@Composable
private fun UserBubble(
    message: ChatMessage.User,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.closePaw.spacing
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier.widthIn(max = maxWidth).testTag("qa-user-bubble"),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
                    )
                }
            }
            Text(
                text = formatTime(LocalContext.current, message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Use the platform's time format (respects user's 12/24h preference and locale).
private fun formatTime(context: Context, timestamp: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(Date(timestamp))
