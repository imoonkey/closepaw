# Memory & Conversation History

> Two layers: durable cross-session memory (markdown files) and the in-session
> conversation history with auto-compaction.
> Last updated: 2026-05-16

## Two Memory Layers

| Layer | Lifetime | Storage | Purpose |
|-------|----------|---------|---------|
| **Conversation History** | One session | In-memory `HistoryManager` + checkpoint | What's happened so far in this run; fed back to the LLM each turn |
| **Cross-Session Memory** | Forever (until edited) | `filesDir/memory/*.md` | Durable user/device/app facts injected via `MemoryRecaller` |

The first half of this doc covers cross-session memory (the original Memory V2 system).
[Conversation History & Compaction](#conversation-history--compaction) at the end covers
the in-session layer.

## Cross-Session Memory (Overview)

Memory V2 keeps long-term memory deliberately small and deterministic:

- `user.md` stores cross-app user facts and preferences.
- `device.md` stores device-wide facts, pitfalls, and verification hints.
- `apps/<package>.md` stores app-local overrides, preferences, and operational notes.

The agent does not keep a second session-log memory layer. Per-session evidence already lives in session history, and durable notes are promoted directly into these persistent files.

Core components:

| Component | Role | File |
|-----------|------|------|
| **MemorySchema** | Shared scope/section vocabulary | `memory/MemorySchema.kt` |
| **MemoryStore** | Canonical markdown read/write + validation | `memory/MemoryStore.kt` |
| **MemoryRecaller** | Deterministic prompt recall | `memory/MemoryRecaller.kt` |
| **RememberExperienceTool** | Typed write path for durable learnings | `tool/impl/RememberExperienceTool.kt` |

## Storage Model

Files live under `<filesDir>/memory/`:

```text
memory/
  user.md
  device.md
  apps/
    com.android.settings.md
    org.tasks.md
```

Each file has a fixed section layout and timestamped bullets:

```markdown
# User Memory

## Facts
- [2026-03-13 18:32:34 EDT] User's name is Qi.

## Preferences
- [2026-03-13 18:32:34 EDT] Prefer search over scrolling when possible.
```

```markdown
# Device Memory

## Facts
- [2026-03-13 18:32:34 EDT] Device uses gesture navigation.

## Pitfalls
- [2026-03-13 18:32:34 EDT] BACK may dismiss keyboard before leaving screen.

## Verification
- [2026-03-13 18:32:34 EDT] Re-check page title after BACK on OEM settings screens.
```

```markdown
# App Memory: com.android.settings
> Local delta over app skill. If conflict exists, trust this file.

## App Skill Overrides
- [2026-03-13 18:32:34 EDT] Search is more reliable than scrolling on this build.

## Preferences
- [2026-03-13 18:32:34 EDT] User prefers search when available.

## Operational Notes
- [2026-03-13 18:32:34 EDT] Developer Options is under System.
- [2026-03-13 18:32:34 EDT] BACK may dismiss keyboard first.
```

Notes:

- All entries use full timestamps: `[YYYY-MM-DD HH:MM:SS TZ]`.
- App `Operational Notes` are plain-language bullets. They do not use inline `[pitfall]` or `[verification]` tags.
- If app memory conflicts with the shipped app skill, trust app memory.

## Write Paths

### 1. Voluntary writes via `remember_experience`

`remember_experience` stays as a dedicated memory tool. It does not collapse into generic file writing.

Parameters:

- `scope`: `user`, `device`, or `app`
- `section`: one of the fixed sections allowed for that scope
- `content`: 1-2 durable sentences
- `package_name`: required only for `scope=app`

Allowed section matrix:

| Scope | Allowed sections |
|------|-------------------|
| `user` | `facts`, `preferences` |
| `device` | `facts`, `pitfalls`, `verification` |
| `app` | `app_skill_overrides`, `preferences`, `operational_notes` |

The store normalizes legacy inline kind prefixes away on write, so app operational notes stay plain-language even if the model emits an older `[pitfall]`-style prefix.

### 2. Failure auto-retain

When a task fails and the model never called `remember_experience`, `Agent.kt` writes one fallback entry into the current app's `Operational Notes` section:

```kotlin
if (!result.success && !services.memoryStore.hasWrittenThisSession()) {
    val pkg = services.platform.getCurrentPackageName() ?: lastKnownPackage
    if (pkg != null) {
        val entry = "Failed on \"${config.goal.take(60)}\": ${result.message.take(80)}"
        services.memoryStore.appendAppOperationalNote(pkg, entry)
    }
}
```

This keeps the promotion path tied to task outcome without introducing a separate episodic memory store.

## Recall Path

Each planning turn, `TurnPlanningPhaseRunner` calls `memoryRecaller.recall(currentPackageName)`.

Recall is deterministic and scope-first:

1. Load `user.md` if it exists.
2. Load `device.md` if it exists.
3. Load `apps/<current-package>.md` if it exists.

The recaller injects the full file contents as a `## Recalled Memory` block between working memory and app skill:

```text
History -> Working Memory -> Recalled Memory -> App Skill -> Observation
```

There is no vector search, SQLite, or cross-app recall in V2.

## Security and Validation

- Package names are validated against `^[a-zA-Z0-9_.]+$`.
- Content is truncated to `maxContentLength` (default 2000 chars).
- Writes use temp-file replacement to avoid partial-file corruption.
- File I/O is synchronized in `MemoryStore`.

### Memory Gate

`RememberExperienceTool` enforces a **memory gate** that blocks writes when the current foreground app is classified as `BLOCKED` (financial/auth). This prevents the agent from creating persistent knowledge about blocked app content, even if the LLM attempts to call `remember_experience` while a blocked app is in the foreground.

→ See: `tool/impl/RememberExperienceTool.kt`, `tool/AppClassifier.kt`

## Eval Isolation

Eval hygiene still relies on two guarantees:

- `remember_experience` is excluded from eval tool exposure by config.
- The eval bridge clears `files/memory` before each task launch.

That keeps prompt recall empty at task start and prevents cross-task contamination during eval runs.

---

## Conversation History & Compaction

> See: `history/HistoryManager.kt`, `history/Compactor.kt`, `history/ResponseItem.kt`

The in-session conversation history is the LLM's working context — the sequence of
`USER_INTENT`, `SCREEN_OBSERVATION`, `ASSISTANT_TEXT`, `FunctionCall`, and
`FunctionCallOutput` items the model sees on each turn. It is bounded by
**context-window-driven auto-compaction**, not by a turn count.

### HistoryManager: revision + CAS

`HistoryManager` is `@Synchronized` and exposes a monotonically increasing
`revision: Long` bumped on every mutation (`addItem`, `recordItems`, `replaceAll`,
`clear`, `replaceAllIfRevision`). Two APIs make compaction safe under concurrent
supplements:

```kotlin
@Synchronized fun snapshot(): Pair<Long, List<ResponseItem>>
@Synchronized fun replaceAllIfRevision(expected: Long, newItems: List<ResponseItem>): Boolean
```

- `snapshot()` returns an atomic `(rev, items)` pair — the compactor can release the
  monitor and spend seconds in an LLM summarization call without blocking writers.
- `replaceAllIfRevision(expected, newItems)` is a compare-and-swap: it only replaces
  the list if `_revision == expected`. If a `Supplement` arrived during summarization,
  the CAS fails, the half-baked summary is discarded, and the next turn re-evaluates
  with the supplement included.

### downgradeOldScreens (kept)

On every new `SCREEN_OBSERVATION`, `HistoryManager.downgradeOldScreens()` rewrites
all but the last `recentFullScreens` (default 3) screen messages to a one-line
summary (`"Screen: N elements (compressed)"`). This is the cheap, lossless,
local pass that runs before any LLM-driven compaction is needed.

The old lossy Phase-2 eviction (group-aware oldest-first deletion + breadcrumb
digest) was removed when `Compactor` took over context-window pressure.
`HistoryManager.compress(target)` now only runs normalization + screen downgrade
and returns a `CompressionResult`; it never drops content.

### Compactor algorithm

→ See: `history/Compactor.kt`

`Compactor` is **one instance per `Agent`** (subagents construct their own bound
to the child model and `LLMClient`). Defaults:

| Constant | Value | Rationale |
|---|---|---|
| `staticOverheadTokens` | 12_000 | System prompt + tools + skills + scratchpad |
| `reserveTokens` | 24_000 | Next-turn output + summary message + headroom for drift |
| `keepRecentTokens` | 20_000 | ~3–5 most recent turns survive verbatim |
| `keepRecentTokens` (forced) | 10_000 | Reactive path uses half — more aggressive |
| `maxSummaryTokens` | 5_000 | Per-call summary budget |

`maybeCompact(currentGoal, history)` runs at the top of each agent turn:

1. `snapshot()` reads `(rev, items)`. If
   `items.sumOf { estimateTokens() } + staticOverhead ≤ contextWindow − reserve`,
   return `Skipped`.
2. `findSafeCutPoint(items, keepRecentTokens)` chooses an index:
   - Walk back from the end, accumulating `estimateTokens()`, until
     `keepRecentTokens` is met. That gives a hit index `h`.
   - Snap **forward** from `h` to the next safe boundary — `ResponseItem.Message`
     or `ResponseItem.FunctionCall`. `ResponseItem.FunctionCallOutput` is never a
     valid cut point because it would orphan its paired `FunctionCall`.
   - If no safe boundary exists in the tail, return `items.size` → outcome
     `NothingToCompact` (the caller logs a warning; the reactive path will
     eventually fire if this keeps recurring).
3. Find the most recent `COMPACTION_SUMMARY` in `items` (if any) — used as the
   `previousSummary` for the UPDATE prompt.
4. LLM-summarize the prefix using `compaction_initial.md` (first compaction) or
   `compaction_update.md` (subsequent compactions wrap the prior summary in
   `<previous-summary>` tags). The summarizer call uses **no tools**.
   `CancellationException` propagates — it is **not** a compaction failure.
   Any other throwable → `CompactionOutcome.Failed(reason)`.
5. New history:
   ```
   [USER_INTENT("Goal: <currentGoal>"), COMPACTION_SUMMARY(summary), …kept]
   ```
6. `replaceAllIfRevision(rev, newItems)`:
   - Success → `CompactionOutcome.Compacted(before, after)`.
   - Mismatch → `CompactionOutcome.Stale` (a supplement arrived during the LLM
     call; the next turn retries with the supplement included).

`forceCompactNow(currentGoal, history)` is the reactive variant: runs
unconditionally with `keepRecentTokens / 2`. Called by `Turn.runStreaming` when
the provider throws `ContextWindowExceededException`.

### COMPACTION_SUMMARY as user-role message

`MessageKind.COMPACTION_SUMMARY` returns `apiRole = "user"`. The summary is
**context the runtime feeds back**, not the assistant's own prior output.

When `PromptBuilder` renders a `COMPACTION_SUMMARY` item, it prepends:

```
[Context checkpoint from earlier work in this session]

<summary text>
```

This makes provenance unambiguous to the model — it is reading a handoff from a
previous segment of the same session, not its own train of thought.

### Defense layers (recap)

```
addItem(SCREEN_OBSERVATION)
        │
        ▼
downgradeOldScreens()        ◄── Layer 0: free, local. Old full screens → one-liner.
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

The 3-strike circuit breaker for `Failed` outcomes lives in the agent loop, not
in `Compactor`. See [loop.md](loop.md#auto-compaction) for the loop-side wiring.
