# P3-9: Scoring Diagnostics

## Problem

When a task scores 0.0, we can't easily tell whether the agent failed or the validator couldn't read the UI. The ClockStopWatchRunning and ContactsNewContactDraft false negatives were only discovered through manual trace analysis.

User's note: "可以做，但要做得干净。"

## Design: Structured Scoring Log

### What to Log

At scoring time (in `runner.py`, right before `task.is_successful(env)`), capture:

```python
def _log_scoring_context(self, task, env, run_id: str) -> dict:
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

        # 3. UI element count from AccessibilityForwarder
        # (only available if forwarder is running)

        return {
            "scoring_timestamp": time.time(),
            "foreground_package": fg_package,
            "foreground_activity": fg_activity,
            "enabled_a11y_services": a11y_result.stdout.strip(),
            "run_id": run_id,
        }
    except Exception as e:
        return {"scoring_error": str(e)}
```

### Where to Log

In `runner.py`, around the scoring call:

```python
# Before scoring
scoring_ctx = self._log_scoring_context(task, env, run_id)
_log.info("Scoring context: %s", json.dumps(scoring_ctx))

# Score
scripted_score = float(task.is_successful(env))

# After scoring
_log.info("Scoring result: run_id=%s score=%.1f", run_id, scripted_score)
```

Write to per-task artifact directory:
```python
scoring_log_path = artifact_dir / "scoring_context.json"
scoring_ctx["score"] = scripted_score
with open(scoring_log_path, "w") as f:
    json.dump(scoring_ctx, f, indent=2)
```

### Per-Task Output

```json
{
  "scoring_timestamp": 1771991800.5,
  "foreground_package": "com.google.android.deskclock",
  "foreground_activity": ".DeskClock",
  "enabled_a11y_services": "com.moonkey.androidagent/...AgentService:com.google.androidworld/...AccessibilityForwarder",
  "run_id": "aw_20260224_222320_ClockStopWatchRunning_6_0",
  "score": 0.0
}
```

This immediately tells you:
- Was AccessibilityForwarder enabled at scoring time? (If not → false negative risk)
- Was the correct app in foreground? (If not → agent navigated away before scoring)
- Which a11y services were active?

### Integration with Existing Artifacts

The scoring context JSON sits alongside existing trace artifacts:
```
artifacts/
  aw_..._ClockStopWatchRunning_6_0/
    trace/
      trace.jsonl
      meta.json
    scoring_context.json    ← new
```

## Files Changed

| File | Change |
|---|---|
| `eval/aw_bridge/runner.py` | Add `_log_scoring_context()`, write `scoring_context.json` |

## Impact

- Future eval analysis can quickly filter "agent failed" vs "validator couldn't see" by checking `enabled_a11y_services` in scoring_context.json
- Combined with P0-3 (keep AccessibilityForwarder enabled), this becomes a diagnostic safety net

## Risks

- Adds ~500ms per task for ADB commands (negligible vs task execution time)
- `scoring_context.json` is ~200 bytes per task (negligible storage)
