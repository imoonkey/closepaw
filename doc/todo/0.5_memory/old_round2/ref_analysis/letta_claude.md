# Letta Memory System: High-Level Architecture

## 1. Core Philosophy/Metaphor

**"Stateful agents with advanced memory that can learn and self-improve over time"**

Letta frames memory as a **multi-layered context management system** where different types of information serve different roles in an agent's cognition:
- **Core memory** = immediate, editable knowledge about the user and agent personality
- **Archival memory** = long-term, searchable historical records
- **Recall memory** = conversation history in the local context window
- **File memory** = structured access to external documents

The system metaphor is **hierarchical and functional** -- different memory tiers are optimized for different access patterns and lifespans.

## 2. Memory Types Defined

**Three Primary Memory Layers:**

1. **Core Memory (In-Context Blocks)**
   - Editable label-value pairs (e.g., "human", "persona", custom blocks)
   - Character-limited blocks with descriptions and metadata
   - Rendered into XML tags within system prompt
   - Two rendering modes: standard vs. git-backed filesystem
   - Can be read-only or mutable

2. **Archival Memory (Long-Term Storage)**
   - Collection of *passages* (text snippets with embeddings)
   - Organized into named *archives* shared across agents
   - Each passage has: text, embedding, tags, timestamps, metadata
   - Taggable for semantic organization
   - Retrieved via vector similarity search

3. **Recall Memory (Conversation History)**
   - Sequence of conversation messages (user/agent/system)
   - Stored in database with full context
   - Only recent messages kept in context window via summarization
   - Can be condensed/summarized when context exceeds limits

4. **File Memory (Data Access)**
   - Special "file blocks" representing open documents
   - Tracks file status (open/closed), access history
   - Supports multi-file context with limits on concurrent open files
   - Includes embeddings for file sections

## 3. Memory Storage Strategy

**Multi-Store Architecture:**

- **Primary: PostgreSQL Database**
  - Stores blocks, passages, messages, archives
  - Indexed for fast retrieval (created_at, IDs, archive_id)
  - Cache layer for git-backed data

- **Secondary: Git Repository (Optional)**
  - Version-controlled blocks for agents with "git-memory-enabled" tag
  - Each block = markdown file with YAML frontmatter
  - Full commit history with author tracking
  - Enables undo/rollback capabilities
  - Source of truth when enabled (PostgreSQL is cache)

- **Tertiary: Vector Database**
  - Embeddings for all passages (native, pgvector, or external providers)
  - Supports semantic search in archival memory
  - Configurable embedding models per archive

## 4. Memory Retrieval/Recall Mechanisms

**Compilation into Context (Just-In-Time Construction):**

The system **assembles memory at request time** rather than storing pre-formed prompts:

1. **Core Memory Rendering** (into `<memory_blocks>` XML)
   - Fetches current block values from DB
   - Renders with metadata (char_limit, chars_used, descriptions)
   - Line-numbered variant for Anthropic models
   - Git-filesystem variant shows filesystem tree + bare file blocks

2. **External Memory Summary** (in `<memory_metadata>`)
   - Count of archival passages
   - List of archive tags (for semantic context)
   - Message counts (in-context vs. archival)
   - Token usage estimates

3. **Semantic Search** (for archival passages)
   - Queries embedded text via vector similarity
   - Filtered by archive and tags
   - Results included as `<directories>` in context

4. **Context Window Calculation**
   - Token counter estimates space available
   - Determines which recall messages fit
   - Triggers summarization if needed
   - Component extraction parses sections

## 5. Memory Update/Consolidation Strategies

**Active Memory Management:**

1. **Immediate Updates (Core Blocks)**
   - Agent calls `core_memory_append()` or `core_memory_replace()`
   - Updates persisted to DB
   - If git-enabled: commits to git repo first, then syncs to PostgreSQL

2. **Archival Writing**
   - `CreateArchivalMemory` creates new passages
   - Optional embeddings computed and stored
   - Tagged for later retrieval
   - Timestamped and metadata-enriched

3. **Conversation Summarization (Memory Consolidation)**
   - Triggered when message buffer exceeds limit
   - Configurable modes: static buffer, sliding window, or LLM-generated summary
   - Summarized content can be:
     - Appended to core memory blocks
     - Stored as archival passages
     - Or both for redundancy
   - Reduces context window pressure through consolidation

4. **Conversation Trimming**
   - Oldest messages evicted when context full
   - Partial eviction percentage configurable
   - Can trigger just-in-time summarization

## 6. Overall Architecture (Components & Layers)

**High-Level Component Stack:**

```
+----------------------------------------------------------+
|  Agent Loop (letta_agent_v3, voice_agent, etc.)          |
|  - Handles conversation steps & message I/O              |
+---------------------+------------------------------------+
                      |
    +----------------++--------------+
    |                 |              |
+---v---------+ +----v--------+ +--v-----------+
|  Managers   | |   Services  | |   Storage    |
+-------------+ +-------------+ +--------------+
| Agent       | | Block       | | PostgreSQL   |
| Message     | | Manager     | | Git Repo     |
| Passage     | |             | | Vector DB    |
| Archive     | | Archive     | |              |
|             | | Manager     | |              |
|             | |             | |              |
|             | | Conversation| |              |
|             | | Manager     | |              |
|             | |             | |              |
|             | | Memory Repo | |              |
|             | | (Git ORM)   | |              |
+-------------+ +-------------+ +--------------+
                      |
    +-----------------v------------------+
    |  Compilation & Indexing            |
    +------------------------------------+
    | Context Window Calculator          |
    | - Token counting                   |
    | - Component extraction             |
    | - System message assembly          |
    |                                    |
    | Summarizer Service                 |
    | - Statement compression            |
    | - LLM summarization                |
    | - Archive writing                  |
    +------------------------------------+
                      |
    +-----------------v------------------+
    |  Prompt Generator                  |
    |  - System message formatting       |
    |  - XML tag rendering               |
    |  - Memory compilation              |
    +------------------------------------+
                      |
    +-----------------v------------------+
    |  LLM (via openai client)           |
    +------------------------------------+
```

**Key Service Components:**

1. **BlockManager** - CRUD operations on memory blocks
2. **GitEnabledBlockManager** - Wraps BlockManager with git-backed versioning
3. **ArchiveManager** - Manages passage collections
4. **PassageManager** - Handles passage CRUD and semantic search
5. **ConversationManager** - Manages message threads and isolation
6. **MessageManager** - Stores and retrieves conversation messages
7. **ContextWindowCalculator** - Token counting and component extraction
8. **Summarizer** - Conversation compression and archival
9. **MemfsClient** - Git operations wrapper for memory repos

## 7. Novel/Unique Concepts

**1. Git-Backed Memory Filesystem**
   - Memory blocks map to filesystem structure (e.g., `system/human`, `custom/preferences`)
   - Full version control: undo/rollback, blame history, branching possible
   - Hybrid: git is source-of-truth, PostgreSQL is cache for performance
   - Renders as Unix-style filesystem tree in context window

**2. Layered Memory with Differential Retrieval**
   - **Core memory always in-context** (small, editable)
   - **Archival memory retrieved only on-demand** via semantic search (large, append-only)
   - **Recall memory filtered by recency** (sliding window with summarization)
   - **File blocks context-aware** (only open files included, with entry controls)

**3. "Memory Commits" for Mutations**
   - Block updates tracked as git commits with:
     - Author (agent/user/system)
     - Timestamp
     - File diffs (additions/deletions)
     - Commit message
   - Enables audit trail and rollback

**4. Conversation Isolation Blocks**
   - Conversations can have "isolated blocks" distinct from agent's core blocks
   - Allows per-conversation memory customization
   - Linked via `BlocksConversations` junction table

**5. Multi-Agent Archive Sharing**
   - Archives can be attached to multiple agents
   - Tagged passages discoverable across agent team
   - Shared institutional memory pattern

**6. Dynamic Context Assembly**
   - Memory never "pre-compiled"
   - Each prompt runtime assembles by:
     - Reading current block values
     - Calculating token budgets
     - Selecting archival passages
     - Filtering conversation history
   - Supports live external updates without agent restart

**7. Embedding-Based Semantic Organization**
   - Passages support dual discovery: tags + vector search
   - Configurable embedding models per archive
   - Handles padding/alignment for mixed embedding dimensions

## Summary Table

| Aspect | Approach |
|--------|----------|
| **Storage Model** | Multi-store (PostgreSQL + optional git + vector DB) |
| **Memory Types** | Core blocks, archival passages, message history, file chunks |
| **Retrieval** | Compilation-on-demand + semantic search + temporal filtering |
| **Update Pattern** | Direct edits + commits + summarization + archival |
| **Scalability** | Layered: small in-context, large archival, trimmed history |
| **Auditability** | Git commits + timestamps + author tracking |
| **Sharing** | Archives shared across agents; blocks per-agent or per-conversation |
| **Key Innovation** | Git-backed versioned blocks + dynamic context assembly |

This architecture enables agents to maintain **persistent, editable, versionable, searchable** memory while keeping **in-context window efficient** through smart filtering and consolidation.
