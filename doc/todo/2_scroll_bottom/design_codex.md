status: draft

# Scroll-Bottom Detection Design (Codex)

Date: 2026-02-16
Goal: make downward scrolling reliably detect "reached bottom" and expose that signal to the agent so it stops blind repeat swipes.

## Problem
Current behavior in `SwipeExecutor` detects boundary only by comparing pre/post element text+bounds (`UiChangeDetector.detectScrollBoundary`).

This is useful but weak:
1. `dispatchGesture` success does not mean content actually scrolled.
2. Repeated or sticky content can look unchanged even when scroll happened.
3. Some apps emit weak a11y trees; snapshot comparison alone is noisy.
4. No direct use of semantic scroll signals (`TYPE_VIEW_SCROLLED`, `scrollY/maxScrollY`, `toIndex/itemCount`).

## Scope
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/SwipeExecutor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/UiChangeDetector.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/NodeActionPerformer.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`
- New scroll-signal and semantic-scroll helper files under `platform/` + `tool/action/`.

## Design Principles
1. Prefer semantic scrolling over raw gestures when possible.
2. Use multi-signal verification, not one heuristic.
3. Keep `AndroidPlatform` atomic and backward-compatible.
4. Degrade gracefully on unsupported platforms (VD/custom views).
5. Keep agent-facing output explicit: progress vs boundary vs unknown.

## Non-Goals
1. Perfect bottom detection for every custom OpenGL/WebView app.
2. Adding a new public tool API in this phase.
3. Large planner prompt redesign.

## Proposed Architecture

### 1) Add semantic scroll capability (optional interface)
Introduce a capability interface instead of expanding `AndroidPlatform`:

```kotlin
interface SemanticScrollCapability {
    suspend fun performSemanticScroll(direction: ScrollDirection, anchor: Target?): ActionResult
}
```

- `AccessibilityPlatform` implements it.
- `VirtualDisplayPlatform` does not implement (gesture fallback remains).

Implementation in `NodeActionPerformer`:
1. Resolve candidate scrollable node (prefer target ancestor, then center-nearest scrollable node).
2. Try `ACTION_SCROLL_FORWARD` (for scroll down intent) or `ACTION_SCROLL_BACKWARD`.
3. Return `Success`/`Failure` only for dispatch result (same semantics as other atomic actions).

### 2) Add scroll signal monitor (optional interface)
Introduce a lightweight event monitor fed by `AgentService.onAccessibilityEvent`.

```kotlin
interface ScrollSignalCapability {
    suspend fun awaitScrollSignal(
        sinceUptimeMs: Long,
        timeoutMs: Long,
        packageName: String?
    ): ScrollSignal?
}
```

`ScrollSignal` keeps minimal fields:
- `eventTimeMs`, `packageName`, `windowId`
- `scrollY`, `maxScrollY`, `scrollX`, `maxScrollX`
- `fromIndex`, `toIndex`, `itemCount`

`AgentService` currently handles only `TYPE_WINDOW_STATE_CHANGED`; extend it to also push `TYPE_VIEW_SCROLLED` into a small in-memory flow/buffer consumed by `AccessibilityPlatform`.

### 3) Add `ScrollBoundaryEvaluator` (single source of truth)
Create a dedicated evaluator in `tool/action/` and remove duplicated boundary logic.

Inputs:
- pre/post snapshot change result
- optional `ScrollSignal`
- attempted direction

Output:

```kotlin
enum class ScrollOutcome {
    Progressed,
    ReachedBoundaryStrong,
    ReachedBoundaryLikely,
    Unverifiable
}
```

Rules (ordered):
1. Strong boundary if signal proves end:
- vertical: `maxScrollY > 0 && scrollY >= maxScrollY`
- list-style: `itemCount > 0 && toIndex >= itemCount - 1`
2. Progressed if snapshot changed or scroll signal received without boundary proof.
3. Likely boundary if no signal and snapshot unchanged.
4. Unverifiable otherwise.

### 4) Update `SwipeExecutor` attempt policy
Replace single-attempt swipe with capability-aware chain:

1. Semantic attempt (if platform supports it and direction is vertical)
- dispatch semantic scroll
- await scroll signal (short timeout, e.g. 350-500ms)
- capture post snapshot
- evaluate via `ScrollBoundaryEvaluator`

2. Gesture attempt (existing swipe path)
- current geometry logic stays
- await scroll signal when capability exists
- capture post snapshot
- evaluate via same evaluator

3. Return message with explicit status token:
- `scroll_status=progressed`
- `scroll_status=boundary_strong`
- `scroll_status=boundary_likely`
- `scroll_status=unverifiable`

This keeps the current LLM-facing text flow but adds a deterministic machine-readable hint.

### 5) Unify legacy swipe-boundary calls
`UIActionInvocation` currently has its own swipe boundary detection helper.

Refactor:
1. remove local duplicate comparison logic in `UIActionInvocation`
2. reuse `ScrollBoundaryEvaluator` + `UiChangeDetector` helper
3. keep warning text consistent across both invocation paths

## Agent Behavior Impact
1. Boundary conditions become explicit in tool output, so planner/executor can stop repeated scrolling earlier.
2. Existing loop detection (`NavigationState` + `LoopDetectionPolicy`) remains intact and benefits from clearer tool feedback.
3. No behavior change for non-scroll actions.

## Failure Handling
1. If semantic scroll unavailable or fails: fallback to gesture swipe.
2. If no scroll signal capability: rely on snapshot diff path.
3. If snapshot capture fails: return `unverifiable` rather than fake success.

## Testing Plan

### Unit tests
1. `ScrollBoundaryEvaluatorTest`
- `scrollY/maxScrollY` boundary cases
- `toIndex/itemCount` boundary cases
- unchanged-without-signal => likely boundary
- changed snapshot => progressed

2. `SwipeExecutorTest`
- semantic success + strong boundary signal
- semantic failure -> gesture fallback success
- no capability path retains old behavior

3. `NodeActionPerformerTest` (mocked tree)
- chooses scrollable ancestor
- falls back to nearest scrollable container

### Integration tests (instrumentation)
1. RecyclerView screen: verify bottom reached emits `boundary_strong`.
2. ScrollView screen: verify `scrollY/maxScrollY` path.
3. No-scroll screen: verify immediate likely boundary.

## Rollout Plan

### Phase 1
- Introduce capability interfaces and `ScrollBoundaryEvaluator`.
- Wire signal capture from `AgentService`.
- Keep existing swipe path; add evaluation only.

### Phase 2
- Add semantic scroll in `NodeActionPerformer` + `AccessibilityPlatform`.
- Enable semantic-first policy in `SwipeExecutor`.

### Phase 3
- Remove duplicate legacy boundary code (`UIActionInvocation`).
- Tune message wording and timeout constants from debug-run traces.

## Risks
1. Event matching ambiguity in multi-window transitions.
- Mitigation: filter by package + time window, keep timeout short.

2. Some apps do not populate scroll metrics.
- Mitigation: fallback to snapshot-based likely boundary.

3. Added complexity in swipe flow.
- Mitigation: isolate complexity in `ScrollBoundaryEvaluator` and capability interfaces.

## Verification
1. `./gradlew test`
2. `./scripts/debug-run.sh "Scroll to bottom of Settings list"`
3. Confirm logs/tool output include `scroll_status` and that repeated swipes stop near boundary.
