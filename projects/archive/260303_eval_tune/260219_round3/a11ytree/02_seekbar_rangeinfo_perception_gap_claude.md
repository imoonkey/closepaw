# Perception Gap: SeekBar/Slider RangeInfo Never Captured

## Problem

Brightness SeekBar (and all other sliders) show no current value, min, or max in the a11y tree. The agent cannot determine the current brightness level when using the quick settings slider, and the "screen content unchanged" detector cannot detect slider value changes.

**Impact**: SystemBrightnessMinVerify — agent swiped the slider 6 times, each returning "screen content unchanged", never knowing it had already succeeded (brightness was at min). Burned 20 turns without completing.

## Evidence

### Quick Settings Brightness SeekBar — Raw Tree (`52_raw_1771524017221.json`)

```json
{
  "className": "android.widget.SeekBar",
  "text": "Display brightness",
  "bounds": [42, 357, 1038, 483],
  "clickable": false,
  "scrollable": false,
  "editable": false,
  "childCount": 0
}
```

**No value. No min. No max.** The SeekBar node is identical before and after swipe gestures, making slider adjustments invisible.

### Quick Settings Brightness SeekBar — Sanitized Tree (`53_sanitized_1771524017221.json`)

```json
{
  "index": 7,
  "text": "Display brightness",
  "class": "SeekBar",
  "clickable": false,
  "editable": false,
  "scrollable": false,
  "bounds": [42, 357, 1038, 483],
  "center": [540, 420]
}
```

Also no value — but the problem is NOT the sanitizer. The raw tree also lacks value info.

### Contrast: Settings > Display Page — Has Value via Text

```json
// Sanitized tree for Display settings page:
{"index": 5, "text": "Brightness level | 100%", "class": "LinearLayout", "clickable": true}
```

The Display settings page shows brightness as text ("100%" or "6%") via child TextViews. The sanitizer's `enrichEmptyTextElements()` correctly bubbles these up. **This works fine — the problem is only with the SeekBar widget.**

## Root Cause

Android's `AccessibilityNodeInfo` provides `getRangeInfo()` which returns:

```kotlin
class RangeInfo {
    val current: Float  // e.g., 6.0
    val min: Float      // e.g., 0.0
    val max: Float      // e.g., 100.0
    val type: Int       // INT, FLOAT, or PERCENT
}
```

This API exists specifically for SeekBar/ProgressBar/Slider widgets. **Neither the raw tree dumper nor the Perceptor reads it.**

### Layer 1: A11yTreeDumper.kt — Never calls `getRangeInfo()`

`A11yNodeDump` data class (line 10-30) has no field for range info. The `dumpNode()` method (line 43-88) never calls `node.rangeInfo`.

### Layer 2: Perceptor.kt — Never calls `getRangeInfo()`

The `traverse()` method reads: text, contentDescription, hintText, clickable, scrollable, editable, enabled, focused, etc. — but never `rangeInfo`. The `PerceptionElement` model (Models.kt:107-125) has no field for range data.

### Consequence Chain

```
Agent swipes brightness slider left
  → Android adjusts brightness (value changes: 50 → 6)
  → SeekBar's AccessibilityNodeInfo.getRangeInfo().current changes: 50.0 → 6.0
  → But A11yTreeDumper never reads getRangeInfo()
  → Raw tree is byte-for-byte identical (only "Display brightness" text, no value)
  → Sanitized tree is identical
  → UiChangeDetector compares trees → "screen content unchanged"
  → Agent thinks swipe had no effect
  → Agent retries with wider swipes (already at min, no effect)
  → Loop for 6 more turns
```

## Proposed Fix

### Fix 1: Add `rangeInfo` to A11yNodeDump (A11yTreeDumper.kt)

```kotlin
@Serializable
data class A11yNodeDump(
    // ... existing fields ...
    val rangeInfo: A11yRangeInfo? = null   // NEW
)

@Serializable
data class A11yRangeInfo(
    val current: Float,
    val min: Float,
    val max: Float
)
```

In `dumpNode()`:

```kotlin
val range = node.rangeInfo
val rangeDump = range?.let {
    A11yRangeInfo(current = it.current, min = it.min, max = it.max)
}
```

### Fix 2: Add `rangeInfo` to PerceptionElement (Models.kt)

```kotlin
data class PerceptionElement(
    // ... existing fields ...
    val rangeInfo: RangeInfo? = null   // NEW
)

data class RangeInfo(
    val current: Float,
    val min: Float,
    val max: Float
)
```

### Fix 3: Read `rangeInfo` in Perceptor.traverse() (Perceptor.kt)

```kotlin
val rangeInfo = node.rangeInfo?.let {
    RangeInfo(current = it.current, min = it.min, max = it.max)
}
```

Pass to `PerceptionElement` constructor.

### Fix 4: Output rangeInfo in toPromptJson() (Perceptor.kt)

```kotlin
elem.rangeInfo?.let { range ->
    put("range_current", range.current)
    put("range_min", range.min)
    put("range_max", range.max)
}
```

### Expected Result After Fix

Quick settings brightness SeekBar in sanitized tree:

```json
{
  "index": 7,
  "text": "Display brightness",
  "class": "SeekBar",
  "clickable": false,
  "scrollable": false,
  "range_current": 1.0,
  "range_min": 0.0,
  "range_max": 255.0,
  "bounds": [42, 357, 1038, 483],
  "center": [540, 420]
}
```

The LLM can now:
1. See `range_current: 1.0` and `range_max: 255.0` → know brightness is already at minimum
2. Not waste turns swiping an already-minimized slider
3. Declare success immediately ("brightness is already at minimum")

The UiChangeDetector can now:
1. Detect that `range_current` changed from 128.0 to 1.0 after a swipe
2. Report "screen content changed" instead of "unchanged"
3. Give the agent accurate feedback on swipe actions

### Applicability Beyond Brightness

This fix benefits ALL slider/range widgets across Android:
- **Volume controls** (media, ring, alarm, notification)
- **Display timeout slider** (in some OEMs)
- **Any progress bar** that exposes progress state (download progress, etc.)
- **Star ratings** (some apps use SeekBar for ratings)

## Files to Modify

1. `app/src/main/kotlin/com/moonkey/androidagent/trace/A11yTreeDumper.kt`
   - Add `A11yRangeInfo` data class
   - Add `rangeInfo` field to `A11yNodeDump`
   - Read `node.rangeInfo` in `dumpNode()`
2. `app/src/main/kotlin/com/moonkey/androidagent/model/Models.kt`
   - Add `RangeInfo` data class
   - Add `rangeInfo` field to `PerceptionElement`
3. `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt`
   - Read `node.rangeInfo` in `traverse()`
   - Output `range_current/min/max` in `toPromptJson()`
