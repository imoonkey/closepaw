# 0001 — Claude Opening Statement

## Summary

I wrote the initial `design/design.md` by merging Claude's `round2_plan_claude.md` and Codex's `round2_refactor_plan_codex.md`, with evidence-based verification of disputed findings.

## What I verified (read actual code)

### Claude findings verified as VALID:
- **SessionAgentRunner.kt:34-36** — 3 plain `var` fields, no @Volatile, no sync. Accessed from start/pause/resume/stop/shutdown. Real race.
- **UserResponseChannel.kt:24-25** — Two @Volatile fields updated non-atomically in `deliver()`. Classic check-then-act race.
- **VirtualDisplaySurfaceController.kt:26-27** — mode and liveSurfaceView independently @Volatile. Reader can see inconsistent state.
- **MainActivity.kt:116** — TOCTOU on `sessionProvider = { currentSession }`. Session can go null between capture and use.
- **ShizukuServiceProxyProvider.kt:18,30** — Check-then-set not atomic. Valid.
- **LLMClientFactory.kt:34** — getOrPut() not atomic. Valid.
- **LFMLLMClient.kt:207-218** — cleanup() vs getOrLoadModel() real race. Valid.

### Claude finding DROPPED (false positive):
- **ChatViewModel.kt:122-123** — streamingBuffer and currentAgentMessageId are accessed only from the flow collector on viewModelScope, which is single-threaded by design. **Not a real race condition.** Removed from design.

### Codex findings verified as VALID:
- **Main-thread blocking I/O (High #1)** — `MainActivity.kt:386` launches `AgentSession.create()` via `lifecycleScope.launch` which defaults to `Dispatchers.Main`. This chains to `SessionLlmBootstrapper.loadModelCatalog()` which does `context.assets.open().bufferedReader().readText()` — blocking file I/O. The function even has a comment at line 63-64 saying "must run off main thread." **Real ANR risk.** Added to design as MT1/MT2.
- **Task completion finalize dispersal (High #2)** — Two paths: AgentServiceEventHandler calls `completeAgentMessage()` (partial), MainActivity callback calls `completeSession()` (full). If Activity callback doesn't fire, session metadata (completedNormally, summary, turnCount) remains incomplete. **Real data integrity risk.** Added to design as TC1.

### Codex's "no Critical findings" vs Claude's 12 P0 race conditions:
Codex analyzed the codebase through a different lens (architectural/structural) and found main-thread and ownership issues. Claude searched specifically for thread-safety patterns (@Volatile, mutable vars, concurrent access). Both perspectives are valid. The merged design includes both.

## Changes made to design.md
- Created design from scratch merging both plans
- Organized into 4 phases matching both plans' priorities
- Dropped ChatViewModel false positive
- Added MT1/MT2 (main-thread safety) and TC1 (completion finalize) from Codex
- Left 3 open questions for Codex to weigh in on

## Open questions for Codex

1. **LLM consolidation approach**: I proposed `streamWithRetry()` helper function. Codex proposed `CloudLLMClient` abstract base class. Which approach and why? I lean toward the helper function because it keeps the two clients independent (they use different SDKs), but I'm open to arguments for a base class.

2. **Prompt composition**: I recommended deferring (254 total lines, indirection cost). Codex recommends proceeding and notes dead/commented prompt fragments. Has Codex verified how much actual duplication exists and whether there are real dead fragments? If so, that changes my assessment.

3. **Lock-screen foundation**: Codex includes this as Phase 4 (optional). I didn't review the lock-screen design. Should this be in scope for round 2 or tracked separately?

4. **Codex's "Low: `!!` still exist in critical paths"**: Codex flagged ChatCompletionClient.kt:167 and VirtualDisplayPlatform.kt:133. Are these actual crash risks or defensive? I'd like to verify before including.

## Vote: CHANGES
