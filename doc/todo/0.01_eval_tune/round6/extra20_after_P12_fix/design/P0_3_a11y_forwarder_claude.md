# P0-3: Keep AccessibilityForwarder Enabled During Eval

## Problem

The eval bridge strips AndroidWorld's AccessibilityForwarder before task execution (`native_agent_bridge.py:259-267`). This causes AndroidWorld's UI-state validators (`task.is_successful(env)`) to read an empty/stale accessibility tree, producing false negatives.

Evidence: ClockStopWatchRunning and ContactsNewContactDraft scored 0.0 despite correct agent behavior. Runner.log shows validators couldn't find UI elements that were clearly on-screen.

## Current Code (native_agent_bridge.py:259-267)

```python
# Strip other accessibility services (e.g. AccessibilityForwarder)
# that Android World env setup enables.
if current and current != "null":
    parts = [p for p in current.split(":") if p == self._A11Y_SERVICE]
    current = ":".join(parts)
```

This filters out everything except `AgentService`. The original motivation was to avoid Binder contention and "keeps stopping" crash dialogs.

## Design

### Change: Preserve all services, ensure AgentService is present

Replace lines 259-267 with:

```python
# Ensure AgentService is in the enabled list alongside any existing
# services (e.g. AndroidWorld's AccessibilityForwarder needed for scoring).
if current and current != "null":
    parts = [p for p in current.split(":") if p]
    if self._A11Y_SERVICE not in parts:
        parts.append(self._A11Y_SERVICE)
    current = ":".join(parts)
```

This is the minimal change. It:
1. Keeps AccessibilityForwarder (from AndroidWorld `task.initialize_task()`)
2. Adds AgentService if not already present
3. Doesn't add random other services — only preserves what's already enabled

### User's Note

> "这个之前是为了看AndroidWorld的accessibility forwarder会不会影响我的Android agent app的accessibility actions。后来好像发现没有太多关系。你也可以把这儿的一些AndroidWorld accessibility forwarder的权限removal都给删掉,就给它付权限就好了,你就保证任何时候我的app跟它的eval的accessibility forwarder都有权限就行。"

Translation: The stripping was exploratory (testing whether forwarder interfered with agent actions). It didn't. Just keep both enabled.

### Delete the stripping comment

Remove the old comment block (lines 259-264) explaining why stripping was needed. Replace with a short note:

```python
# Keep existing a11y services (including AndroidWorld's AccessibilityForwarder
# needed for scoring) and ensure AgentService is also enabled.
```

## Open Question Resolved

> "Evaluator compatibility strategy: keep both accessibility services enabled throughout eval?"

Answer (from user note): **Yes, both enabled throughout**. Ensure permissions at task start.

### Ensure permissions at each task start

The current `_ensure_accessibility_service()` already runs at the start of each task (called from `_start_agent()` at line 160). The fix above makes it additive rather than destructive, which is sufficient.

If there are intermittent permission losses between tasks (user mentioned this), the existing call flow already handles it — `_ensure_accessibility_service()` re-enables AgentService if missing. The new logic also preserves AccessibilityForwarder.

## Files Changed

| File | Change |
|---|---|
| `eval/aw_bridge/native_agent_bridge.py` | Lines 259-267: change filter-only to preserve-and-add |

## Impact

- Fixes false negatives for ClockStopWatchRunning and ContactsNewContactDraft (would flip 2 tasks from 0.0 to 1.0 if agent behavior was correct)
- Fixes scoring reliability for ALL UI-state validators going forward

## Risks

- Binder contention between two a11y services: the original comment mentioned this concern, but user confirmed it's not a real issue in practice. If it resurfaces, it would manifest as ANRs or "keeps stopping" dialogs — easily detected in eval logs.
