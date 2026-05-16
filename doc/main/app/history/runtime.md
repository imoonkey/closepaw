# Runtime Prompt History & Compaction

> In-memory conversation history, token estimation, downgrade, and the
> context-window-driven `Compactor`.
> -> See: [overview](overview.md) for architecture.
> Last updated: 2026-05-16

## ResponseItem

> See: `history/ResponseItem.kt`

Sealed class for conversation items. Each item has `estimateTokens(): Long`.

- `Message(kind: MessageKind, content, name?)` — `role` derived from `kind.apiRole`
- `FunctionCall(id, name, arguments: JSONObject)`
- `FunctionCallOutput(callId, content, success, truncated)`

## MessageKind

> See: `history/ResponseItem.kt`

Explicit message classification:

| Kind | API Role | Description |
|------|----------|-------------|
| `USER_INTENT` | user | User's task goal or follow-up |
| `SCREEN_OBSERVATION` | user | Screen state captured each turn |
| `ASSISTANT_TEXT` | assistant | Agent reasoning / action description |
| `COMPACTION_SUMMARY` | **user** | LLM-generated summary of older history inserted by `Compactor` |

**Why `COMPACTION_SUMMARY` is user-role:** the summary is context the runtime
feeds back to the model, not the assistant's own output. Treating it as
assistant-role would invite the model to read its own train of thought when it
is actually reading a handoff. `PromptBuilder` prepends the body with
`[Context checkpoint from earlier work in this session]` to make provenance
unambiguous.

(`COMPRESSION_DIGEST`, the previous breadcrumb kind inserted by the now-deleted
lossy Phase-2 eviction, was renamed to `COMPACTION_SUMMARY` and given a role
flip when the LLM-summarization compactor took over.)

## HistoryManager

> See: `history/HistoryManager.kt`

In-memory conversation history for each active agent. All mutating methods are
`@Synchronized`; reads expose defensive copies. Key API:

| API | Purpose |
|---|---|
| `addItem(item)` | Append one item; triggers `downgradeOldScreens` on new `SCREEN_OBSERVATION` |
| `recordItems(items, policy)` | Bulk append from a turn |
| `replaceAll(items)` | Replace history (used by checkpoint reload — bypasses CAS) |
| `snapshot(): (Long, List<ResponseItem>)` | Atomic read of `(revision, items)` for the compactor |
| `replaceAllIfRevision(expected, newItems): Boolean` | CAS swap — returns false on revision mismatch |
| `getAll()` / `forPrompt()` | Defensive copy / normalized history for the LLM |
| `estimateTokenCount()` | Cached sum of `ResponseItem.estimateTokens()` (`TOKENS_PER_CHAR = 0.25f`) |
| `compress(targetTokens)` | Lossless normalize + screen-downgrade pass; returns `CompressionResult` |
| `setMutationListener(listener)` | Hook for checkpoint coordination |

### Revision + CAS

```kotlin
@Volatile private var _revision: Long = 0
val revision: Long get() = _revision
```

Every mutation (`addItem`, `recordItems`, `replaceAll`, `clear`,
`replaceAllIfRevision`) bumps `_revision`. The compactor uses this to detect
concurrent `Supplement` writes that landed while it was spending seconds in an
LLM summarization call. If `replaceAllIfRevision(expected, …)` sees a moved
revision, the swap is refused, the half-baked summary is dropped, and the
agent retries compaction on the next turn with the supplement included.

### Proactive screen downgrade

`downgradeOldScreens()` runs on every `addItem`/`recordItems` that introduces a
new `SCREEN_OBSERVATION`. All but the last `HistoryConfig.recentFullScreens`
(default 3) screen messages get rewritten to `"Screen: N elements (compressed)"`
or `"Screen: screenshot only (compressed)"`. This is free, deterministic, and
keeps the per-turn growth at ~275 tokens instead of ~4 K for a full a11y JSON.

### `compress(targetTokens)` (lossless only)

Lossy eviction was removed. `compress()` now only runs normalization (pairing
`FunctionCall`/`FunctionCallOutput`) and `downgradeOldScreens`, then returns:

- `CompressionResult.Noop(before, after)` — already at/under target or no progress
- `CompressionResult.Compressed(before, after, stepsApplied=1)` — downgrade reduced tokens

It never deletes content. Context-window pressure is now handled by `Compactor`
via `snapshot` / `replaceAllIfRevision`.

## HistoryConfig

> See: `history/HistoryConfig.kt`

| Parameter | Default | Description |
|-----------|---------|-------------|
| `recentFullScreens` | 3 | Recent screen observations kept as full JSON; older ones downgraded |
| `recentWindowSize` | 10 | Tail items protected from downgrade |
| `defaultTruncationPolicy` | varies | Tool-output truncation applied on ingestion |

The old eviction-budget fields (`maxTokenBudget`, `autoCompressThreshold`,
`compressTargetRatio`, `autoCompress`) were removed together with the lossy
Phase-2 pipeline.

**TruncationPolicy** — applied on ingestion of `FunctionCallOutput`s (tool
outputs are usually 13–65 tokens but unbounded in principle): `NONE` (unlimited),
`CONSERVATIVE` (8 K), `AGGRESSIVE` (2 K), `MINIMAL` (500).

## Compactor

> See: `history/Compactor.kt`

Context-window-triggered auto-compaction. One instance per `Agent` (subagents
build their own bound to the child model + `LLMClient`).

### Trigger

```
triggerTokens = model.contextWindow − reserveTokens
maybeCompact() runs at top of each agent turn:
  estimate = items.sumOf { estimateTokens() } + staticOverheadTokens
  if estimate ≤ triggerTokens: return Skipped
```

`Compactor` defaults:

| Constant | Value | Rationale |
|---|---|---|
| `staticOverheadTokens` | 12_000 | System prompt + tool schemas + skills + scratchpad |
| `reserveTokens` | 24_000 | Next-turn output + summary message + overhead drift |
| `keepRecentTokens` | 20_000 | ~3–5 most recent turns survive verbatim |
| `keepRecentTokens` (forced) | 10_000 | Reactive path uses half — more aggressive |
| `maxSummaryTokens` | 5_000 | Per-summary call budget |

`model.contextWindow` comes from `ModelEntry.contextWindow`, populated from
`llm_models.json` with provider-mode-aware fallback (8_000 for `AuthMode.Local`,
128_000 elsewhere).

### `findSafeCutPoint(items, keepTokens)`

```
1. Walk back from items.lastIndex, accumulating estimateTokens(),
   until acc ≥ keepTokens. That gives a hit index h.
   (If total < keepTokens, return 0 — nothing to compact.)
2. Snap forward from h to the next SAFE boundary:
     SAFE   = ResponseItem.Message OR ResponseItem.FunctionCall
     UNSAFE = ResponseItem.FunctionCallOutput  (orphans its paired call)
3. If no safe boundary exists in the tail, return items.size
   → outcome NothingToCompact (caller logs; reactive path is the fallback).
```

The forward-snap is what makes the rule generic — pi-style. It handles:
- **Supplement-only boundary**: a fresh `USER_INTENT` is a `Message`, hence a
  valid cut point.
- **Oversized newest turn**: snap-forward returns `items.size`; we don't
  corrupt history.
- **Post-action screens in `FunctionCallOutput`**: cut snaps past them so the
  call/output pair stays atomic.

### Algorithm

1. `snapshot()` → `(rev, items)`.
2. Token estimate check; if below threshold and not `force`, return `Skipped`.
3. `findSafeCutPoint`; if `≤ 0` or `≥ items.size`, return `NothingToCompact`.
4. Find the most recent `COMPACTION_SUMMARY` in `items` → `previousSummary`.
5. Filter prior summaries out of the prefix to be summarized (we don't re-feed
   them as raw content; they go in via the UPDATE prompt instead). If nothing
   non-summary remains in the prefix, return `NothingToCompact`.
6. LLM-summarize with `compaction_initial.md` (no prior) or `compaction_update.md`
   (prior wrapped in `<previous-summary>` tags). Tools list is empty — clean
   text-only call. `CancellationException` propagates; any other throwable →
   `CompactionOutcome.Failed(reason)`.
7. Build `newItems = [USER_INTENT("Goal: $goal"), COMPACTION_SUMMARY(text), …kept]`.
8. `replaceAllIfRevision(rev, newItems)`:
   - true → `CompactionOutcome.Compacted(before, after)`
   - false → `CompactionOutcome.Stale` (CAS missed; retry next turn)

### `forceCompactNow(goal, history)`

Reactive variant: runs unconditionally with `keepRecentTokens / 2`. Used by
`Turn.runStreaming` when the provider throws `ContextWindowExceededException`
(HTTP 413 / `prompt_too_long` / `request_too_large` / Ollama context-overflow).
On a second context-window failure after force compaction, the exception
propagates out of the streaming flow.

### `CompactionOutcome`

| Variant | Meaning | Loop-side effect |
|---|---|---|
| `Skipped` | Under threshold | Counter reset |
| `NothingToCompact` | No safe cut found | Counter reset |
| `Stale` | CAS missed (supplement arrived during summarization) | Counter reset; retry next turn |
| `Compacted(before, after)` | Successful summarize + swap | Counter reset; emit status |
| `Failed(reason)` | LLM-side error during summarization | Counter++; at 3 → `AgentStopReason.Error` |

→ See: [agent/loop.md](../../agent/loop.md#auto-compaction) for the agent-loop wiring
and circuit breaker.

## Defense layers

```
addItem(SCREEN_OBSERVATION)
        │
        ▼
downgradeOldScreens()        ◄── Layer 0: free, local. Older full screens → one-liner.
        │
        ▼
top of next turn:
compactor.maybeCompact()      ◄── Layer 1: proactive. LLM summarize when
        │                          tokens > contextWindow − reserve.
        ▼
LLM call (in turn)
        │ catch ContextWindowExceededException
        ▼
compactor.forceCompactNow() → retry once   ◄── Layer 2: reactive safety net.
```

## Prompt assets

- `assets/prompts/compaction_initial.md` — first-time summary template.
- `assets/prompts/compaction_update.md` — UPDATE template; merges new events into
  a previous summary wrapped in `<previous-summary>` tags.

Both forbid restating the goal — the goal is re-injected canonically by the
agent loop as `USER_INTENT("Goal: $goal")` at the head of the new history.
