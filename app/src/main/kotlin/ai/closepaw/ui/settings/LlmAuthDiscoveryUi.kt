package ai.closepaw.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalogRepository
import ai.closepaw.llm.ModelEntry
import ai.closepaw.ui.theme.closePaw
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Searchable grouped model picker for OPENROUTER + OTHER. Renders the pure
 * state from [ModelPicker.buildState] — the Compose layer is a thin
 * presentation shell so all sort, filter, and grouping behavior is covered
 * by [ModelPickerTest]. On open the selected row's group is auto-expanded
 * and the list scrolls so the selected row is visible without manual scroll.
 */
@Composable
internal fun SearchableGroupedModelPicker(
    entries: List<ModelEntry>,
    selectedName: String,
    onSelect: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var expandedKeys by remember(entries) { mutableStateOf(setOf<String>()) }
    val state = ModelPicker.buildState(
        allEntries = entries,
        query = query,
        selectedName = selectedName,
        expandedKeys = expandedKeys,
    )

    val listState = rememberLazyListState()
    LaunchedEffect(state.selectedRowIndex, entries) {
        val idx = state.selectedRowIndex
        if (idx >= 0) listState.scrollToItem(idx)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text("Search models") },
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 360.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = MaterialTheme.shapes.medium,
                )
                .clip(MaterialTheme.shapes.medium)
        ) {
            if (state.groups.isEmpty()) {
                Text(
                    text = if (state.isSearching) "No models match \"$query\"" else "No models available",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(MaterialTheme.closePaw.spacing.cardPadding),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    state.groups.forEach { group ->
                        val expanded = group.key in state.expandedKeys
                        item(key = "header:${group.key}") {
                            GroupHeaderRow(
                                label = group.key,
                                count = group.rows.size,
                                expanded = expanded,
                                onClick = {
                                    expandedKeys = if (expanded) expandedKeys - group.key
                                    else expandedKeys + group.key
                                },
                            )
                        }
                        if (expanded) {
                            items(items = group.rows, key = { "row:${it.name}" }) { row ->
                                PickerRow(
                                    label = row.displayName,
                                    selected = row.name == selectedName,
                                    onClick = { onSelect(row.name) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeaderRow(
    label: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = MaterialTheme.closePaw.spacing.md, vertical = 10.dp),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
            contentDescription = null,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "($count)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PickerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // Primary selection signal is the leading check icon (left-aligned, like a
    // settled-state checklist). A faint primaryContainer tint stays as a
    // secondary cue; the previous full-fill orange row read as a hover.
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .background(background)
            .padding(horizontal = MaterialTheme.closePaw.spacing.cardPadding, vertical = 10.dp),
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = "Selected",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * Refresh-models button + status row for [provider]. Gating uses
 * [RefreshButtonGate] so the disabled tooltip names the missing piece. On
 * tap the caller's [onRefresh] launches the repo's suspend refresh; the
 * row renders the latest spinner / timestamp / error from [discoveryState].
 */
@Composable
internal fun RefreshModelsRow(
    provider: LLMProvider,
    apiKey: String,
    otherBaseUrl: String,
    discoveryState: ModelCatalogRepository.DiscoveryState,
    allowDebugHttp: Boolean,
    onRefresh: () -> Unit,
) {
    val gate = RefreshButtonGate.evaluate(
        provider = provider,
        apiKey = apiKey,
        otherBaseUrl = otherBaseUrl,
        allowDebugHttp = allowDebugHttp,
    )
    val refreshing = provider in discoveryState.refreshing
    val enabled = gate is RefreshButtonGate.State.Enabled && !refreshing
    val reason = (gate as? RefreshButtonGate.State.Disabled)?.reason

    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onRefresh, enabled = enabled) {
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("Refreshing…")
            } else {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Refresh models")
            }
        }
        val timestamp = discoveryState.lastFetchedAt[provider]
        if (timestamp != null) {
            Text(
                text = "Last refreshed: ${formatTimestamp(timestamp)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MaterialTheme.closePaw.spacing.xs),
            )
        }
        val error = discoveryState.lastError[provider]
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = MaterialTheme.closePaw.spacing.xs),
            )
        } else if (reason != null) {
            Text(
                text = "Refresh disabled: $reason",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = MaterialTheme.closePaw.spacing.xs),
            )
        }
    }
}

private fun formatTimestamp(epochMillis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(epochMillis))
}
