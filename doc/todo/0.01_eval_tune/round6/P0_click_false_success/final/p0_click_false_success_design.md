# P0 Unified Design: Click False Success

Status: **Aligned**
Scope: `node_action_click` reports success without effect; align a robust and transparent execution design.

## 1. Problem Statement

Current click chain is `NODE_CLICK -> GESTURE_TAP`, but executor returns on first `ActionResult.Success`.

This is wrong for `ACTION_CLICK` because:
- `performAction(ACTION_CLICK)=true` means action accepted, not effect guaranteed.
- Some widgets respond only to real touch (`dispatchGesture`).

Round6 evidence (`SimpleSmsSend`) shows same coordinate:
- node path: success text, no UI progress
- gesture path: immediate progress

## 2. Alignment Principles

1. **Full visibility to agent**: no hidden retries, no hidden heuristics.
2. **No-change != always failure**: some valid clicks are semantic no-op from a11y-tree perspective.
3. **KISS**: one fallback step max (`node -> gesture`), no nested retry state machine.
4. **Execution layer owns reliability; agent keeps override control**.

## 3. Aligned Core Design

### 3.1 Default execution mode: `auto`

For click/long-press semantic targets (current default channel order):
- attempt 1: node action
- if dispatch failed -> fallback to gesture
- if dispatch accepted but effect is `no_observable_change` -> single fallback to gesture
- stop after gesture attempt (no chain retry)

### 3.2 Explicit output contract (transparent attempts)

Tool output must contain ordered attempts with per-attempt status:
- `channel`: `node_action_click` / `gesture_tap`
- `dispatch`: `success` | `failure` | `cancelled`
- `effect`: `changed` | `no_observable_change` | `unknown`
- `reason`: optional details

Final status should separate certainty:
- `success_verified`
- `success_unverified`
- `failed`

### 3.3 Agent override knobs — DEFERRED

~~Expose click mode in schema: `click_mode=auto|node|gesture`~~

**Deferred.** No eval evidence that the LLM agent needs explicit channel control. Adding a parameter increases tool schema complexity for every call. The `auto` behavior is the right default. The code structure (filtering `ActionPriorityOrder.click`) makes this trivial to add later if evidence emerges. YAGNI.

## 4. Canonical Algorithm

```text
resolve target
channels = [NODE, GESTURE]  # from current ActionPriorityOrder
for channel in channels:
  dispatch = performAction(channel)
  record attempt(dispatch)
  if dispatch is failure: continue   # try next channel
  if dispatch is cancelled: return cancelled

  post = captureScreen()
  effect = UiChangeDetector.compare(pre, post)
  record attempt(effect)

  if effect == Changed:
    return success(verified=true, observation=buildObservation(post))

  # Unchanged OR Unverifiable → fallback to next channel
  # (Unverifiable = can't verify either way, safer to try gesture)
  lastSuccessPost = post
  continue

# Loop exhausted. If any channel dispatched successfully:
if lastSuccessPost != null:
  return success(verified=false, observation=buildObservation(lastSuccessPost))
return failed
```

**Key decision**: `Unverifiable` triggers fallback identical to `Unchanged`. If we can't tell whether the action worked, we should try the more reliable path. The cost (one extra gesture tap) is negligible vs. the cost of a false success loop (5-10 wasted turns).

Note: `click_mode` remains deferred and is not required for this design. If added later, it only changes the `channels` list construction, not the fallback semantics.

## 5. Minimal Implementation Surface

1. `tool/action/PointActionExecutorCore.kt`
- Move delay + capture into the channel loop (before it was only in `buildPointActionSuccess`)
- Add `UiChangeDetector.compare(pre, post)` after each successful dispatch
- If `Changed` → return `Success(verified=true)` with post-snapshot observation
- If `Unchanged`/`Unverifiable` → record in trail, continue to next channel
- After loop: if any dispatch succeeded → return `Success(verified=false)` with last post-snapshot
- Trail format: `"node_action_click: dispatch=success, effect=unchanged"`

2. `tool/action/ClickExecutor.kt`
- No changes needed (channels stay the same)

3. `tool/action/UiChangeDetector.kt` (EXISTING — no new file)
- Already provides `compare(pre, post) → Changed/Unchanged/Unverifiable`
- Already has FNV-1a element hash + perceptual screenshot hash fallback
- No new `ScreenChangeDetector.kt` needed

Note: `LongPressExecutor` also uses `executePointAction` — gets the fix for free.

## 6. Safety Boundaries

- Only apply node-success fallback logic to semantic targets by default.
- Gesture attempt is at most one per action.
- Never mark node accepted + no-change as hard failure by itself.

## 7. Validation Plan

Functional:
- `SimpleSmsSend` should auto-fallback to gesture and reduce wasted turns.

Regression:
- Stable click tasks (`SystemWifi`, `ContactsAddContact`) should not show large turn inflation.

Observability metrics:
- `node_dispatch_success_count`
- `node_no_observable_change_count`
- `node_to_gesture_fallback_count`
- `fallback_success_verified_count`
- `success_unverified_count`

## 8. Open Questions — Resolved Positions

1. **`success_unverified` semantics → Success-with-warning.** Not a retryable failure. The action was dispatched and accepted. The LLM sees `[unverified]` in the tool output (already wired in `MobileActionInvocation`) and decides whether to proceed or adjust. Making it "retryable failure" would cause wasteful retry loops for clicks that legitimately don't change a11y tree.

2. **`click_mode` exposure → Deferred (YAGNI).** No eval evidence the agent needs explicit channel control. `auto` is the right default. Code structure makes it trivial to add later if evidence emerges.

3. **`Unverifiable` triggers fallback → Yes.** Treat same as `Unchanged`. Can't verify = should try gesture. Cost of one extra gesture tap is negligible. Keeps logic simple: `Changed` → stop, everything else → continue.

## 9. Current Codex Vote

**CHANGES**
