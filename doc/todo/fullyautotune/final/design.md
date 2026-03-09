# Fullyautotune Restructure — Final Aligned Design

## Problem

Three nested loop concepts create a contract mismatch:

- `autotune` SKILL.md: one round, explicitly stops for human review
- `fullyautotune` SKILL.md: overrides that stop, keeps looping automatically
- `ralph-loop`: generic mechanical re-feed engine (Claude plugin)

Who owns iteration is ambiguous. Domain-specific principles (anti-overfit, token minimalism) are buried in the wrong skill.

## Design

### Responsibility Split

| Layer | Owner | Job |
|---|---|---|
| `ralph-loop` | Claude plugin | Mechanical prompt re-feed engine. Generic, not autotune-aware. |
| `autotune-loop` | Repo skill (`.ai-dev/skills/autotune-loop/`) | Autonomous multi-round controller. Reads/writes `loop_state.json`. Decides continue/stop. Emits Ralph completion promise when done. |
| `autotune` | Repo skill (`.ai-dev/skills/autotune/`) | One round: FIX → PREPARE → RUN → ANALYZE. Has manual and orchestrated modes. |
| `prompt-tune` | Repo skill (`.ai-dev/skills/prompt-tune/`) | Apply prompt/tool/app-skill edits in correct ownership layer. Strengthened with shared tuning principles. |

This is not three nested loops. It is:
- one mechanical engine (ralph-loop)
- one domain controller (autotune-loop)
- one round worker (autotune)

### Naming

`fullyautotune` is renamed to `autotune-loop`. The old name sounds like "autotune but more." The new name communicates "the thing that loops autotune."

### State Model

#### loop_state.json

Single canonical control-plane file at `doc/autotune/meta/loop_state.json`.

```json
{
  "goal": "Improve target eval set",
  "mode": "auto",
  "next_round": 16,
  "status": "running",
  "stop_reason": null,
  "last_round": {
    "round": 15,
    "run_id": "20260308_221303",
    "result": "improved",
    "recommended_action": "continue",
    "summary_path": "doc/autotune/round_15/.../common_problems_claude.md"
  }
}
```

Fields:
- `goal`: human-readable objective for this loop run
- `mode`: `"manual"` (human reviews each round) or `"auto"` (autonomous)
- `next_round`: integer, next round to execute
- `status`: `"running"` | `"waiting_review"` | `"complete"`
- `stop_reason`: null or string explaining why the loop stopped
- `last_round`: null before the first round, otherwise the structured verdict from the most recent round
  - `result`: `"improved"` | `"no_change"` | `"regressed"` | `"blocked"`
  - `recommended_action`: `"continue"` | `"wait_human"` | `"stop_success"` | `"stop_exhausted"`
  - `summary_path`: path to the round's analysis summary

Design decisions:
- **No `round_verdict.json`** — merged into `loop_state.json` as `last_round`
- **No `attempt_counts`** — use `scoreboard.json` for retry/stuck decisions
- **No filesystem heuristic discovery** — round number, summary path, etc. all come from this one file
- `autotune` updates this file at the end of each round as the control-plane handoff
- `autotune-loop` reads this file to decide continue/stop

#### Artifact Hierarchy

| File | Role |
|---|---|
| `doc/autotune/meta/loop_state.json` | Control-plane state (current loop only) |
| `doc/autotune/meta/scoreboard.json` | Machine-readable run history, used for retry/stuck decisions |
| `doc/autotune/meta/scoreboard.md` | Human-readable scoreboard view |
| `doc/autotune/meta/per_task/*.md` | Human-readable per-task history |
| `doc/autotune/meta/issues.md` | Curated parked/stuck issue tracker |
| `doc/autotune/meta/changelog.md` | Round-over-round changelog |
| `doc/autotune/round_N/...` | Per-round evidence, summaries, per-task analyses |

### autotune — Two Modes

#### Manual mode (default, standalone `/autotune`)

Same as today: FIX → PREPARE → RUN → ANALYZE → STOP for human review.

After analysis, sets `loop_state.json`:
```json
"status": "waiting_review",
"last_round": { "recommended_action": "wait_human", ... }
```

#### Orchestrated mode (called from `autotune-loop`)

Same steps, but instead of stopping:
- Writes round artifacts
- Updates `loop_state.json` with round verdict
- Returns control to `autotune-loop`

The mode is determined by context: if `autotune-loop` invokes it, it's orchestrated. If a human runs `/autotune` standalone, it's manual.
`loop_state.json` records the active loop mode, but invocation context decides behavior; `/autotune` should not infer orchestrated mode from a stale state file alone.

### autotune-loop — Thin Controller

Per ralph-loop iteration, `autotune-loop` does:

1. Read `loop_state.json`
2. Check stop conditions:
   - `status == "complete"` → emit `<promise>AUTOTUNE_LOOP_COMPLETE</promise>`, exit
3. Invoke one orchestrated `autotune` round
4. Re-read `loop_state.json`
5. If `last_round.recommended_action == "stop_success"` or `"stop_exhausted"`, set `status: "complete"`, persist `stop_reason`, emit promise. Otherwise exit normally (ralph-loop re-feeds)

Stop criteria:
- All high/med priority items addressed or parked → `stop_success`
- Targeted improvement failed 3+ times (checked via `scoreboard.json`) → add to `cannot_handle_group.txt`, `stop_exhausted`
- No actionable fixes identified → `stop_exhausted`

Commit policy:
- Code changes: committed in autotune Step 1 (`feat(agent): autotune round N — <summary>`)
- End-of-round artifacts and control-plane updates (`loop_state.json`, scoreboard, changelog, per-task updates, `issues.md`, `cannot_handle_group.txt`) are committed after Step 4
- Round-0 (no fix step): commit end-of-round artifacts only

### Ralph Invocation

```
/ralph-loop "Run /autotune-loop for this repo.
Read and update doc/autotune/meta/loop_state.json.
Execute exactly one orchestrated autotune round per iteration.
Stop only when loop_state.json status=complete, then output
<promise>AUTOTUNE_LOOP_COMPLETE</promise>." \
--max-iterations 10 \
--completion-promise "AUTOTUNE_LOOP_COMPLETE"
```

The promise means only "the loop is finished." The actual stop reason is in `loop_state.json`.

### Shared Tuning Principles

New file: `.ai-dev/skills/autotune/references/tuning_principles.md`

```markdown
# Tuning Principles

Every change during autotune must pass these gates:

1. **Anti-overfit** — Does this help real users, not just eval tasks?
   - Core prompt: must be generally applicable to any app/task
   - App skills: must cover beyond the specific eval task that triggered it
   - If the answer is "only helps this one eval task" → do not add

2. **Token minimalism** — Is every token earning its keep?
   - Core prompt target: ~80-100 lines
   - App skills: <20 lines (loaded every turn when app is foreground)
   - Can this be said in fewer words without losing clarity?

3. **Generalization** — Eval tasks are training data. Always ask:
   would this change also help an unseen user task in the same app?
```

Linked from:
- `prompt-tune` SKILL.md — promoted to top-level Principles section (for prompt editing decisions)
- `autotune` SKILL.md — referenced in Step 1 (fix decisions) and Step 2 (task selection)
- `autotune-loop` SKILL.md — referenced in stop/continue decisions

### Multi-Agent Analysis

**Default**: single-agent analysis. One subagent per task via `/cog-tune`. This is the current autotune Step 4 behavior.

**Escalation to `/double-design`**: optional, triggered when:
- Same task cluster fails 2+ rounds with no progress
- Proposed fix touches core prompt or major tool semantics
- Capability-gap candidate needs confirmation before parking

This keeps the base loop lightweight. `/double-design` + `/multmux` is an escalation path, not the default contract.

## Migration Plan

1. Create `.ai-dev/skills/autotune/references/tuning_principles.md`
2. Update `prompt-tune` SKILL.md — add Principles section linking to shared reference
3. Update `autotune` SKILL.md:
   - Add manual/orchestrated mode documentation
   - Add `loop_state.json` update as final step of orchestrated mode
   - Reference tuning principles in Step 1 and Step 2
   - Add optional `/double-design` escalation in Step 4
4. Rename `.ai-dev/skills/fullyautotune/` → `.ai-dev/skills/autotune-loop/`
5. Rewrite `autotune-loop` SKILL.md as thin controller
6. Create `doc/autotune/meta/loop_state.json` seeded with current state
7. Add canonical Ralph invocation example in `autotune-loop` SKILL.md
8. Update CLAUDE.md and AIDEV.md skill lists
