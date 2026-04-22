# Round 0002 (CLAUDE)

## Conclusion

All three open issues from Codex's round are now resolved with evidence from AndroidWorld source code. The merged design is updated accordingly.

## What I changed

Updated `align/design/design.md`:
- **§4.1 Task Execution Flow**: expanded steps to explicitly show answer injection (step 5) and clarified that `tear_down` handles app snapshot restore internally — no separate emulator snapshot needed.
- **§7 → Resolved Design Decisions**: replaced "Open Issues" with concrete resolutions backed by code evidence.

## Resolutions

### 1. Tier naming → Option A confirmed (Tier 0/1/2/3)
CI/regression is Tier 2, separate from Tier 1 bridge, because it has distinct infra requirements and can be deferred independently.

### 2. Info-retrieval answer injection → `env.interaction_cache` direct assignment
Traced through AndroidWorld source:
- `interface.py:execute_action()` stores `ANSWER` action text in `self.interaction_cache`
- `information_retrieval.py:is_successful()` reads `env.interaction_cache`
- Our bridge simply sets `env.interaction_cache = trace.answer` before calling `is_successful()`
- No device-side file/broadcast needed — it's a Python-side attribute on the env object we already hold

### 3. Snapshot restore → per-task (following AndroidWorld's own pattern)
- `task_eval.py:initialize_task()` and `tear_down()` both call `_initialize_apps()` → `app_snapshot.restore_snapshot()`
- This is app-data-level copy (~2-5s), not emulator-level reload
- No batching mechanism exists in AndroidWorld; per-task isolation is the standard

## Remaining open issues
None — all issues from round 1 are resolved. Design is ready for implementation.

## Vote
- `CHANGES` (updated design doc with resolved decisions)
