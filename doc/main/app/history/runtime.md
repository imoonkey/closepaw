# Runtime Prompt History & Compression

> In-memory conversation history, token budgeting, and multi-phase compression pipeline.
> -> See: [overview](overview.md) for architecture.

## ResponseItem

> See: `history/ResponseItem.kt`

Sealed class for conversation items. Each item has `estimateTokens(): Long`.

- `Message(kind: MessageKind, content, name?)` — `role` derived from `kind.apiRole`
- `FunctionCall(id, name, arguments: JSONObject)`
- `FunctionCallOutput(callId, content, success, truncated)`

## MessageKind

> See: `history/ResponseItem.kt`

Explicit message classification replacing the ambiguous `role: String` + `isScreenObservation: Boolean`.

| Kind | API Role | Description |
|------|----------|-------------|
| `USER_INTENT` | user | User's task goal or follow-up |
| `SCREEN_OBSERVATION` | user | Screen state captured each turn |
| `ASSISTANT_TEXT` | assistant | Agent reasoning / action description |
| `COMPRESSION_DIGEST` | assistant | Breadcrumb inserted when history is evicted |

**Why explicit kinds:** The previous design used `role == "user"` for both user intent and screen observations. During compression, all `role == "user"` messages were protected — including screen observations (the biggest token consumer). Result: compression entered no-op loops and could never reach budget.

## HistoryManager

> See: `history/HistoryManager.kt`

In-memory conversation history for each active agent session. Key API: `addItem()`, `getAll()`, `forPrompt()`, `compress(targetTokens)`, `estimateTokenCount()`.

Key behaviors:
- **Token estimation**: `TOKENS_PER_CHAR = 0.25f`
- **Proactive screen downgrade**: on every new `SCREEN_OBSERVATION`, downgrades all but last `recentFullScreens` to one-line summaries
- **Auto-compression**: triggers at `autoCompressThreshold` (85%) of `maxTokenBudget`, compresses to `compressTargetRatio` (50%)
- **History normalization** (`forPrompt`): ensures function call/output pairs are matched
- **Thread-safe**: all public methods `@Synchronized`
- **Mutation listener**: `setMutationListener()` for checkpoint coordination

## HistoryConfig

> See: `history/HistoryConfig.kt`

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxTokenBudget` | 100,000 | Upper bound for estimated token count |
| `autoCompressThreshold` | 0.85 | Fraction of budget at which auto-compress triggers |
| `compressTargetRatio` | 0.5 | Fraction of budget to compress DOWN to |
| `recentFullScreens` | 3 | Recent screen observations kept as full JSON |
| `recentWindowSize` | 10 | Tail items protected from eviction |

**TruncationPolicy** — applied on ingestion, not during compression (tool outputs are typically 13-65 tokens): `NONE` (unlimited), `CONSERVATIVE` (8K), `AGGRESSIVE` (2K), `MINIMAL` (500).

## Compression Pipeline

> See: `history/HistoryManager.kt` — `compress(targetTokens)`

### Design Principles

1. **`USER_INTENT` is never deleted** — hard invariant. Users issue follow-up requests; all must survive.
2. **Screen observations are the primary compression target** — full a11y tree JSON dominates token usage.
3. **Call/output pairing preserved** — evicted as atomic group.
4. **Deterministic** — no LLM calls, no randomness.
5. **Compress rarely, compress deep** — compress to 50% of budget to maximize KV cache stability.

### Pipeline Phases

```
compress(targetTokens)
│
├─ Budget check: already ≤ target? → Noop
├─ Phase 0: Normalize (ensure every FunctionCall has paired output)
├─ Phase 1: Screen Downgrade (keep last 3 full, rewrite older to one-liner)
├─ Phase 2: Group-Aware Eviction (oldest→newest, skip USER_INTENT/DIGEST,
│           evict groups atomically, insert COMPRESSION_DIGEST breadcrumb)
└─ Phase 3: Hard Guard (merge adjacent digests, BudgetUnreachable if stuck)
```

Results: `Noop`, `Compressed(before, after, stepsApplied)`, or `BudgetUnreachable`.

Phase 2 keeps a local `runningTokens` counter and subtracts the evicted item's
`estimateTokens()` on each removal, so the eviction loop stays O(n) even on
large histories — `estimateTokenCount()` is only called once up-front and once
after the loop to reset `lastTokenEstimate`.

### Proactive Screen Downgrade

Runs on every `addItem()` with `SCREEN_OBSERVATION`, not just during `compress()`:
- Count all screen observations; if > `recentFullScreens`, rewrite older ones to `"Screen: N elements (compressed)"`
- Net token growth per turn is ~275 tokens (not ~4K for full screen JSON)

### KV Cache Efficiency

Frequent small compressions destroy cache hit rate (every eviction invalidates all subsequent token caches). Compress to 50% gives ~22 turns of headroom vs compressing to ~95% which re-triggers every 1-2 turns.
