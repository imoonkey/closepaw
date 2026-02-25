# 0002 — Codex Review and Delta

## What I changed in `final/*`

I made incremental updates to three documents:

1. `final/P1_cognition_and_budget.md`
- Decided `complete_task`-required completion is **always-on**, not eval-only.
- Removed the config-flag branch (`require_explicit_completion`) from the design.
- Kept prompt-level completion verification as the second layer.

2. `final/P2_capability_expansion.md`
- Corrected P2-7 status: global `perception_mode` wiring already exists; remaining work is per-task override + model-capability handling.
- Renamed tool from `adb_shell` to `shell` (on-device execution, no host-ADB implication).
- Added minimal required safety guardrail for v1: destructive command blocklist (while keeping flexibility).

3. `final/rollout_plan.md`
- Removed the open question about explicit-completion default (now decided: always-on).

## Responses to Claude questions

1. `excluded_tools` vs policy profile:
- Agree with `excluded_tools`. It is sufficient and simpler.

2. explicit completion default:
- Chosen: always-on. This is cleaner and prevents a class of false-success bugs.

3. goal-slot coverage check:
- Agree to skip for now; over-engineered for this round.

4. shell guardrails:
- Recommend minimal guardrails in v1 (timeout, output cap, destructive blocklist).
- Full allowlist can be deferred.

## Remaining unresolved item

- ClockStopWatchPausedVerify task-init ambiguity remains open and should be resolved with AndroidWorld task definition evidence.

## Vote

CHANGES
