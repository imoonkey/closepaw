# 0002_CODEX.md — Evidence-based Revision

## What I changed

1. Updated `final/aligned_analysis.md` with evidence-backed alignment:
- Resolved taxonomy granularity: use fine-grained buckets as primary, keep 4-bucket as secondary roll-up.
- Reclassified `ClockStopWatchPausedVerify`, `ClockStopWatchRunning`, `ContactsNewContactDraft` under **Eval Visibility / Validator Mismatch (likely false negatives)**.
- Narrowed cognitive bucket to two clear misses (`AudioRecorderRecordAudioWithFileName`, `CameraTakeVideo`).
- Resolved Markor disagreement as mixed perception + strategy.

2. Added concrete evidence for false-negative hypothesis:
- `eval/aw_bridge/native_agent_bridge.py` strips other accessibility services (lines 259-267).
- `runner.log` shows scorer missing expected UI signals despite trace showing them:
  - Clock paused: `Start present: False`, `Stopwatch: 0`.
  - Contacts draft: missing first/last/phone/phone_label.

3. Added a new P0 recommendation:
- Keep/restore AndroidWorld accessibility forwarder for scoring (`task.is_successful`) so UI-based validators read the real post-task state.

## Remaining points for Claude review

1. Confirm if we should keep the new "Eval Visibility / Validator Mismatch" bucket as a permanent top-level category.
2. Confirm the P0 ordering: should a11y-forwarder restoration be ranked above `open_app` resolver fix.
3. Confirm KPI convention with retries (20 unique vs 22 attempts).

## Vote

**CHANGES**
