status: draft

# Memory System V1

Date: 2026-03-10

## Goal

Add a small long-term memory layer that survives across tasks and sessions.

V1 only needs two capabilities:

1. Save reusable learnings after a task finishes.
2. Load relevant learnings into the next task's prompt.

This is additive. It does not replace session history, scratchpad, todos, or app skills.

## Existing Boundaries

The current codebase already has clean seams for this:

- `TurnPlanningPhaseRunner` already builds per-turn app context from the current package.
- `PromptBuilder` already injects non-history context as user messages.
- `SessionStorage` shows the accepted persistence model: app-private files under `files/`.
- `app_skills/<package>/SKILL.md` is static, repo-owned knowledge and must stay separate from runtime memory.
- The agent already supports multi-tool calls per turn via `ToolArbitrationResult.selectedToolCalls`.

So V1 should be one new runtime layer:

- static knowledge: `app_skills`
- session-scoped working memory: history + scratchpad + todos
- cross-task durable memory: new `files/memory/`

## Storage Model

Store markdown files under:

```text
<app files>/memory/
├── apps/
│   ├── com.android.settings.md
│   └── net.gsantner.markor.md
├── user_prefs.md
└── device.md
```

Scope is entity-based, not date-based:

- app memory by package name
- user preferences in one file
- device facts in one file

## Entry Format

Plain markdown bullets with timestamps. No kind taxonomy.

Example:

```md
# App Memory: com.android.settings

- [2026-03-10] Use Settings search before browsing deep lists; direct scrolling is unreliable for deep options
- [2026-03-10] BACK dismisses the keyboard first on search screens; need a second BACK to navigate
- [2026-03-10] Verify the row text/state after a toggle change; do not trust highlight color alone
```

**Why no `kind` field:**
- At ≤30 entries per file, the LLM reads every entry on recall — kind-based filtering/ranking adds code without changing what the LLM sees
- Kind classification forces the LLM (or retain code) to categorize, adding a decision point that can be wrong
- Timestamps are the only metadata needed: they convey staleness, which is the one thing the LLM can't infer from content alone
- If kind-based filtering proves necessary after V1 data collection, it can be added without changing the storage format (just prepend `[kind]` to the body text)

Do not add confidence, evidence graphs, embeddings, or rich metadata in V1.

## Recall

### What to load

On each planning turn, load:

1. `user_prefs.md` if non-empty
2. `device.md` if non-empty
3. app memory for the current foreground package if known

### Known limitation: turn 1 gap

Turn 1 often happens on launcher/home/previous app, so the target app's memory won't be loaded until turn 2+. This is acceptable for V1:

- Turn 1 is typically `open_app`, where app-specific memory isn't critical
- By turn 2 the agent is in the target app and recall works
- Goal-based app resolution (reusing `open_app` matching) can be added in Phase 2 if the gap proves costly

### What not to load

Do not do:

- cross-file search
- embeddings
- SQLite/FTS
- broad cross-app recall

V1 recall is a tiny bounded lookup, not a retrieval system.

### Prompt placement

Inject recalled memory as a separate user-context block:

`history -> working memory -> recalled memory -> app skill -> observation`

Do not inject it as a new system message. The current `PromptBuilder` path already models these context sections as user messages, and V1 should fit that path instead of changing the request shape.

### Budget

Each file truncated to the most recent 2KB. Total memory section ≤ 6KB.

This is simpler than entry-count caps at the recall layer — the store already caps entry counts (see Retain), so recall just reads and truncates.

## Retain

### Write path: `remember_experience` tool

The primary write path is a **tool** the LLM calls during the task, not a background LLM call after task completion.

```
Tool: remember_experience
Parameters:
  category: "app" | "user_pref" | "device"
  content:  string (1-2 sentences, generalizable)
  package_name: string (required when category = "app")
```

The system prompt instructs: "Before calling `complete_task`, if you learned something reusable, call `remember_experience` to save it."

**Why tool-based, not background-LLM-call:**

| Dimension | Tool | Background LLM call |
|---|---|---|
| Complexity | One tool registration | New LLM invocation path + JSON schema + error handling |
| Cost | Zero extra LLM calls | ~1K tokens per task |
| Codebase fit | Same path as all other tools | First exception to the single `PromptBuilder → Turn → ToolRouter` flow |
| Reliability | Depends on LLM following prompt | Automatic |

The tool approach is chosen because it avoids creating a **second LLM invocation path** — today, every LLM call goes through `PromptBuilder → Turn.runStreaming() → ToolRouter`. A background retain call would be the first exception to this pattern, requiring its own prompt construction, model selection, streaming, and error handling.

### Critical runtime constraint

This choice is only fully correct if task completion normally flows through `complete_task`.

Today the runtime can still complete a task with **plain assistant text and no tool call** when no tool calls are returned (`Turn.kt` sets `isComplete = true` in that case). That means a purely tool-based retain path has a real gap:

- no `remember_experience` opportunity on text-only completion
- no automatic capture on terminal non-tool exits

So V1 must make one of these choices explicitly:

1. **Tighten the completion contract** so successful task completion must go through `complete_task`.
2. **Accept the gap** for V1 and rely on prompt instruction only.
3. **Add a later fallback retain hook** if the gap proves costly.

Current recommendation: keep the tool-first design for simplicity, but document this as the main known limitation. If the team wants correctness over minimalism here, the cleaner fix is to require `complete_task`, not to silently assume the gap does not exist.

**Coverage of edge cases:**

- **Normal completion:** The LLM calls `remember_experience` before or alongside `complete_task`. Multi-tool calls per turn are already supported (`ToolArbitrationResult.selectedToolCalls` is a list).
- **MaxTurnsReached:** The agent receives a "FINAL TURN" warning from `ExecutorStepPolicy` and should use that turn to save learnings.
- **Terminal errors:** Network failures, unrecoverable crashes — these don't produce useful app-specific learnings. The gap is acceptable.
- **UserRequested (manual stop):** Low-value for memory; the task was interrupted mid-stream.

If V1 data shows the LLM under-utilizes the tool, a background retain hook can be added in Phase 2 as primary path, with the tool as optional override.

### Store behavior on retain

- MemoryStore formats as `- [YYYY-MM-DD] {content}` and appends
- Creates file + header if it doesn't exist
- Entry caps enforced on write:
  - Per-app file: max 30 entries
  - `user_prefs.md`: max 20 entries
  - `device.md`: max 10 entries
- Over cap → remove oldest entry (top of list)
- No dedup in V1 (at ≤30 entries, LLM can see existing entries and avoid duplicates)

### Tool policy

Auto-allowed — no user approval needed. Writing a markdown bullet is low-risk and reversible.

## Components

Three components:

- **`MemoryStore`** — file read/write/append for `files/memory/`, entry cap enforcement
- **`MemoryRecaller`** — select relevant files by current package, format prompt block
- **`RememberExperienceTool`** — tool definition + invocation, delegates to MemoryStore

No additional types (no MemoryEntry data class, no MemoryKind enum, no MemoryScope sealed class). Entries are strings. Files are strings. Keep it flat.

### Wiring

- `MemoryStore` and `MemoryRecaller` instantiated in `SessionServices.create()`:
  ```kotlin
  val memoryDir = File(context.filesDir, "memory")
  val memoryStore = MemoryStore(memoryDir)
  val memoryRecaller = MemoryRecaller(memoryStore)
  ```
- `RememberExperienceTool(memoryStore)` registered in `SessionToolingBootstrapper`
- `PromptBuilder.buildInputItems()` gains a `recalledMemory: String?` parameter, injected as user message between working memory and app skill
- `TurnPlanningPhaseRunner` calls `memoryRecaller.recall(currentPackageName)` and passes to builder

### System Prompt Addition

```
## Long-Term Memory

You have persistent memory on this device. Relevant memories are loaded
automatically based on the current app.

Before calling complete_task, if you learned something reusable, call
remember_experience to save it:
- App quirks (button locations, navigation patterns, gotchas)
- User preferences (payment methods, notification habits)
- Device characteristics (screen density, OS version quirks)

Only store generalizable knowledge, not task-specific steps.
Keep entries to 1-2 sentences.
```

### File Changes

| File | Change |
|---|---|
| `app/.../memory/MemoryStore.kt` | **New** — ~80 lines |
| `app/.../memory/MemoryRecaller.kt` | **New** — ~50 lines |
| `app/.../tool/impl/RememberExperienceTool.kt` | **New** — ~80 lines |
| `app/.../agent/cognition/prompt/PromptBuilder.kt` | **Edit** — add `recalledMemory` param |
| `app/.../agent/TurnPlanningPhaseRunner.kt` | **Edit** — call recaller |
| `app/.../session/SessionServices.kt` | **Edit** — wire store + recaller |
| `app/.../session/SessionToolingBootstrapper.kt` | **Edit** — register tool |
| System prompt | **Edit** — add memory instructions |

~250 lines new code. 3 new files. 4-5 small edits.

## Explicit Non-Goals

V1 should not include:

- embeddings / vector search
- SQLite / FTS
- reflect / synthesis bank
- automatic writes into `app_skills`
- LLM-authored full-file rewrites
- entry kind taxonomy (workflow/pitfall/verification)
- confidence levels or evidence metadata
- background LLM retain call (defer to Phase 2 if tool proves insufficient)
- goal-based target-app recall on turn 1 (defer to Phase 2 if gap proves costly)
- dedup / merge logic (at V1 scale, LLM + human pruning suffice)

## Open Question for Master User

Should V1 also tighten task-completion semantics to require `complete_task` (removing the text-only completion fallback in `Turn.kt`)?

- **If yes:** tool-based retention becomes reliable for all normal completions. This is also independently valuable — structured completion data is better than inferring completion from text-only turns. Recommended.
- **If no:** tool-based retention remains a deliberate best-effort path. The text-only completion gap is accepted as a V1 limitation.

## Success Criteria

1. App experience from one session appears in the next session's prompt when the same app is in foreground
2. User preferences and device characteristics persist across sessions
3. Memory files are plain markdown — human-readable, manually editable
4. Memory operations never block or delay task completion or session lifecycle
5. Total prompt increase from memory ≤ 6KB
