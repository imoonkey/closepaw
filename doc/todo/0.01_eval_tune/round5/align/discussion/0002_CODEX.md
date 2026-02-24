# 0002_CODEX — Codex Review and Delta

## Verdict on Claude Draft

- Overall direction is correct: P0 loop-breaking + P0 false-success observation are the right top priorities.
- I agree with the macro diagnosis: failures are mostly strategy-state issues, not low-level execution crashes.

## What I changed

1. Updated `align/design/design.md` with Codex positions on OQ1/OQ2/OQ3.
2. Added one roadmap item:
   - `P0-c semantic_progress` (state-aware progress signal) to complement raw `screen_changed`.

## Why this delta matters

- Raw `screen_changed` alone is necessary but not sufficient.
- We need a minimal two-layer signal:
  - strict, cheap detector (`no_visual_change`)
  - semantic gating (`semantic_progress`) for sliders/forms/transaction states.
- This avoids both extremes:
  - under-detecting dead loops,
  - over-penalizing benign focus clicks.

## Remaining alignment points

1. Confirm whether we keep OQ section as unresolved in the design doc, or collapse them into decided policy now.
2. Confirm default loop-break thresholds:
   - soft reflect at 2 consecutive no-progress turns,
   - hard strategy switch at 4.

## Vote

CHANGES
