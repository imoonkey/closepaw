# Click Reliability Redesign (Codex)

## Background
Current `click` can return `success` when Android API reports dispatch success (`ACTION_CLICK=true` or gesture callback completed), but the UI may not actually change. This creates false-positive tool outcomes and wastes turns.

Related code paths:
- `app/src/main/kotlin/com/moonkey/androidagent/tool/handlers/ClickTargetInvocation.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/handlers/TargetingInvocationUtils.kt`

Reference repos under `.reference/mobile_agent/` mostly treat transport success as action success (ADB click/tap returned), so they do not directly solve this issue for accessibility-based execution.

## Problem Statement
We currently conflate two different notions of success:
1. **Action dispatch success**: platform accepted click command.
2. **Interaction success**: UI state changed as expected.

For agent control, (2) is what matters. Without it, planner/executor receives misleading feedback.

## Goals
1. Split click execution into **atomic attempts** by API strategy.
2. Run attempts in selector fallback order aligned with planner intent: `element_index -> text -> coordinates`.
3. Add shared post-action detection: if no observable UI change, continue fallback instead of returning immediate success.
4. Improve failure output with per-attempt reasons.

## Non-goals
1. No semantic task-level verification (e.g., "form submitted") in this change.
2. No broad refactor of long_press/swipe in this iteration.
3. No dependency on ADB tooling.

## Design

### 1) Atomic Click APIs
Introduce explicit atomic click actions in `UIAction` and platform:
- `ClickNodeAt(x, y)`: try accessibility `ACTION_CLICK` only.
- `TapAt(x, y)`: try gesture tap only.

This removes hidden API fallback coupling from invocation-level decision logic.

### 2) Selector-to-attempt Plan
`ClickTargetInvocation` builds a flat attempt plan:
- `element_index` (if provided):
  - `ClickNodeAt(center)`
  - `TapAt(center)`
- `text` (if provided and resolvable):
  - resolve to element center
  - `ClickNodeAt(center)`
  - `TapAt(center)`
- `x,y` (if provided):
  - `ClickNodeAt(x,y)`
  - `TapAt(x,y)`

Notes:
- `bounds` is removed from click targeting path.
- Coordinate attempts are not blocked by `hasActionableElementAt` precheck.

### 3) Shared Post-click Change Detection
Add shared utility in `TargetingInvocationUtils`:
- Compare pre-snapshot and post-snapshot fingerprints.
- Fingerprint includes stable UI fields (`resourceId`, `className`, `text`, `description`, `bounds`, `isFocused`, `isEnabled`).
- If unchanged after a successful atomic attempt, mark as "no observable UI change" and continue fallback.

### 4) Click Success Contract
`click` returns `Success` only when:
- action dispatch succeeded, and
- snapshot change is observed (or change cannot be checked because snapshot/observation unavailable).

If all attempts fail or stay unchanged, return `Failure` with attempt trail.

## Why this improves reliability
1. Prevents false-positive success from transport-level acknowledgements.
2. Makes retries deterministic and explainable.
3. Keeps logic local to click invocation rather than hidden in platform fallback chains.

## Compatibility
- `click` validation no longer accepts bounds (`x1,y1,x2,y2`).
- `mobile_action` global schema keeps bounds fields because they are still used by `long_press` / directional `swipe` targeting.

## Tests
Update and add unit tests in `app/src/test/kotlin/com/moonkey/androidagent/tool/handlers/TargetInvocationsTest.kt`:
1. Click coordinate with null snapshot uses atomic API path.
2. Selector fallback order follows `element_index -> text -> coordinates` and atomic API order per selector.
3. New: successful dispatch with unchanged screen triggers retry and only succeeds when later attempt changes UI.

## Rollout / follow-up
1. Apply same "dispatch success != interaction success" contract to `long_press` and optionally directional `swipe`.
2. Consider adding optional per-action settle timeout tuning for slower apps.
