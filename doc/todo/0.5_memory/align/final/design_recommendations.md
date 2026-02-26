# Android Agent Memory System — Design Recommendations

Design specification for the Android agent's persistent memory system, synthesized from analysis of 9 reference systems and aligned with the existing codebase architecture.

---

## 1. Design Principles

1. **SQLite-first**: Room + FTS5 is the only infrastructure. No external services, no cloud dependencies, no embedding models.
2. **Agent-driven first, lifecycle-assisted**: Explicit `memory_write` is the primary path; lightweight lifecycle hooks can promote high-value candidates later.
3. **Progressive disclosure**: Memory keys (~10 tokens each) are injected proactively; summaries and full content are accessed on-demand via tools. Never inject full content automatically.
4. **Token-budget aware**: Memory injection is hard-capped (e.g., max 5 memory keys per turn, ~100 tokens total). Full content only via explicit tool calls.
5. **Graceful degradation**: If memory store is empty (new install), the agent works exactly as it does today. Memory is additive, not required.
6. **KISS**: Minimal layers, minimal nesting, no over-engineering. SQL categories + FTS beats vector DBs + graph DBs for this use case.
7. **Local-first & privacy-aware**: All data stays on device. Sensitive data from banking/payment apps is excluded by default.
8. **Guarded persistence boundary**: Every write passes deterministic validation/sanitization/dedup rules in `MemoryStore` before commit.

---

## 2. Memory Taxonomy

Three memory categories stored in the persistent memory system. The existing working memory (scratchpad + todo) remains session-scoped and unchanged.

### 2.1 App Knowledge (`app_knowledge`)

**What**: Facts about specific apps — navigation paths, UI patterns, element identifiers, quirks.

**Why**: The highest-value opportunity for a UI automation agent. Knowing where settings live, which elements are scrollable, what resource-ids exist — directly reduces future turn counts and increases task success rate.

**Examples**:
- "In WeChat, the search bar is at the top of the Chats tab"
- "Google Maps requires scrolling down 3 times to reach 'Offline maps' in settings"
- "Settings > Display > Dark theme toggle has resource-id `dark_theme_switch`"

**Lifecycle**: Long-lived, updated when app layouts change. Keyed by `package_name`.

### 2.2 User Preferences (`user_preference`)

**What**: Learned user habits, defaults, naming conventions, preferred apps.

**Examples**:
- "User prefers WeChat over SMS for messaging"
- "User's primary email is work@example.com"
- "When user says 'home screen', they mean the launcher, not the Smart Home app"

**Lifecycle**: Long-lived, rarely changes. Updated explicitly or inferred from repeated behavior.

### 2.3 Task Patterns (`task_pattern`)

**What**: Reusable procedures for recurring tasks — learned from successful completions.

**Examples**:
- "To send a WeChat message: open_app('WeChat') > tap search > type name > tap contact > type message > tap send"
- "To toggle WiFi: Settings > Network & internet > Internet > WiFi toggle"

**Lifecycle**: Medium-lived. Validated by successful task completion. Invalidated by failures.

### 2.4 Rationale for this taxonomy

We chose flat categories over Codex's layered hierarchy (L0-L3) because:
- SQL `WHERE category = ?` is simpler than layered routing logic
- Each category has clear write triggers and lifecycle rules
- No nesting or hierarchy to manage
- Maps directly to the agent's operational needs (know apps, know user, know procedures)

The existing scratchpad + todo system continues to serve as session-scoped working memory. It is not part of and not replaced by this persistent memory system.

---

## 3. Architecture: Working Memory + Archival Memory

The system extends the existing architecture with one new persistent layer:

```
+----------------------------------------------------------+
|                    LLM PROMPT CONTEXT                     |
|                                                          |
|  [Working Memory]  <-- always injected                   |
|   |- Todo List (current tasks)          -- existing      |
|   |- Scratchpad (current session facts) -- existing      |
|   |- Memory Keys (titles of relevant    -- NEW           |
|   |  long-term memories, max 5)                          |
|                                                          |
+---------------------------+------------------------------+
                            |
                     tool calls (on-demand)
                            |
+---------------------------v------------------------------+
|                 PERSISTENT MEMORY                        |
|                 (Room/SQLite + FTS5)                     |
|                                                          |
|  [memory_search] -> query -> ranked results (summaries)  |
|  [memory_read]   -> id -> full content                   |
|  [memory_write]  -> upsert with category/key/content     |
|  [memory_delete] -> remove by id                         |
|                                                          |
|  Tables:                                                 |
|   |- memories (structured records with FTS5 index)       |
+----------------------------------------------------------+
```

### 3.1 Working Memory (unchanged)

The existing `AgentSessionState` (scratchpad + todo) remains as-is. It is session-scoped, lives in `session/`, and is injected into prompts by `PromptBuilder.buildMemorySection()`.

The only change: after the existing scratchpad/todo section, `PromptBuilder` appends a **Memory Keys** section showing titles of relevant long-term memories. This primes the agent to know what it has remembered without consuming significant tokens.

```
## Working Memory

### Todo List
1. [IN_PROGRESS] Send message to John in WeChat

### Scratchpad
- recipient_name: John Smith
- message_draft: Meeting at 3pm

### Relevant Memories (use memory_read for details)
- [app] WeChat: chat search navigation path
- [pref] User's preferred messaging app: WeChat
- [task] Procedure: send WeChat message (5 steps)
```

Token cost for Memory Keys: ~50-100 tokens (just titles, max 5 entries).

### 3.2 Persistent Memory (new)

Four tools expose the persistent memory store to the agent:

**`memory_search(query, category?, package_name?)`**
- Returns top-K results as summaries (~100 chars each)
- Uses FTS5 full-text search + optional category/package filtering
- Ranked by relevance * recency * confidence * usage
- Agent decides whether to call this

**`memory_read(memory_id)`**
- Returns full content of a specific memory
- Used after `memory_search` when the agent needs details

**`memory_write(category, key, content, package_name?, summary?)`**
- Creates or updates a memory entry
- If key already exists for the same category+package: update (overwrite) with new content
- Agent explicitly decides what to persist
- If `summary` is omitted, `MemoryStore` generates one deterministically (truncate/normalize `content`) with no extra LLM call

**`memory_delete(memory_id)`**
- Removes an outdated or incorrect memory
- Agent calls this when it discovers saved information is wrong

---

## 4. Storage Schema

### 4.1 Room/SQLite Table

```sql
CREATE TABLE memories (
    id            TEXT PRIMARY KEY,    -- UUID
    category      TEXT NOT NULL,       -- app_knowledge | user_preference | task_pattern
    key           TEXT NOT NULL,       -- human-readable title
    summary       TEXT NOT NULL,       -- short summary (~100 chars)
    content       TEXT NOT NULL,       -- full content
    package_name  TEXT,                -- nullable, for app_knowledge
    confidence    REAL DEFAULT 1.0,    -- 0.0-1.0, decays over time
    access_count  INTEGER DEFAULT 0,   -- usage tracking
    created_at    INTEGER NOT NULL,    -- epoch millis
    updated_at    INTEGER NOT NULL,    -- epoch millis
    UNIQUE(category, key, package_name)
);

-- Full-text search index
CREATE VIRTUAL TABLE memory_fts USING fts5(
    key, summary, content,
    content='memories',
    content_rowid='rowid'
);
```

### 4.2 Why Room/SQLite + FTS5

- **Android-native**: Room is the standard persistence layer. No additional dependencies.
- **Lightweight**: Zero APK size increase. No embedding model needed.
- **Sufficient retrieval quality**: FTS5 provides keyword/phrase matching that covers the agent's needs (searching by app name, action verbs, preference keywords).
- **Structured queries**: `WHERE category = ? AND package_name = ?` is fast and precise.
- **Human-reviewable**: DB can be inspected via Android Studio Database Inspector. Entries have human-readable keys and categories, no opaque vector blobs.

### 4.3 Why NOT vector embeddings (for initial version)

- On-device embedding models add 50-200MB to APK size
- Consume battery and require runtime inference
- FTS5 provides ~80% of retrieval quality for ~1% of the cost
- Vector search can be added later as an opt-in enhancement if FTS5 proves insufficient

### 4.4 Retrieval Scoring

```
score = fts5_rank(query)
      * recency_boost(updated_at)
      * confidence
      * (1 + 0.1 * log(access_count + 1))
```

Where `recency_boost = e^(-lambda * age_days)` with a 90-day half-life (app knowledge is fairly stable; longer than OpenClaw's 30-day default for daily notes).

---

## 5. Write Strategy

### 5.1 Agent-Driven Explicit Writes (Primary)

The agent calls `memory_write` during task execution, just like it currently uses `scratchpad`:
- When it discovers an app navigation path
- When it learns a user preference
- When it successfully completes a recurring task pattern

System prompt instruction: *"Use `memory_write` to save reusable knowledge (app navigation, user preferences, successful procedures) that would help future tasks. Be selective — only save knowledge you would want available across sessions."*

### 5.2 Write Guardrails (Mandatory, Phase 1)

Every `memory_write` is passed through a deterministic store-level guard before persistence:
1. **Schema validation**: category must be in enum; `package_name` required for `app_knowledge`, forbidden for others.
2. **Denylist gate**: reject app-scoped writes from blocked packages (banking/payment by default).
3. **Sensitive pattern scan**: block the write if password/OTP/card/token-like strings are detected (regex + keyword heuristics).
4. **Normalization**: trim key/content, clamp lengths, and generate fallback summary when missing.
5. **Dedup/upsert**: apply `UNIQUE(category, key, package_name)` and reinforce metadata (`updated_at`, `access_count`, `confidence`) instead of duplicating rows.

This is not a heavy async pipeline; it is a compact boundary check that prevents memory noise and secret leakage.

### 5.3 Session-End Extraction (Secondary, Phase 2)

At session end, optionally run a lightweight extraction pass:
1. Check if scratchpad has entries that could be promoted to long-term memory
2. Use heuristic rules (not LLM) to classify candidates:
   - Scratchpad key contains a package name -> candidate for `app_knowledge`
   - Scratchpad key starts with `user_` or `pref_` -> candidate for `user_preference`
   - Task completed successfully with >3 turns -> candidate for `task_pattern`
3. Promote candidates via `memory_write`

This remains a Phase 2 enhancement. The primary write path is explicit agent tool calls.

### 5.4 Confidence Decay

Memories that haven't been accessed or updated decay gradually:
- `confidence = initial_confidence * e^(-lambda * days_since_last_access)`
- Half-life: 90 days
- Accessing a memory resets its `updated_at` and increments `access_count`
- Memories below threshold (e.g., 0.1) are excluded from search results but not deleted

---

## 6. Proactive Memory Injection + Retrieve Gate

Beyond tool-based retrieval, some memories should be proactively loaded into Memory Keys:

### 6.1 Package-Based Loading

When the agent observes which app is on screen (via current package name), automatically load L0 keys for all `app_knowledge` memories for that package:

```kotlin
val currentPackage = platform.getCurrentPackageName()
val appMemories = memoryStore.getKeysForPackage(currentPackage, limit = 3)
```

### 6.2 Task-Based Priming

When a new task starts, do a lightweight FTS search against the task description:

```kotlin
val taskMemories = memoryStore.search(userTask, limit = 3)
```

Combined with package-based loading, the total is capped at 5 Memory Keys per turn.

### 6.3 Retrieve Gate Policy (KISS)

To avoid unnecessary search calls, retrieval follows one simple gate:
- If `Relevant Memories` already contains a high-confidence key that matches the current task/app, call `memory_read` directly.
- Call `memory_search` only when keys are missing, conflicting, or too low-confidence.
- Never call `memory_search` repeatedly in the same turn after a decisive miss.

This preserves Claude's proactive-key approach while adding Codex's budget guard.

---

## 7. Integration with Existing Codebase

### 7.1 New Package: `memory/`

```
app/src/main/kotlin/com/moonkey/androidagent/
└── memory/
    ├── MemoryStore.kt         -- Room DAO wrapper (search/read/write/delete)
    ├── MemoryEntity.kt        -- Room entity (@Entity)
    ├── MemoryDao.kt           -- Room DAO interface
    └── MemoryDatabase.kt      -- Room database definition
```

### 7.2 New Tools: `tool/memory/`

```
app/src/main/kotlin/com/moonkey/androidagent/
└── tool/memory/
    ├── MemorySearchTool.kt    -- FTS5 search, returns summaries
    ├── MemoryReadTool.kt      -- Full content by ID
    ├── MemoryWriteTool.kt     -- Upsert with category/key/content
    └── MemoryDeleteTool.kt    -- Remove by ID
```

### 7.3 PromptBuilder Changes

In `PromptBuilder.buildMemoryText()`, after existing scratchpad/todo rendering:

```kotlin
val relevantKeys = memoryStore.findRelevantKeys(
    taskDescription = currentTask,
    packageName = currentPackageName,
    limit = 5
)
if (relevantKeys.isNotEmpty()) {
    appendLine()
    appendLine("### Relevant Memories (use memory_read for details)")
    for (mem in relevantKeys) {
        appendLine("- [${mem.categoryShort}] ${mem.key}")
    }
}
```

### 7.4 Tool Registration

Add to `SessionToolingBootstrapper.registerBuiltInTools()`:

```kotlin
register(MemorySearchTool(memoryStore))
register(MemoryReadTool(memoryStore))
register(MemoryWriteTool(memoryStore))
register(MemoryDeleteTool(memoryStore))
```

Add to `StandaloneAgentDef.allowedTools`:

```kotlin
"memory_search", "memory_read", "memory_write", "memory_delete"
```

### 7.5 MemoryStore Scope

`MemoryStore` is **application-scoped** (not session-scoped):
- Lives in the Application component, not in `SessionServices`
- Persists across sessions — this is its entire purpose
- `SessionServices` receives a reference to the app-scoped `MemoryStore`
- The Room database is application-level, managed by `AgentApplication` or a DI scope

### 7.6 System Prompt Addition

Add to the agent's system prompt:

```
## Memory
You have persistent memory across sessions stored in a database.
- `memory_search(query, category?, package_name?)`: Find relevant memories. Returns summaries.
- `memory_read(memory_id)`: Get full details of a specific memory.
- `memory_write(category, key, content, package_name?, summary?)`: Save reusable knowledge (summary optional).
- `memory_delete(memory_id)`: Remove outdated or incorrect memories.
- Categories: app_knowledge, user_preference, task_pattern.
- Save knowledge that would help future tasks: app navigation paths, user preferences, successful procedures.
- Do not save secrets (passwords, OTPs, card numbers, auth tokens); blocked writes are expected.
- If relevant keys are already visible in Working Memory, prefer `memory_read` before `memory_search`.
- The "Relevant Memories" section in Working Memory shows what you have saved before. Use memory_read if you need details.
```

---

## 8. Privacy & Security

### 8.1 Sensitive Data Exclusion

- Banking and payment apps (configurable denylist by package name) are excluded from `app_knowledge` memory writes by default.
- The agent system prompt instructs: *"Never save passwords, verification codes, card numbers, or authentication tokens to memory."*
- `MemoryStore` enforces store-level validation/sanitization (denylist + sensitive-pattern checks) even if the model emits a bad write.

### 8.2 User Control

- Settings page provides: view all memories, delete individual memories, clear all memories, toggle memory on/off.
- Memory database can be exported for user inspection.

### 8.3 On-Device Only

- All memory data stays on device in Room/SQLite.
- No cloud sync, no remote storage, no telemetry of memory contents.

---

## 9. Phased Implementation

### Phase 1: Foundation

- Room database + Memory table + FTS5 index
- `MemoryStore` class (application-scoped DAO wrapper)
- 4 tool specs: `MemorySearchTool`, `MemoryReadTool`, `MemoryWriteTool`, `MemoryDeleteTool`
- Tool registration in `SessionToolingBootstrapper` and `StandaloneAgentDef`
- System prompt instructions for memory usage
- Proactive Memory Keys injection in `PromptBuilder`
- Deterministic write guardrails in `MemoryStore` (validation/sanitization/dedup)
- Retrieve gate policy for `memory_search` vs `memory_read`

### Phase 2: Consolidation & Intelligence

- Session-end scratchpad promotion (heuristic-based)
- Confidence decay + access counting
- Task-based memory priming (FTS search on new task)

### Phase 3: Polish & Observability

- Memory management UI (view/delete/edit memories in Settings)
- App denylist configuration
- Memory deduplication (detect near-duplicate entries)
- Optional: on-device embedding model for semantic search (opt-in, Phase 3+)

---

## 10. Resolved Design Decisions

1. **Capacity policy**: Phase 1 relies on confidence decay + manual cleanup only. No hard cap. A single-user on-device agent is unlikely to accumulate thousands of memories quickly. If growth becomes a problem, Phase 2 can introduce a soft threshold (e.g., 1000 records) with lowest-confidence eviction. Adding an eviction policy now would be premature optimization.

2. **Sanitization strictness**: Phase 1 **blocks the entire write** when sensitive patterns are detected. The agent receives a clear failure result (`ToolExecutionResult.Failure` with explanation) and can reformulate. Blocking is simpler and safer than partial redaction — redacted content may be meaningless or misleadingly incomplete. Redaction can be revisited in Phase 2 if blocking proves too aggressive in practice.
