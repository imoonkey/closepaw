# Round 2 — Claude

## What I changed in `final/design.md`

### 1. Retain: tool-based instead of background LLM call

Replaced the async `handleAgentComplete` retain with a `remember_experience` tool. Reasoning:

- **No second LLM path.** Today every LLM call flows through `PromptBuilder → Turn.runStreaming() → ToolRouter`. A background retain call would be the first exception — it needs its own prompt, model selection, streaming, error handling. That's not a small addition.
- **Multi-tool works.** I verified in the codebase: `ToolArbitrationResult.selectedToolCalls` is a list, `TurnExecutionPhaseRunner.executeActions` iterates over it. The LLM can call `remember_experience` + `complete_task` in the same turn.
- **MaxTurnsReached is covered.** `ExecutorStepPolicy` emits a "FINAL TURN" warning. The LLM can use that turn to save learnings.
- **Error cases are low-value.** Terminal errors (network, crashes) don't produce app-specific learnings worth persisting.

If V1 data shows the LLM under-calls the tool, background retain can be Phase 2.

### 2. Dropped `kind` field from entry format

Changed from `- [date][kind] text` to `- [date] text`. Reasoning:

- At ≤30 entries, the LLM reads everything. Kind-based filtering/ranking adds code that doesn't change what the LLM sees.
- The Codex design's recall budget (8 entries, 4/app) was where kind mattered (for sorting). I replaced that with a simpler 2KB-per-file truncation, making kind irrelevant at the recall layer.
- Kind can be added later by prepending `[workflow]` to body text — no storage format change needed.

### 3. Dropped goal-based app recall on turn 1

Changed to currentPackage-only recall. Turn 1 is typically `open_app` where app memory isn't critical. By turn 2, recall works. The gap is documented as a known limitation.

### 4. Simplified recall budget

Replaced entry-count caps at recall layer (8 total, 4/app, 1200 chars) with per-file 2KB truncation. Simpler, and the store already caps entry counts on write.

### 5. Simplified components

Three components, no data classes or enums for entries: `MemoryStore`, `MemoryRecaller`, `RememberExperienceTool`. Added concrete wiring details (SessionServices, PromptBuilder, TurnPlanningPhaseRunner).

## What I kept from Codex's draft

- Storage model: `files/memory/` with `apps/`, `user_prefs.md`, `device.md` — consensus
- Entity-based organization, not date-based — consensus
- Prompt placement: user message between working memory and app skill — consensus
- Not injecting as system message — consensus
- All non-goals (no embeddings, no SQLite, no reflect, no app_skills writes) — consensus

## Remaining disagreements

### A. Tool vs background LLM retain (HIGH)

This is the core disagreement. Codex argues tool-only is unreliable because "there is no guaranteed post-completion turn." I argue:
- There IS a turn — the same turn where the LLM calls `complete_task`. The LLM can also call `remember_experience` in that turn.
- The "no post-completion turn" concern applies to a SEPARATE turn after completion. But retain doesn't need a separate turn.
- Background LLM call complexity cost is real and non-trivial.

### B. Entry kind field (MEDIUM)

Codex wants `[date][kind]`, I want `[date]` only. This is a question of whether the structure pays for itself at V1 scale. I say no — the LLM reads all entries regardless.

### C. Goal-based recall on turn 1 (LOW)

Codex wants it, I say defer. This is a cost/benefit judgment call. If Codex feels strongly, I'd accept it as an optional enhancement rather than a blocker.

Vote: CHANGES
