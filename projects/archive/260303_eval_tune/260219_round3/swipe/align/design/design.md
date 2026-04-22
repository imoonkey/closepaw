# Swipe Redesign - Aligned Design (Post-Review)

## Status: FINAL (reviewed by Claude + Codex, consolidated)

---

## 1. Final Decisions

1. Add a11y scroll action layer before gesture dispatch for directional swipes.
2. Use coordinate-based `UIAction.ScrollNodeAt(x, y, scrollAction)`, not nodeId-based.
3. Prefer API 23+ directional scroll actions; fall back to FORWARD/BACKWARD.
4. Fix symmetric endpoint calculation with asymmetric start/end and increased distance factors.
5. Target resolve failure for targeted swipes returns error, not silent center-fallback.
6. Dynamic settle delay scaled to swipe duration, not fixed 300ms.
7. NoChange result stays `ActionOutcome.Success` with enhanced warning in P0; structured `noEffect` flag in P1.
8. Keep single `mobile_action(action="swipe")` tool; optional `swipe_intent` field in P1.
9. No cognition-layer stall policy change — prompt-level escalation hints only.
10. No feature flag for this redesign; direct replacement with clean git revert path.
11. Keep external contracts stable: `mobile_action` schema, `ToolRouter`, `AndroidPlatform`, `UIAction` sealed interface.
12. `performAction` return `false` → skip screen capture, go directly to gesture fallback.

---

## 2. Why These Decisions Match Current Code Reality

1. `SwipeExecutor` is gesture-only today (explicit comment: "No node-based fallback — swipe is gesture only"). Adding a11y scroll action as a first-try layer mirrors the proven ClickExecutor pattern (node action → gesture fallback) without changing the gesture path.
2. Current `UIAction` uses coordinate-based patterns (`ClickNodeAt(x, y)`, `TapAt(x, y)`). `PerceptionElement` exposes `element_index` (snapshot-derived, re-indexed each turn) but no stable native nodeId. Coordinate-based `ScrollNodeAt` is consistent and implementable.
3. `isScrollable` is already exposed in `PerceptionElement` (Models.kt:115) and included in the LLM-facing JSON (Perceptor.kt:141). Finding scrollable nodes requires no new perception work.
4. Current `computeEndpoints()` spreads symmetrically from origin: `origin +/- delta`. When origin is near a screen edge, safe-inset clamping reduces effective distance by 50-75% (eval evidence: 190px instead of 389px for ExpenseAddSingle). The asymmetric fix addresses this directly.
5. `ActionOutcome` already has `Success.verified` boolean flag. Adding `noEffect: Boolean` follows the same pattern.
6. `LoopDetectionPolicy.maxConsecutiveScrollActions=5` already exists. The existing prompt-level hint ("after 2 unchanged swipes, change strategy") is sufficient without code-level policy changes.

---

## 3. Problem Statement

Eval run `20260219_124436`: 29 swipe actions, 100% tool-level success, but only
~7–10 out of 29 actually progressed the task. The swipe action is the single
largest source of wasted agent turns.

Key failure modes:
- Non-scrollable containers receiving repeated swipe gestures (RecipeAddSingleRecipe: 8/8 failed)
- Horizontal RecyclerView swipes with insufficient distance (ExpenseAddSingle)
- Direction confusion in LLM ("scroll down" → `direction="down"` which is wrong)
- No accessibility scroll action fallback (gesture-only execution)
- Agent retries same failing strategy without escalation

---

## 4. Design Principles

1. **Container-first**: Identify scrollable containers before swiping
2. **Verifiable outcome**: Return structured signals about what happened
3. **Early bail-out**: Prompt-level guidance to change strategy after repeated failure
4. **Intent clarity**: Distinguish scrolling (find content) from precision dragging (slider)

---

## 5. Directional Swipe Execution Contract (P0)

Pipeline:

1. Parse target (`element_index` / `text` / coordinate / none).
2. If targeted: resolve target to `(x,y)` via `TargetResolver`. **Fail if unresolved.**
3. If untargeted: use screen center as origin.
4. Find scrollable node containing origin (`isScrollable=true` in current snapshot).
5. If scrollable node found:
   a. Map direction to scroll action (see mapping table below).
   b. Try `performAction(scrollAction)` on that node via `UIAction.ScrollNodeAt(x, y, action)`.
   c. If `performAction` returns `false`: skip screen capture, go to step 6 (dispatch failure is definitive).
   d. If `performAction` returns `true`: capture screen + compare.
      - If screen changed: settle delay, return success with observation.
      - If unchanged: fall through to step 6.
6. Compute asymmetric gesture endpoints from origin.
7. Dispatch `UIAction.Swipe` once.
8. Dynamic settle delay: `max(200, (durationMs * 0.75).toLong()).coerceAtMost(800)`.
9. Capture screen + `detectScrollBoundary`.
10. Return `ActionOutcome.Success` with observation (+ no-change warning if boundary detected).

**Explicitly disallowed:**

- No retry loop in SwipeExecutor
- No silent center-fallback for targeted swipes that fail resolution
- No `ActionOutcome.Failed` for executed-but-unchanged gestures (warning only in P0)
- No per-attempt UiChangeDetector success gating (boundary detection is informational)
- No cognition-layer stall policy code changes

---

## 6. Direction → Scroll Action Mapping

Prefer API 23+ directional actions; fall back to FORWARD/BACKWARD when directional
actions are not in the node's declared action list.

| Agent direction | Content effect | Primary action (API 23+) | Fallback action (API 16+) |
|-----------------|---------------|--------------------------|---------------------------|
| `"up"` | Content scrolls DOWN | `ACTION_SCROLL_DOWN` | `ACTION_SCROLL_FORWARD` |
| `"down"` | Content scrolls UP | `ACTION_SCROLL_UP` | `ACTION_SCROLL_BACKWARD` |
| `"left"` | Content scrolls RIGHT | `ACTION_SCROLL_RIGHT` | `ACTION_SCROLL_FORWARD` |
| `"right"` | Content scrolls LEFT | `ACTION_SCROLL_LEFT` | `ACTION_SCROLL_BACKWARD` |

Semantics note: `ACTION_SCROLL_DOWN` means "the viewport scrolls down, revealing content
below." This matches `direction="up"` (finger up → content below comes into view).

Implementation:
```kotlin
private fun resolveScrollAction(
    direction: String,
    nodeActions: List<AccessibilityAction>
): Int? {
    val (primary, fallback) = when (direction) {
        "up"    -> ACTION_SCROLL_DOWN to ACTION_SCROLL_FORWARD
        "down"  -> ACTION_SCROLL_UP to ACTION_SCROLL_BACKWARD
        "left"  -> ACTION_SCROLL_RIGHT to ACTION_SCROLL_FORWARD
        "right" -> ACTION_SCROLL_LEFT to ACTION_SCROLL_BACKWARD
        else    -> return null
    }
    return when {
        nodeActions.any { it.id == primary } -> primary
        nodeActions.any { it.id == fallback } -> fallback
        else -> null
    }
}
```

Note: FORWARD/BACKWARD are orientation-dependent (FORWARD = down for vertical, right for
horizontal LTR). The directional actions avoid this ambiguity. The fallback to FORWARD
for `"left"` is imperfect (assumes horizontal LTR) but acceptable because:
1. If directional actions are declared (API 23+, standard widgets), they take priority.
2. If only FORWARD/BACKWARD are available, the container is likely a simple vertical
   ScrollView where left/right scroll is uncommon.
3. Gesture fallback catches the mismatch case.

---

## 7. Resolved Changes

### 7.1 [P0] A11y Scroll Action Fallback

For directional scroll intents, try a11y scroll action on the nearest scrollable
container before falling back to gesture dispatch.

**Rationale**: Mirrors the existing ClickExecutor pattern (node action → gesture fallback).
The a11y scroll actions work at framework level and bypass gesture interception issues
(keyboard blocking, custom views, etc.).

**Implementation**:
- Find scrollable node containing the resolved origin point (`isScrollable` flag from snapshot)
- Scrollable node selection: largest `isScrollable=true` node whose bounds contain the origin
- Resolve scroll action via direction mapping table (Section 6)
- Check node's declared `actionList` for supported actions
- If `performAction` returns `false` → go directly to gesture (no screen capture)
- If `performAction` returns `true` → capture + compare; if unchanged, fall through to gesture
- New `UIAction.ScrollNodeAt(x: Int, y: Int, scrollAction: Int)` variant

**Files**: `SwipeExecutor.kt`, `UIAction.kt`, `AccessibilityPlatform.kt`,
`NodeActionPerformer.kt`, `AccessibilityNodeFinder.kt`, `VirtualDisplayPlatform.kt`

### 7.2 [P0] Fix Directional Swipe Geometry (Edge Clamping)

Symmetric `origin +/- delta` calculation loses 50-75% of intended distance when origin
is near a screen edge. Replace with asymmetric endpoint calculation.

**Implementation**:
- Start 1/3 of distance from origin in opposite direction
- End at full distance from start
- Clamp start first, then compute end relative to clamped start
- Increase distance factors: short 15%→25%, medium 40%→50%, long 70%→80%

**Files**: `SwipeExecutor.kt` — `computeEndpoints()`, `computeDistancePx()`

### 7.3 [P0] Target Resolve Failure → Fail for Targeted Scroll

When the LLM specifies a swipe target (`element_index` / `text` / `x,y`) and
resolution fails, return failure instead of silently falling back to screen center.

- Keep center-origin only for untargeted directional swipes (no explicit target provided)
- Removes silent wrong-container swipes while preserving backward compat for simple use

**Files**: `SwipeExecutor.kt` — `executeDirectionalSwipe()`

### 7.4 [P0] Dynamic Settle Delay

Scale UI settle delay with swipe duration instead of fixed 300ms.
```
settleMs = max(200, (durationMs * 0.75).toLong()).coerceAtMost(800)
```

Produces: 400ms swipe → 300ms settle (current behavior), 1000ms → 750ms, 1200ms → 800ms cap.

**Files**: `SwipeExecutor.kt` — `dispatchSwipe()`

### 7.5 [P0] Prompt Guidance Improvement

Explicit rules in Executor + Standalone + Planner prompts:
- Direction semantics: "direction=up means finger moves up, content scrolls DOWN"
- Container targeting: "prefer specifying element_index of scrollable container"
- No-change escalation: "after 2 consecutive unchanged swipes, change strategy
  (different direction, click-based navigation, or report obstacle)"
- Scroll vs drag: "use direction+distance for scrolling lists, use start/end coords
  for slider drags and precision gestures"

**Files**: `ExecutorAgentDef.kt`, `StandaloneAgentDef.kt`, `PlannerAgentDef.kt`

### 7.6 [P0] Enhanced No-Change Warning Message

Keep tool call transport as `ActionOutcome.Success` for executed gestures.
Enhance warning message with actionable escalation hints when screen is unchanged.

Warning includes: specific suggestion to try opposite direction, click-based navigation,
or report that scrolling appears blocked.

**Files**: `SwipeExecutor.kt` — `dispatchSwipe()`

### 7.7 [P1] scroll/drag Intent Separation

Keep single `mobile_action(action="swipe")` tool. Add optional
`swipe_intent: "scroll" | "drag"` field.

- Default intent inference (backward-compatible):
  - `start/end` present → `drag`
  - `direction` present → `scroll`
- Conflict rule: if `direction` is present and `swipe_intent: "drag"`, ignore `swipe_intent`
  (direction-based parameter shape takes precedence)
- `expected_effect` stays optional and descriptive in v1 (trace metadata, not
  hard-gating executor success)
- `container_element_index` is optional; executor auto-selects best scrollable
  ancestor when omitted

**Files**: `MobileActionTool.kt` schema/validation

### 7.8 [P1] No-Effect Structured Marker → Cognition Context

Wire structured `noEffect` flag from SwipeExecutor through to cognition context.

- Add `noEffect: Boolean = false` field to `ActionOutcome.Success` (alongside existing `verified`)
- SwipeExecutor sets `noEffect = true` when boundary detected
- `MobileActionInvocation` / `TurnExecutionPhaseRunner` reads the flag for cognition
  context injection (e.g., appending to action classification: `scroll:up:no_effect`)
- Enables future policy decisions without string parsing

**Files**: `ActionOutcome.kt`, `SwipeExecutor.kt`, `MobileActionInvocation.kt`,
`TurnExecutionPhaseRunner.kt`

### 7.9 [DEFERRED] Observation Signal Enhancement — P2

- Keep current pre/post a11y fingerprinting as primary signal
- Defer `TYPE_VIEW_SCROLLED` event stream integration (requires service-level
  buffering + threading design, materially larger scope)
- Defer container signature tracking for per-container stall detection

---

## 8. External Contract Stability

The following external contracts are **unchanged** in P0:

- `mobile_action` JSON schema (no new required fields)
- `MobileActionTool` validation and routing
- `ToolRouter` dispatch
- `AndroidPlatform` public interface (new `ScrollNodeAt` action is additive, not breaking)
- `UIAction` sealed interface (new variant is additive)
- `ActionOutcome` interface (new `noEffect` field has default value, backward-compatible)

P1 adds optional `swipe_intent` and `expected_effect` fields to the tool schema.
These are optional with defaults, so existing tool calls remain valid.

---

## 9. File-Level Change Plan

### P0 — Modify

| File | Change |
|------|--------|
| `SwipeExecutor.kt` | Add scroll action layer, fix geometry, target resolve failure, dynamic settle, enhanced warning |
| `UIAction.kt` | Add `ScrollNodeAt(x, y, scrollAction)` variant |
| `AccessibilityPlatform.kt` | Handle `ScrollNodeAt` in `performAction` switch |
| `VirtualDisplayPlatform.kt` | Handle `ScrollNodeAt` in `performAction` switch (throw / no-op with log, since virtual display may not have a11y node tree) |
| `NodeActionPerformer.kt` | Add `performScrollAt()` method — find scrollable node at/containing (x,y), perform scroll action |
| `AccessibilityNodeFinder.kt` | Add `findScrollableNodeAt(x, y)` — find nearest scrollable node containing point |
| `ExecutorAgentDef.kt` | Updated scroll/swipe prompt guidance |
| `StandaloneAgentDef.kt` | Updated scroll/swipe prompt guidance |
| `PlannerAgentDef.kt` | Updated scroll/swipe prompt guidance |

### P0 — Unchanged

| File | Reason |
|------|--------|
| `MobileActionTool.kt` | Schema unchanged in P0 |
| `TargetResolver.kt` | Already returns typed result; no change needed |
| `ClickExecutor.kt` | Unrelated |
| `LongPressExecutor.kt` | Unrelated |
| `ActionOutcome.kt` | Unchanged in P0 (P1 adds `noEffect` field) |
| `AccessibilityGestureInjector.kt` | Gesture injection unchanged |
| `UiChangeDetector.kt` | Detection logic unchanged |
| `LoopDetectionPolicy.kt` | No policy threshold changes |

### P1 — Modify

| File | Change |
|------|--------|
| `MobileActionTool.kt` | Add optional `swipe_intent`, `expected_effect` fields |
| `ActionOutcome.kt` | Add `noEffect: Boolean = false` to `Success` |
| `SwipeExecutor.kt` | Set `noEffect` flag on boundary detection |
| `MobileActionInvocation.kt` | Read `noEffect` flag |
| `TurnExecutionPhaseRunner.kt` | Include `no_effect` in action classification |

---

## 10. Risks

1. **Double screen capture latency**: Scroll-action-then-gesture path captures screen
   twice when scroll action succeeds but produces no change. Mitigated by skipping capture
   when `performAction` returns `false` (dispatch failure). Monitor actual latency impact.
2. **isScrollable false positives**: Containers that report `isScrollable=true` but don't
   actually scroll trigger a wasted scroll action attempt. Correctness is preserved by
   gesture fallback; monitor whether latency matters.
3. **Cross-platform coverage**: `VirtualDisplayPlatform` must handle `ScrollNodeAt`. If
   virtual display path lacks a11y node tree, the action should no-op gracefully and fall
   through to gesture.
4. **FORWARD/BACKWARD orientation ambiguity**: When directional actions (API 23+) are
   absent and only FORWARD/BACKWARD are available, `"left"` → FORWARD may produce wrong
   direction on vertical containers. Gesture fallback handles this.

---

## 11. Validation Plan

### Unit tests

1. Scrollable node found → scroll action dispatched before gesture.
2. Scroll action `performAction` returns `false` → gesture fallback, no intermediate screen capture.
3. Scroll action succeeds, screen unchanged → fall through to gesture.
4. Scroll action succeeds, screen changed → return success (no gesture).
5. Target resolve failure for targeted swipe → `ActionOutcome.Failed`.
6. Untargeted directional swipe uses screen center (not failure).
7. Asymmetric endpoint calculation produces correct distance near screen edges.
8. Dynamic settle delay scales correctly: 400ms→300ms, 1000ms→750ms.
9. Direction mapping: "up" → ACTION_SCROLL_DOWN (primary), ACTION_SCROLL_FORWARD (fallback).

### Regression targets

- `ExpenseAddSingle`: horizontal scroll effectiveness (geometry fix)
- `RecipeAddSingleRecipe`: form scrolling (scroll action fallback)
- `FilesMoveFile`: direction correctness (prompt fix)
- `SystemBrightnessMinVerify`: no regression on slider drag

### Metrics

- `scripted_success_rate` improvement
- `swipe_no_change_ratio` target: <40% (from current 65%)
- `MaxTurnsReached` rate reduction

### Eval sequence

1. `aw_subset_smoke.txt` — quick sanity
2. `aw_subset_core.txt` — full comparison against baseline

### P1 gate

P1 changes ship in same series if P0 eval shows no regressions. Otherwise next patch.
Decision based on eval evidence, not schedule.
