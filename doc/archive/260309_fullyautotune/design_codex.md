# Fullyautotune Restructure Review and Counter-Design

## Verdict

I agree with the diagnosis, but not the full decomposition.

The current contract mismatch is real:

- `.ai-dev/skills/autotune/SKILL.md` defines one round and explicitly says "stop for human review".
- `.ai-dev/skills/fullyautotune/SKILL.md` overrides that and keeps looping automatically.

That should be cleaned up.

I also agree that Ralph is a valid iteration engine now that there is a real Claude plugin behind `/ralph-loop`.

Where I disagree is the proposed ownership split:

- deleting `fullyautotune`
- making `autotune` infer loop state from ad hoc filesystem heuristics
- making `/double-design` + `multmux` part of the default `autotune` contract

My preferred design is:

- `autotune` stays the one-round worker
- `prompt-tune` gets stronger top-level principles
- `fullyautotune` stays as a thin domain-specific loop wrapper
- `ralph-loop` is the mechanical re-feed engine under that wrapper, not the owner of autotune semantics

## What I Agree With

### 1. The current loop ownership is confused

This is the strongest point in the proposal.

Today the docs encode two different contracts:

- `autotune`: do one round, analyze, stop, wait for approval
- `fullyautotune`: keep going automatically, no human gate

That is a real design bug. One layer should own round-to-round continuation.

### 2. `autotune` should remain one-round and re-entrant

I agree that the inner loop should not live inside `autotune`.

`autotune` is already structured as a single round:

`FIX -> PREPARE -> RUN -> ANALYZE`

That is the right unit of work. Multi-round continuation should be outside of it.

### 3. The anti-overfit and token-discipline principles should be promoted

These principles are important enough that they should not live only in a niche wrapper skill.

They clearly belong near prompt editing decisions, so promoting them in `prompt-tune` is directionally correct.

### 4. Ralph is a good low-level iterator

After reading the Claude plugin docs under `~/.claude/plugins/.../ralph-loop`, the model is clear:

- Ralph keeps replaying the same prompt
- files are the persistent memory between iterations
- a completion promise is just an exact string match

That is a good fit for "repeat a bounded workflow until a stop condition is written down somewhere".

## What I Disagree With

### 1. Do not delete `fullyautotune`

I do not think the right answer is "Ralph + autotune is enough, delete the wrapper".

Reasons:

- `fullyautotune` expresses a real domain intent: "run autonomous multi-round autotune".
- `ralph-loop` is generic and Claude-specific. It should not become the only documented owner of this repo workflow.
- This repo is shared across agents. A repo-local skill remains the right place to describe the autotune-specific control policy.

Said differently:

- Ralph should own repetition.
- `fullyautotune` should own autotune-loop policy.
- `autotune` should own one-round execution.

That is cleaner than pushing everything into a generic re-feed prompt.

### 2. File-discovery by heuristic is too brittle

The proposal says each `autotune` invocation should discover state from files:

- current round number = count `doc/autotune/round_*`
- previous summary = latest `common_problems_*.md`
- failed tasks = latest scoreboard

I do not think that is safe enough.

Concrete problems in the current repo:

- `doc/autotune/round_11` does not exist, so "count round directories" does not equal "next round number".
- round summaries are not uniform: older rounds have `common_problems_<agent>.md`, while `doc/autotune/round_14/analysis.md` uses a different shape.
- some rounds have both Claude and Codex outputs, so "latest common_problems file" is ambiguous even when it exists.

This is the biggest technical objection. If Ralph keeps replaying the same prompt, then the loop needs one canonical state artifact. "Infer it from whatever files happen to exist" is the wrong foundation.

### 3. Do not make `/double-design` + `multmux` the default Step 4 path

I agree with using separate subagents per task. The current `autotune` skill already enforces that, and it is the right default for clean context.

I do not agree with requiring dual-agent analysis for every round.

Reasons:

- it roughly doubles analysis cost
- it adds orchestration complexity to the hot path
- this repo already has an orchestration note at `doc/todo/0.5_eval_accelerate/discussion/ORCHESTRATOR_NOTE.md` saying `multmux` align sessions stalled in practice

That makes `/double-design` an escalation path, not a baseline contract.

### 4. Do not move all principles into `prompt-tune`

I agree `prompt-tune` should surface them prominently.

I do not agree that `prompt-tune` should become their only home.

Anti-overfit and generalization affect more than prompt edits:

- which tasks get selected in the next round
- whether to add regression canaries
- whether to mark something as `cannot_handle`
- whether a proposed "fix" is just eval gaming

Those are `autotune` decisions too.

### 5. Do not silently remove the human-gated `autotune` contract

Standalone `/autotune` is still useful as a human-reviewed workflow.

The current skill description and the original autotune design both treat that review step as intentional, not accidental.

So I would not rewrite `/autotune` to mean "always autonomous now".

Instead, make the difference explicit:

- standalone `autotune` = manual review mode
- `fullyautotune`-invoked `autotune` = orchestrated mode

### 6. The commit policy is not actually solved by current Step 1

The proposal says "commit every round" can be deleted because `autotune` Step 1 already commits.

That is only partially true.

Step 1 covers code changes before eval. It does not fully define what to do with:

- round-0 runs with no fix step
- analysis-only artifact updates
- `cannot_handle_group.txt` changes after analysis
- final round documentation when the loop decides to stop

That policy still needs an explicit owner.

## Counter-Design

### Goal

Fix the ownership conflict without making the loop state implicit or Claude-plugin-specific.

### 1. Responsibility Split

Use four layers, each with one job:

- `ralph-loop`: generic repetition engine only
- `fullyautotune`: autonomous multi-round autotune controller
- `autotune`: one round of fix/prepare/run/analyze
- `prompt-tune`: apply prompt/tool/app-skill edits in the correct ownership layer

This is not "three nested loops". It is:

- one mechanical loop engine
- one domain loop wrapper
- one round worker

That is a reasonable split.

### 2. Keep `fullyautotune`, but make it thin and explicit

I would rewrite `fullyautotune` as a controller with very little logic, not delete it.

Its job is:

1. Read the loop state
2. Invoke one orchestrated `autotune` round
3. Update stop/continue state
4. If complete, emit the Ralph completion promise
5. Otherwise exit normally and let Ralph re-feed

This preserves a clean user-facing entry point:

- humans and agents can still ask for "fully autonomous autotune"
- the repo still owns the autotune policy in a repo-local skill
- Ralph stays an implementation detail

If you want a cleaner name later, rename it to `autotune-loop`, but that is a separate cleanup.

### 3. Introduce explicit loop state

Add a canonical machine-readable state file, for example:

`doc/autotune/meta/loop_state.json`

Suggested fields:

```json
{
  "goal": "Improve target eval set",
  "mode": "manual|auto",
  "current_round": 15,
  "last_run_id": "20260308_221303",
  "last_summary_path": "doc/autotune/round_14/analysis.md",
  "approved_next_steps_path": "doc/autotune/round_14/analysis.md",
  "attempt_counts": {
    "TasksCompletedToggleFix": 2,
    "SportsTrackerListParsing": 1
  },
  "status": "running|waiting_review|complete",
  "stop_reason": null
}
```

Key point: the loop reads one canonical file, not "latest matching artifact".

That solves:

- non-contiguous round numbering
- mixed summary filenames
- multiple agents writing parallel analyses
- Ralph's fixed-prompt model

### 4. Make `autotune` produce a stable round output contract

`autotune` should always produce:

- round artifacts under `doc/autotune/round_N/...`
- scoreboard/meta updates
- one machine-readable round verdict

For example:

`doc/autotune/round_N/round_verdict.json`

```json
{
  "round": 15,
  "result": "improved|no_change|regressed|blocked",
  "recommended_action": "continue|wait_human|stop_success|stop_exhausted",
  "summary_path": "doc/autotune/round_15/20260309_x/common_problems_codex.md"
}
```

This lets `fullyautotune` decide continuation from a stable contract instead of scraping prose.

### 5. Keep both manual and orchestrated modes explicit

I would document two modes in `autotune`:

- **Manual mode**: default standalone behavior; stop after analysis and wait for human review
- **Orchestrated mode**: when called from `fullyautotune`; write the round verdict and return control to the wrapper

That removes the contract conflict without breaking the current manual workflow.

### 6. Put tuning principles in a shared reference, not only `prompt-tune`

Create a short shared reference, for example:

- `.ai-dev/skills/autotune/references/tuning_principles.md`

Then:

- `prompt-tune` promotes those principles in its preamble because prompt edits need them constantly
- `autotune` links the same principles in Step 1, Step 2, and capability-gap decisions

That keeps one source of truth while making the principles visible where they are applied.

### 7. Make multi-agent analysis optional and targeted

Recommended default:

- keep the current "one subagent per task" rule for `/cog-tune`
- use `/double-design` only when the issue is high-leverage or repeatedly stuck
- optionally use dual-agent review for the final round summary, not every per-task analysis

Suggested escalation triggers:

- same task cluster fails 2+ rounds with no progress
- proposed fix touches core prompt or major tool semantics
- a task looks like a true capability-gap candidate

That gets the benefit of dual-agent scrutiny without making the base loop heavy and fragile.

### 8. Use Ralph with a stable wrapper prompt

Because Ralph replays the exact same prompt every iteration, the prompt should point to the canonical loop state.

Example shape:

```text
/ralph-loop "Run /fullyautotune for this repo.
Read and update doc/autotune/meta/loop_state.json.
Execute exactly one orchestrated autotune round per iteration.
Stop only when loop_state.json says status=complete, then output
<promise>AUTOTUNE_LOOP_COMPLETE</promise>." \
--max-iterations 10 \
--completion-promise "AUTOTUNE_LOOP_COMPLETE"
```

Important detail:

- the promise should mean only "the loop is finished"
- the actual stop reason should be recorded in `loop_state.json`

Do not overload the promise string with business semantics like "ALL TARGETS PASSING OR EXHAUSTED". Ralph only checks exact text; the repo should store the real reason separately.

### 9. Keep external-tool coupling out of the core contract

Ralph currently lives in Claude plugin space and uses plugin-local state (`.claude/ralph-loop.local.md`) plus shell dependencies like `jq`.

That is fine as an execution engine.

It is not a good place to encode the core autotune state model.

The repo-local skill and repo-local state file should remain the source of truth.

## Minimal Migration Plan

1. Update `prompt-tune` to add a visible Principles section, but link to a shared tuning-principles reference.
2. Update `autotune` to define:
   - manual vs orchestrated mode
   - round output contract
   - optional `/double-design` escalation path
3. Rewrite `fullyautotune` as a thin wrapper around:
   - `loop_state.json`
   - one orchestrated `autotune` round per iteration
   - Ralph completion signaling
4. Add `doc/autotune/meta/loop_state.json` and `round_verdict.json`.
5. Add one canonical Ralph invocation example that references the state file, not filesystem heuristics.

## Alternative Proposal If You Insist on Deleting `fullyautotune`

If the team strongly wants only `autotune` + `prompt-tune`, I would still require these guardrails:

- keep a repo-local `loop_state.json`
- make `autotune` modes explicit
- do not use `count(round_*)` or `latest common_problems` as state discovery
- keep `/double-design` optional
- document Ralph as an external engine, not the owner of autotune policy

That would be workable.

I still think the thin wrapper is the cleaner design.
