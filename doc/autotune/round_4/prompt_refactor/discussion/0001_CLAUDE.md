# Round 0001 — CLAUDE (Initial Draft)

## What This Round Does

Created `final/design.md` — the initial aligned design synthesizing both individual designs and cross-reviews.

## Key Decisions Made

### Consensus (adopted from both designs)

1. **Three-layer ownership model**: core prompt, tool descriptions, dynamic hints
2. **Root problem**: no rule ownership model, not raw prompt length
3. **App tips → dynamic injection** (DroidRun App Card pattern)
4. **autodevice = anti-pattern** to avoid
5. **Single-agent scope** — no Planner/Executor split in this refactor
6. **No per-tool subsections** in core prompt
7. **Target ~80-100 lines** for core prompt (down from ~205)

### Adopted from Codex's Design

1. **Task Modes as a first-class section** — Manipulation, Information, Blocked, Unsupported (not just "Strategies" subsections)
2. **Phase 0: Rule inventory** before any code changes — tag every rule with its owner
3. **Hint quality bar** — only keep hints that are stable, broadly useful, non-obvious, not already in tool descriptions
4. **"No per-tool sections" heuristic** — if a rule needs a per-tool heading, it goes in the tool description
5. **Ownership guardrails** after refactor — invariants to prevent re-accumulation
6. **Unsupported-task policy stays in core prompt** (Task Modes > Unsupported), not buried in CompleteTaskTool

### Adopted from Claude's Design

1. **Critical Rules First** ordering — highest-impact constraints at top of prompt (from minitap pattern)
2. **Line-level "What Moves Where" table** — explicit migration map for every current section
3. **Concrete tool description enrichments** — specific text to add to each tool
4. **Cross-reference pattern summary table** — comparison across all 6 reference repos

### Codex's Criticism Accepted: Split Inconsistency

Codex correctly identified that my original design was inconsistent: I stated "system prompt = cognitive playbook, tool descriptions = API references" but then proposed moving cross-tool cognitive rules (retry policy, pre-completion checklist) into tool descriptions. The aligned design fixes this:

- **Retry/pivot policy** → stays in core prompt (Execution Policy + Critical Rules)
- **Pre-completion verification** → stays in core prompt (Completion section)
- **"Shell: 2 failures → switch to UI"** → stays in core prompt (Execution Policy, cross-tool coordination)
- **Tool-local scope** (shell = file ops only, mobile_action targeting) → moves to tool descriptions

### Codex's Criticism Accepted: App Tip Injection Lifecycle

Codex correctly flagged that `{{app_tips}}` in the system prompt assumes per-turn dynamism, but the current prompt is resolved once at session start. The aligned design marks this as an **open question** with two options (session-scoped vs per-turn via PromptBuilder).

## Open Questions for Codex

1. **Injection point**: Should hints go in the system prompt (session-scoped, based on goal text) or in per-turn user messages via PromptBuilder (reactive to foreground app)? I lean toward session-scoped for simplicity — the goal text usually names the target app, and the rare case of navigating to an unmentioned app is acceptable. But Codex may have a stronger opinion based on the runtime architecture.

2. **Hint taxonomy**: Codex proposed separate `AppHint`, `TaskHint`, `EnvironmentHint` buckets. I simplified to `GLOBAL` + `BY_APP` in the registry. Is the three-bucket model worth the extra complexity? The `EnvironmentHint` case (capsule UI) is already in Critical Rules. The `TaskHint` case (blank line = `\n\n`, exact filename matching) could be either global or app-scoped. Are there concrete cases where the three-bucket distinction prevents a real problem?

3. **"Turn-budget awareness, partial > nothing" rule**: Codex flagged this as likely overfit. I think it encodes a genuine principle (a partial answer is better than timeout with no answer), but the specific "< 5 turns" threshold is eval-shaped. Should we keep the principle but drop the threshold?

## My Vote

**CHANGES** — I created the final doc, so I must vote CHANGES to let Codex review.
