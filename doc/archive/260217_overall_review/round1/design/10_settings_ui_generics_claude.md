# Design: Settings UI Generics

**Priority**: P2 — DRY
**Files affected**: `ui/settings/SettingsDropdowns.kt`

---

## Problem

`SettingsDropdowns.kt` (413 lines) contains 5 dropdown composables that are structurally identical:

1. `ModelDropdown` — selects LLM model
2. `ExecutorModelDropdown` — selects executor model
3. `ApprovalModeDropdown` — selects approval mode
4. `AgentModeDropdown` — selects agent mode
5. `PlatformModeDropdown` — selects platform mode

Each follows the exact same pattern:

```kotlin
@Composable
fun XxxDropdown(
    selected: XxxType,
    onSelect: (XxxType) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.displayName(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Label") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            XxxType.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.displayName()) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    }
                )
            }
        }
    }
}
```

The only differences: the type parameter, label text, and display name mapping.

## Solution

Extract a generic `SettingsDropdown<T>` composable:

```kotlin
// ui/settings/SettingsDropdown.kt
@Composable
fun <T> SettingsDropdown(
    label: String,
    selected: T,
    options: List<T>,
    displayName: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = displayName(selected),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { item ->
                DropdownMenuItem(
                    text = { Text(displayName(item)) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    }
                )
            }
        }
    }
}
```

### Usage (callers become one-liners)

```kotlin
// In SettingsSheet.kt or wherever dropdowns are used:
SettingsDropdown(
    label = "Model",
    selected = config.mainModel,
    options = availableModels,
    displayName = { it },
    onSelect = onModelSelected
)

SettingsDropdown(
    label = "Approval Mode",
    selected = config.approvalMode,
    options = ApprovalMode.entries,
    displayName = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
    onSelect = onApprovalModeSelected
)
```

### For model dropdowns (with subtitle)

The model dropdowns have a slightly richer item layout (model name + provider subtitle). Add an optional `itemContent` parameter:

```kotlin
@Composable
fun <T> SettingsDropdown(
    ...,
    itemContent: @Composable (T) -> Unit = { Text(displayName(it)) }
)
```

## Steps

1. Create `ui/settings/SettingsDropdown.kt` with the generic composable
2. Replace each existing dropdown in `SettingsDropdowns.kt` with a call to `SettingsDropdown<T>`
3. If any dropdown has unique behavior (e.g., model dropdown shows provider), use the `itemContent` override
4. Delete the original per-type dropdown functions
5. Rename or delete `SettingsDropdowns.kt` if it becomes empty

## Result

- `SettingsDropdown.kt`: ~60 lines (generic component)
- `SettingsDropdowns.kt`: deleted or ~30 lines (thin wrappers if needed)
- Total: ~90 lines (down from 413)
- **~320 lines eliminated**

## Risks

- **None**: Pure UI refactoring with no behavioral change
- **Low**: Compose generics work well with reified type inference
