package com.moonkey.androidagent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.moonkey.androidagent.ui.theme.ChatError
import com.moonkey.androidagent.ui.theme.ChatSuccess
import com.moonkey.androidagent.ui.theme.ChatWarning

/**
 * SettingsSheet - Bottom sheet for configuration.
 * 
 * Sections:
 * - API Key
 * - Accessibility Service status
 * - Overlay Permission status
 * - Clear Conversation
 */
@Suppress("UNUSED_PARAMETER") // onDismiss reserved for future use (e.g., explicit close button)
@Composable
fun SettingsSheet(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onOverlayClick: () -> Unit,
    onClearConversation: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var apiKeyVisible by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .navigationBarsPadding()
    ) {
        // Title
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        // API Key Section
        SettingsSection(title = "Configuration") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "OpenAI API Key",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { 
                        Text(
                            "sk-...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        ) 
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                imageVector = if (apiKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (apiKeyVisible) "Hide" else "Show",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Permissions Section
        SettingsSection(title = "Permissions") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Accessibility Service
                SettingsRow(
                    icon = Icons.Outlined.Settings,
                    title = "Accessibility Service",
                    isEnabled = isAccessibilityEnabled,
                    onClick = onAccessibilityClick
                )
                
                // Overlay Permission
                SettingsRow(
                    icon = Icons.Outlined.Layers,
                    title = "Overlay Permission",
                    isEnabled = isOverlayEnabled,
                    onClick = onOverlayClick
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Destructive Actions
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClearConversation),
            color = ChatError.copy(alpha = 0.1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = ChatError
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Clear Conversation",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ChatError
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        content()
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            
            // Status indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isEnabled) ChatSuccess else ChatWarning)
                )
                Text(
                    text = if (isEnabled) "Enabled" else "Required",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isEnabled) ChatSuccess else ChatWarning
                )
            }
        }
    }
}
