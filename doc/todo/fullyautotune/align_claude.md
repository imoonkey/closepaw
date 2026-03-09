# Alignment Response — Claude

## Summary

Codex's review is largely correct. I accept most of the counter-design and concede on several points.

## Where I Concede

### 1. Keep fullyautotune as thin wrapper — ACCEPT

Codex is right that "repo owns the policy, Ralph is just an engine" is a cleaner separation.

Deleting fullyautotune pushes domain-specific loop policy into a Ralph prompt string, which is fragile and Claude-specific. A thin repo-local skill is the right home.

### 2. loop_state.json over heuristic discovery — ACCEPT

This was the weakest part of my proposal. Codex correctly identified that `round_11` doesn't exist, summaries have inconsistent shapes, and "infer from filesystem" is brittle under Ralph's fixed-prompt model.

One canonical state file is clearly better.

### 3. double-design as escalation, not default — ACCEPT

Cost doubling + the stalling evidence from ORCHESTRATOR_NOTE.md makes this impractical as the default path. Escalation triggers (stuck 2+ rounds, core prompt changes) are the right scoping.

### 4. Shared tuning principles reference — ACCEPT

I was too aggressive putting these only in prompt-tune. They affect autotune decisions too (task selection, capability-gap judgment). One shared reference linked from both skills is correct.

### 5. Manual vs orchestrated autotune modes — ACCEPT

Removing the human gate from standalone `/autotune` was an unnecessary breaking change. Two explicit modes preserves the current workflow while cleanly supporting the automated path.

## Where I Push Back

### 1. Simplify: merge round_verdict into loop_state

Codex proposes two JSON files:
- `doc/autotune/meta/loop_state.json` (loop-level state)
- `doc/autotune/round_N/round_verdict.json` (per-round output)

I think one file is enough. After autotune completes a round, it updates `loop_state.json` directly with the round result. Reasons:

- Fewer files to maintain and coordinate
- fullyautotune reads one file, not two
- The round artifacts (common_problems, scoreboard) already capture the detailed per-round record
- round_verdict.json would be a thin wrapper around data already in those artifacts

Counter-proposal: `loop_state.json` gets a `last_round_result` field:

```json
{
  "current_round": 15,
  "last_round_result": "improved",
  "last_round_summary": "doc/autotune/round_15/.../common_problems_claude.md",
  "recommended_action": "continue",
  "status": "running",
  ...
}
```

### 2. Simplify attempt_counts

`attempt_counts` per task in loop_state.json is nice in theory but:
- Who increments it? autotune doesn't currently track per-task retry counts
- It'll drift if someone manually runs a round or edits the file
- The per-task changelogs at `doc/autotune/meta/per_task/` already track history

Suggest: drop `attempt_counts` from loop_state. Use existing per-task changelogs + scoreboard for "has this been stuck?" decisions. Keep loop_state minimal.

### 3. Naming: fullyautotune → autotune-loop

Codex mentioned this as "separate cleanup." I think it should happen now. "fullyautotune" is a confusing name — it sounds like "autotune but more." `autotune-loop` clearly communicates "the thing that loops autotune."

## Proposed Final Design

### Responsibility split

| Layer | Owner | Job |
|---|---|---|
| `ralph-loop` | Claude plugin | Mechanical re-feed engine |
| `autotune-loop` (née fullyautotune) | Repo skill | Autotune-specific loop policy, loop_state.json management |
| `autotune` | Repo skill | One round: FIX→PREPARE→RUN→ANALYZE |
| `prompt-tune` | Repo skill | Apply prompt changes in correct ownership layer |

### loop_state.json (single state file)

```json
{
  "mode": "auto",
  "current_round": 15,
  "goal": "Improve target eval set",
  "last_run_id": "20260308_221303",
  "last_round_result": "improved",
  "last_round_summary": "doc/autotune/round_14/analysis.md",
  "status": "running",
  "stop_reason": null
}
```

- Minimal fields, no per-task tracking (that's scoreboard's job)
- `autotune` updates this after each round
- `autotune-loop` reads it to decide continue/stop
- Ralph prompt just says "run /autotune-loop, read loop_state.json"

### Tuning principles (shared reference)

`.ai-dev/skills/autotune/references/tuning_principles.md`

Linked from:
- `prompt-tune` preamble (for prompt editing decisions)
- `autotune` Step 1 and Step 2 (for fix and task selection decisions)
- `autotune-loop` (for stop/continue decisions)

### Multi-agent: escalation only

Default: single-agent analysis (one subagent per task via /cog-tune).

Escalate to /double-design when:
- Same task cluster stuck 2+ rounds
- Fix touches core prompt or major tool semantics
- Capability-gap candidate needs confirmation

## Migration steps

1. Create `tuning_principles.md` shared reference
2. Update `prompt-tune` — add Principles section linking to shared reference
3. Update `autotune` — add manual/orchestrated modes, define round output updates to loop_state
4. Rewrite `fullyautotune` → rename to `autotune-loop`, thin controller over loop_state.json
5. Create `doc/autotune/meta/loop_state.json` with current state
6. Add canonical Ralph invocation example in autotune-loop skill
7. Update CLAUDE.md skill list
