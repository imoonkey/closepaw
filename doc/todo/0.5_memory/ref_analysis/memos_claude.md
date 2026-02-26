# MemOS: Memory Operating System - Architecture Analysis

## 1. Core Philosophy & Metaphor

MemOS explicitly models memory as an **Operating System** for AI agents. Instead of treating memory as a simple embedding store, it views memory management as a systems-level abstraction with:
- Multiple isolated contexts (MemCubes)
- Different memory types coexisting
- Lifecycle management and scheduling
- Multi-user access control
- Resource isolation and sharing

The core component is **MOSCore** (Memory Operating System Core), which orchestrates memory cubes and manages access policies, similar to how an OS manages processes.

## 2. Memory Type System

MemOS defines **four distinct memory types**, operating at different cognitive levels:

- **Textual Memory** (episodic/semantic): Conversation history, facts, documents. Stored as vectors with graph relationships. Supports full lifecycle: extraction -> storage -> search -> feedback.

- **Activation Memory** (working memory): Transformer KV-caches for efficient LLM inference. Stores computed attention patterns from previous context to accelerate generation of new tokens.

- **Parametric Memory** (implicit knowledge): Model weight updates and internal representations. Used for fine-tuning and domain-specific optimization.

- **Preference Memory** (episodic + user profiles): User preferences, personalization signals, interaction patterns extracted from conversations.

Each memory type is independently configurable and can be enabled/disabled per MemCube.

## 3. Storage Architecture

MemOS uses **polyglot persistence** -- different backends optimized for different access patterns:

**Vector Databases:**
- Qdrant, Milvus
- Store dense embeddings with payload filtering
- Optimized for semantic similarity search
- Support filtering by metadata (user_id, session_id, source)

**Graph Databases:**
- Neo4j, NebulaGraph, PolarDB, Postgres
- Store semantic relationships between memories
- Enable: deduplication, conflict detection, relationship traversal
- Support complex memory organization and cross-linking

**Hierarchical Text Storage:**
- Tree-based indexing for plaintext memory
- Session-level isolation
- Efficient range queries on temporal data

Data flows bidirectionally: vectors enable fast retrieval; graphs enable deep understanding of relationships.

## 4. Memory Retrieval & Recall

MemOS implements **multi-modal retrieval**:

- **Semantic Search**: Query -> embedder -> vector DB search -> top-k ranked results
- **Graph Traversal**: Entity relationships, temporal chains, user context
- **Tree Navigation**: Hierarchical plaintext search with contextual understanding
- **Priority Weighting**: Session-based or explicit search priorities
- **Filtering**: By session, user, timestamp, or custom metadata
- **Deduplication & Conflict Detection**: Graph DB identifies contradictions before retrieval

The retrieval process also captures **context** (chat history, session info) and passes it to the search engine for improved relevance ranking.

## 5. Memory Update & Consolidation: The MemScheduler

Rather than synchronous updates, MemOS uses **asynchronous consolidation** via the **MemScheduler**:

- **Event-Driven Tasks**: Messages submitted to scheduler with labels (ADD, QUERY, MEM_READ, ANSWER, PREFERENCE)
- **Queue Management**: Redis Streams for durability; supports task priority and auto-recovery
- **Two-Mode Processing**:
  - **Sync**: Immediate processing (good for APIs)
  - **Async**: Queued processing (good for batch ingestion)

- **MemReader Integration**: LLM-based extraction happens asynchronously
  - **Fast Mode**: Quick memory extraction for async mode
  - **Fine Mode**: Deep semantic understanding for immediate/permanent memories

- **Intelligent Consolidation**:
  - Memory conflict resolution (contradictions detected via graph DB)
  - Deduplication (preventing duplicate storage)
  - Extraction of preferences from conversations
  - Version tracking for memory updates (archived versions preserved)

## 6. Overall Architecture: Layered Model

```
+------------------------------------------+
|  MOSCore: Memory Operating System Layer  |  <- Multi-user, multi-cube orchestration
+------------------------------------------+
|       MemCube: Memory Container          |  <- Composable memory units
|  (text_mem, activation_mem, para_mem,    |
|   pref_mem working together)             |
+------------------------------------------+
|  Memory Backends:                        |
|  - Vector DB (Qdrant, Milvus)            |  <- Fast semantic search
|  - Graph DB (Neo4j, etc.)                |  <- Relationship & dedup
|  - Tree Storage (plaintext)              |  <- Hierarchical indexing
+------------------------------------------+
|  Processing Layers:                      |
|  - MemReader (extraction + inference)    |  <- LLM-based
|  - MemScheduler (async consolidation)    |  <- Redis Streams
|  - MemFeedback (correction interface)    |  <- Natural language feedback
+------------------------------------------+
|  Cross-Cutting:                          |
|  - Embedders (multiple backends)         |  <- Text -> vectors
|  - Rerankers (result refinement)         |  <- Top-k optimization
|  - LLMs (inference + extraction)         |  <- GPT-4, local models
+------------------------------------------+
```

**Data Flow**:
1. User input -> MOSCore.add() -> MemScheduler (queues message)
2. Async: MemReader extracts memories -> Graph DB checks conflicts -> Vector DB stores
3. On query: MOSCore.search() -> parallel search across text/pref -> reranking -> return

## 7. Novel Concepts & Key Innovations

**MemCube (Memory Containers)**
- Composable, isolated memory units per user/context
- Can be selectively shared across users
- Each cube independently configurable
- Supports multi-cube queries (search across cubes accessible to user)

**MemScheduler (Intelligent Async Processing)**
- Not just queue-and-fire; handles:
  - Task prioritization and isolation
  - Auto-recovery from failures
  - Quota-based rate limiting
- Enables production-scale handling of high concurrency

**Memory Feedback Loop**
- Natural language feedback to refine memories
- Supports correction, supplementation, deletion
- Tracked as memory updates with version history
- Enables human-in-the-loop memory refinement

**Provenance Tracking (SourceMessage)**
- Every memory item tracks its origin:
  - Chat turn, document, URL, file
  - Preserves locators (message_id, doc_path, timestamp)
  - Enables auditability, reproducibility, rollback

**Multi-Modal Memory**
- Images/charts embedded alongside text
- Tool traces (API calls, function results)
- Skill memory for agent action history
- All indexed and searchable together

**Memory Versioning & Archiving**
- When memories are updated/deleted, history preserved
- Conflict resolution creates archived versions
- Enables rollback and audit trails

**Unified API**
- Single interface: add, search, update, delete (CRUD)
- Works across all memory types transparently
- Abstraction hides backend complexity

## Summary

MemOS designs memory as a **distributed, multi-typed system** where:
- **Philosophy**: OS-inspired architecture for scalability and isolation
- **Structure**: Distinct memory types (textual, activation, parametric, preference) coexist
- **Storage**: Polyglot (vectors + graphs + hierarchical) for different access patterns
- **Retrieval**: Multi-modal (semantic + graph + tree search)
- **Consolidation**: Asynchronous, LLM-driven, with feedback loops
- **Scale**: Multi-user, multi-cube, production-grade scheduling

The key innovation is treating memory as a **first-class OS resource** rather than an afterthought, enabling agents to have persistent, queryable, correctable, and auditable memory systems at scale.
