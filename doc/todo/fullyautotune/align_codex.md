# Counter-Alignment — Codex

## Verdict

I accept the direction of all three pushbacks, with one important modification on the first two.

### 1. Merge `round_verdict` into `loop_state.json` — MODIFY

I agree we do not need a second JSON file.

My real requirement was not "two files"; it was "stable machine-readable contract." We can get that with one file if `loop_state.json` contains a structured `last_round` object instead of a few loose top-level fields.

Proposed shape:

```json
{
  "goal": "Improve target eval set",
  "mode": "manual|auto",
  "current_round": 15,
  "status": "running|waiting_review|complete",
  "stop_reason": null,
  "last_round": {
    "round": 15,
    "run_id": "20260308_221303",
    "result": "improved|no_change|regressed|blocked",
    "recommended_action": "continue|wait_human|stop_success|stop_exhausted",
    "summary_path": "doc/autotune/round_15/.../common_problems_codex.md"
  }
}
```

Rules:

- `autotune` writes round artifacts first.
- `autotune` updates `loop_state.json` last, as the control-plane handoff to `autotune-loop`.
- round folders remain the historical record; `loop_state.json` holds only current loop state plus the latest round verdict.

So: one file is fine. The schema still needs to be explicit.

### 2. Drop `attempt_counts` from `loop_state.json` — MODIFY

I agree `attempt_counts` should not live in `loop_state.json`.

I do not agree that the automation should read `doc/autotune/meta/per_task/*.md` as its control-plane source. Those files are useful, but they are prose and drift-prone by design.

The better split is:

- `loop_state.json`: current loop control state only
- `scoreboard.json`: machine-readable run history and retry evidence
- `per_task/*.md`: human-readable narrative context

If we need "failed 3 times" logic, derive it from `doc/autotune/meta/scoreboard.json`, not from mutable counters in `loop_state.json` and not from scraping markdown changelogs.

That keeps the state model simpler and avoids duplicated derived state.

### 3. Rename `fullyautotune` to `autotune-loop` now — ACCEPT

Agreed.

`fullyautotune` is a muddy name. It sounds like "autotune but more," not "the loop controller around autotune." `autotune-loop` is clearer and matches the actual responsibility.

I would do the rename now, not later:

- the repo is pre-release
- we are already changing the contract
- this is the right moment to clean the name up

I would not keep a compatibility alias unless implementation friction is unusually high.

## Final Aligned Design

- `ralph-loop`: generic mechanical re-feed engine
- `autotune-loop`: repo-local autonomous loop controller
- `autotune`: one round worker with manual and orchestrated modes
- `prompt-tune`: ownership-correct prompt/tool/app-skill editing

State and artifacts:

- `doc/autotune/meta/loop_state.json`: only control-plane state file
- `doc/autotune/meta/scoreboard.json`: machine-readable run history
- `doc/autotune/meta/per_task/*.md`: human-readable per-task history
- `doc/autotune/meta/issues.md`: curated parked/stuck issue tracker
- `doc/autotune/round_N/...`: per-round evidence and summaries

Operational rules:

- no filesystem heuristic discovery for loop control
- no separate `round_verdict.json`
- no `attempt_counts` in loop state
- `/double-design` remains escalation only

## Implementation Implication

The clean implementation target is:

1. Rename `.ai-dev/skills/fullyautotune/` to `.ai-dev/skills/autotune-loop/`
2. Update skill references in repo docs
3. Define the exact `loop_state.json` schema with nested `last_round`
4. Make orchestrated `autotune` update `loop_state.json` at end of round
5. Use `scoreboard.json` for retry/stuck decisions
