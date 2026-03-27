# Cross-Session Memory System

> Persistent learning across sessions using scope-first markdown files.
> Last updated: 2026-03-26

## Overview

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

## Design References

- Current design note: `doc/todo/0.5_memory/memory_v2_note.md`
- This task's implementation plan: `doc/todo/0.5_memory/memory_v2_implementation_plan.md`
