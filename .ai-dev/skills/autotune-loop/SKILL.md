---
name: autotune-loop
description: Autonomous multi-round controller for `/autotune`. Reads and updates `loop_state.json`, decides continue or stop, and emits the Ralph completion promise when the loop is finished.
---

# AutoTune Loop

Thin controller around `/autotune`.

Responsibilities:
- `ralph-loop`: generic prompt re-feed engine
- `autotune-loop`: autotune-specific loop policy
- `autotune`: one round worker

Do not scrape prose or infer loop state from filesystem heuristics. `doc/autotune/meta/loop_state.json` is the only control-plane file.

## Principles

- Keep loop policy simple. One Ralph iteration should do at most one orchestrated `/autotune` round.
- Use `.ai-dev/skills/autotune/references/tuning_principles.md` for stop/continue judgment. Do not keep pushing eval-only patches.
- Use `doc/autotune/meta/scoreboard.json` for retry and "failed 3+ times" decisions.

## Loop

For each Ralph iteration:

1. Read `doc/autotune/meta/loop_state.json`.
2. If `status == "complete"`, emit `<promise>AUTOTUNE_LOOP_COMPLETE</promise>` and exit.
3. Ensure the active loop run is marked `mode: "auto"` and `status: "running"` before delegating the round.
4. Invoke exactly one orchestrated `/autotune` round.
   - **Verify sync**: After `/autotune` Step 1 commits changes, confirm the remote has the latest code and APK rebuilt (`git push` + remote `git pull && ./gradlew assembleDebug`) BEFORE the eval runs. `/autotune` Step 3 owns this, but if running `--remote`, double-check the remote is up to date. Running eval on stale code wastes the entire round.
5. Re-read `doc/autotune/meta/loop_state.json`.
6. If `last_round.recommended_action` is `stop_success` or `stop_exhausted`, set `status` to `complete`, persist `stop_reason`, emit `<promise>AUTOTUNE_LOOP_COMPLETE</promise>`, and exit.
7. Otherwise exit normally and let `ralph-loop` re-feed the next iteration.

## Stop Criteria

Set `last_round.recommended_action` to:

- `continue`: more targeted work is justified
- `stop_success`: all high/medium-priority items are fixed or consciously parked
- `stop_exhausted`: no actionable fixes remain, or the same targeted improvement failed 3+ times

When stopping as exhausted:
- Add genuinely out-of-reach tasks to `eval/config/cannot_handle_group.txt`.
- Update `doc/autotune/meta/issues.md` with the parked rationale.
- Base the "failed 3+ times" call on `doc/autotune/meta/scoreboard.json`, not ad hoc memory.

## Commit Policy

- Code changes belong to Step 1 inside `/autotune`: `feat(agent): autotune round N — <summary>`
- End-of-round artifacts belong after analysis: `loop_state.json`, scoreboard, changelog, per-task updates, `issues.md`, and `cannot_handle_group.txt`
- Round 0 has no fix-step commit; commit the end-of-round artifacts only

## Ralph Invocation

```text
/ralph-loop "Run /autotune-loop for this repo.
Read and update doc/autotune/meta/loop_state.json.
Execute exactly one orchestrated autotune round per iteration.
Stop only when loop_state.json status=complete, then output
<promise>AUTOTUNE_LOOP_COMPLETE</promise>." \
--max-iterations 10 \
--completion-promise "AUTOTUNE_LOOP_COMPLETE"
```

The promise means only "the loop is finished." The actual stop reason lives in `doc/autotune/meta/loop_state.json`.

## Key Files

- Loop state: `doc/autotune/meta/loop_state.json`
- Scoreboard: `doc/autotune/meta/scoreboard.json`
- Changelog: `doc/autotune/meta/changelog.md`
- Issues: `doc/autotune/meta/issues.md`
- Exclusions: `eval/config/cannot_handle_group.txt`
- Round worker: `.ai-dev/skills/autotune/SKILL.md`
- Shared tuning principles: `.ai-dev/skills/autotune/references/tuning_principles.md`
