# Auto-Compact — Implementation Summary

> Status: shipped on branch `task/auto-compact` (commit range `2efcb905..8d0d5d4e`).
> Authoritative design doc: [`design_claude.md`](design_claude.md).
> Codex cross-review: [`review_codex.md`](review_codex.md).

## What landed

Replaced the `maxTurns` hard cap with **context-window-driven auto-compaction**.
The agent now runs as long as the work needs; whenever the conversation
approaches the model's real context window, an LLM-summarized checkpoint
replaces the older prefix and the loop continues.

### Layers of defense (top to bottom)

1. **`HistoryManager.downgradeOldScreens`** (unchanged, free, local) — older
   `SCREEN_OBSERVATION`s past `recentFullScreens` (default 3) become one-line
   `"Screen: N elements (compressed)"` placeholders on every new screen
   addition.
2. **Proactive `Compactor.maybeCompact`** at the top of every agent turn — if
   `historyTokens + staticOverhead > contextWindow − reserve`, the prefix is
   summarized via a clean LLM call (no tools) and CAS-swapped into history as
   `[USER_INTENT("Goal: …"), COMPACTION_SUMMARY(...), …kept]`.
3. **Reactive `Compactor.forceCompactNow`** in `Turn.runStreaming` — catches
   `ContextWindowExceededException` (HTTP 413 / `prompt_too_long` / Ollama
   overflow), shrinks `keepRecentTokens` to half, retries the call once.

### Key code

- `app/src/main/kotlin/ai/closepaw/history/Compactor.kt` (~245 LOC, new)
- `app/src/main/kotlin/ai/closepaw/history/HistoryManager.kt` — added
  `revision: Long`, `snapshot()`, `replaceAllIfRevision(expected, …)`; deleted
  Phase-2 eviction + `mergeAdjacentDigests` + `autoCompressIfNeeded` + the
  `BudgetUnreachable` branch.
- `app/src/main/kotlin/ai/closepaw/history/ResponseItem.kt` — `COMPRESSION_DIGEST`
  → `COMPACTION_SUMMARY`; `apiRole = "user"`.
- `app/src/main/kotlin/ai/closepaw/agent/Agent.kt` — turn-count guard deleted;
  per-turn compaction with 3-strike circuit breaker
  (`MAX_CONSECUTIVE_COMPACTION_FAILURES = 3`); optional `evalTurnBudget`
  safety net.
- `app/src/main/kotlin/ai/closepaw/agent/Turn.kt` — reactive retry wired to
  `Compactor.forceCompactNow` on `ContextWindowExceededException`.
- `app/src/main/kotlin/ai/closepaw/agent/cognition/prompt/PromptBuilder.kt` —
  drops `maxTurns`; renders `COMPACTION_SUMMARY` with
  `[Context checkpoint from earlier work in this session]\n\n` prefix.
- `app/src/main/kotlin/ai/closepaw/llm/ContextWindowExceededException.kt` (new)
  + `CloudStreamRetryRunner.kt` classifies the provider error.
- `app/src/main/kotlin/ai/closepaw/llm/ModelCatalog.kt` — added
  `ModelEntry.contextWindow: Int`; populated all 12 entries in
  `app/src/main/assets/llm_models.json`.
- `app/src/main/assets/prompts/compaction_initial.md`,
  `app/src/main/assets/prompts/compaction_update.md` (new) — structured
  summary template.
- Removed `maxTurns` everywhere downstream: `SessionConfig`,
  `AgentExecutionConfig`, `AgentRoleDef`, `TaskOutcome.MAX_TURNS`,
  `AgentStopReason.MaxTurnsReached`, `TurnBudget.isFinalTurn` callers,
  trace/snapshot fields, all UI/settings/intent surfaces.
- `eval/aw_bridge/native_agent_bridge.py` — renamed intent extra
  `max_turns` → `eval_turn_budget`; yaml-side `max_turns:` key kept for
  backwards compat and now plumbed through to `SessionConfig.evalTurnBudget`.

## Key decisions (vs the design doc)

- **Cut-point rule generalized** from the v1 "SCREEN_OBSERVATION-only" rule to
  pi's "any safe boundary": walk back to `keepRecentTokens`, snap forward to
  the next `Message` or `FunctionCall`. `FunctionCallOutput` is never a valid
  cut point. This handles supplement-only boundaries and oversized newest
  turns cleanly.
- **`COMPACTION_SUMMARY` is user-role**, not assistant. The summary is context
  the runtime feeds back, not the assistant's own output. The
  `[Context checkpoint from earlier work in this session]` prefix makes
  provenance unambiguous in the prompt.
- **`evalTurnBudget` kept**, not deleted. It's the only surviving turn-budget
  concept, scoped explicitly to "give up before burning $100 on an infinite
  eval loop". Production code never sets it (`null`); the eval bridge does.
- **3-strike circuit breaker lives in the agent loop**, not in `Compactor`.
  `Compactor` only returns outcomes; the loop interprets them.
- **`TurnBudget.kt` and its test were retained as `DelegationSummaryFormatter`**
  (still used by subagents on timeout) — the file no longer participates in
  agent loop control.

## Defaults

| Constant | Value | Rationale |
|---|---|---|
| `staticOverheadTokens` | 12_000 | System prompt + tool schemas + skills + scratchpad ceiling |
| `reserveTokens` | 24_000 | Next-turn output + summary message + drift headroom |
| `keepRecentTokens` | 20_000 | ~3–5 most recent turns survive verbatim |
| `keepRecentTokens` (forced) | 10_000 | Reactive path: more aggressive |
| `MAX_CONSECUTIVE_COMPACTION_FAILURES` | 3 | Matches Claude Code |
| Default `contextWindow` (cloud, missing in JSON) | 128_000 | All cloud catalog entries are ≥ this |
| Default `contextWindow` (local backend) | 8_000 | Leap on-device models are 4–32 K |
| `evalTurnBudget` (production) | `null` | No cap |
| `evalTurnBudget` (eval default) | 30 (yaml) | Eval safety net, unchanged from prior eval semantics |

## Verification

- Unit tests: `Compactor`, `HistoryManager` (CAS + concurrent supplement),
  `Turn` reactive compaction, prompt builder checkpoint rendering, and
  delegation summary formatter all green.
- `./gradlew clean assembleDebug lint test` green on the worktree branch.

## Open follow-ups

- Tune `staticOverheadTokens` / `reserveTokens` / `keepRecentTokens` if eval
  traces show systematic over- or under-compaction.
- Consider per-model `staticOverheadTokens` if tool-schema overhead diverges
  meaningfully across providers.
