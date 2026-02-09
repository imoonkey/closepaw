package com.moonkey.androidagent.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.moonkey.androidagent.BuildConfig
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.LLMBackendType

/**
 * SettingsSheet - Bottom sheet for configuration.
 *
 * Sections:
 * - LLM Backend (Cloud/Local)
 * - Model Selection (cloud or local based on backend)
 * - Max Turns
 * - API Key (cloud only)
 * - Accessibility Service status
 * - Overlay Permission status
 * - About & Debug
 */
@Composable
fun SettingsSheet(
    // Backend selection
    llmBackend: LLMBackendType,
    onBackendChange: (LLMBackendType) -> Unit,
    // Cloud model selection
    selectedModel: String,
    onModelChange: (String) -> Unit,
    // Local model selection
    selectedLocalModel: String,
    onLocalModelChange: (LocalModelOption) -> Unit,
    // Local model loading status
    modelLoadingStatus: ModelLoadingStatus,
    // API key (cloud only)
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    // Other settings
    maxTurns: Int,
    onMaxTurnsChange: (Int) -> Unit,
    agentMode: AgentMode,
    onAgentModeChange: (AgentMode) -> Unit,
    perceptionMode: String,
    onPerceptionModeChange: (String) -> Unit,
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onOverlayClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var apiKeyVisible by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val isCloudBackend = llmBackend == LLMBackendType.OPENAI

    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .displayCutoutPadding()
            .navigationBarsPadding()
    ) {
        SettingsHeader(onClose = onDismiss)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
        ) {
            SettingsSection(title = "LLM Backend") {
                BackendSelector(
                    selectedBackend = llmBackend,
                    onBackendChange = onBackendChange
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            AnimatedVisibility(visible = isCloudBackend) {
                Column {
                    SettingsSection(title = "Cloud Model") {
                        CloudModelDropdown(
                            selectedModel = selectedModel,
                            onModelChange = onModelChange
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

            AnimatedVisibility(visible = !isCloudBackend) {
                Column {
                    SettingsSection(title = "Local Model") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LocalModelDropdown(
                                selectedModelId = selectedLocalModel,
                                onModelChange = onLocalModelChange
                            )

                            ModelLoadingStatusIndicator(status = modelLoadingStatus)
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

            SettingsSection(title = "Max Turns") {
                MaxTurnsDropdown(
                    maxTurns = maxTurns,
                    onMaxTurnsChange = onMaxTurnsChange
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            SettingsSection(title = "Execution") {
                AgentModeDropdown(
                    agentMode = agentMode,
                    onAgentModeChange = onAgentModeChange
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            SettingsSection(title = "Perception") {
                PerceptionModeSelector(
                    selectedMode = perceptionMode,
                    onModeChange = onPerceptionModeChange
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            AnimatedVisibility(visible = isCloudBackend) {
                Column {
                    SettingsSection(title = "API Key") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                            imageVector = if (apiKeyVisible) {
                                                Icons.Outlined.VisibilityOff
                                            } else {
                                                Icons.Outlined.Visibility
                                            },
                                            contentDescription = if (apiKeyVisible) "Hide" else "Show",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                visualTransformation = if (apiKeyVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
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
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

            SettingsSection(title = "Permissions") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsRow(
                        icon = Icons.Outlined.Settings,
                        title = "Accessibility Service",
                        isEnabled = isAccessibilityEnabled,
                        onClick = onAccessibilityClick
                    )

                    SettingsRow(
                        icon = Icons.Outlined.Layers,
                        title = "Overlay Permission",
                        isEnabled = isOverlayEnabled,
                        onClick = onOverlayClick
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Spacer(modifier = Modifier.height(20.dp))

            SettingsSection(title = "About & Debug") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Android Agent",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.BugReport,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Debug Mode",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "Enable verbose logging",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = debugMode,
                                onCheckedChange = onDebugModeChange,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PerceptionModeSelector(
    selectedMode: String,
    onModeChange: (String) -> Unit
) {
    val modes = listOf(
        "accessibility_only" to "Accessibility Only",
        "hybrid" to "Hybrid (A11y + Screenshot)",
        "screenshot_only" to "Screenshot Only"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Perception Mode",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "How the agent perceives the screen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                modes.forEach { (value, label) ->
                    val isSelected = selectedMode == value
                    Surface(
                        onClick = { onModeChange(value) },
                        modifier = Modifier.weight(1f),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = if (isSelected) 2.dp else 0.dp
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
