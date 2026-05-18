# Memory & Conversation History

> Two layers: durable cross-session memory (markdown files) and the in-session
> conversation history with auto-compaction.
> Last updated: 2026-05-17 (raw read/write API, WYSIWYG files, MemoryEditGate)

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
| **MemorySchema** | Shared scope/section vocabulary + `SaveResult` sealed class | `memory/MemorySchema.kt` |
| **MemoryStore** | Raw markdown read/write + schema-aware append | `memory/MemoryStore.kt` |
| **MemoryRecaller** | Deterministic prompt recall | `memory/MemoryRecaller.kt` |
| **RememberExperienceTool** | Typed agent-side write path for durable learnings | `tool/impl/RememberExperienceTool.kt` |
| **MemoryEditGate** | Single-writer lock for Settings-side edits | `app/MemoryEditGate.kt` |

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

Files are **WYSIWYG**: the bytes on disk are exactly what `MemoryStore.read()`
returns and exactly what gets injected into the prompt via `MemoryRecaller`.
There is no canonicalizer, no migration pass, no on-read normalization. The
Settings → Memory editor saves the verbatim buffer; the agent's `append` path
inserts under the named heading following explicit insertion rules
(see [Append insertion rules](#append-insertion-rules)).

The skeleton produced by `append()` when a file does not yet exist follows the
schema below, but once a file exists Settings can freely reshape it — `append`
will re-create missing headings on demand and will not reformat what's there.

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

- Entries inserted by `append()` use full timestamps: `[YYYY-MM-DD HH:MM:SS TZ]`.
- App `Operational Notes` are plain-language bullets. There is no inline tagging.
- If app memory conflicts with the shipped app skill, trust app memory.
- Hand-edits from Settings are preserved byte-for-byte — including blank files
  created by the `+ Memory` chip in App Access, which `MemoryRecaller` skips
  rather than injecting an empty `## Recalled Memory` block.

## Store API

`MemoryStore` exposes a small raw API. All file IO is `@Synchronized`; writes go
through an atomic temp-file rename, so a crash mid-write leaves the previous
contents intact.

| Method | Behavior |
|---|---|
| `read(scope, packageName?): String?` | Side-effect-free. Returns the file's raw UTF-8 contents, or `null` if missing/unreadable. |
| `write(scope, packageName?, content): SaveResult` | Atomic replace. Enforces an 8192 UTF-8 byte cap. Returns `Success`, `TooLarge`, `InvalidScope`, or `IoError(msg)`. |
| `delete(scope, packageName?): Boolean` | Removes the file. Returns `true` if the file is gone (including the not-present case). |
| `listAppPackages(): List<String>` | Sorted list of `apps/<pkg>.md` package names that pass `[a-zA-Z0-9_.]+` validation. Drives App Access content chips. |
| `append(scope, section, content, packageName?): Boolean` | Schema-aware insertion under the named heading. See rules below. |

`SaveResult` is a sealed class (`Success`, `TooLarge`, `InvalidScope`,
`IoError(message)`) so Settings can render outcome-specific feedback. The legacy
canonicalizer and the one-off `user_prefs.md → user.md` migration are deleted.

### Append insertion rules

`append()` is the only write path the agent uses (via `RememberExperienceTool`
and the failure auto-retain in `Agent.kt`). The model never sees the raw file —
it just names a `scope`, `section`, and content sentence:

1. **Sanitize** the input: fold `\r\n\t` to single spaces, strip Unicode `Cc`
   control characters, collapse runs of whitespace, trim, then truncate to
   2000 chars (`maxContentLength`). Order matters — folding before stripping
   prevents tokens from gluing together.
2. **Format** as `- [<full timestamp>] <sanitized content>`.
3. **No file yet** → write a fresh skeleton: title, optional intro, then one
   heading per section in the schema's declared order; place the new entry
   under the target heading.
4. **File exists, heading present** → insert the bullet immediately below the
   last bullet of that section (skipping back over the blank line that
   separates sections, so the new bullet sits next to the existing ones).
5. **File exists, heading missing** → append `\n## <heading>\n<entry>\n` at EOF.
   This means hand-edited files that have lost a heading still get new entries
   appended without reshaping the rest of the file.
6. **Duplicate heading** → insert under the **last** occurrence. A warning is
   logged but the write proceeds.

After every step `append()` re-checks the UTF-8 byte size against the 8 KB cap
and refuses the write if it would push the file over.

### Write Paths

**1. Voluntary writes via `remember_experience`** — `RememberExperienceTool`
stays as a dedicated memory tool. Parameters:

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

**2. Failure auto-retain** — when a task fails and the model never called
`remember_experience`, `Agent.kt` writes one fallback entry into the current
app's `Operational Notes` section via `appendAppOperationalNote(pkg, …)`.

**3. Settings → Memory editor (free-text)** — the user opens
`Settings → Memory → User Memory` (or Device, or App Access → expand) and
edits the full file in a `MemoryFileEditor`. Saving calls
`MemoryStore.write(scope, pkg?, buffer)` directly — no canonicalization,
no section rewriting. The 8 KB `SaveResult.TooLarge` cap is the only content
check.

## Recall Path

Each planning turn, `TurnPlanningPhaseRunner` calls `memoryRecaller.recall(currentPackageName)`.

Recall is deterministic and scope-first:

1. Load `user.md` if it exists, is non-blank, and is ≤ 8 KB.
2. Load `device.md` under the same gates.
3. Load `apps/<current-package>.md` under the same gates.

Blank files (e.g. ones the `+ Memory` chip just created in App Access) are
dropped before assembly so the prompt never contains an empty `## Recalled
Memory` block. Files over the 8 KB cap are skipped with a warning — a stale
pre-cap file should not silently inflate every prompt.

The recaller injects the surviving file contents as a `## Recalled Memory`
block between working memory and app skill:

```text
History -> Working Memory -> Recalled Memory -> App Skill -> Observation
```

There is no vector search, SQLite, or cross-app recall.

## Single-Writer Model: `MemoryEditGate`

Both the running agent and the Settings UI can write `apps/<pkg>.md`, and the
agent can `append` to `user.md` / `device.md` at any time. Without coordination
a Settings save could clobber an agent append (or vice versa). The fix is a
single-writer lock keyed off session liveness.

`SessionCoordinator` exposes:

```kotlin
val currentSessionState: StateFlow<SessionState?>
```

…a hot reflection of the active session's state (`Created`, `Running`, `Idle`,
`TakeoverPending`, `Paused`, `Shutdown`) or `null` when no session exists.

`MemoryEditGate` maps that flow to `memoryEditLocked: StateFlow<Boolean>`:

- `true` whenever the state is non-null and not `Shutdown` (i.e. a session
  exists and could write at any moment).
- `false` only when `currentSessionState` is `null` or `Shutdown`.
- **Initial value is `true`** — Settings opens in the safe state until the
  upstream flow has emitted at least once.

UI contract (see `MemoryFileEditor.kt`):

- Save / Discard / Delete buttons are disabled and a banner reads
  *"Session is open. Stop the session to edit memory."* when locked.
- The typed buffer is preserved — the user is not popped out of EDIT mode.
- **Action-time TOCTOU re-check**: every save/delete handler re-reads
  `gate.memoryEditLocked.value` inside its coroutine immediately before
  calling `MemoryStore.write` / `delete`. If a session began between the
  click and the IO, the write aborts with a toast and the file on disk is
  untouched. The same re-check guards the App Access `+ Memory` chip, which
  also goes through `write(scope, pkg, "")` to create an empty placeholder.

The agent side has no symmetric lock — sessions own the write path while they
exist, and the gate guarantees the editor cannot collide with them.

## Security and Validation

- Package names are validated against `^[a-zA-Z0-9_.]+$` (read, write, and
  `listAppPackages`).
- `append()` sanitizes and caps content to `maxContentLength` (default 2000
  chars). `write()` does not sanitize — it trusts Settings to send the bytes
  the user typed, capped only at the 8 KB file ceiling.
- All writes use temp-file replacement to avoid partial-file corruption.
- File IO is `@Synchronized` inside `MemoryStore`.

### Memory Gate (blocked-app write refusal)

`RememberExperienceTool` enforces a **memory gate** that blocks agent writes
when the current foreground app is classified as `BLOCKED` (financial/auth).
This prevents the agent from creating persistent knowledge about blocked-app
content even if the LLM calls `remember_experience` while a blocked app is in
the foreground.

Settings-side edits intentionally bypass this gate: a user opening the
expanded App Access row for a blocked app and typing into the editor is the
explicit consent that the agent-side gate exists to demand. The row still
shows a warning chip so the consequence ("entries are still recalled when
this app is foreground") is visible.

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
