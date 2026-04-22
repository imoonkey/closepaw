# P3 Scoring Diagnostics: Structured Scoring Context

This adds diagnostic context at scoring time to quickly distinguish "agent failed" vs "validator couldn't read the UI." Low-effort, high-signal investment.

---

## Problem

When a task scores 0.0, we can't tell whether the agent failed or the validator couldn't read the UI. The ClockStopWatchRunning and ContactsNewContactDraft false negatives were only discovered through manual trace analysis.

## Design: Scoring Context JSON

### What to Capture

Before calling `task.is_successful(env)` in `runner.py`, capture device state:

```python
def _capture_scoring_context(self, task, env, run_id: str) -> dict:
    """Capture UI state at scoring time for diagnostics."""
    try:
        # 1. Current foreground package and activity
        fg_result = env.controller.execute_adb_command(
            ["shell", "dumpsys", "activity", "activities"],
            timeout=5
        )
        # Parse topActivity from output

        # 2. Enabled accessibility services
        a11y_result = env.controller.execute_adb_command(
            ["shell", "settings", "get", "secure", "enabled_accessibility_services"],
            timeout=5
        )

        # 3. UI element count from environment state
        try:
            state = env.get_state()
            ui_element_count = len(state.ui_elements) if state and state.ui_elements else 0
        except Exception:
            ui_element_count = -1

        return {
            "scoring_timestamp": time.time(),
            "foreground_package": fg_package,
            "foreground_activity": fg_activity,
            "enabled_a11y_services": a11y_result.stdout.strip(),
            "ui_element_count": ui_element_count,
            "run_id": run_id,
        }
    except Exception as e:
        return {"scoring_error": str(e)}
```

### Where to Log

In `runner.py`, around the scoring call:

```python
# Before scoring
scoring_ctx = self._capture_scoring_context(task, env, run_id)
_log.info("Scoring context: %s", json.dumps(scoring_ctx))

# Score
scripted_score = float(task.is_successful(env))

# After scoring
scoring_ctx["score"] = scripted_score
scoring_ctx["scoring_duration_ms"] = int((time.time() - scoring_ctx["scoring_timestamp"]) * 1000)
_log.info("Scoring result: run_id=%s score=%.1f", run_id, scripted_score)
```

Write to per-task artifact directory:

```python
scoring_log_path = artifact_dir / "scoring_context.json"
with open(scoring_log_path, "w") as f:
    json.dump(scoring_ctx, f, indent=2)
```

### Per-Task Output Example

```json
{
  "scoring_timestamp": 1771991800.5,
  "foreground_package": "com.google.android.deskclock",
  "foreground_activity": ".DeskClock",
  "enabled_a11y_services": "com.moonkey.androidagent/...AgentService:com.google.androidworld/...AccessibilityForwarder",
  "ui_element_count": 24,
  "run_id": "aw_20260224_222320_ClockStopWatchRunning_6_0",
  "score": 0.0,
  "scoring_duration_ms": 450
}
```

This immediately tells you:
- Was AccessibilityForwarder enabled at scoring time? (If not, false negative risk)
- Was the correct app in foreground? (If not, agent navigated away before scoring)
- Were UI elements visible to the validator? (`ui_element_count > 0`)
- How long did scoring take? (Unusual duration may indicate validator timeout)

### Integration with Existing Artifacts

```
artifacts/
  aw_..._ClockStopWatchRunning_6_0/
    trace/
      trace.jsonl
      meta.json
    scoring_context.json    <- new
```

## Files Changed

| File | Change |
|---|---|
| `eval/aw_bridge/runner.py` | Add `_capture_scoring_context()`, write `scoring_context.json` |

## Impact

- Future eval analysis can quickly filter "agent failed" vs "validator couldn't see" by checking `enabled_a11y_services` and `ui_element_count` in `scoring_context.json`
- Combined with P0-3 (keep AccessibilityForwarder enabled), this becomes a diagnostic safety net

## Risks

- Adds ~500ms per task for ADB commands (negligible vs task execution time)
- `scoring_context.json` is ~300 bytes per task (negligible storage)
