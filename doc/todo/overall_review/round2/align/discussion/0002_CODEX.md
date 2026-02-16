# 0002 — Codex Review and Revision

## What I changed

I revised `design/design.md` to resolve the current disputes with code-backed decisions:

1. Re-prioritized to put **main-thread safety** and **completion-finalize convergence** first.
2. Kept only race findings with concrete risk; downgraded or dropped several false positives.
3. Resolved the LLM consolidation disagreement: use **helper extraction** (`streamWithRetry`) rather than a new inheritance layer now.
4. Resolved prompt disagreement with a compromise: **prompt hygiene now**, full composition later.
5. Marked lock-screen as **out of Round 2 core scope**.

## Key disagreements resolved

- Dropped as false positives:
  - `ChatViewModel` streaming buffer race
  - `VirtualDisplayViewerTouchHandler` plain-var race
  - `TurnPlanningPhaseRunner` "streamError context lost"
  - `HistoryManager` "items unsynchronized" (core methods are synchronized)
- De-prioritized:
  - `!!` usages in `ChatCompletionClient` and `VirtualDisplayPlatform` (guarded control flow)
- Kept and prioritized:
  - Main-thread session creation + blocking asset I/O
  - Completion finalize ownership split
  - Multi-tool loop-detection bug
  - `FileTraceRecorder` unlimited channel risk
  - `LLMClientFactory` concurrent getOrPut behavior

## External evidence used

- Kotlin concurrent `getOrPut` caveat: default block may run multiple times.
- Java `computeIfAbsent` atomicity semantics.
- Android guidance for main-thread scoped ViewModel coroutines.
- Coroutines `Channel.UNLIMITED` memory-bound behavior.

## Vote

CHANGES
