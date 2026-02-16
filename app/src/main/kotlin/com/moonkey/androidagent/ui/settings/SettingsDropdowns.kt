@file:OptIn(ExperimentalMaterial3Api::class)

package com.moonkey.androidagent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.LLMBackendType

@Composable
private fun DropdownSelectedIndicator() {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
    )
}

@Composable
private fun dropdownFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline
)

@Composable
private fun SettingsDropdownField(
    label: String,
    value: String,
    leadingIcon: ImageVector,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    menuContent: @Composable () -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            leadingIcon = {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(12.dp),
            colors = dropdownFieldColors()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            menuContent()
        }
    }
}

@Composable
internal fun BackendSelector(
    selectedBackend: LLMBackendType,
    onBackendChange: (LLMBackendType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val backends = listOf(
        LLMBackendType.OPENAI to "Cloud (OpenAI)",
        LLMBackendType.LOCAL to "Local (On-Device)"
    )
    val selectedDisplayName = backends.find { it.first == selectedBackend }?.second ?: "Cloud (OpenAI)"

    SettingsDropdownField(
        label = "Backend",
        value = selectedDisplayName,
        leadingIcon = if (selectedBackend == LLMBackendType.OPENAI) {
            Icons.Outlined.Cloud
        } else {
            Icons.Outlined.PhoneAndroid
        },
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        backends.forEach { (backend, displayName) ->
            DropdownMenuItem(
                text = { Text(displayName) },
                onClick = {
                    onBackendChange(backend)
                    expanded = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (backend == LLMBackendType.OPENAI) {
                            Icons.Outlined.Cloud
                        } else {
                            Icons.Outlined.PhoneAndroid
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (backend == selectedBackend) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            )
        }
    }
}

@Composable
internal fun CloudModelDropdown(
    selectedModel: String,
    modelOptions: List<Pair<String, String>>,
    onModelChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedDisplayName = modelOptions.find { it.first == selectedModel }?.second ?: selectedModel

    SettingsDropdownField(
        label = "Model",
        value = selectedDisplayName,
        leadingIcon = Icons.Outlined.Psychology,
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        modelOptions.forEach { (modelId, displayName) ->
            DropdownMenuItem(
                text = { Text(displayName) },
                onClick = {
                    onModelChange(modelId)
                    expanded = false
                },
                leadingIcon = if (modelId == selectedModel) {
                    { DropdownSelectedIndicator() }
                } else {
                    null
                }
            )
        }
    }
}

@Composable
internal fun ExecutorModelDropdown(
    selectedModel: String?,
    modelOptions: List<Pair<String, String>>,
    onModelChange: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedDisplayName = when {
        selectedModel == null -> "(Same as Main Model)"
        else -> modelOptions.find { it.first == selectedModel }?.second ?: selectedModel
    }

    SettingsDropdownField(
        label = "Executor Model",
        value = selectedDisplayName,
        leadingIcon = Icons.Outlined.Psychology,
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        DropdownMenuItem(
            text = { Text("(Same as Main Model)") },
            onClick = {
                onModelChange(null)
                expanded = false
            },
            leadingIcon = if (selectedModel == null) {
                { DropdownSelectedIndicator() }
            } else {
                null
            }
        )

        modelOptions.forEach { (modelId, displayName) ->
            DropdownMenuItem(
                text = { Text(displayName) },
                onClick = {
                    onModelChange(modelId)
                    expanded = false
                },
                leadingIcon = if (modelId == selectedModel) {
                    { DropdownSelectedIndicator() }
                } else {
                    null
                }
            )
        }
    }
}

@Composable
internal fun LocalModelDropdown(
    selectedModelId: String,
    onModelChange: (LocalModelOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedModel = AVAILABLE_LOCAL_MODELS.find { it.id == selectedModelId }
        ?: AVAILABLE_LOCAL_MODELS.first()

    SettingsDropdownField(
        label = "Local Model",
        value = selectedModel.displayName,
        leadingIcon = Icons.Outlined.Memory,
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        AVAILABLE_LOCAL_MODELS.forEach { model ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(model.displayName)
                        Text(
                            text = model.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                onClick = {
                    onModelChange(model)
                    expanded = false
                },
                leadingIcon = if (model.id == selectedModelId) {
                    { DropdownSelectedIndicator() }
                } else {
                    null
                }
            )
        }
    }
}

@Composable
internal fun MaxTurnsDropdown(
    maxTurns: Int,
    onMaxTurnsChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    SettingsDropdownField(
        label = "Max Turns",
        value = "$maxTurns turns",
        leadingIcon = Icons.Outlined.Repeat,
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        MAX_TURNS_OPTIONS.forEach { turns ->
            DropdownMenuItem(
                text = { Text("$turns turns") },
                onClick = {
                    onMaxTurnsChange(turns)
                    expanded = false
                },
                leadingIcon = if (turns == maxTurns) {
                    { DropdownSelectedIndicator() }
                } else {
                    null
                }
            )
        }
    }
}

@Composable
internal fun AgentModeDropdown(
    agentMode: AgentMode,
    onAgentModeChange: (AgentMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val modeItems = listOf(
        AgentMode.BASIC to "Basic (Standalone)",
        AgentMode.PRO to "Pro (Planner + Executor)"
    )
    val selectedDisplayName = modeItems.find { it.first == agentMode }?.second ?: agentMode.name

    SettingsDropdownField(
        label = "Execution Mode",
        value = selectedDisplayName,
        leadingIcon = Icons.Outlined.Speed,
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        modeItems.forEach { (mode, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    onAgentModeChange(mode)
                    expanded = false
                },
                leadingIcon = if (mode == agentMode) {
                    { DropdownSelectedIndicator() }
                } else {
                    null
                }
            )
        }
    }
}
