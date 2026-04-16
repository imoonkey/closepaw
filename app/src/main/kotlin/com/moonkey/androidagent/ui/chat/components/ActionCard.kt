package com.moonkey.androidagent.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.moonkey.androidagent.ui.chat.model.ActionCardData
import com.moonkey.androidagent.ui.chat.model.ActionState
import com.moonkey.androidagent.ui.theme.ChatError
import com.moonkey.androidagent.ui.theme.ChatErrorBg
import com.moonkey.androidagent.ui.theme.ChatPrimary
import com.moonkey.androidagent.ui.theme.ChatSuccess
import com.moonkey.androidagent.ui.theme.ChatSuccessBg

/**
 * ActionCard - Displays a tool execution in the chat.
 * 
 * Shows the tool name, description, and status with visual feedback.
 */
@Composable
fun ActionCard(
    data: ActionCardData,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    val backgroundColor = when (data.state) {
        ActionState.Proposed -> MaterialTheme.colorScheme.surface
        ActionState.Executing -> ChatPrimary.copy(alpha = 0.05f)
        ActionState.Success -> ChatSuccessBg
        ActionState.Failed -> ChatErrorBg
        ActionState.Skipped -> MaterialTheme.colorScheme.surface
    }
    
    val borderColor = when (data.state) {
        ActionState.Proposed -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ActionState.Executing -> ChatPrimary
        ActionState.Success -> ChatSuccess
        ActionState.Failed -> ChatError
        ActionState.Skipped -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }
    
    val isDashed = data.state == ActionState.Proposed || data.state == ActionState.Skipped
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (data.expandedContent != null) {
                    Modifier.clickable { expanded = !expanded }
                } else Modifier
            ),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        border = BorderStroke(
            width = 1.dp,
            color = borderColor
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Tool icon
                Icon(
                    imageVector = data.toolIcon ?: Icons.Rounded.Build,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(Modifier.width(12.dp))
                
                // Content
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = data.toolName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val resultSummary = data.resultSummary
                    if (resultSummary != null) {
                        Text(
                            text = resultSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (data.description.isNotEmpty() && data.state != ActionState.Success) {
                        Text(
                            text = data.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Status indicator
                ActionStatusIcon(state = data.state)
            }
            
            // Expandable content
            AnimatedVisibility(visible = expanded && data.expandedContent != null) {
                Text(
                    text = data.expandedContent ?: "",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * ActionStatusIcon - Status indicator for action cards.
 */
@Composable
fun ActionStatusIcon(state: ActionState) {
    when (state) {
        ActionState.Proposed -> {
            // Empty or subtle indicator
        }
        ActionState.Executing -> {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = ChatPrimary
            )
        }
        ActionState.Success -> {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Success",
                modifier = Modifier.size(20.dp),
                tint = ChatSuccess
            )
        }
        ActionState.Failed -> {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Failed",
                modifier = Modifier.size(20.dp),
                tint = ChatError
            )
        }
        ActionState.Skipped -> {
            Icon(
                imageVector = Icons.Rounded.Remove,
                contentDescription = "Skipped",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}
