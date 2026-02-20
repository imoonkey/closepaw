# AccessibilityForwarder A/B Test & dispatchGesture Flakiness Analysis

**Date**: 2026-02-20
**Context**: Debugging flaky `dispatchGesture()` on BrightnessDialog SeekBar
**Tasks**: SystemBrightnessMax, SystemBrightnessMin

## Background

Two consecutive eval runs (025115 and 025322) with identical code produced different results: both passed in 025115, BrightnessMin failed in 025322. The swipe gesture reported "Success" via callback but had zero effect on the SeekBar value.

Investigation identified `com.google.androidenv.accessibilityforwarder.AccessibilityForwarder` as a potential interference source — it was enabled alongside our `AgentService` and subscribes to ALL accessibility events.

## What is AccessibilityForwarder?

A **read-only observation service** from Google DeepMind's android_env project:

- Subscribes to `typeAllMask` (every accessibility event type)
- Every 100ms traverses the full accessibility node tree, serializes to protobuf
- Forwards events + tree snapshots via **gRPC** to host-side Python process
- Used by Python-based Android World agents to "see" the screen remotely

**Our agent doesn't need it** — we have our own native `AgentService` that reads the accessibility tree directly on-device.

### Potential interference mechanisms (theoretical)

1. **Binder contention**: Both services query `AccessibilityNodeInfo` via shared Binder IPC; concurrent tree traversals can return stale/null data
2. **Event queue blocking**: `onAccessibilityEvent` does blocking gRPC calls (1s timeout); if gRPC server is down, backs up the framework's event queue
3. **Crash dialogs**: When gRPC is misconfigured, produces "keeps stopping" dialogs that pollute the UI

## A/B Test Design

Modified `native_agent_bridge.py:_ensure_accessibility_service()` to strip AccessibilityForwarder from `enabled_accessibility_services` before each task (WITHOUT condition), vs. preserving the default behavior of appending our service to the existing list (WITH condition).

## Results

| Run | Forwarder | BrightnessMax | BrightnessMin | Notes |
|-----|-----------|---------------|---------------|-------|
| 025115 | WITH | PASS | PASS | baseline |
| 025322 | WITH | PASS | FAIL | swipe no effect (13 actual turns, 0 reported) |
| 033659 | WITHOUT | PASS | PASS | first WITHOUT test |
| 034104 | WITH | STUCK | N/A | agent hung after init (ANR/freeze) |
| 035422 | WITH | PASS | PASS | retry after stuck |
| 035747 | WITHOUT | FAIL | FAIL | Max: 30 turns swipe no effect; Min: timeout/ANR |
| 114830 | WITHOUT | FAIL | PASS | Max: 0 turns reported (trace bug), 134s |
| 115544 | WITH | PASS | PASS | both passed |

### Aggregate

| Condition | Runs | All-pass | Partial/fail | All-pass rate |
|-----------|------|----------|-------------|---------------|
| WITH forwarder | 4 (excl. stuck) | 3 | 1 | 75% |
| WITHOUT forwarder | 3 | 1 | 2 | 33% |

## Conclusion

**AccessibilityForwarder is NOT the root cause of dispatchGesture flakiness.**

Failures occur both with and without the forwarder. The flakiness has multiple independent causes:

1. **Platform-level dispatchGesture unreliability on SeekBar** — identical coordinates, identical code, gesture callback reports success, but SeekBar value doesn't change. This is the primary issue and is not related to AccessibilityForwarder.

2. **Agent ANR / process freezing** — the agent app itself sometimes becomes unresponsive (seen in runs 034104 and 035747-Min), producing ANR dialogs that block progress.

3. **FileTraceRecorder channel overflow** — trace recording buffer overflows during shutdown, `session_stopped` event gets dropped, eval parser reports 0 turns. Separate infra bug.

## Decision

**Strip AccessibilityForwarder by default.** Although it's not the root cause of flakiness, it is:
- Unnecessary for our native agent (we don't use the gRPC observation path)
- A source of CPU/Binder overhead (100ms tree traversal polling)
- A potential source of crash dialogs when gRPC is misconfigured
- One fewer variable in debugging

## Code Change

`eval/aw_bridge/native_agent_bridge.py` — `_ensure_accessibility_service()`:

```python
# Strip other accessibility services (e.g. AccessibilityForwarder)
# that Android World env setup enables.  We don't use the gRPC
# observation path — our agent has its own native a11y service —
# so the forwarder is unnecessary overhead and a source of Binder
# contention, blocking gRPC calls in onAccessibilityEvent, and
# "keeps stopping" crash dialogs.
if current and current != "null":
    parts = [p for p in current.split(":") if p == self._A11Y_SERVICE]
    current = ":".join(parts)
```

## Remaining Open Issues

1. **dispatchGesture SeekBar flakiness** — platform-level; potential fix paths:
   - Use `AccessibilityNodeInfo.ACTION_SET_PROGRESS` (API 24+) for SeekBar widgets
   - Use `ACTION_SCROLL_FORWARD/BACKWARD` as fallback
   - Both bypass touch injection entirely

2. **FileTraceRecorder channel overflow** — eval infra bug causing 0-turn reporting

3. **Agent ANR** — investigate main-thread work during LLM response processing
