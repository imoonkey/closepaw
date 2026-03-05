# P3 Scoring Diagnostics Design (Codex)

## Scope
- Recommendation 9: clean scoring diagnostics to separate agent failure vs evaluator visibility failure

## Current gap
- `task.is_successful(env)` currently returns only score.
- When score is 0, we often lack normalized context at scoring time:
  - which activity/package was foreground,
  - how many UI elements were visible to validator,
  - whether both accessibility services were enabled.
- Result: hard to distinguish true agent miss vs forwarder visibility mismatch.

## Design

### 1) Introduce scoring probe wrapper (runner-side)
Add a wrapper around `task.is_successful(env)` in `eval/aw_bridge/runner.py`:
- `capture_pre_score_context(...)`
- call `task.is_successful(env)`
- `capture_post_score_context(...)`

### 2) Diagnostics payload per attempt
Write `artifact_dir/scoring_diagnostics.json` with:
- task/run metadata
- current activity (`adb_utils.get_current_activity`)
- current package (derived from activity)
- ui element count (`len(env.get_state().ui_elements)`)
- top visible tokens snapshot (first N text/content_desc/hint)
- enabled accessibility services string
- score and success flag
- elapsed scoring duration

### 3) Structured runner logging
Standardized lines:
- `[score_probe] pre ...`
- `[score_probe] post ...`
- `[score_probe] services ...`
This makes grep-based triage deterministic.

### 4) Optional task-specific matcher adapters (phase 2)
For high-noise validators (Clock/Contacts), add adapter hooks in runner:
- recompute key booleans from same `ui_elements` (`start_present`, `lap_present`, etc.)
- store under `matcher_hints` field.
Keep optional and isolated so baseline runner remains clean.

## Why this is clean
- No invasive patching of AndroidWorld task code required for phase 1.
- All diagnostics remain in bridge harness layer.
- Reusable for future false-negative investigations.

## Validation plan
1. Re-run known mismatch tasks:
- `ClockStopWatchRunning`
- `ContactsNewContactDraft`
2. Confirm each failed attempt now has `scoring_diagnostics.json`.
3. Verify we can answer, from one artifact, whether:
- UI tree was empty/stale,
- wrong activity/package was foreground,
- a11y services were missing.

## Success criteria
- Triage time per failed task materially reduced.
- False-negative hypotheses can be validated without manual trace spelunking.
