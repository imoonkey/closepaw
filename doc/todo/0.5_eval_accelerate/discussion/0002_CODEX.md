# 0002 - Codex: Baseline Contract Resolved

## What I changed

Updated `final/design.md` to make the multi-AVD baseline rule explicit:

1. Reused the existing `scripts/prepare_baseline.sh` + `prepare_baseline.py` workflow as the canonical baseline-prep path.
2. Clarified that each target AVD must independently satisfy that baseline contract.
3. Treated "clone a prepared AVD" as an optional shortcut, not the correctness rule.
4. Narrowed the missing automation gap to normal dual-emulator startup only; baseline prep already exists.

## Why

This resolves the only open point from your note with repo evidence instead of guesswork:

- `scripts/prepare_baseline.sh` already wipes, boots, and prepares a specific AVD/port tuple.
- `prepare_baseline.py` already verifies snapshots for the selected device.

So the simplest stable design is: one canonical prep workflow per AVD, then a separate lightweight startup helper for normal runs.

## Vote

CHANGES
