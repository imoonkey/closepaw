# Recommendations: Android Agent Memory System Design

Concrete design recommendations for the Android agent's memory system, grounded in analysis of 9 reference systems and the agent's actual architecture.

---

## 1. Proposed Memory Taxonomy

Based on what this agent actually does (UI automation on Android), we propose **4 memory categories**:

### 1.1 App Knowledge (`app_knowledge`)
**What**: Facts about specific apps — navigation paths, UI patterns, element identifiers, quirks.
**Why**: The most unique opportunity. An agent that "learns" where settings live in each app, which elements are scrollable, what a specific button does — this is knowledge that directly reduces future turn counts.
**Examples**:
- "In WeChat, the search bar is at the top of the Chats tab"
- "Google Maps requires scrolling down 3 times to reach 'Offline maps' in settings"
- "Settings → Display → Dark theme toggle has resource-id `dark_theme_switch`"

**Lifecycle**: Long-lived, updated when app layouts change. Keyed by `package_name`.

### 1.2 User Preferences (`user_preference`)
**What**: Learned user habits, defaults, naming conventions, preferred apps.
**Examples**:
- "User prefers WeChat over SMS for messaging"
- "User's primary email is work@example.com"
- "When user says 'home screen', they mean the launcher, not the Smart Home app"
- "User prefers dark mode"

**Lifecycle**: Long-lived, rarely changes. Updated explicitly or inferred from repeated behavior.

### 1.3 Task Patterns (`task_pattern`)
**What**: Reusable procedures for recurring tasks — learned from successful completions.
**Examples**:
- "To send a WeChat message: open_app('WeChat') → tap search → type name → tap contact → type message → tap send"
- "To toggle WiFi: Settings → Network & internet → Internet → WiFi toggle"

**Lifecycle**: Medium-lived. Validated by successful task completion. Invalidated by failures.

### 1.4 Session Context (`session_context`)
**What**: This is the *existing* scratchpad + todo system. Facts relevant to the current task only.
**Examples**: Email addresses extracted from screen, intermediate calculation results, step tracking.
**Lifecycle**: Session-scoped. Discarded on session end (unless promoted to another category).

---

## 2. Architecture: Two-Layer Memory

Inspired by Letta's working/archival split and OpenViking's progressive loading:

```
+----------------------------------------------------------+
|                    LLM PROMPT CONTEXT                     |
|                                                          |
|  [Working Memory]  <-- always injected (~200-500 tokens) |
|   |- Todo List (current tasks)                           |
|   |- Scratchpad (current session facts)                  |
|   |- Memory Keys (L0: titles of relevant memories)       |
|                                                          |
+---------------------------+------------------------------+
                            |
                     tool calls (on-demand)
                            |
+---------------------------v------------------------------+
|                   ARCHIVAL MEMORY                        |
|                   (Room/SQLite)                          |
|                                                          |
|  [memory_search] → query → ranked results (L1 summaries)|
|  [memory_read]   → id → full content (L2)               |
|  [memory_write]  → create/update/delete                  |
|                                                          |
|  Tables:                                                 |
|   |- memories (id, category, key, summary, content,      |
|   |           package_name, confidence, created_at,      |
|   |           updated_at, access_count)                  |
|   |- memory_fts (FTS5 virtual table on key+summary+      |
|   |             content)                                 |
|   +- memory_tags (memory_id, tag)                        |
+----------------------------------------------------------+
```

### 2.1 Working Memory (In-Context, Every Turn)

**Current**: Scratchpad keys + Todo list → injected in `PromptBuilder.buildMemorySection()`

**Proposed extension**: Add a **Memory Keys** section showing L0 references (titles only) of relevant long-term memories. This primes the agent to know what it has remembered without consuming significant tokens.

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

Token cost: ~50-100 tokens for the Memory Keys section (just titles). Controlled by a cap (e.g., max 5 relevant memory keys per turn).

### 2.2 Archival Memory (Tool-Accessed, On-Demand)

Three new tools for the agent:

**`memory_search(query, category?, package_name?)`**
- Returns top-K results as L1 summaries (~100 chars each)
- Uses FTS5 full-text search + optional category/package filtering
- Ranked by relevance score * recency bonus
- Agent decides whether to call this based on Memory Keys or task needs

**`memory_read(memory_id)`**
- Returns full L2 content of a specific memory
- Used after `memory_search` when the agent needs details

**`memory_write(category, key, content, package_name?)`**
- Creates or updates a memory entry
- If key already exists for the same category+package: update (overwrite) with new content
- Agent explicitly decides what to persist (like current scratchpad writes)

---

## 3. Storage Strategy

### 3.1 Room/SQLite (Recommended)

Room is the standard Android persistence layer. Use it as the single storage backend.

**Memory table schema**:
```sql
CREATE TABLE memories (
    id          TEXT PRIMARY KEY,    -- UUID
    category    TEXT NOT NULL,       -- app_knowledge | user_preference | task_pattern
    key         TEXT NOT NULL,       -- human-readable title
    summary     TEXT NOT NULL,       -- L1: short summary (~100 chars)
    content     TEXT NOT NULL,       -- L2: full content
    package_name TEXT,               -- nullable, for app_knowledge
    confidence  REAL DEFAULT 1.0,   -- 0.0-1.0, decays over time
    access_count INTEGER DEFAULT 0, -- usage tracking
    created_at  INTEGER NOT NULL,   -- epoch millis
    updated_at  INTEGER NOT NULL,   -- epoch millis
    supersedes_id TEXT,             -- optional: previous version's ID
    UNIQUE(category, key, package_name)
);

-- Full-text search index
CREATE VIRTUAL TABLE memory_fts USING fts5(
    key, summary, content,
    content='memories',
    content_rowid='rowid'
);
```

**Why not vector storage**: On-device embedding models add 50-200MB to APK, consume battery, and require runtime inference. FTS5 provides 80% of the retrieval quality for 1% of the cost. Vector search can be added later as an opt-in enhancement.

**Why not files**: Files lack structured queries, FTS, and transactions. The agent needs "find all memories about WeChat" — SQL does this natively.

### 3.2 Retrieval Ranking

Simple scoring function (no vectors needed):

```
score = fts5_rank(query)
      * recency_boost(updated_at)
      * confidence
      * (1 + 0.1 * log(access_count + 1))
```

Where `recency_boost = e^(-lambda * age_days)` with a 90-day half-life (longer than OpenClaw's 30-day because app knowledge changes less frequently than daily notes).

---

## 4. Consolidation Strategy

### 4.1 Explicit Agent Writes (Primary)

The agent calls `memory_write` during task execution, just like it currently uses `scratchpad`:
- When it discovers an app navigation path
- When it learns a user preference
- When it successfully completes a recurring task pattern

System prompt instruction: *"Use `memory_write` to save reusable knowledge (app navigation, user preferences, successful procedures) that would help future tasks."*

### 4.2 Session-End Extraction (Secondary)

At session end (transition to Idle/Shutdown), run a lightweight extraction pass:

1. Check if scratchpad has entries that could be promoted to long-term memory
2. Use a single LLM call (or heuristic rules) to classify: "Is this fact session-specific or reusable?"
3. Promote reusable facts to archival memory via `memory_write`

This mirrors OpenClaw's "automatic memory flush before compaction" pattern, adapted to the session lifecycle.

**Heuristic rules** (no LLM needed for common cases):
- Scratchpad key starts with `app_` or contains a package name → candidate for `app_knowledge`
- Scratchpad key starts with `user_` or `pref_` → candidate for `user_preference`
- Task completed successfully + >3 turns → candidate for `task_pattern` extraction

### 4.3 Confidence Decay

Memories that haven't been accessed or updated decay gradually:
- `confidence = initial_confidence * e^(-lambda * days_since_last_access)`
- Half-life: 90 days (app knowledge is fairly stable)
- Accessing a memory resets its `updated_at` and boosts `access_count`
- Memories below confidence threshold (e.g., 0.1) are excluded from search results

---

## 5. Integration with Existing Architecture

### 5.1 PromptBuilder Changes

Current prompt flow: History → Working Memory → Observation

New flow: History → Working Memory (including Memory Keys) → Observation

In `PromptBuilder.buildMemorySection()`:
```kotlin
// After existing scratchpad/todo rendering, add:
val relevantMemoryKeys = memoryStore.findRelevantKeys(
    taskDescription = currentTask,
    packageName = currentPackageName,
    limit = 5
)
if (relevantMemoryKeys.isNotEmpty()) {
    appendLine()
    appendLine("### Relevant Memories (use memory_read for details)")
    for (mem in relevantMemoryKeys) {
        appendLine("- [${mem.category}] ${mem.key}")
    }
}
```

### 5.2 Tool Registration

Add to `StandaloneAgentDef.allowedTools`:
```kotlin
"memory_search", "memory_read", "memory_write"
```

Three new `ToolSpec` implementations under `tool/memory/`:
- `MemorySearchTool` — queries FTS, returns L1 summaries
- `MemoryReadTool` — returns full L2 content by ID
- `MemoryWriteTool` — upsert with category/key/content

### 5.3 SessionServices Extension

Add `MemoryStore` to `SessionServices`:
```kotlin
class SessionServices(
    ...
    val memoryStore: MemoryStore,  // Room DAO wrapper
    ...
)
```

`MemoryStore` is **session-independent** (unlike scratchpad/todo). It persists across sessions, lives in the Application scope, not the session scope.

### 5.4 Session Checkpoint Integration

- Archival memory is NOT part of session checkpoints (it's persistent in Room)
- Scratchpad + Todos remain session-scoped and checkpointed as before
- On session end: optional scratchpad → memory promotion pass

### 5.5 System Prompt Addition

Add to the agent's system prompt:
```
## Memory
- You have persistent memory across sessions stored in a database.
- `memory_search(query="...", category="...", package_name="...")` to find relevant memories.
- `memory_read(memory_id="...")` to get full details of a specific memory.
- `memory_write(category="...", key="...", content="...", package_name="...")` to save reusable knowledge.
- Categories: app_knowledge, user_preference, task_pattern.
- Save knowledge that would help future tasks: app navigation paths, user preferences, successful procedures.
- The "Relevant Memories" section in Working Memory shows what you've saved before. Use memory_read if you need details.
```

---

## 6. Proactive Memory Injection

Beyond tool-based retrieval, some memories should be **proactively injected** into the prompt:

### 6.1 Package-Based Context Loading

When the agent observes which app is on screen (via `getCurrentPackageName()`), automatically load L0 keys for all `app_knowledge` memories for that package:

```kotlin
// In TurnExecutionPhaseRunner or PromptBuilder
val currentPackage = platform.getCurrentPackageName()
val appMemories = memoryStore.getKeysForPackage(currentPackage, limit = 5)
// Inject as Memory Keys in Working Memory section
```

This is the OpenViking "L0 progressive loading" pattern — tiny cost (just keys), high value (agent knows what it has learned about this app).

### 6.2 Task-Based Priming

When a new task starts, do a lightweight FTS search against the task description:
```kotlin
// On new UserInput
val taskMemories = memoryStore.search(userTask, limit = 3)
// Inject top results as Memory Keys
```

---

## 7. What NOT to Build

### 7.1 No vector embeddings (initial version)
- FTS5 is sufficient for keyword/phrase matching
- On-device embedding is expensive and unnecessary at this scale
- Can be added later if FTS5 proves insufficient

### 7.2 No graph storage
- Relationship tracking (mem0/supermemory style) adds complexity
- A simple `supersedes_id` column handles version chains
- Tags table handles categorization

### 7.3 No async background workers
- No WorkManager jobs for memory consolidation
- Session-end extraction is synchronous and sufficient
- Background processing adds battery/complexity concerns

### 7.4 No fine-tuning / parametric memory
- No on-device model fine-tuning
- Second-Me's approach is irrelevant for task automation

### 7.5 No multi-agent memory sharing (initially)
- The agent has planner/executor sub-agents, but they share the same session
- Cross-session memory is application-wide, not agent-specific
- Multi-agent complexity can be added after the basic system works

---

## 8. Phased Implementation

### Phase 1: Foundation
- Room database + Memory table + FTS5 index
- `MemoryStore` class (DAO wrapper with search/read/write)
- 3 tool specs: `MemorySearchTool`, `MemoryReadTool`, `MemoryWriteTool`
- Tool registration in `StandaloneAgentDef`
- System prompt instructions for memory usage
- Proactive package-based L0 key injection in `PromptBuilder`

### Phase 2: Consolidation
- Session-end scratchpad promotion (heuristic-based)
- Confidence decay + access counting
- Task-based memory priming (FTS search on new task)
- Memory management UI (view/delete/edit memories)

### Phase 3: Intelligence
- LLM-driven session-end extraction (optional, for higher-quality memory)
- Memory deduplication (detect near-duplicate entries)
- Task pattern learning from successful multi-step completions
- Optional: on-device embedding model for semantic search

---

## 9. Key Design Principles

1. **SQLite-first**: Room + FTS5 is the only infrastructure. No external services, no cloud dependencies.

2. **Agent-driven writes**: The agent explicitly decides what to remember (like scratchpad). No automatic extraction that the agent can't control.

3. **Progressive loading**: L0 (key only, ~10 tokens) → L1 (summary, ~50 tokens) → L2 (full content, variable). Never inject L2 into the prompt automatically.

4. **Token-budget aware**: Memory Keys section is hard-capped (e.g., 5 entries * ~20 tokens = 100 tokens max). Full content only via tool calls.

5. **Category-gated search**: The 4-category system prevents "memory soup". `app_knowledge` is scoped to `package_name`, `task_pattern` is scoped to successful completions.

6. **Graceful degradation**: If memory store is empty (new install), the agent works exactly as it does today. Memory is additive, not required.

7. **Human-reviewable**: Room DB can be exported; entries have human-readable keys and categories. No opaque vector blobs.
