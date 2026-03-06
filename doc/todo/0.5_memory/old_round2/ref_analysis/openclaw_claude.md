# OpenClaw Memory System Architecture

## 1. Core Philosophy/Metaphor

**"Persistent workspace files as the source of truth"** -- OpenClaw treats memory as plain Markdown files stored in a user's workspace (`~/.openclaw/workspace`), not ephemeral database records. The model only "remembers" what gets written to disk. This embraces a **git-friendly, human-reviewable, durable memory model** that survives process restarts and agent episodes.

**Secondary metaphor: "Letta/MemGPT-inspired archival memory"** -- They blend:
- A small "core" memory injected into every session (context-aware)
- Large archival memory retrieved via tools on-demand (token-efficient)
- Explicit tool-driven memory mutations (append/search/read)

## 2. Types of Memory Defined

OpenClaw defines a **three-tier memory hierarchy**:

1. **Daily Logs** (`memory/YYYY-MM-DD.md`)
   - Append-only narrative logs
   - Read at session start: today + yesterday
   - Day-to-day notes and running context
   - Each day is a separate file for organization

2. **Curated Long-Term Memory** (`MEMORY.md`)
   - Manually curated stable facts and preferences
   - Only loaded in main/private sessions (never in group contexts)
   - Smaller, durable, high-signal facts
   - Alternative: entity-centric pages (`bank/entities/Peter.md`, etc.)

3. **Session Transcripts** (optional, experimental)
   - Indexed conversation history (`~/.openclaw/agents/<id>/sessions/*.jsonl`)
   - Opted-in via `memorySearch.experimental.sessionMemory`
   - Debounced indexing, integrated into semantic search

## 3. How Memory Is Stored

**Primary storage: Markdown files**
- Plain text, git-trackable, human-editable
- Located in agent workspace: `~/.openclaw/workspace/`
- Pattern: daily logs by date, stable named files for curated memory

**Derived storage: SQLite index**
- Per-agent SQLite at `~/.openclaw/memory/<agentId>.sqlite`
- Always **rebuildable from Markdown** (not the source of truth)
- Contains:
  - **Chunks table**: tokenized/chunked Markdown with embeddings
  - **Embedding cache**: provider/model-specific cached vectors (avoid re-embedding)
  - **FTS5 virtual table**: full-text search indices (when available)
  - **Metadata**: file hashes, modification times, source tracking

**Optional QMD backend** (experimental)
- Launches a local sidecar (`qmd` CLI) for advanced retrieval
- Combines local embeddings + BM25 + reranking in a subprocess
- Auto-downloads GGUF models on first use; runs fully offline

## 4. How Memory is Retrieved/Recalled

Two **agent-facing tools**:

**`memory_search` (semantic + lexical recall)**
- Queries indexed Markdown chunks semantically and lexically
- Returns: snippet text (~700 chars), file path, line range, relevance score, provider metadata
- Hybrid retrieval: `finalScore = vectorWeight * vectorScore + textWeight * textScore`
  - Default: 70% vector (semantic), 30% BM25 (exact keywords)
  - Candidate pool multiplier to balance relevance/diversity
- Optional post-processing:
  - **MMR (Maximal Marginal Relevance)**: re-rank to reduce redundant results (useful for daily logs with repetitive themes)
  - **Temporal decay**: exponentially decay scores by age; recent notes rank higher (30-day half-life default)
- Provider options: local embeddings (GGUF via node-llama-cpp), OpenAI, Gemini, Voyage, Mistral, or auto-select
- Scope control: DM-only by default; can be loosened to group contexts via `memory.qmd.scope` rules

**`memory_get` (targeted file read)**
- Direct access to memory Markdown by path
- Optional: read from line N for M lines
- Graceful fallback: returns empty `{path, text: ""}` if file doesn't exist (no exception)
- Used after `memory_search` to pull exactly-needed lines and minimize context

**Design principle**: "mandatory recall step" -- the system prompt explicitly instructs the model to use `memory_search` before answering questions about decisions, dates, people, preferences, or todos.

## 5. How Memory Is Updated/Consolidated

**Synchronous writes (explicit agent action)**
- Agent calls memory tools to write/append to Markdown files
- Direct file I/O; persists immediately
- Agent can use `memory_get` then modify via external editor or tool

**Automatic memory flush (pre-compaction)**
- When session approaches token limit, OpenClaw triggers a **silent agentic turn** before compaction
- Reminds agent: "Store durable memories now"
- Model may reply with `NO_REPLY` (silent) if nothing to store
- Configurable soft threshold (default: 4000 tokens before capacity)
- Prevents loss of insights during context compaction

**Index synchronization**
- File watcher monitors `MEMORY.md` + `memory/` (debounce: 1.5s default)
- On detection: marks index dirty, schedules async reindex
- Reindex runs: on session start, on search, or on interval (default 5m with QMD)
- Timestamp tracking (`updated_at`) ensures fast delta syncs

## 6. Overall Architecture (Components, Layers, Data Flow)

```
+-------------------------------------------------------------+
|                      Agent Runtime                           |
|  (OpenClaw session, model, tools)                            |
+----------------------+--------------------------------------+
                       |
                       |-> TOOL LAYER
                       |   |- memory_search (semantic + lexical)
                       |   +- memory_get (targeted file read)
                       |
                       |-> MANAGER LAYER
                       |   |- MemorySearchManager (in-process orchestrator)
                       |   |- Embedding provider client (OpenAI/Gemini/local)
                       |   +- QMD subprocess launcher (optional)
                       |
                       |-> INDEX LAYER
                       |   |- SQLite: chunks, embeddings, FTS5
                       |   |- Vector store (in-JS or sqlite-vec extension)
                       |   |- Embedding cache (avoid re-embeds)
                       |   +- File watcher + debounced sync
                       |
                       +-> STORAGE LAYER
                           |- Markdown files (git-tracked)
                           |  |- memory/YYYY-MM-DD.md (daily logs)
                           |  |- MEMORY.md (curated long-term)
                           |  +- bank/ (entity pages, opinions)
                           |
                           +- Derived indices (not source-of-truth)
                              |- ~/.openclaw/memory/<agentId>.sqlite
                              +- ~/.openclaw/agents/<id>/qmd/ (optional)
```

**Data flow**:
1. Agent writes facts to Markdown -> file watcher detects -> marks index dirty
2. On next search/session: chunks Markdown (400 tokens, 80 overlap) -> embeds via provider
3. Query arrives -> parallel vector + FTS5 -> weighted merge -> optional MMR/decay -> top-K
4. Results cite sources (path#lineNumber) when `memory.citations = "auto"`

## 7. Novel/Unique Concepts

**Hybrid retrieval (BM25 + vectors)**
- Pragmatic blend: vectors shine at semantic paraphrases, BM25 wins at exact tokens (IDs, env vars, code symbols)
- Weighted merge: `0.7 * vectorScore + 0.3 * textScore` (configurable)
- Fallback: if embeddings unavailable, pure BM25 still works

**Temporal decay**
- Not a standard memory feature; addresses the "old info outranks new info" problem in daily-note workflows
- Exponential decay: `score * e^(-lambda * ageInDays)` with 30-day half-life default
- Evergreen files (`MEMORY.md`, non-dated `memory/*.md`) never decay
- Example: a 6-month-old note drops to ~1.6% of original relevance score

**Citations + scope control**
- Results can include source footers (`Source: path#line123`) when `memory.citations = "auto"`
- Scoped retrieval: memory search can be limited to DM-only, excluding group chats via `memory.qmd.scope` rules

**QMD backend (experimental)**
- Runs semantic search as a **subprocess sidecar** (not in-process)
- Combines local embeddings (GGUF, auto-download) + query expansion + reranking
- Graceful fallback: if QMD fails, reverts to builtin SQLite manager
- Offline-first: no cloud dependency for indexing or queries

**Chunking with overlap**
- Markdown tokenized into ~400-token chunks with 80-token overlap (configurable)
- Overlap preserves context boundaries; helps with queries that span natural breaks

**Session transcript indexing**
- Optional, experimental feature: index conversation history as a separate memory source
- Deltaed indexing (triggers on 100 KB or 50 messages of new content) to avoid constant re-embeds
- Kept separate from curated memory; `memory_get` still limited to `.md` files

**Automatic provider fallback + reindexing detection**
- If provider changes (e.g., OpenAI -> Gemini), automatically resets and re-indexes entire store
- Detects provider/model/endpoint fingerprint changes automatically
- Embedding cache prevents re-embedding unchanged text across model switches

---

**Summary**: OpenClaw's memory is fundamentally a **git-friendly, human-readable Markdown layer** with a sophisticated **derived SQLite index** that powers semantic + lexical retrieval via tools. It blends **Letta-style archival memory** (small core + large retrieval) with structured fact management, all while maintaining Markdown as the persistent, auditable source of truth.
