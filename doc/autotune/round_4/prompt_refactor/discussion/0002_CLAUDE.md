# Round 0002 — CLAUDE

## Situation

We have two competing drafts in `final/`:
- `design.md` (mine)
- `prompt_refactor_aligned_draft.md` (Codex's)

Both are strong and agree on ~80% of substance. This note identifies the real differences and proposes resolutions so we can converge to ONE document.

## Differences and My Positions

### D1: Hint Injection Point — Session-scoped vs Per-Turn PromptBuilder

**Codex's position:** Per-turn injection via `PromptBuilder`. Static system prompt stays static.

**My original position:** Left as open question, leaned session-scoped.

**My resolution: I agree with Codex.** The evidence is clear — `SessionAgentRunner.resolvePromptTemplates` runs once at session start (line 71, line 104). The foreground app changes mid-task. Per-turn injection via `PromptBuilder` is the architecturally correct choice. No argument.

The implementation detail Codex proposes — a `## Relevant Hints` user message between memory and observation — is clean and minimal.

### D2: Hint Taxonomy — Three Classes vs GLOBAL + BY_APP

**Codex's position:** Three buckets: `AppHint`, `TaskHint`, `EnvironmentHint`.

**My original position:** Two buckets: `GLOBAL` (always injected) + `BY_APP` (keyed by package).

**My resolution: Simplified two-bucket is sufficient for v1.** Here's why:

- **EnvironmentHint**: The only example Codex gives is "ignore capsule controls." That's already in Critical Rules in both drafts. I can't think of another environment hint. If one appears, it can go in Critical Rules or GLOBAL. A dedicated class for one rule is over-engineering.

- **TaskHint**: Codex's examples are "blank line = `\n\n`" and "exact filename matching." These are cross-app. They belong in GLOBAL (always injected). The token cost of always injecting 2-3 short bullets is negligible (~30 tokens). Building a task-pattern detector to conditionally inject them adds complexity for near-zero benefit.

- **AppHint**: Both designs agree on this. It's the only bucket that needs conditional injection (based on foreground app / goal text).

**Concrete proposal:** `HintCatalog` has two lists:
```
GLOBAL: List<String>       // always injected
BY_APP: Map<String, List<String>>  // keyed by app name, injected when relevant
```

If a third class becomes genuinely needed later, the catalog can be extended. But don't build it before there's a use case.

**Question for Codex:** Can you name a concrete `TaskHint` or `EnvironmentHint` that (a) doesn't fit in GLOBAL or BY_APP, and (b) would cause a real problem if always-injected or keyed by app?

### D3: Migration Phase Ordering

**Codex's draft:** Phase 3 = Core Prompt Rewrite, Phase 4 = Hint Infrastructure.

**My draft:** Phase 2 = Hint Infrastructure, Phase 3 = Core Prompt Rewrite.

**My resolution: Codex's ordering is better.** Rewriting the prompt first means we can validate the new structure with the old app tips still present (just moved to a temporary location or kept inline). Then building hint infra in Phase 4 extracts them cleanly. My ordering would require building the hint system before having a prompt that consumes it, which is backwards.

### D4: Sections — Minor Naming/Structure

Both drafts have nearly identical 7-section hierarchies. Minor differences:

| Topic | My draft | Codex's draft |
|-------|----------|---------------|
| Section 3 name | "Execution Policy" | "Execution Loop" |
| Section 4 name | "Working Memory" | "Working Memory And Planning" |
| Recovery/pivot bullets | In "Execution Policy" | Split across "Critical Rules" and "Execution Loop" |

These are cosmetic. I'm fine with either naming. The one substantive point: **recovery/pivot policy should be consolidated in one place**, not split. I put it in Execution Policy; Codex puts the main rule in Critical Rules (#4-5) and the loop in Execution Loop. I think Critical Rules is the right home since it's highest-priority guidance — keeping it there means the LLM sees it early.

**Proposal:** Keep pivot/retry as Critical Rule items (Codex's approach). Drop my separate "Execution Policy" section. Rename Codex's "Execution Loop" to just the compact observe-decide-act-verify cycle (which is what Codex already intends).

### D5: Shell "2 failures → switch to UI" — Whose Rule?

**Codex's draft:** "may stay tool-local only if written narrowly as shell escalation guidance"

**My draft:** Stays in core prompt as cross-tool coordination.

**My position: This is genuinely cross-tool.** The rule says "after shell fails, switch to UI (mobile_action/open_app)." That coordinates between tools. It doesn't belong in ShellTool.description because ShellTool shouldn't know about mobile_action. Keep it in the core prompt under the pivot/recovery principle.

## Summary of Proposed Convergence

| Decision | Resolution |
|----------|-----------|
| Injection point | Per-turn via PromptBuilder (Codex wins) |
| Hint taxonomy | Two buckets: GLOBAL + BY_APP (Claude wins, pending Codex counterexample) |
| Migration ordering | Prompt rewrite before hint infra (Codex wins) |
| Section naming | Adopt Codex's structure, keep recovery in Critical Rules |
| Shell fallback rule | Core prompt, not tool-local (Claude wins) |

## Next Step

If Codex agrees on these resolutions (or proposes alternatives), one of us should produce a single merged `final/` document and delete the other. I will NOT touch `final/` this round.

## Vote

**CHANGES** — I am not modifying final/ but I'm proposing substantive resolutions that need Codex's agreement before merge.
