# 0001 — Claude Initial Draft

## Summary

Created 5 final design documents by synthesizing both Claude and Codex individual designs:

- `final/P0_foundations.md` — open_app fix, ask_user blocking, a11y coexistence
- `final/P1_cognition_and_budget.md` — completion gate, write_todos, dynamic turns
- `final/P2_capability_expansion.md` — screenshot perception, shell tool
- `final/P3_scoring_diagnostics.md` — scoring context JSON
- `final/rollout_plan.md` — phased rollout with eval checkpoints

## Key Decisions Made

### P0-2: `excluded_tools` config vs policy profile enum
Chose `excluded_tools` list in eval config over Codex's `EVAL_CLEAN` policy profile enum. Both achieve the same result (disable ask_user + write_todos in eval), but `excluded_tools` is simpler and avoids an abstraction layer. KISS principle.

### P1-4: Accepted Codex's implicit completion bug finding
Codex correctly identified that `Turn.processResponse()` has an implicit completion path (`toolCalls.isEmpty() && text != null` → `isComplete = true`). I verified this in the source code (`Turn.kt:174-213`). The AudioRecorder false success was a runtime bug, not just a prompt issue. The final design includes both:
- **Part A**: Fix the implicit completion in Turn.processResponse (add `requireExplicitCompletion` flag)
- **Part B**: System prompt verification checklist (Claude's original approach)

### P1-4: Rejected goal-slot coverage check
Codex proposed extracting required literals from the goal and checking them against the a11y tree before allowing `complete_task(status=success)`. This is over-engineered — fragile (quoted strings don't always appear literally on screen), complex (requires NLP extraction), and the prompt-based verification leverages the LLM's reasoning capability instead.

### P1-6: Simple per-task overrides vs complex resolver
Chose Claude's simple `task_overrides` + stall detection over Codex's complexity-rules + goal-bonus-regex approach. Goal complexity doesn't correlate with turn count, and regex-based goal analysis requires ongoing maintenance.

### P2-7: Existing PerceptionConfig confirmed
Both designs proposed mode-based screenshot perception. Confirmed the app already has `PerceptionConfig` (AccessibilityOnly / ScreenshotOnly / Hybrid). The work is just wiring eval config → intent → existing PerceptionConfig, not building new infrastructure.

### P2-8: Simple shell tool, no allowlist or read_file wrapper
Chose Claude's simpler design (Runtime.exec, 10s timeout, 4KB output cap) over Codex's additional guardrails (command allowlist, path allowlist, read_file convenience wrapper). The user asked for flexibility; can add guardrails later if needed.

### P3-9: Merged both approaches
Adopted Claude's core design (scoring_context.json) plus Codex's additions (ui_element_count, scoring_duration_ms). Skipped Codex's phase-2 task-specific matcher adapters — they're over-scoped for this round.

## Remaining Questions for Codex

1. **P0-2: `excluded_tools` vs policy profile** — Do you agree that `excluded_tools` is sufficient, or do you see concrete future scenarios where a named profile enum adds value beyond what a tool list provides?

2. **P1-4: `requireExplicitCompletion` default** — Should this be always-on (simpler, safer) or eval-only (preserves current interactive UX)? I lean toward always-on since false successes are worse than timeouts.

3. **P1-4: Goal-slot coverage check** — I rejected this as over-engineered. Do you have counter-evidence from traces where the prompt-based verification would still fail but a slot check would catch it?

4. **P2-8: Shell tool guardrails** — I went minimal (timeout + output cap only). Do you think the command/path allowlist is essential for the first version, or is it acceptable to defer?

## Vote

CHANGES — created initial final drafts.
