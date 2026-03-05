# P1: DocumentsUI "Move" Button Missing from A11y Tree

## Problem

When using Android's DocumentsUI (Files app) to move a file, the "Move" confirmation button at the bottom of the screen is **not present in the accessibility tree**. The agent correctly navigates to the destination folder (DCIM) but cannot see or click the "Move" button to confirm the operation. The button is visually present on screen (confirmed by screenshot) but invisible to the accessibility service.

## Evidence (FilesMoveFile, round6)

### 1. Agent explicitly reports the button is missing

Turn 13 LLM output (logcat):
```
Looking at the screen, I don't see a specific "Move here" button, which suggests
the move might have completed automatically when I navigated to DCIM. However, I
should verify by checking if the file appears in DCIM. Since it shows "No items",
let me wait a moment for the UI to update, or there might be a paste/confirm
action needed.
```

The agent correctly reached DCIM in the move dialog, looked for a confirmation button, couldn't find one, and rationalized that the move "might have completed automatically."

### 2. Element text "Move" never appears in any a11y tree snapshot

`grep` for `"text":\s*"Move"` across the entire logcat (all a11y tree JSON) found **zero matches**. The only "Move" reference in element data was:
- `"Move to…"` — the dialog title at index 0, not an action button

### 3. Element counts confirm sparse tree in move dialog

When inside DCIM in the move dialog, `AccessibilityPlatform` captured very few elements:

```
12:52:47.995  Captured screen [AccessibilityOnly]: 9 elements
12:52:50.117  Captured screen [AccessibilityOnly]: 9 elements
12:52:54.095  Captured screen [AccessibilityOnly]: 9 elements
12:52:56.218  Captured screen [AccessibilityOnly]: 5 elements
12:52:59.296  Captured screen [AccessibilityOnly]: 0 elements   ← tree completely empty
12:53:01.370  Captured screen [AccessibilityOnly]: 27 elements  ← regained after navigation
12:53:05.778  Captured screen [AccessibilityOnly]: 9 elements
12:53:07.864  Captured screen [AccessibilityOnly]: 10 elements
```

9-10 elements is the typical count when in the DCIM folder of the move dialog. A complete view would include the toolbar "Move" button — its absence indicates the button is not in the `AccessibilityNodeInfo` tree returned by `getRootInActiveWindow()`.

### 4. Same failure across rounds

This exact failure point occurred in both round5 and round6:
- **Round5**: Agent reached DCIM in move dialog, couldn't find "Move" button, fell into 12-turn `long_press` loop
- **Round6**: Agent reached DCIM in move dialog, couldn't find "Move" button, tried varied strategies (clicking, scrolling, navigating away), wasted 18 turns

## Why This Happens

The "Move" button in DocumentsUI is rendered in the **bottom toolbar** (ActionBar/Toolbar at the bottom of the pick-destination dialog). Android's accessibility framework sometimes fails to include toolbar action buttons in the `AccessibilityNodeInfo` tree returned by `getRootInActiveWindow()`. Possible reasons:

1. The button is in a **separate window layer** (e.g., a `DecorView` toolbar) that `getRootInActiveWindow()` doesn't traverse
2. The button is implemented as a **menu item** in the ActionBar, which the a11y framework exposes differently than standard views
3. The DocumentsUI app has a **custom toolbar implementation** that doesn't properly expose its children to the accessibility service

## Current Code Path (Perception)

```
AccessibilityPlatform.captureScreen()
  → service.rootInActiveWindow                     # AccessibilityPlatform.kt:116
  → Perceptor.snapshot(root)                       # Perceptor.kt:35-86
    → traverse(root, INTERACTIVE_ONLY)             # First pass: clickable/editable/scrollable
    → traverse(root, ALL)                          # Second pass: anything with content
    → enrichEmptyTextElements()                    # Bubble up child text
    → applyTruncation(maxElements=80)              # Cap at 80
```

**The button is not filtered** — it never enters the tree at all. The `traverse()` function at `Perceptor.kt:178-323` can only process nodes that exist as children of `rootInActiveWindow`. If the toolbar button isn't a descendant of that root, it's invisible regardless of filter settings.

## Key Files

| File | Role |
|------|------|
| `platform/AccessibilityPlatform.kt:116-125` | `getRootInActiveWindow()` call + retry loop |
| `perception/Perceptor.kt:35-86` | Tree snapshot entry point |
| `perception/Perceptor.kt:178-323` | Recursive traversal with filtering |
| `perception/PerceptorFilterConfig.kt` | Filter tunables (visibility, size, keyboard) |
| `perception/PerceptorInternals.kt` | Helper functions (visibility, enrichment) |

## Impact

- FilesMoveFile task is **permanently blocked** — no prompt or reasoning fix can make the agent click a button it cannot see
- Any other app that renders critical actions in bottom toolbars/ActionBars with similar a11y tree gaps will hit this
- The agent wastes 18+ turns trying alternative approaches, none of which can succeed

## Reproduction

1. Open Files app on Android emulator (API 34)
2. Long-press a file → More options → Move to...
3. Navigate to a destination folder (e.g., DCIM)
4. Dump the accessibility tree via `uiautomator dump` or the agent's own `AccessibilityPlatform`
5. Verify: the "Move" button at the bottom of the screen is missing from the dump

## Fix Direction

### Option A: Multi-window tree capture
Call `service.windows` (returns `List<AccessibilityWindowInfo>`) instead of just `service.rootInActiveWindow`. Each window has its own root. Traverse all window roots to capture toolbar buttons that live in separate window layers.

Relevant API: `AccessibilityService.getWindows()` → `AccessibilityWindowInfo.getRoot()`.

### Option B: Coordinate-based fallback for known patterns
When the agent detects it's in a DocumentsUI "Move to" dialog (title = "Move to…") and has navigated to a destination folder, inject a `gesture_tap` at the known bottom-toolbar coordinates for the "Move" button.

### Option C: Screenshot-based perception
Add visual perception mode that can detect buttons from screenshots when the a11y tree is incomplete. This is the most general solution but highest effort.

### Recommended: Option A
Multi-window capture is the most principled fix:
- `service.rootInActiveWindow` returns only the **focused window's** root
- `service.windows` returns **all windows**, including system bars, popups, and toolbar overlays
- Iterating all window roots would capture the "Move" button without any app-specific logic

Note: `service.windows` requires `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` to be set in the accessibility service configuration. Check `accessibility-service.xml` to verify this flag is present.
