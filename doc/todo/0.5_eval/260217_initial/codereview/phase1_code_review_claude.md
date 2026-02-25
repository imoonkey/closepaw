# Code Review: eval system (aw_bridge) — Claude

Commit: `fb66e25` `feat: implementing eval system with aw_bridge`
Reviewer: Claude
Date: 2026-02-17

---

## Summary

Solid MVP implementation of the eval bridge. The architecture follows the aligned design closely — custom bridge runner reusing AndroidWorld task lifecycle, logcat completion monitor, trace parsing, per-task result persistence. Code is clean and well-structured.

Three findings need attention before first real eval run. The rest are improvements for hardening.

---

## Findings

### [HIGH] Goal text shell injection via `adb shell am start`

**File:** `eval/aw_bridge/native_agent_bridge.py:181-199`

`subprocess.run(["adb", "shell", "am", "start", ..., "--es", "goal", goal])` passes arguments to `adb`, but `adb shell` concatenates all post-`shell` arguments into a single string and executes it through the device's shell (`/system/bin/sh`). If the goal text contains shell metacharacters (quotes, backticks, `$()`, semicolons, parentheses), they will be interpreted by the device shell.

AndroidWorld task goals frequently include punctuation and special characters (e.g. `"Add John's contact (john@example.com) to Contacts"`).

**Impact:** Goal text is silently corrupted or causes `am start` to fail. Eval results are invalid without any obvious error signal.

**Fix:** Use `shlex.quote()` on each argument that goes after `adb shell`, or pass the goal via a temporary file on the device (`adb push` then read via intent), or use `adb shell` with stdin piping. Simplest approach:

```python
import shlex

def _shell_args(self, args: list[str]) -> list[str]:
    """Wrap args for adb shell with proper escaping."""
    escaped = " ".join(shlex.quote(a) for a in args)
    return self._adb_base() + ["shell", escaped]
```

### [HIGH] `STOP_AGENT` broadcast only works in debug builds

**File:** `app/.../AgentServiceReceiverHelpers.kt:11`

```kotlin
if (!BuildConfig.DEBUG) return
```

The `STOP_AGENT` broadcast receiver is only registered in debug builds. If the eval APK is a release build, `bridge.stop_agent()` has no effect. The subsequent `force_stop()` kills the process immediately, which means:
- The `session_stopped` trace event may not be written
- The `run_summary.json` may not be flushed to disk
- Trace parsing will miss `completion_reason`, `turns_executed`, `tool_calls`, `tool_failures`

**Impact:** With a release APK, all trace-derived metrics would be zero/null. Only `bridge_status` and `duration_sec` from logcat would be valid.

**Fix:** Either (a) always use debug APK for eval (document this requirement prominently), or (b) register the receiver in all builds (it's already guarded by package-scoped delivery `-p`).

### [HIGH] Logcat reason extraction pattern doesn't match actual log format

**File:** `eval/aw_bridge/completion_monitor.py:21`

```python
REASON_PATTERN = re.compile(r"reason=([A-Z_]+)")
```

But the actual app log format (from `AgentServiceEventHandler.kt:76,109`) is:
```
Task completed: <taskId>, reason: GOAL_ACHIEVED
Session completed: <sessionId>, reason: GOAL_ACHIEVED
```

Note `reason: ` (colon-space) vs the regex expecting `reason=` (equals). The reason will never be extracted from logcat.

**Impact:** `bridge_outcome.agent_completion_reason` will always be `None` from the logcat path. The trace parser's `stop_reason` is the primary fallback, but if the trace is incomplete (timeout, infra failure), there's no reason captured at all.

**Fix:**
```python
REASON_PATTERN = re.compile(r"reason[=:]\s*([A-Za-z_]+)")
```

### [MEDIUM] `goalachieved` comparison breaks for logcat-derived reasons

**File:** `eval/aw_bridge/result_schema.py:66`

```python
if (r.agent_completion_reason or "").strip().lower() == "goalachieved"
```

Two different formats exist:
- Trace-derived (run_summary `stop_reason`): `"GoalAchieved"` → lowered to `"goalachieved"` — matches
- Logcat-derived (if the pattern fix above is applied): `"GOAL_ACHIEVED"` → lowered to `"goal_achieved"` — does NOT match (underscore)

**Fix:** Normalize by stripping underscores, or match against both forms:
```python
normalized = (r.agent_completion_reason or "").strip().lower().replace("_", "")
if normalized == "goalachieved":
```

### [MEDIUM] Dead code: `suite.suite_family = suite_family`

**File:** `eval/aw_bridge/task_loader.py:58`

`create_suite()` returns an `OrderedDict`. Setting `suite.suite_family` works (Python allows arbitrary attributes on dict subclasses) but the attribute is never read. Should be removed.

### [LOW] `_read_jsonl` duplicated across modules

**Files:** `eval/aw_bridge/trace_parser.py:90-102` (`_iter_jsonl`), `eval/analysis/summarize.py:31-43` (`_read_jsonl`)

Same logic, different names. Consider extracting to a shared utility if more modules need it.

### [LOW] Test coverage gaps

- `completion_monitor.py` has no tests. The logcat polling + pattern matching is the most failure-prone part of the bridge. A test with a synthetic logcat file would catch the `reason=` vs `reason:` mismatch above.
- `test_trace_parser.py` has only one test case. Missing: empty trace, trace with no `complete_task`, trace with multiple `complete_task` calls, corrupt JSONL lines.
- `native_agent_bridge.py` is hard to unit-test (subprocess calls), but the `_start_agent` extras list could be tested for completeness against the app's expected extras.

### [LOW] `compare_runs.py` outputs only raw deltas

No regression flag or threshold check. This is fine for MVP but worth noting that the design calls for "compare against pinned baseline; fail on configured regression threshold" in Tier 2. Currently just prints numeric deltas.

---

## Contract Verification

Verified against app source code:

| Aspect | Bridge | App | Match |
|---|---|---|---|
| Intent extra names | 12 extras sent | 15 extras accepted | Partial (missing `api_key`, `openrouter_api_key`, `novita_api_key` — acceptable, should be pre-configured on device) |
| Boolean extra type | `--ez` | `getBooleanExtra()` | OK |
| String extra type | `--es` | `getStringExtra()` | OK |
| Trace dir path | `/sdcard/Android/data/{pkg}/files/inspection-trace/{run_id}` | `getExternalFilesDir("inspection-trace")` | OK |
| STOP_AGENT action | `{pkg}.STOP_AGENT` | `ACTION_STOP_AGENT` | OK (debug-only receiver) |
| `complete_task` tool name | `"complete_task"` | `ToolName.CompleteTask.raw` | OK |
| Stop reason class names | checks for `GoalAchieved` | `AgentStopReason.GoalAchieved::class.simpleName` | OK |

---

## Verdict

Address the three HIGH findings before running real eval. The shell escaping issue is the most urgent — it will silently corrupt task goals. The other two HIGHs (debug-only receiver, reason pattern mismatch) degrade trace quality but don't block basic execution.
