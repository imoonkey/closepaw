package com.moonkey.androidagent.ui.chat.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moonkey.androidagent.ui.chat.model.InputState
import com.moonkey.androidagent.ui.theme.ChatSendButtonActive
import com.moonkey.androidagent.ui.theme.ChatSendButtonOnActive

/**
 * InputDock - Always-visible input area at the bottom of the chat.
 * 
 * Supports two states:
 * - Idle: User can type and send messages
 * - Working: Input is disabled, shows stop button
 * 
 * Clean design: Clear borders, visible placeholder, good contrast.
 */
@Composable
fun InputDock(
    state: InputState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    val isWorking = state == InputState.Working
    val hasText = text.isNotBlank()
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp  // Subtle shadow
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.Bottom
        ) {
            // Text field - clear, visible borders
            OutlinedTextField(
                value = if (isWorking) "" else text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                enabled = !isWorking,
                placeholder = {
                    Text(
                        text = if (isWorking) "Agent is working..." else "What can I help with?",
                        color = MaterialTheme.colorScheme.onSurfaceVariant  // Visible placeholder
                    )
                },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,  // Visible border
                    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                maxLines = 4
            )
            
            Spacer(Modifier.width(12.dp))
            
            // Send/Stop button - Pure black when has text (like ChatGPT)
            // Uses ChatSendButtonActive/OnActive for themed high-contrast styling
            FilledIconButton(
                onClick = {
                    if (isWorking) {
                        onStop()
                    } else if (hasText) {
                        onSend(text)
                        text = ""
                    }
                },
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = when {
                        isWorking -> MaterialTheme.colorScheme.error
                        hasText -> ChatSendButtonActive  // Themed pure black
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = when {
                        isWorking -> MaterialTheme.colorScheme.onError
                        hasText -> ChatSendButtonOnActive  // Themed pure white
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            ) {
                Crossfade(targetState = isWorking, label = "buttonIcon") { working ->
                    Icon(
                        imageVector = if (working) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                        contentDescription = if (working) "Stop" else "Send",
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
