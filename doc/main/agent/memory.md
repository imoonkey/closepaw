# Cross-Session Memory System

> Persistent learning across sessions. The agent remembers app-specific workflows, pitfalls, and verification strategies.
> Last updated: 2026-03-11 (commit: faf18ab)

## Overview

The memory system lets the agent accumulate experience over time. When it encounters the same app again, prior learnings are automatically injected into the prompt — no user action needed.

```
Session 1: Agent navigates Settings, learns "Developer Options is under System"
Session 2: Agent sees Settings → recalled memory injected → skips exploration
```

Three components:

| Component | Role | File |
|-----------|------|------|
| **MemoryStore** | File I/O, entry caps, thread safety | `memory/MemoryStore.kt` |
| **MemoryRecaller** | Elastic-budget recall per turn | `memory/MemoryRecaller.kt` |
| **RememberExperienceTool** | LLM-callable tool for voluntary writes | `tool/impl/RememberExperienceTool.kt` |

## Storage Model

Markdown files under `<filesDir>/memory/`:

```
memory/
  apps/
    com.android.settings.md    # per-app memories
    com.google.android.clock.md
  user_prefs.md                # cross-app user preferences
  device.md                    # device-specific facts
```

Each file is a markdown list with timestamped entries:

```markdown
# App Memory: com.android.settings

- [2026-03-11] [workflow] Developer Options is under System > Developer Options
- [2026-03-11] [pitfall] "About phone" scroll position resets on back-navigate
```

Entry caps prevent unbounded growth: 30/app, 20/user_prefs, 10/device. Oldest entries are evicted first via atomic temp-file rewrite.

## Write Paths

### 1. Voluntary (LLM calls `remember_experience`)

The system prompt instructs the agent to call `remember_experience` before `complete_task` when it learns something reusable. Three kind tags:

- `[workflow]` — navigation sequences, operation patterns, shortcuts
- `[pitfall]` — traps, gotchas, things that don't work as expected
- `[verification]` — how to verify a result in this app

The tool is classified as cognitive (non-screen-changing) and auto-allowed by PolicyEngine.

### 2. Failure auto-retain (Agent.kt)

When a task fails (`TurnOutcome.Complete` with `success=false`) and the LLM never called `remember_experience` during the session, the agent auto-saves a `[pitfall]` entry:

```kotlin
if (!result.success && !services.memoryStore.hasWrittenThisSession()) {
    val pkg = services.platform.getCurrentPackageName() ?: lastKnownPackage
    if (pkg != null) {
        val entry = "[pitfall] Failed on \"${config.goal.take(60)}\": ${result.message.take(80)}"
        services.memoryStore.appendAppMemory(pkg, entry)
    }
}
```

`lastKnownPackage` is tracked through the turn loop as a fallback for when `getCurrentPackageName()` returns null (e.g., 0 a11y elements at failure time).

## Recall Path

Each turn, `TurnPlanningPhaseRunner` calls `memoryRecaller.recall(currentPackageName)`. The recaller uses an elastic budget:

| Source | Budget | Priority |
|--------|--------|----------|
| Device | 1 KB | First (always loaded) |
| User prefs | 1.5 KB | Second |
| Current app | Remainder (up to 3.5 KB) | Third (gets leftover) |
| **Total** | **≤ 6 KB** | |

Truncation keeps newest entries (tail), drops oldest (head).

The result is injected as a `## Recalled Memory` user message in `PromptBuilder.buildInputItems()`, positioned between working memory and app skill:

```
History → Working Memory → **Recalled Memory** → App Skill → Observation
```

→ See: [turn_prompt_anatomy.md](turn_prompt_anatomy.md) for full injection order.

## Security

- Package names validated against `^[a-zA-Z0-9_.]+$` (rejects path traversal)
- Content truncated to `maxContentLength` (default 2000 chars)
- Atomic writes via temp file + rename to prevent corruption on crash
- All file I/O is `@Synchronized`

## Eval Isolation

Memory is always writable. For eval runs, clear memory before batch via:

```bash
adb shell rm -rf /data/data/com.moonkey.androidagent/files/memory/
```

## Design Doc

→ See: `doc/todo/0.5_memory/final/design.md`
