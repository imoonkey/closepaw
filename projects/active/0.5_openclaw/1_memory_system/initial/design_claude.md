# Memory System Design — Retain & Recall

## Problem

Android Agent loses all learned knowledge when a session ends. Each task starts from zero — the agent re-discovers app quirks, user preferences, and device characteristics every time. A memory system lets the agent accumulate and reuse operational experience across sessions.

## Design Principles

1. **KISS** — Markdown files on disk, no databases, no embeddings, no new dependencies
2. **App-centric** — Memory organized by package name (the agent's core entity), not by date
3. **LLM-authored** — The LLM decides what's worth remembering; no rule-based extraction
4. **Read-path first** — Recall (loading relevant memory into context) matters more than perfect retention
5. **Additive** — Memory accumulates; old entries aren't deleted automatically (human can prune)

## Architecture Overview

```
memory/                              # On-device: /data/data/{pkg}/files/memory/
├── apps/
│   ├── com.wechat.android.md        # Per-app operational experience
│   ├── com.alipay.android.md        # keyed by package name
│   └── com.android.settings.md
├── user_prefs.md                    # Cross-app user preferences
└── device.md                        # Device characteristics
```

Two new components, one new tool:

```
MemoryStore          — Read/write markdown files in memory/
MemoryRecaller       — Select relevant memory for prompt injection
remember_experience  — Tool the LLM calls to persist a learning
```

## Component Design

### 1. MemoryStore

Thin file I/O wrapper. No indexing, no parsing — just read/write/append markdown files scoped to `memory/`.

```kotlin
class MemoryStore(private val memoryDir: File) {

    fun readApp(packageName: String): String?
    fun readUserPrefs(): String?
    fun readDevice(): String?

    fun appendToApp(packageName: String, entry: String)
    fun appendToUserPrefs(entry: String)
    fun appendToDevice(entry: String)
}
```

**Storage format** — Each file is plain markdown. Entries are appended as bullet points with a timestamp prefix:

```markdown
# com.wechat.android

- [2026-03-10] Send button is bottom-right; long-press triggers voice, not send
- [2026-03-10] "Moments" tab requires swipe-right from chat list, not a bottom tab
- [2026-03-11] Group chat names truncate at 12 chars in the list view
```

No structured schema beyond this. The LLM writes free-form text. Timestamps enable staleness awareness.

**File size guard** — `appendToApp` checks file size before writing. If a file exceeds 8KB (~100 entries), the append still succeeds but a flag is set for future compaction (Phase 2 concern, not Phase 1).

### 2. MemoryRecaller

Decides which memory files to load into the LLM prompt for the current turn. Runs at prompt-build time.

```kotlin
class MemoryRecaller(private val store: MemoryStore) {

    fun recall(currentPackage: String?): List<MemoryBlock>
}

data class MemoryBlock(
    val label: String,      // e.g. "App Memory: com.wechat.android"
    val content: String
)
```

**Recall logic (Phase 1)** — Simple and deterministic:

1. If `currentPackage` is non-null → load `apps/{currentPackage}.md`
2. Always load `user_prefs.md` (if exists and non-empty)
3. Always load `device.md` (if exists and non-empty)

No search, no ranking, no embeddings. The current package is known from perception — that's the only signal needed. This is the simplest thing that works.

**Token budget** — Each loaded file is truncated to 2KB before injection. Total memory budget: 6KB (one app + prefs + device). This fits comfortably within the existing context window alongside history, app skills, and observation.

### 3. remember_experience Tool

A new tool the LLM can invoke to persist a learning.

```kotlin
class RememberExperienceTool(private val store: MemoryStore) : Tool {

    override val name = "remember_experience"
    override val description = """
        Save an operational experience or user preference to long-term memory.
        Use after discovering something useful about an app, the user, or the device
        that would help in future tasks. Only store generalizable knowledge,
        not task-specific details.
    """

    // Parameters:
    //   category: "app" | "user_pref" | "device"
    //   content: string — the experience to remember (1-2 sentences)
    //   package_name: string? — required when category is "app"
}
```

**When does the LLM call this?** — The system prompt instructs: "After completing a task or recovering from a failure, if you learned something reusable about the app, user, or device, call `remember_experience` to save it."

No automatic extraction. No post-task hook. The LLM is the judge.

## Integration Points

### PromptBuilder — Memory Section

Memory slots into the existing four-section prompt architecture between HISTORY and APP_SKILL:

```
1. HISTORY          (existing)
2. MEMORY           (existing — todos + scratchpad)
3. LONG-TERM MEMORY (new — from MemoryRecaller)
4. APP SKILL        (existing)
5. OBSERVATION      (existing)
```

The new section is a system message block:

```
## Long-Term Memory

### App: com.wechat.android
- [2026-03-10] Send button is bottom-right; long-press triggers voice
- ...

### User Preferences
- Prefers dark mode apps
- ...
```

If no memory files exist, this section is omitted entirely (no empty header).

### SessionServices — Bootstrap

MemoryStore and MemoryRecaller are instantiated in `SessionServices.create()`:

```kotlin
val memoryDir = File(context.filesDir, "memory")
val memoryStore = MemoryStore(memoryDir)
val memoryRecaller = MemoryRecaller(memoryStore)
```

`remember_experience` is registered in `SessionToolingBootstrapper` alongside existing tools.

### Tool Policy

`remember_experience` is **auto-allowed** (no user approval needed). Writing a markdown file is low-risk and reversible. The PolicyEngine rule: `Allow` for `remember_experience`.

### Checkpoint & Persistence

Memory files live outside the session checkpoint system. They persist independently in `files/memory/`. This is intentional:
- Memory outlives any single session
- No coupling to session lifecycle
- Files are just files — backup, restore, and manual editing all trivial

## Prompt Addition (System Prompt)

Add to the agent's system prompt:

```
## Long-Term Memory

You have persistent memory stored on this device. Relevant memories are
automatically loaded into your context based on the current app.

After completing a task or recovering from a failure, if you learned
something reusable, call `remember_experience` to save it:
- App behavior quirks (button locations, navigation patterns, gotchas)
- User preferences (payment methods, notification habits, preferred apps)
- Device characteristics (screen density, OS version quirks)

Only store generalizable knowledge. Do not store task-specific steps.
Keep entries to 1-2 sentences.
```

## What's Explicitly Out of Scope

| Feature | Why deferred |
|---|---|
| Embedding / vector search | KISS — package-name lookup is sufficient for Phase 1 |
| Memory compaction / dedup | Wait until files actually grow large enough to need it |
| Confidence scores / evidence | Adds complexity with no clear benefit at this stage |
| Cross-app memory recall | Current app is the right recall key; cross-app adds noise |
| SQLite / FTS index | Only needed if file count or size makes linear reads slow |
| Automatic post-task extraction | LLM-initiated via tool is simpler and more accurate |
| Memory deletion tool | Manual file editing covers this; add tool if needed later |
| Reflect / synthesis layer | Phase 2+ — requires enough raw memories to synthesize from |

## File Changes Summary

| File | Change |
|---|---|
| `app/.../memory/MemoryStore.kt` | **New** — File I/O for memory dir |
| `app/.../memory/MemoryRecaller.kt` | **New** — Recall logic |
| `app/.../tool/RememberExperienceTool.kt` | **New** — Tool definition |
| `app/.../agent/PromptBuilder.kt` | **Edit** — Add long-term memory section |
| `app/.../session/SessionServices.kt` | **Edit** — Wire MemoryStore + Recaller |
| `app/.../session/SessionToolingBootstrapper.kt` | **Edit** — Register tool |
| `app/.../llm/SystemPrompt.kt` (or equivalent) | **Edit** — Add memory instructions |

~300 lines of new code. Three new files. Four small edits.

## Risks & Mitigations

| Risk | Mitigation |
|---|---|
| LLM writes low-quality memories | Prompt instructs "generalizable only"; human can prune files |
| Memory file grows unbounded | 8KB soft cap with size check; compaction deferred to Phase 2 |
| Stale/wrong memories mislead agent | Timestamp prefix lets LLM judge freshness; human can edit |
| Memory adds context bloat | 6KB hard budget; files truncated; section omitted if empty |
| Tool called too frequently | Low cost (file append); no rate limit needed |

## Self-Review

**Strengths:**
- Minimal surface area — 3 new files, 4 edits, no new dependencies
- Aligns with existing patterns: markdown assets (app skills), file-based persistence (sessions), tool-based LLM actions
- Recall is O(1) — just read one file by package name, no search
- Human-readable, Git-friendly, manually editable

**Weaknesses:**
- No cross-app recall — if a learning about "sharing" applies to multiple apps, it must be stored per-app or in user_prefs
- No dedup — the LLM might write the same insight twice; acceptable at Phase 1 scale
- Recall is coarse — loads entire app file, not specific entries; fine within 2KB budget

**Trade-off:** This design optimizes for simplicity and fast shipping over sophistication. The right call for Phase 1 — we need real memory data to inform what Phase 2 should look like.
