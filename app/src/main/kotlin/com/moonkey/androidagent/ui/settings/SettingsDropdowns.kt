package com.moonkey.androidagent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.LLMBackendType

@Composable
internal fun BackendSelector(
    selectedBackend: LLMBackendType,
    onBackendChange: (LLMBackendType) -> Unit
) {
    val backends = listOf(
        LLMBackendType.OPENAI to "Cloud (OpenAI)",
        LLMBackendType.LOCAL to "Local (On-Device)"
    )
    val selectedDisplayName = backends.find { it.first == selectedBackend }?.second ?: "Cloud (OpenAI)"

    SettingsDropdown(
        label = "Backend",
        value = selectedDisplayName,
        leadingIcon = backendIcon(selectedBackend),
        options = backends,
        isSelected = { (backend, _) -> backend == selectedBackend },
        onOptionSelected = { (backend, _) -> onBackendChange(backend) },
        optionText = { (_, displayName) -> Text(displayName) },
        optionLeadingIcon = { (backend, _), selected ->
            {
                Icon(
                    imageVector = backendIcon(backend),
                    contentDescription = null,
                    modifier = androidx.compose.ui.Modifier.size(20.dp),
                    tint =
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                )
            }
        }
    )
}

@Composable
internal fun CloudModelDropdown(
    selectedModel: String,
    modelOptions: List<Pair<String, String>>,
    onModelChange: (String) -> Unit
) {
    val selectedDisplayName = modelOptions.find { it.first == selectedModel }?.second ?: selectedModel

    SettingsDropdown(
        label = "Model",
        value = selectedDisplayName,
        leadingIcon = Icons.Outlined.Psychology,
        options = modelOptions,
        isSelected = { (modelId, _) -> modelId == selectedModel },
        onOptionSelected = { (modelId, _) -> onModelChange(modelId) },
        optionText = { (_, displayName) -> Text(displayName) },
        optionLeadingIcon = { _, selected ->
            if (selected) { { DropdownSelectedIndicator() } } else null
        }
    )
}

@Composable
internal fun ExecutorModelDropdown(
    selectedModel: String?,
    modelOptions: List<Pair<String, String>>,
    onModelChange: (String?) -> Unit
) {
    val options = listOf(null to "(Same as Main Model)") + modelOptions
    val selectedDisplayName =
        options.find { it.first == selectedModel }?.second ?: "(Same as Main Model)"

    SettingsDropdown(
        label = "Executor Model",
        value = selectedDisplayName,
        leadingIcon = Icons.Outlined.Psychology,
        options = options,
        isSelected = { (modelId, _) -> modelId == selectedModel },
        onOptionSelected = { (modelId, _) -> onModelChange(modelId) },
        optionText = { (_, displayName) -> Text(displayName) },
        optionLeadingIcon = { _, selected ->
            if (selected) { { DropdownSelectedIndicator() } } else null
        }
    )
}

@Composable
internal fun LocalModelDropdown(
    selectedModelId: String,
    onModelChange: (LocalModelOption) -> Unit
) {
    val selectedModel = AVAILABLE_LOCAL_MODELS.find { it.id == selectedModelId }
        ?: AVAILABLE_LOCAL_MODELS.first()

    SettingsDropdown(
        label = "Local Model",
        value = selectedModel.displayName,
        leadingIcon = Icons.Outlined.Memory,
        options = AVAILABLE_LOCAL_MODELS,
        isSelected = { model -> model.id == selectedModelId },
        onOptionSelected = onModelChange,
        optionText = { model ->
            Column {
                Text(model.displayName)
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        optionLeadingIcon = { _, selected ->
            if (selected) { { DropdownSelectedIndicator() } } else null
        }
    )
}

@Composable
internal fun MaxTurnsDropdown(
    maxTurns: Int,
    onMaxTurnsChange: (Int) -> Unit
) {
    SettingsDropdown(
        label = "Max Turns",
        value = "$maxTurns turns",
        leadingIcon = Icons.Outlined.Repeat,
        options = MAX_TURNS_OPTIONS,
        isSelected = { turns -> turns == maxTurns },
        onOptionSelected = onMaxTurnsChange,
        optionText = { turns -> Text("$turns turns") },
        optionLeadingIcon = { _, selected ->
            if (selected) { { DropdownSelectedIndicator() } } else null
        }
    )
}

@Composable
internal fun AgentModeDropdown(
    agentMode: AgentMode,
    onAgentModeChange: (AgentMode) -> Unit
) {
    val modeItems = listOf(
        AgentMode.BASIC to "Basic (Standalone)",
        AgentMode.PRO to "Pro (Planner + Executor)"
    )
    val selectedDisplayName = modeItems.find { it.first == agentMode }?.second ?: agentMode.name

    SettingsDropdown(
        label = "Execution Mode",
        value = selectedDisplayName,
        leadingIcon = Icons.Outlined.Speed,
        options = modeItems,
        isSelected = { (mode, _) -> mode == agentMode },
        onOptionSelected = { (mode, _) -> onAgentModeChange(mode) },
        optionText = { (_, label) -> Text(label) },
        optionLeadingIcon = { _, selected ->
            if (selected) { { DropdownSelectedIndicator() } } else null
        }
    )
}

private fun backendIcon(backend: LLMBackendType): ImageVector {
    return if (backend == LLMBackendType.OPENAI) {
        Icons.Outlined.Cloud
    } else {
        Icons.Outlined.PhoneAndroid
    }
}
