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
import androidx.compose.ui.unit.dp
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.LLMBackendType

/**
 * Backend selector (Cloud vs Local).
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedDisplayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Backend") },
            leadingIcon = {
                Icon(
                    imageVector = if (selectedBackend == LLMBackendType.OPENAI)
                        Icons.Outlined.Cloud else Icons.Outlined.PhoneAndroid,
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
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
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
                            imageVector = if (backend == LLMBackendType.OPENAI)
                                Icons.Outlined.Cloud else Icons.Outlined.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (backend == selectedBackend)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }
    }
}

/**
 * Cloud model selection dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudModelDropdown(
    selectedModel: String,
    onModelChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedDisplayName = AVAILABLE_CLOUD_MODELS.find { it.first == selectedModel }?.second ?: selectedModel

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedDisplayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
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
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AVAILABLE_CLOUD_MODELS.forEach { (modelId, displayName) ->
                DropdownMenuItem(
                    text = { Text(displayName) },
                    onClick = {
                        onModelChange(modelId)
                        expanded = false
                    },
                    leadingIcon = if (modelId == selectedModel) {
                        {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    } else null
                )
            }
        }
    }
}

/**
 * Local model selection dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalModelDropdown(
    selectedModelId: String,
    onModelChange: (LocalModelOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedModel = AVAILABLE_LOCAL_MODELS.find { it.id == selectedModelId }
        ?: AVAILABLE_LOCAL_MODELS.first()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedModel.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Local Model") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Memory,
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
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
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
                        {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    } else null
                )
            }
        }
    }
}

/**
 * Max turns selection dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaxTurnsDropdown(
    maxTurns: Int,
    onMaxTurnsChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = "$maxTurns turns",
            onValueChange = {},
            readOnly = true,
            label = { Text("Max Turns") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Repeat,
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
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            MAX_TURNS_OPTIONS.forEach { turns ->
                DropdownMenuItem(
                    text = { Text("$turns turns") },
                    onClick = {
                        onMaxTurnsChange(turns)
                        expanded = false
                    },
                    leadingIcon = if (turns == maxTurns) {
                        {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    } else null
                )
            }
        }
    }
}

/**
 * Agent execution mode selector.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedDisplayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Execution Mode") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Speed,
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
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            modeItems.forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onAgentModeChange(mode)
                        expanded = false
                    },
                    leadingIcon = if (mode == agentMode) {
                        {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    } else null
                )
            }
        }
    }
}
