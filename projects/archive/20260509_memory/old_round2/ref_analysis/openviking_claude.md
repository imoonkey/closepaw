# OpenViking Memory System Architecture

## 1. Core Philosophy/Metaphor

OpenViking embraces a **filesystem paradigm** as its core metaphor for memory. Rather than treating memory as flat vector embeddings (traditional RAG), they conceptualize it as a hierarchical directory structure with unique URIs (like `viking://resources/docs/api`). This allows agents to "navigate" and "browse" memory deterministically through standard filesystem operations (ls, find, read, mkdir) instead of vague semantic matching.

**Key insight**: Agents can find information like developers work with code -- precise, traceable, and hierarchical.

## 2. Memory Types (Three Categories)

OpenViking abstracts context into three fundamental types:

- **Resource**: User-provided external knowledge (API docs, code repos, manuals). Long-term, relatively static, user-driven.
- **Memory**: Agent-learned knowledge about users and the world. Long-term, dynamically updated, agent-driven. Split into 6 subcategories:
  - User memories: profile, preferences, entities, events
  - Agent memories: cases (problem+solution), patterns (reusable patterns)
- **Skill**: Callable capabilities/tools. Long-term, static, agent-invoked.

All three types are seamlessly unified under the same storage and retrieval system.

## 3. Storage Architecture

**Dual-layer storage** separating concerns:

- **AGFS (Content Storage Layer)**: Stores actual file content, L0/L1/L2 full content, multimedia, and relations. Provides POSIX-style file operations with multiple backend support (local filesystem, S3, memory).
- **Vector Index (Semantic Index Layer)**: Stores only URIs, vectors, and metadata -- not file content. Separate from content storage to optimize memory and enable independent scaling.

This dual-layer design ensures a single source of truth while enabling efficient semantic search.

## 4. Memory Storage & Organization

Memory is stored in a **unified virtual filesystem** called **VikingFS** with URI abstraction:

```
viking://
|- resources/          # External knowledge
|- user/               # User-level data
|  +- memories/
|      |- preferences/
|      |- entities/
|      +- events/
|- agent/              # Agent-level data
|  |- memories/
|  |   |- cases/
|  |   +- patterns/
|  +- skills/
+- session/            # Current conversation
```

Each directory maintains a unified structure with L0/L1/L2 content layers (see #5 below).

## 5. Memory Retrieval/Recall

**Two-stage retrieval process**:

1. **Intent Analysis** (for complex queries): LLM analyzes query context and generates 0-5 typed queries with metadata (query string, context type, intent, priority).

2. **Hierarchical Retrieval** (directory recursive search):
   - Vector search locates initial high-score directories
   - Recursively refines search within subdirectories using priority queue
   - Uses score propagation (50% embedding score + 50% parent directory score)
   - Convergence detection stops recursion when top results stabilize
   - Returns contexts ranked by relevance

3. **Reranking** (optional): AI-powered model reranking refines top candidates.

**Key distinction**: Simple queries use `find()` (direct semantic search), while complex tasks use `search()` (with intent analysis and hierarchical retrieval).

## 6. Memory Update/Consolidation

**Session-based automatic consolidation** when `commit()` is called:

1. **Message Compression**: Keeps recent N rounds, archives older messages into versioned snapshots
2. **Session Summarization**: LLM generates structured summaries (overview, key concepts, pending tasks) for archived history
3. **Memory Extraction**: LLM automatically extracts 6 categories of memories from conversation
4. **Deduplication**: Vector pre-filtering + LLM dedup logic decides whether to skip, create, merge, or delete extracted memories
5. **Self-Iteration**: Updated memories are written back to storage and vectorized for future retrieval

This creates a **self-evolving memory system** where agents get "smarter with use" through automatic experience consolidation.

## 7. Overall Architecture & Components

**Layered system architecture**:

```
Client Layer
    |
Service Layer (FSService, SearchService, SessionService, ResourceService, etc.)
    |
Core Modules:
|- Retrieve (Intent Analysis, Hierarchical Retrieval, Rerank)
|- Session (Message recording, compression, memory extraction)
|- Parse (Document parsing, tree building, semantic generation)
|- Compressor (6-category memory extraction, LLM dedup)
    |
Storage Layer:
|- VikingFS (URI abstraction, relation management)
|- AGFS (Content storage backend)
+- Vector Index (Semantic search index)
```

**Data flows**:
- **Add**: Input -> Parser -> TreeBuilder -> AGFS -> SemanticQueue -> Vector Index
- **Search**: Query -> Intent Analysis -> Hierarchical Retrieval -> Rerank -> Results
- **Commit**: Messages -> Compress -> Archive -> Memory Extraction -> Storage

## 8. Novel/Unique Concepts

1. **Three-Layer Information Model (L0/L1/L2)**:
   - **L0 (Abstract)**: ~100 tokens for quick filtering
   - **L1 (Overview)**: ~2k tokens for understanding scope and navigation
   - **L2 (Detail)**: Full content loaded on-demand

   This enables progressive context loading, dramatically reducing token consumption.

2. **Directory Recursive Retrieval**: Unlike flat vector DBs, OpenViking understands context hierarchically -- combining directory positioning with semantic search to achieve both semantic relevance and global context awareness.

3. **Visualized Retrieval Trajectory**: Full preservation of the "browsing path" (which directories were traversed, which files matched) allows observable, debuggable retrieval for understanding why specific results were returned.

4. **Automatic Context Self-Iteration**: Built-in loop that extracts long-term memories from sessions and consolidates them back into the memory system, enabling genuine learning without explicit programming.

5. **Unified Filesystem Paradigm**: All memory types (resources, memories, skills) unified under a single URI-based filesystem model, replacing fragmented vector storage with deterministic, traceable operations.

6. **6-Category Memory Taxonomy**: Structured extraction distinguishes between user profiles/preferences/entities/events and agent cases/patterns, with smart dedup logic using both vector similarity and LLM reasoning.

---

This architecture treats agent memory not as a retrieval problem but as a **filesystem management problem**, fundamentally shifting how context is organized, navigated, and evolved.
