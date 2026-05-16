package ai.closepaw.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun CloudModelDropdown(
    selectedModel: String,
    modelOptions: List<Pair<String, String>>,
    onModelChange: (String) -> Unit
) {
    val matched = modelOptions.find { it.first == selectedModel }
    val selectedDisplayName = matched?.second ?: "Select a model"

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
internal fun SubagentModelDropdown(
    selectedModel: String?,
    modelOptions: List<Pair<String, String>>,
    onModelChange: (String?) -> Unit
) {
    val options = listOf(null to "(Same as Main Model)") + modelOptions
    val selectedDisplayName =
        options.find { it.first == selectedModel }?.second ?: "(Same as Main Model)"

    SettingsDropdown(
        label = "Subagent Model",
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
