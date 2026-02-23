# 0001 — Claude: Initial Consensus Draft

## What I did

Created `align/design/design.md` as an initial consensus draft, merging both designs.

## Where we agree (no changes needed)

1. LLM context needs independent persistence — not reconstructable from UI `MessageRecord`
2. Sealed class/interface for persisted history items (Message, FunctionCall, FunctionCallOutput)
3. Function call arguments stored as raw JSON string
4. `isScreenObservation` flag must be persisted (both designs include it)
5. Persist todos + scratchpad alongside history
6. Persist a config snapshot (model, perception mode, etc.)
7. Debounced writes + force flush on TaskCompleted / Shutdown
8. Reload path: load from disk → hydrate HistoryManager → transparent to Agent/PromptBuilder
9. Release heavy resources (platform, LLM, tools) after task completion
10. Three implementation stages: data model → write/reload → lifecycle cleanup
11. Same verification approach (unit round-trip, integration prompt comparison, E2E)

## Codex contributions adopted

- **`schemaVersion` field** — Claude's design omitted this. Added.
- **`CheckpointState` enum** (`IDLE_READY` / `RUNNING_DIRTY` / `CLOSED`) — more explicit than Claude's lifecycle-only approach. Added.
- **Failure/degradation strategy** — Claude didn't address this. Adopted Codex's: corrupt = view-only; schema mismatch = fail-fast; flush failure = don't release runtime.
- **Atomic write** (temp file + rename) — Claude didn't mention this. Added.
- **Debug hash assertion** — optional hash of `llm_input_items` for cache hit verification. Added as optional.
- **Config freeze semantics** — Codex was more explicit about this. Adopted: reloaded session uses snapshot config, not current app settings.

## Two open questions that need Codex's input

### 1. Single file vs sibling file

- **Claude proposed**: separate `context-*.json` alongside `session-*.json` (2 files)
- **Codex proposed**: embed `runtimeSnapshot` inside `SessionRecord` (1 file)

Trade-offs documented in `design.md §3`. I lean toward **sibling file** because:
- No schema change to existing `SessionRecord`
- Independent write cycles (UI recording can save without touching runtime data and vice versa)
- UI-only operations (history list, session browser) don't pay the cost of loading runtime data

But I want to hear Codex's argument. If atomic consistency between UI and runtime data matters enough, single file might win.

### 2. JSONObject round-trip safety

I investigated the actual data flow in the codebase:
- `LLMToolCall.arguments` is always a `String` from the API
- `Turn.convertToToolCallRequest()` parses it to `JSONObject`
- `PromptBuilder.toResponseInputItem()` calls `arguments.toString()` to build LLM input
- Android's `org.json.JSONObject` uses `LinkedHashMap` → insertion order preserved in practice

**Option A (pragmatic)**: use `JSONObject.toString()` for persistence. Same risk as current runtime behavior (already doing this round-trip on every turn). No refactor needed.

**Option B (robust, Codex's preference)**: change `ResponseItem.FunctionCall.arguments` from `JSONObject` to `String`. Eliminates round-trip risk entirely. But requires refactoring tool execution to parse on-demand — scope creep beyond session reload.

I propose **Option A for session reload scope** (it adds no new risk beyond what already exists), with Option B as a follow-up improvement. But if Codex feels strongly about B, I'm open to including it.

## Vote

**CHANGES** — I created the initial design draft; Codex needs to review and respond.
