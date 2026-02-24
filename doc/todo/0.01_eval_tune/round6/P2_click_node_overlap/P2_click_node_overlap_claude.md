# P2: `node_action_click` Hits Wrong Node Due to Overlapping Bounds

## Problem

When the agent clicks an element by index, `node_action_click` resolves the element's center coordinates and then re-searches the **live** accessibility tree for a clickable node at those coordinates using `findClickableNodeAtLocation`. Due to overlapping element bounds in the live tree, the search can find a **different clickable node** than the one the agent intended. The click dispatches `performAction(ACTION_CLICK)` on this wrong node, producing an unintended side effect. Because `performAction` returns `true`, the tool reports **"Success"** — and the fallback to `gesture_tap` is never attempted.

This is distinct from P0 (click false success / no UI change). In P0, the correct node is found but `ACTION_CLICK` doesn't work on it. In P2, a **wrong node** is found and successfully clicked, producing the **wrong UI change**.

## Evidence (FilesMoveFile, round6 — eval run `20260224_171706`)

### 1. Agent clicks "Show roots" but "Open with" dialog appears

After successfully moving a file, the agent tries to navigate to the destination folder to verify. It clicks "Show roots" (element_index=4) to open the navigation drawer:

| Turn | Target | Element Text | Method | Coordinates | Tool Result | Actual UI |
|------|--------|-------------|--------|-------------|-------------|-----------|
| 15 | element_index=4 | "Show roots" | `node_action_click` | (73, 191) | `Success: Clicked (73,191)` | **"Open with" dialog** (music apps) |
| 16 | BACK | — | system_button | — | `Success` | Back to Podcasts folder |
| 17 | element_index=4 | "Show roots" | `node_action_click` | (73, 191) | `Success: Clicked (73,191)` | **"Open with" dialog** again |
| 18 | BACK | — | system_button | — | `Success` | Back to Podcasts folder |
| 19 | element_index=4 | "Show roots" | `node_action_click` | (73, 191) | `Success: Clicked (73,191)` | **"Open with" dialog** again |

The agent loops this pattern from turn 15 to turn 30 — click "Show roots" → get "Open with" → press back → repeat. All clicks report success.

### 2. Overlapping bounds in the accessibility tree

The sanitized tree at seq 255 (pre-click state) shows these elements near coordinates (73, 191):

| Index | Text | Class | Clickable | Bounds | Contains (73,191)? |
|-------|------|-------|-----------|--------|---------------------|
| 4 | "Show roots" | ImageButton | **yes** | `[0, 128, 147, 254]` | **yes** |
| 5 | — | ScrollView | no | `[0, 128, 1080, 2400]` | yes |
| 6 | — | RecyclerView | no | `[0, 128, 1080, 2400]` | yes |
| **7** | **"Oct 15, 2023, 21 B, MP3 audio"** | **LinearLayout** | **yes** | **`[0, 128, 1080, 225]`** | **yes** |
| 8 | "Files in Podcasts" | TextView | no | `[63, 128, 890, 257]` | yes |

Element 4 ("Show roots", 147×126 px) and element 7 (file item row, 1080×97 px) both contain point (73, 191) and are both clickable. The file item's bounds `[0, 128, 1080, 225]` start at the **same top edge** (y=128) as the toolbar, creating a full-width overlap zone.

### 3. DFS z-order picks the file item

The typical DocumentsUI view hierarchy:

```
FrameLayout [0, 0, 1080, 2400]
  ├── Toolbar [0, 88, 1080, 254]           ← child 0
  │   └── ImageButton "Show roots" [0, 128, 147, 254]  ← clickable ✓
  └── ScrollView/RecyclerView [0, 128, 1080, 2400]     ← child 1 (LAST = higher z-order)
      └── LinearLayout (file item) [0, 128, 1080, 225] ← clickable ✓
```

`findClickableNodeAtLocation` traverses children in **reverse index order** (last child first = highest z-order). At (73, 191):

1. DFS enters ScrollView/RecyclerView **first** (it's the last child, checked before Toolbar)
2. Finds the file item LinearLayout at `[0, 128, 1080, 225]` — contains (73, 191) ✓, clickable ✓
3. Returns it immediately — **never reaches Toolbar/Show roots**

`performAction(ACTION_CLICK)` runs on the file item → opens the audio file → Android shows "Open with" chooser.

### 4. Post-click tree confirms the wrong action

The sanitized tree at seq 261 (after the click) shows:

```
Index 5: "Open with" (title)
Index 6: Retro Music (clickable)
Index 8: VLC (clickable)
Index 10: YouTube Music (clickable)
Index 12: "Just once" (disabled)
Index 13: "Always" (disabled)
```

This is the Android intent chooser for opening an audio file — confirming a file item was clicked, not the "Show roots" button.

## Why This Happens

The `findClickableNodeAtLocation` algorithm (DFS with reverse child iteration) is designed to find the **topmost z-order** node at a point. This works correctly for simple layouts where the visually-on-top element is the last child. But it breaks when:

1. A clickable container (RecyclerView item) has bounds that **overlap** with a separate clickable widget (toolbar button)
2. The container is a **later sibling** in the view hierarchy (higher z-order in the DFS)
3. The container is **not visually covering** the button — but its reported `getBoundsInScreen()` extends into the button's area

This creates a **perception-action mismatch**: the Perceptor correctly identifies "Show roots" as element 4 with center (73, 191), but the click dispatch's re-lookup at those same coordinates finds a different node due to the DFS z-order priority.

## Current Code Path

```
Agent selects element_index=4 ("Show roots", center=(73,191))
  → ClickExecutor.execute()
    → PointActionExecutorCore.executePointAction()
      → channels = [NODE_CLICK, GESTURE_TAP]
      → NodeActionPerformer.performNodeClickAt(73, 191)            # NodeActionPerformer.kt:20
        → withRoot { root → ... }                                   # Gets LIVE root (rootInActiveWindow)
          → AccessibilityNodeFinder.findClickableNodeAtLocation(root, 73, 191)
                                                                     # AccessibilityNodeFinder.kt:19-24
            → findActionableNodeAtLocation(root, 73, 191, predicate)
                                                                     # AccessibilityNodeFinder.kt:44-80
              → DFS: reverse child order (line 60)
              → RecyclerView child checked FIRST (last sibling)
              → Finds file item LinearLayout [0,128,1080,225] ← WRONG NODE
              → Returns immediately (line 63-65)
          → node.performAction(ACTION_CLICK) → true                  # NodeActionPerformer.kt:203
          → ActionResult.Success                                     # line 204
      → Success → return immediately, skip GESTURE_TAP               # PointActionExecutorCore.kt:83
```

The mismatch occurs between two different node-finding strategies:
- **Perception** (Perceptor): traverses all nodes, creates flat list, spatially sorts → "Show roots" = element 4
- **Action dispatch** (AccessibilityNodeFinder): DFS with z-order priority → finds file item, not "Show roots"

## Key Files

| File | Role |
|------|------|
| `platform/AccessibilityNodeFinder.kt:44-80` | `findActionableNodeAtLocation` — DFS with reverse child order |
| `platform/AccessibilityNodeFinder.kt:19-24` | `findClickableNodeAtLocation` — entry point for click dispatch |
| `platform/NodeActionPerformer.kt:20-28` | `performNodeClickAt` — resolves coordinates to node |
| `platform/NodeActionPerformer.kt:192-213` | `performNodeActionAt` — calls `performAction` on found node |
| `tool/action/PointActionExecutorCore.kt:75-99` | Channel fallback loop — exits on first Success |
| `perception/Perceptor.kt` | Tree traversal + spatial sort (assigns element indices) |

## Impact

- Agent enters **infinite loop** (15+ turns) clicking "Show roots" → "Open with" → back → repeat
- Task succeeds on the move operation but **fails verification** — agent cannot navigate to confirm the file was moved
- Any app with overlapping clickable containers in the accessibility tree is vulnerable
- The overlap pattern (toolbar button + list item at same y-offset) is common in Android apps with collapsible toolbars, bottom sheets, or dense header layouts

## Reproduction

1. Open Files app on Android emulator (API 34)
2. Navigate to any folder with files (e.g., Podcasts)
3. Dump accessibility tree — find "Show roots" button bounds and first file item bounds
4. Verify: file item bounds start at the same y-coordinate as the toolbar, creating overlap
5. Call `findClickableNodeAtLocation(root, showRootsCenter.x, showRootsCenter.y)` — observe it returns the file item, not the button
