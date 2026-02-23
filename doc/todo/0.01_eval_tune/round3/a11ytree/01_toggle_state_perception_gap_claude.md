# Perception Gap: Switch/Toggle State Dropped by Sanitizer

## Problem

Wi-Fi, Bluetooth, and Airplane mode Switch widgets are **completely dropped** from the sanitized a11y tree. The LLM never sees whether toggles are ON or OFF, resulting in blind toggling and ping-pong behavior.

**Impact**: 3 false positives + 1 failure in Round 3 (SystemBluetoothTurnOn, SystemBrightnessMax via indirect, SystemWifiTurnOn, SystemWifiTurnOff).

## Evidence

### Raw tree contains the state (BT example, `65_raw_1771523909086.json`)

```
LinearLayout (clickable: true)          <-- "Use Bluetooth" row
  ├── TextView (text: "Use Bluetooth")
  └── Switch                            <-- THE CRITICAL NODE
        checkable: true
        checked: true                   <-- BT IS ON
        clickable: false
        text: ""
        contentDescription: ""
```

### Sanitized tree drops the Switch entirely (`66_sanitized_1771523909086.json`)

```json
{"index": 3, "text": "Use Bluetooth", "class": "LinearLayout", "clickable": true}
{"index": 4, "text": "Use Bluetooth", "class": "TextView", "clickable": false}
// NO Switch node. No checked/checkable anywhere.
```

Same pattern confirmed for:
- **Wi-Fi Switch** on Internet settings page (`checked: true/false` dropped)
- **Airplane mode Switch** on Network & internet page (`checked: false` dropped)

## Root Cause

`Perceptor.kt` lines 224-227, the `shouldKeep` filter:

```kotlin
val shouldKeep = when (mode) {
    TraversalMode.INTERACTIVE_ONLY -> clickable || editable || scrollable
    TraversalMode.ALL -> clickable || editable || scrollable || hasContent
}
```

The Switch node has:
| Property | Value | Checked by shouldKeep? |
|----------|-------|----------------------|
| `clickable` | `false` | YES but false |
| `editable` | `false` | YES but false |
| `scrollable` | `false` | YES but false |
| `text` | `""` | YES (via hasContent) but empty |
| `contentDescription` | `""` | YES (via hasContent) but empty |
| `checkable` | `true` | **NO - never checked** |
| `checked` | `true/false` | **NO - never checked** |

Result: `shouldKeep = false` → node dropped entirely.

## Secondary Issue: `enabled` Property Not Serialized

Even for nodes that ARE kept (like the parent LinearLayout), the `enabled` property is stored in `PerceptionElement.isEnabled` but never output by `toPromptJson()`. During BT/WiFi transitions, the toggle row shows `enabled: false` (greyed out) — this information is also invisible to the LLM.

## Proposed Fix

### Fix 1: Add `checkable` to `shouldKeep` (Perceptor.kt:224-227)

```kotlin
val shouldKeep = when (mode) {
    TraversalMode.INTERACTIVE_ONLY -> clickable || editable || scrollable || checkable
    TraversalMode.ALL -> clickable || editable || scrollable || hasContent || checkable
}
```

**Rationale**: `checkable` widgets carry toggle state that is semantically interactive. A Switch is fundamentally an interactive element — it's just that Android routes clicks through the parent LinearLayout rather than making the Switch itself clickable.

### Fix 2: Output `enabled` in `toPromptJson` (Perceptor.kt:~138)

Add after the existing property outputs:

```kotlin
if (!elem.isEnabled) put("enabled", false)
```

Only output when `false` (disabled) to keep the default case compact.

### Expected Result After Fix

The sanitized tree for the BT settings page would become:

```json
{"index": 3, "text": "Use Bluetooth", "class": "LinearLayout", "clickable": true}
{"index": 4, "text": "Use Bluetooth", "class": "TextView", "clickable": false}
{"index": 5, "class": "Switch", "checkable": true, "checked": true, "clickable": false, "bounds": [919,675,1065,801]}
```

The LLM can now:
1. See `checked: true` → know BT is ON
2. Decide NOT to toggle if the goal is "Turn on Bluetooth" and it's already on
3. Avoid the ON→OFF→ON ping-pong pattern

### Verification Scope

All `android.widget.Switch` nodes across Android Settings:
- Wi-Fi toggle (Internet page)
- Bluetooth toggle (Connected devices > Bluetooth)
- Airplane mode (Network & internet)
- Any other Settings toggle (Do Not Disturb, NFC, etc.)

Also applies to `android.widget.ToggleButton`, `com.google.android.material.switchmaterial.SwitchMaterial`, and other Check-family widgets.

## Files to Modify

1. `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt`
   - Line 224-227: `shouldKeep` condition
   - Around line 138-146: `toPromptJson()` — add `enabled` output
2. No model changes needed — `PerceptionElement` already has `isChecked`, `isCheckable`, `isEnabled` fields
3. No dumper changes needed — `A11yTreeDumper` already captures `checkable` and `checked`
