@file:OptIn(ExperimentalMaterial3Api::class)

package com.moonkey.androidagent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
internal fun DropdownSelectedIndicator() {
    Box(
        modifier =
            Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
    )
}

@Composable
private fun dropdownFieldColors() =
    OutlinedTextFieldDefaults.colors(
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
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandedChange) {
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
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(12.dp),
            colors = dropdownFieldColors()
        )

        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            menuContent()
        }
    }
}

@Composable
internal fun <T> SettingsDropdown(
    label: String,
    value: String,
    leadingIcon: ImageVector,
    options: List<T>,
    isSelected: (T) -> Boolean,
    onOptionSelected: (T) -> Unit,
    optionText: @Composable (T) -> Unit,
    optionLeadingIcon: ((item: T, selected: Boolean) -> (@Composable () -> Unit)?)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsDropdownField(
        label = label,
        value = value,
        leadingIcon = leadingIcon,
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        options.forEach { item ->
            val selected = isSelected(item)
            DropdownMenuItem(
                text = { optionText(item) },
                onClick = {
                    onOptionSelected(item)
                    expanded = false
                },
                leadingIcon = optionLeadingIcon?.invoke(item, selected)
            )
        }
    }
}

@Composable
internal fun SettingsDropdownOptionWithDescription(primary: String, secondary: String) {
    Column {
        Text(primary)
        Text(
            text = secondary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
