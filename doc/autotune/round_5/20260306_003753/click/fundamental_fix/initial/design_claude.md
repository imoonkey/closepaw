# Click Execution Reliability: First-Principles Design

**Date**: 2026-03-06
**Author**: Claude (independent)
**Status**: Initial design

---

## Goal

Make click execution reliable within the reachable Android accessibility envelope. Separate proven platform/app limitations from our logic bugs. Centered on Files row click, generalized correctly.

---

## Evidence Summary

### Clean Files probe results

Setup: clean emulator, no overlay, no dev settings, accessibility service enabled, `task.html` in Downloads, FilesActivity launched directly.

Row structure:
- `item_root` LinearLayout `[0,667][1080,859]` clickable=true
- child title TextView `task.html` `[189,706][364,763]` not clickable
- icon element near `(94,761)` (clickable child within row)

| Mechanism | Coord | Transport | UI Effect |
|---|---:|---|---|
| `node_action_click` | (94,761) icon | success | **changed** |
| `node_action_click` | (540,763) row center | success | unchanged |
| `node_action_click` | (276,734) title center | success | unchanged |
| `gesture_tap` | (94,761) icon | success | unchanged |
| `gesture_tap` | (540,763) row center | success | unchanged |
| `gesture_tap` | (276,734) title center | success | unchanged |
| `adb input tap` | all three points | dispatched | unchanged |
| `node_action_long_click` | (540,763) | success | **changed** |
| `node_action_long_click` | (94,761) | success | **changed** |

### What this proves

1. **`node_action_click` is a working mechanism for this widget** — it works at the icon hotspot.
2. **The bug is hotspot-sensitive**: `node_action_click` at row center and title center finds the row node and performs ACTION_CLICK on it. The row's ACTION_CLICK is a no-op for file opening. The icon's ACTION_CLICK opens the file.
3. **gesture_tap and adb tap are genuinely weaker** for this DocumentsUI path — they fail at all tested points, including the icon hotspot.
4. **Not overlay** — no overlay was present.
5. **long_press works everywhere** on this row via the semantic node action path.

---

## Regression Analysis: Did Our Code Introduce This?

### What container promotion changed

Round-5 added `refinePointActionTarget` in `PointActionExecutorCore.kt` (lines 184-206). The logic:

1. Resolve semantic element (e.g., "task.html" text)
2. If element is not clickable → find smallest clickable container (the row)
3. **Return `container.center` as the click point** (line 201)

The row center is (540,763). `findClickableNodeAtLocation(540,763)` finds the row (the only clickable node whose bounds contain that point — the icon at ~(94,761) has bounds that don't extend to x=540). ACTION_CLICK on the row → no-op.

### What happened before container promotion

Before promotion, if the LLM targeted "task.html" text:
- Resolved point = text center (276,734)
- `findClickableNodeAtLocation(276,734)` → icon bounds likely don't extend to x=276, so the row is the only candidate → finds the row → ACTION_CLICK on row → no-op
- **But pre-Round-5 had no post-action verification**, so this was reported as **success** (false positive)

So the qi_note's memory of "当时是能调work了的" (it was working) has two possible explanations:

1. **Different target**: The earlier working session may have targeted the icon element directly (via element_index pointing to the icon, not the text). At the icon's center, `findClickableNodeAtLocation` finds the icon, and ACTION_CLICK works.
2. **False success masking**: Pre-Round-5 code reported dispatch acknowledgment as success. The click didn't actually open the file, but it looked like it worked because no verification existed.
3. **Different activity state**: NavigatedDocumentsUI in "open" mode vs. direct launch.

### Is container promotion a regression?

**Partially yes, partially no.**

- Container promotion did NOT break a previously working click. Before promotion, clicking the text's center found the row and no-oped — same outcome.
- But container promotion **moved the click point further from the icon** (from text center at x=276 to row center at x=540), making the icon even less reachable.
- The bigger regression was actually **post-action verification correctly exposing what was always broken**. We now correctly fail instead of falsely succeeding.

**The real fix** is not to revert anything. It's to make the system **select the right click target** — the icon, not the row.

---

## Separation: Platform/App Limits vs. Our Bugs

### Proven platform/app limitations

| ID | Limitation | Evidence |
|---|---|---|
| L1 | **gesture_tap doesn't open Files rows** | All 3 coordinates tested, including the icon hotspot. gesture_tap and adb tap unchanged everywhere. |
| L2 | **DocumentsUI row ACTION_CLICK is a no-op for "open"** | node_action_click at row center and title center: accepted but unchanged. The row is clickable=true but its click handler does not open files. |
| L3 | **ACTION_CLICK can return true without doing anything useful** | General Android behavior. clickable=true is a node property, not a guarantee that ACTION_CLICK does the user-expected thing. |

These are real limits. gesture_tap is not a viable channel for this widget. We cannot fix L2 or L3 in the app's code. We can only work within these constraints.

### Our bugs — already fixed

| ID | Bug | Fix |
|---|---|---|
| B1 | Non-actionable child was clicked without promotion | Container promotion added (Round 5) |
| B2 | Dispatch acknowledgment treated as success | Post-action verification added (Round 5) |
| B3 | Overlay consuming gesture_tap | OverlayTouchGate FLAG_NOT_TOUCHABLE (Feb 20) |

### Our bug — active, root cause of Files failure

| ID | Bug | Evidence |
|---|---|---|
| **B4** | **Container promotion selects `container.center`, missing clickable children** | node_action_click at icon (94,761) works. Promotion sends to row center (540,763) which finds the row, not the icon. The icon is a smaller clickable child within the row that our promotion logic ignores. |

This is the primary finding. The rest of this design is the fix plan for B4, plus secondary hardening.

---

## How `findClickableNodeAtLocation` Works (Why the Point Matters)

`AccessibilityNodeFinder.findClickableNodeAtLocation(root, x, y)`:
1. DFS the a11y tree
2. Collect all clickable+visible nodes whose bounds contain (x, y)
3. Return the one whose center is nearest to (x, y)

At **(94,761)**: both icon (~[24,679][168,847]) and row ([0,667][1080,859]) contain this point. Icon center is ~(96,763), distance ≈ 3. Row center is (540,763), distance ≈ 446. **Winner: icon.** ACTION_CLICK on icon → file opens.

At **(540,763)**: icon bounds don't extend to x=540, so only the row contains this point. **Winner: row.** ACTION_CLICK on row → no-op.

The finder algorithm is correct. **The only thing that matters is which point we feed in.** If we feed a point inside the icon, the icon wins. If we feed the container center, the row wins.

---

## Fix Plan

### Fix 1 (P0, CRITICAL): Smart child selection in container promotion

**Problem**: `refinePointActionTarget` line 201 returns `container.center`. This point is in the row's dead zone — no clickable child is found there except the row itself.

**Root cause**: The promotion logic finds the container but ignores its clickable children. It assumes the container is the right click target. But in compound rows, a specific child element (icon, thumbnail, button) is the real interaction target.

**Fix**: After finding the container, search for smaller clickable children within it. Prefer the one nearest to the original target point. Fall back to the container only if no clickable children exist.

```kotlin
private fun refinePointActionTarget(
    target: Target,
    resolved: TargetResolver.ResolveResult.Resolved,
    snapshot: ScreenSnapshot?
): TargetResolver.ResolveResult.Resolved {
    val snap = snapshot ?: return resolved
    val element = findResolvedElement(target, resolved, snap.elements) ?: return resolved
    if (element.isClickable || element.isLongClickable) return resolved

    val container = findBestActionableContainer(element.bounds, snap.elements)
        ?: return resolved

    // Prefer a clickable child within the container over the container itself.
    val bestTarget = findBestClickTargetInContainer(
        originalPoint = resolved.point,
        container = container,
        elements = snap.elements
    )

    val hint = SemanticTargetHint(
        resourceId = bestTarget.resourceId,
        text = bestTarget.text,
        description = bestTarget.description,
        className = bestTarget.className,
        bounds = bestTarget.bounds
    )
    return TargetResolver.ResolveResult.Resolved(
        point = bestTarget.center,
        bounds = bestTarget.bounds,
        semanticHint = hint,
        warnings = resolved.warnings
    )
}

/**
 * Among all clickable elements inside [container], find the one whose
 * center is nearest to [originalPoint]. If no children qualify, return
 * [container] itself.
 *
 * This handles compound rows where the container's ACTION_CLICK is a
 * no-op but a specific child (icon, thumbnail) is the real target.
 * The nearest-to-original heuristic respects the LLM's intent: if it
 * targeted text, the icon right next to the text is more relevant than
 * a distant overflow button.
 */
private fun findBestClickTargetInContainer(
    originalPoint: Point,
    container: PerceptionElement,
    elements: List<PerceptionElement>
): PerceptionElement {
    val children = elements.asSequence()
        .filter { (it.isClickable || it.isLongClickable) }
        .filter { it.index != container.index }
        .filter { container.bounds.contains(it.bounds) }
        .filterNot { it.bounds == container.bounds }
        .toList()

    if (children.isEmpty()) return container

    return children.minByOrNull { el ->
        val dx = (originalPoint.x - el.center.x).toLong()
        val dy = (originalPoint.y - el.center.y).toLong()
        dx * dx + dy * dy
    } ?: container
}
```

**Why this works for Files**: The row contains the icon (small, clickable, center ~(96,763)). `findBestClickTargetInContainer` finds the icon as a clickable child nearest to the text point (276,734). Returns icon center. `findClickableNodeAtLocation(96,763)` finds the icon. ACTION_CLICK on icon → file opens.

**Why this generalizes**: Any compound row — photo grids (thumbnail is clickable, not the card), app lists (icon is clickable, not the row), settings items (switch is clickable, not the text). The "nearest to original target" heuristic naturally gravitates toward the contextually correct child.

**What if the container has no clickable children?** Falls back to `container` itself — same behavior as today. No regression for simple cases.

**What if it picks the wrong child?** Post-action verification catches it (unchanged → channel failure → gesture_tap fallback). This is strictly better than today, where we always pick the container.

**File**: `PointActionExecutorCore.kt`

### Fix 2 (P1): Overlay non-touchable during agent execution

**Problem**: `CapsuleOverlayHost.createLayoutParams` uses `MATCH_PARENT` width (line 288). `shouldCapsuleOverlayBeTouchable` returns true for all non-Hidden modes. During agent execution, the transparent full-width overlay strip can intercept touches even when the visible capsule is small.

The touch gate protects `dispatchGesture` calls, but the window is still touchable between gesture dispatches — this can affect the post-action capture window and any other touch-sensitive timing.

**Fix**: Make overlay non-touchable during execution modes:

```kotlin
// OverlayLocationPolicy.kt
internal fun shouldCapsuleOverlayBeTouchable(mode: CapsuleMode): Boolean = when (mode) {
    is CapsuleMode.Hidden -> false
    is CapsuleMode.Running -> false
    is CapsuleMode.Thinking -> false
    else -> true
}
```

**Rationale**: During Running/Thinking, the agent owns the screen. The overlay should not compete with the agent's actions. The user can still stop the agent via the notification or other controls.

**File**: `OverlayLocationPolicy.kt`

### Fix 3 (P2): UiChangeDetector false-identical on decode failure

**Problem**: `computeImageFingerprint` returns `0L` when JPEG decode fails. Two decode failures produce identical hashes → false "unchanged".

**Fix**: Return `null` on decode failure. In comparison: if either fingerprint is null, return `Unverifiable` instead of treating them as equal.

**File**: `UiChangeDetector.kt`

### Fix 4 (P3): Gesture tap duration

**Problem**: `injectTap` uses `ViewConfiguration.getTapTimeout()` (~100ms) as stroke duration. This is the detection timeout, not the ideal gesture duration.

**Fix**: Use 150ms constant. Below long-press threshold (~400ms). Matches what `adb input tap` uses.

**Note**: This does NOT fix the Files row (gesture_tap failed at all points including icon). But it improves general gesture fidelity for edge-case widgets.

**File**: `AccessibilityGestureInjector.kt`

---

## Explicitly Rejected

### Double-tap as a third channel

Rejected. node_action_click at the right point already works. The problem is target selection, not mechanism variety. Double-tap also has semantic ambiguity (zoom, expand) and would fire on every failed single-tap, risking side effects.

### "Keep container.center but add more fallback channels"

Wrong layer. All mechanisms at (540,763) fail. Adding more mechanisms at the wrong point is more ways to fail. Fix the point.

### "Use original child point unchanged"

Insufficient. Original point (276,734) also finds the row. The icon bounds don't extend to x=276. We need to actively find the icon, not just avoid moving the point.

### "Semantic node re-query replacing point-based finder"

Over-engineering. `findClickableNodeAtLocation` works perfectly when given the right point. The probe proves this. The architecture isn't broken — one function picks the wrong input.

### "App-specific Files workaround"

Premature. Fix the general mechanism first. If it works (and the evidence says it will), no app-specific code needed.

---

## Execution Order

| Priority | Fix | Effort | Impact |
|---|---|---|---|
| **P0** | Smart child selection in container promotion | ~30 lines in PointActionExecutorCore | Directly fixes Files click and generalizes to compound rows |
| **P1** | Overlay non-touchable during execution | ~5 lines in OverlayLocationPolicy | Eliminates overlay interference during agent work |
| **P2** | UiChangeDetector hash safety | ~10 lines in UiChangeDetector | Correctness |
| **P3** | Gesture tap duration | 1 line in AccessibilityGestureInjector | Minor fidelity |

### Validation

After P0:
1. Clean Files probe: click "task.html" → expect promotion picks icon → file opens
2. BrowserDraw + BrowserMultiply eval: expect Files row opens without long-press workaround
3. Full eval suite: expect no regressions on clean tasks
4. Multi-child row test (e.g., Settings toggle row): verify nearest-child heuristic

After P1:
5. Overlay interference probe: gesture_tap in capsule strip area during Running mode

---

## State Machine (with Fix 1 applied)

```
click(semantic_target) →
  resolve → child element point + bounds
  ↓
  child is clickable? → keep, done
  child is NOT clickable? →
    find smallest clickable container
    ↓
    container has clickable children?
      YES → pick child nearest to original point → use child.center
      NO  → use container.center
  ↓
  bounds check
  ↓
  channel loop [node_action_click, gesture_tap]:
    dispatch(point, semantic_hint)
    verify(post_action_capture)
    changed? → Success
    unchanged? → next channel
  ↓
  all channels exhausted → Failure
```
