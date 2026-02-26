# memU: High-Level Architectural Analysis

## 1. Core Philosophy/Metaphor

**"Memory as File System"** -- This is memU's central design metaphor:
- Memory is structured, hierarchical, and instantly accessible (like directories/files)
- Navigation moves from broad categories to specific facts (drill-down access)
- Resources can be "mounted" (external conversations, documents, images)
- Cross-references work like symlinks, creating a knowledge graph
- Memories are persistent and portable (can be exported/backed up)

The philosophy enables **proactive intelligence**: treating memory like an organized filesystem makes it natural for agents to anticipate information needs and self-organize knowledge autonomously.

## 2. Memory Types Defined

memU defines **six core memory types**:

| Type | Purpose |
|------|---------|
| **Profile** | User preferences, communication style, goals, interests |
| **Event** | Past interactions, decisions, outcomes, contextual moments |
| **Knowledge** | Learned facts, domain expertise, skills, techniques |
| **Behavior** | Patterns in user actions, habits, response tendencies |
| **Skill** | Procedures, strategies, tools usage patterns, capabilities |
| **Tool** | Tool invocations, success/failure patterns, usage hints |

Each memory type is extracted autonomously during ingestion with LLM-driven analysis.

## 3. Memory Storage Model

Three-layer hierarchical design matching the file system metaphor:

```
Resource Layer (Root)
|- Ingested raw data (conversations, documents, images, video, audio)
|- Entry point for all memory
+- Tracks source and modality

Memory Item Layer (Files)
|- Atomic extracted memories: facts, preferences, skills, behaviors
|- Includes embeddings for semantic search
|- Extra fields store: content hash, reinforcement counts, tool metadata
+- Links to parent resource via resource_id

Memory Category Layer (Folders)
|- Auto-organized topic groupings
|- Category name normalized/deduplicated
|- Includes category-level embeddings and descriptions
+- Bootstrap on-demand with lazy initialization

CategoryItem Relations Layer (Edges)
+- Tracks item-to-category membership graph
```

**Fourth Layer: Scope/User Isolation**
- User scope embedded as first-class fields across all entities
- Supports multi-user, multi-agent, multi-session patterns
- Validated at API boundaries

## 4. Memory Retrieval/Recall

Two retrieval strategies with runtime selection:

**RAG Path (Fast, Embedding-Based)**
- Millisecond-scale context assembly
- Uses vector similarity for recall ranking
- Optional salience ranking (reinforcement/recency-aware)
- Suitable for continuous background monitoring
- Cost: embedding only (no LLM calls)

**LLM Path (Deep, Reasoning-Based)**
- Second-scale complex anticipation
- LLM-driven ranking and context selection
- Infers user intent before asked (proactive)
- Evolves queries based on gathered context
- Can terminate early when sufficient context found
- Cost: LLM inference

Both follow same pipeline stages:
1. Route intention / optional query rewrite
2. Category recall (retrieve topic areas)
3. Optional sufficiency check
4. Item recall (retrieve specific facts)
5. Optional sufficiency check
6. Resource recall (trace to sources)
7. Response building

## 5. Memory Update/Consolidation

**Continuous Learn Pipeline** (`memorize` workflow):

```
Input -> Ingest -> Preprocess -> Extract -> Dedupe/Merge -> Categorize -> Persist -> Return
```

- **Ingest**: Fetch resource (local/remote) via LocalFS abstraction
- **Preprocess**: Modality-specific handling (conversation text parsing, audio transcription, video frame extraction, image analysis)
- **Extract**: LLM-driven extraction into typed memory items per configured memory types
- **Dedupe/Merge**: Placeholder stage for consolidation (currently pass-through)
- **Categorize**: Assign items to auto-organized categories, generate embeddings, persist relations
- **Persist**: Update category summaries, optionally cache item references
- **Return**: Immediate feedback (zero-delay processing)

**Key behaviors**:
- No batch processing -- memories available immediately upon extraction
- Automatic deduplication via content hashing
- Reinforcement tracking (count + recency) for frequently accessed memories
- Cross-reference detection between related memories

## 6. Overall Architecture/Components

**Layered Service Architecture**:

```
+-----------------------------------------+
|   MemoryService (Main Entry Point)      |
|  (Composition Root, Mixin-based)        |
+-----------------------------------------+
| MemorizeMixin | RetrieveMixin | CRUDMixin
+-----------------------------------------+
|  Workflow Engine                        |
+-----------------------------------------+
|  Pipeline Manager (orchestration)       |
|  Step-based DAG validation              |
+-----------------------------------------+
|  LLM Client Layer                       |
|  (Multi-profile, pluggable backends)    |
+-----------------------------------------+
|  Database Abstraction                   |
|  (Repository pattern)                   |
+-----------------------------------------+
|  Backend Implementations                |
|  - In-Memory  - SQLite  - PostgreSQL    |
+-----------------------------------------+
```

**Core Components**:

1. **PipelineManager** - Workflow DAG orchestration
   - Validates step dependencies (requires/produces state keys)
   - Tracks capability requirements and LLM profiles
   - Supports immutable pipeline revisions
   - Runtime mutation: insert/replace/remove steps

2. **WorkflowStep** - Individual operation unit
   - Declares required input state, produced output state
   - Declares capabilities needed (llm, vector, db, io, vision)
   - Async handler functions
   - Per-step configuration (profile routing, etc.)

3. **Workflow Interceptors** - Observability hooks
   - before/after/on_error around each step
   - Enables instrumentation, debugging, custom control flow

4. **LLM Client Layer** - Multi-backend support
   - Default Profile: chat/summarize/vision/embeddings/transcription
   - Embedding Profile: derived or custom
   - Backends: OpenAI SDK, HTTP-based (Doubao, Grok, OpenRouter), LazyLLM
   - Per-step profile routing via workflow config

5. **Database Abstraction** - Storage backends
   - InMemory: Zero-setup development, brute-force vector search
   - SQLite: Portable persistence, embeddings as JSON
   - PostgreSQL: Production-grade, pgvector support
   - All share same repository interface

6. **Blob Storage** - Resource federation
   - LocalFS abstraction for conversations, documents, images
   - Extensible for remote/cloud resources

## 7. Unique/Novel Concepts

**24/7 Proactive Agent Foundation**:
- System designed for agents that never sleep, continuously observe, and anticipate need
- Automatic context injection and pre-fetching for "warm starts"
- Token cost reduction through strategic memory caching vs. raw context window extension

**User Scope as First-Class Pattern** (ADR-003):
- Scope fields (user_id, agent_id, session_id) embedded in every record
- Eliminates ad-hoc filtering: filter at query time via typed `where` clauses
- Enables multi-tenant patterns without separate storage

**Pluggable Workflow Engine** (ADR-001):
- Uniform execution model for all operations (memorize, retrieve, CRUD)
- Recipe-like pipeline definition: order-independent step validation
- Runtime customization: mutate pipelines for A/B testing, feature flags
- Explicit step contracts prevent silent failures

**Backend-Aware Vector Strategy** (ADR-002):
- Vector search adapts to storage tier: brute-force portable, pgvector scalable
- Salience ranking applied locally regardless of backend
- Transparent fallback when native vector index unavailable

**Dual-Mode Memory Lifecycle**:
- Each memory serves **reactive queries** (direct lookup) AND **proactive context** (anticipatory loading)
- Resource layer enables background monitoring without memory extraction
- Category summaries learn and evolve as items accumulate

**Tool Memory Specialization**:
- Distinct memory type for tool/action patterns: tracks success rates, invoke hints, cost metrics
- Enables agents to self-improve tool usage strategy without explicit training

**Structured Extraction at Scale**:
- Per-memory-type LLM prompts with custom templates
- Automatic markdown/JSON parsing
- Deduplication and reinforcement tracking built-in

## Summary

memU treats **long-term agent memory as a constantly-growing, self-organizing knowledge base** structured like a filesystem. It delivers:

- **Hierarchical access**: Navigate from broad categories (topics) to atomic facts (items) tracing back to sources
- **Zero-latency updates**: Memory extracted and queryable instantly via LLM-driven ingestion
- **Dual-speed retrieval**: Fast similarity-based recall (RAG) or deep reasoning-based anticipation (LLM)
- **Production flexibility**: In-memory prototyping to PostgreSQL+pgvector at scale
- **Autonomous organization**: Automatic categorization, deduplication, and cross-referencing
- **Cost efficiency**: Reduce LLM token consumption by caching insights and avoiding redundant context

The architecture achieves this through **loose coupling** (repositories, workflow steps, pluggable backends) and **declarative composition** (DAG-based pipelines, scope merging), making it a practical platform for agents that must operate continuously and cost-efficiently.
