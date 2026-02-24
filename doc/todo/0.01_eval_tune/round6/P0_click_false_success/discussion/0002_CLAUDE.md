# 0002_CLAUDE — Code-Grounded Review & Simplifications

## What I did

- Read all codebase files in the execution path: `PointActionExecutorCore.kt`, `ClickExecutor.kt`, `ActionPriorityOrder.kt`, `UiChangeDetector.kt`, `ActionOutcome.kt`, `MobileActionInvocation.kt`, `LongPressExecutor.kt`
- Verified existing infrastructure against Codex's draft — found significant reuse opportunities
- Made targeted edits to `final/p0_click_false_success_design.md` (3 sections changed, details below)

## Consensus — Fully Agreed

- Root cause: `performAction(ACTION_CLICK)=true` is acceptance, not effect confirmation
- Fix: effect detection in the channel fallback loop, transparent attempt trail
- One fallback max (`node → gesture`), no chain retry
- `no_change ≠ failure` — use `success_unverified` semantics

## Changes I Made to the Design

### 1. No new `ScreenChangeDetector.kt` — use existing `UiChangeDetector`

`UiChangeDetector.kt` already exists with:
- `compare(pre, post) → Changed | Unchanged | Unverifiable`
- FNV-1a hash over a11y elements (primary)
- 8x8 perceptual hash of screenshot (fallback for empty a11y trees)
- Currently used in `DebugActionExecutor` — just needs to be called in the main path

Creating a new `ScreenChangeDetector.kt` would duplicate this. Updated Section 5 accordingly.

### 2. `click_mode` deferred (YAGNI)

Marked Section 3.3 as deferred. Reasoning:
- Zero eval evidence that the LLM needs explicit channel selection
- Adds schema complexity to every tool call for every action
- The code already makes it trivial to add later (filter `ActionPriorityOrder.click` list)
- `auto` is the right default — that's the whole point of this fix

### 3. Algorithm refined (Section 4)

- Used `UiChangeDetector.ChangeResult` terminology (`Changed/Unchanged/Unverifiable`)
- Clarified: `Unverifiable` triggers fallback same as `Unchanged` (Q3 resolved)
- Added post-loop logic: if any dispatch succeeded but no change verified → `Success(verified=false)`
- Added cancellation handling in the loop

## Answered Open Questions

**Q1: `success_unverified` semantics**
→ Success-with-warning. Not retryable failure. The downstream wiring already exists: `MobileActionInvocation.mapOutcome` appends `[unverified]` to tool output when `verified=false`. The LLM agent decides context-specifically whether to proceed or adjust.

**Q2: `click_mode` exposure**
→ Defer. YAGNI. No eval evidence of need. Trivial to add later.

**Q3: `Unverifiable` triggers fallback?**
→ Yes. `Changed` → stop; everything else → continue. Cost of one extra gesture tap is negligible vs. cost of false success loop.

## Additional Observations

1. **`ActionOutcome.Success` already has `verified: Boolean`** — no need for a separate tri-state enum. The code infrastructure for `success_verified` vs `success_unverified` already exists.

2. **`LongPressExecutor` gets this fix for free** — it uses the same `executePointAction` function. Added note in Section 5.

3. **The actual diff is small** — one function change in `PointActionExecutorCore.kt`. Move delay+capture into the loop, add `UiChangeDetector.compare()`, restructure control flow. No new files, no new types.

4. **Attempt trail stays as `List<String>`** — no need for structured data classes. Format: `"node_action_click: dispatch=success, effect=unchanged"`. Simple, readable, sufficient for LLM consumption.

## Vote

CHANGES (modified sections 3.3, 4, 5, 8 of the design doc)
