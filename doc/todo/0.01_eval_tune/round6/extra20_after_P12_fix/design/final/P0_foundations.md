# P0 Foundations: Open App Fix, Ask User Blocking, A11y Coexistence

These three fixes address the highest-impact infrastructure issues. Together they unblock 8/20 tasks (6 ASK_USER_BLOCKED + 2 false negatives) and save 12-13 wasted turns per calendar task.

---

## P0-1: Fix open_app Resolver for Eval Apps

### Problem

`open_app("Simple Calendar Pro")` fails because `AppAliases.PACKAGE_MAP` maps `"calendar"` to Google Calendar. The agent falls back to fuzzy matching, opens Google Calendar, hits GMS sign-in, and wastes 12-13 turns.

### Design

Add eval-relevant app aliases to `AppAliases.PACKAGE_MAP` in `OpenAppTool.kt:21-47`:

```kotlin
private object AppAliases {
    val PACKAGE_MAP = mapOf(
        // ... existing entries ...
        "simple calendar" to "com.simplemobiletools.calendar.pro",
        "simple calendar pro" to "com.simplemobiletools.calendar.pro",
        "simple draw pro" to "com.simplemobiletools.draw.pro",
        "audio recorder" to "com.dimowner.audiorecorder",
        "pro expense" to "com.arduia.expense",
        "markor" to "net.gsantner.markor",
    )
}
```

The existing resolution order stays unchanged: exact label match (step 2) is tried first; aliases only kick in as fallback (step 4). This is the same pattern as existing entries like `"chrome"` and `"google maps"`.

Add unit tests for the resolver to prevent regression:

```kotlin
@Test fun `simple calendar pro resolves to correct package`() {
    val result = resolver.resolve("Simple Calendar Pro")
    assertEquals("com.simplemobiletools.calendar.pro", result.packageName)
}
```

### Files Changed

| File | Change |
|---|---|
| `app/.../tool/impl/OpenAppTool.kt` | Add 5 entries to `AppAliases.PACKAGE_MAP` |
| `app/src/test/.../tool/impl/OpenAppToolTest.kt` | Add resolver unit tests |

### Impact

- Saves 12-13 turns per calendar task (SimpleCalendarAddOneEvent, SimpleCalendarDeleteOneEvent)
- Prevents Google Calendar / GMS sign-in trap
- Also benefits ASK_USER_BLOCKED calendar tasks that burned turns on app resolution before asking

### Risks

None. Additive change, no behavioral change for existing aliases.

---

## P0-2: Block ask_user in Eval Mode

### Problem

6/20 tasks (30%) hit ASK_USER_BLOCKED. The agent calls `ask_user` for date clarification (e.g., "What date is 'tomorrow'?"), which blocks in eval. Zero productive turns executed for these tasks.

Current eval detection: `completion_monitor.py` detects `"Executing tool: ask_user"` in logcat and flags as ASK_USER_BLOCKED error. This is reactive — the agent still wastes turns before triggering it.

### Design: Two Layers

#### Layer 1: Environment Context in System Prompt (Primary)

The root cause of ask_user overuse is that the model lacks basic environment info (especially the current date) and asks the user instead of inferring. Fix this by injecting environment context into the system prompt:

```
## Device Environment
- Device: {device_model} ({device_manufacturer})
- Screen: {screen_width}x{screen_height}
- Date: {current_date}  (e.g., "2026-02-25, Tuesday")
```

**Note**: Include date but NOT time. Time changes between requests and would invalidate the LLM's KV cache. Date is sufficient for resolving "tomorrow", "next week", etc.

These values are populated at session start from `Build.MODEL`, `Build.MANUFACTURER`, display metrics, and `LocalDate.now()`.

#### Layer 1b: ask_user Guidance (Lightweight)

Add minimal guidance to `StandaloneAgentDef` system prompt:

```
## ask_user
- If information seems ambiguous, make the most reasonable assumption and proceed. Use ask_user only when there is no other way around.
- Use ask_user when the task requires information you genuinely cannot infer from the goal, the screen, or the device environment above.
- Use ask_user when the task is genuinely impossible without physical user intervention (e.g., CAPTCHA, biometric authentication, physical camera positioning).
```

This keeps ask_user available for legitimately uninferable situations while discouraging it for things the model can figure out on its own (dates, times, quantities).

#### Layer 2: Eval Config Tool Exclusion (Safety Net)

Add `excluded_tools` to eval bridge config:

```yaml
# eval/config/default.yaml
bridge:
  max_turns: 30
  excluded_tools: ["ask_user"]  # tools to remove from agent in eval mode
```

The bridge passes this via intent extra. The app filters excluded tools from the agent definition's `allowedTools` before session start:

```kotlin
// In SessionAgentRunner or equivalent, before building agent config:
val effectiveTools = agentDef.allowedTools - excludedToolNames
```

This keeps the tool code intact and makes exclusion configurable per eval config.

### Why Not a Policy Profile Enum?

An `EVAL_CLEAN` profile enum was considered but adds abstraction without benefit. The `excluded_tools` config field achieves the same result (list tools to disable) with less indirection. If future eval experiments need different tool sets, the list is already flexible.

### Files Changed

| File | Change |
|---|---|
| `app/.../agent/definition/StandaloneAgentDef.kt` | Add `## Device Environment` (date, device, screen) + `## ask_user` guidance to system prompt |
| `app/.../agent/definition/ExecutorAgentDef.kt` | Add same |
| `eval/config/default.yaml` | Add `excluded_tools: ["ask_user"]` |
| `eval/aw_bridge/native_agent_bridge.py` | Pass `excluded_tools` via intent extra |
| `app/.../session/SessionAgentRunner.kt` | Apply tool exclusion filter from intent; populate environment context template vars |

### Impact

- Unblocks 6 ASK_USER_BLOCKED tasks (30% of failures)
- System prompt fix also improves production behavior (fewer unnecessary user interruptions)

### Risks

- Model may ignore the system prompt instruction (mitigated by Layer 2)
- Excluding ask_user in eval may cause the agent to get stuck on genuinely interactive tasks (acceptable — those tasks shouldn't be in the automated eval set)

---

## P0-3: Keep Both Accessibility Services Enabled During Eval

### Problem

The eval bridge strips AndroidWorld's AccessibilityForwarder before task execution (`native_agent_bridge.py:259-267`). This causes AndroidWorld's UI-state validators to read an empty/stale accessibility tree, producing false negatives.

Evidence: ClockStopWatchRunning and ContactsNewContactDraft scored 0.0 despite correct agent behavior.

### Current Code (native_agent_bridge.py:259-267)

```python
# Strip other accessibility services (e.g. AccessibilityForwarder)
if current and current != "null":
    parts = [p for p in current.split(":") if p == self._A11Y_SERVICE]
    current = ":".join(parts)
```

### Design: Preserve-and-Add

Replace the filter-only logic with preserve-and-add:

```python
# Keep existing a11y services (including AndroidWorld's AccessibilityForwarder
# needed for scoring) and ensure AgentService is also enabled.
if current and current != "null":
    parts = [p for p in current.split(":") if p]
    if self._A11Y_SERVICE not in parts:
        parts.append(self._A11Y_SERVICE)
    current = ":".join(parts)
else:
    current = self._A11Y_SERVICE
```

The existing `_ensure_accessibility_service()` already runs at the start of each task. This fix makes it additive rather than destructive.

### Files Changed

| File | Change |
|---|---|
| `eval/aw_bridge/native_agent_bridge.py` | Lines 259-267: change filter-only to preserve-and-add |

### Impact

- Fixes false negatives for ClockStopWatchRunning and ContactsNewContactDraft
- Fixes scoring reliability for ALL UI-state validators going forward

### Risks

- Binder contention between two a11y services: the original stripping was exploratory (testing whether forwarder interfered with agent actions). User confirmed it doesn't. If contention resurfaces, it would manifest as ANRs — easily detected in eval logs.
