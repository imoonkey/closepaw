# Reference Memory Systems Analysis — Aligned Summary

Consolidated analysis of 9 reference memory systems evaluated for applicability to our on-device Android automation agent. Both Claude and Codex independently analyzed each system; this document synthesizes the consensus view.

---

## 1. Letta (formerly MemGPT)

**Core idea**: Stateful agents with explicit, editable memory, split into always-in-context "core memory" and tool-accessed "archival memory".

**Architecture**:
- **Core memory blocks**: Small, labeled, editable blocks always rendered into the system prompt. Agent mutates them via tool calls (`core_memory_append`, `core_memory_replace`).
- **Archival memory**: Large collection of text passages with embeddings, retrieved on-demand via semantic search.
- **Recall memory**: Sliding-window conversation history with LLM-driven summarization when context fills.
- **Storage**: PostgreSQL + optional git-backed versioning + vector DB for archival.

**Strengths**: High controllability and debuggability. Practical separation of always-on vs. on-demand memory. Memory editing is a first-class tool action, deterministically controlled.

**Tradeoffs**: Requires disciplined block schema design. Manual edits can introduce drift. More explicit memory operations increase agent policy complexity.

**Relevance to Android Agent**: **HIGH**. The core/archival split maps directly to our existing scratchpad (working memory) + a new long-term store. The key takeaway is: *small, editable working memory always in context + large archival store accessed via tools*. This is the architectural pattern we should follow.

---

## 2. Mem0

**Core idea**: Fact-centric intelligence layer that automatically distills conversations into discrete, structured facts.

**Architecture**:
- **Fact extraction**: LLM analyzes conversations and extracts discrete facts as JSON.
- **Four operations**: ADD / UPDATE / DELETE / NONE — LLM decides which operation to apply by comparing new facts against existing memory via vector similarity.
- **Dual-mode**: Separate extraction for user facts vs. agent observations.
- **Storage**: Vector store (20+ backends) + optional Neo4j graph + SQLite audit trail.
- **Scoping**: All operations scoped by user_id / agent_id / run_id.

**Strengths**: Modular adapter architecture. Good deduplication/update logic. Clean ADD/UPDATE/DELETE/NONE model prevents memory bloat. Dual perspective (user vs. agent facts) is elegant.

**Tradeoffs**: Orchestration quality depends on extraction prompts. Multi-backend deployments add ops burden. Generic platform design may need domain tuning.

**Relevance to Android Agent**: **HIGH (selective)**. The fact extraction model (ADD/UPDATE/DELETE/NONE) is directly applicable for maintaining a clean memory store. The dual perspective maps to "user preferences" vs. "app knowledge". However, the vector-heavy storage and graph DB are too heavy for on-device use. We take the consolidation logic, not the infrastructure.

---

## 3. MemOS

**Core idea**: Memory as an operating system — multiple isolated memory planes coordinated through a scheduler and unified search surface.

**Architecture**:
- **MemCubes**: Composable, isolated memory containers per user/context.
- **Four memory planes**: Textual (episodic/semantic), Activation (KV-cache), Parametric (model weights), Preference (user profiles).
- **MemScheduler**: Async Redis-backed queue for decoupled memory writes.
- **Storage**: Polyglot — Qdrant/Milvus for vectors, Neo4j for graphs, hierarchical storage for plaintext.

**Strengths**: Clean separation between memory semantics and storage mechanism. Scheduler-first design is practical for production. Multi-plane concept is intellectually sound.

**Tradeoffs**: Highest operational complexity of all systems reviewed. Cross-plane ranking/fusion can be weak. Too much configuration surface.

**Relevance to Android Agent**: **LOW**. The multi-plane concept is interesting but massively over-engineered for a single-user on-device agent. Parametric and activation memory are irrelevant without model fine-tuning or KV-cache access. The one borrowable insight is the general principle of *separating different memory types with different access patterns* — but we don't need MemOS's infrastructure to achieve that.

---

## 4. memU

**Core idea**: Memory as a hierarchical filesystem with proactive intelligence — agents anticipate information needs and self-organize knowledge.

**Architecture**:
- **Six memory types**: Profile, Event, Knowledge, Behavior, Skill, Tool.
- **Three storage layers**: Resource (raw data) → Memory Item (extracted facts with embeddings) → Memory Category (auto-organized topics).
- **Dual retrieval**: RAG path (fast, embedding-based) vs. LLM path (deep, reasoning-based). Runtime selects strategy.
- **Pipeline architecture**: Staged workflows with validation gates (memorize: ingest → preprocess → extract → dedup → categorize → persist).
- **Scope-first**: User identity fields embedded in every record.

**Strengths**: Well-thought taxonomy. Workflow-driven design is maintainable and testable. Proactive surfacing aligns with assistant experiences. Backend-aware vector strategy transparently falls back.

**Tradeoffs**: Pipeline depth can add latency. More stages mean more failure points. Proactive surfacing needs careful policy controls.

**Relevance to Android Agent**: **HIGH (taxonomy + scope)**. The 6-category taxonomy is the richest we reviewed. For our agent, a subset is directly relevant: **app_knowledge** (maps to Knowledge/Tool), **user_preference** (maps to Profile/Behavior), **task_pattern** (maps to Skill/Event). The scope-first approach and pipeline design are good patterns.

---

## 5. OpenClaw

**Core idea**: Persistent workspace files as memory — plain Markdown files are the source of truth, with a derived SQLite index for search.

**Architecture**:
- **Three memory tiers**: Daily logs (`memory/YYYY-MM-DD.md`), curated long-term (`MEMORY.md`), optional session transcripts.
- **Hybrid retrieval**: BM25 (30%) + vector (70%) weighted merge over chunked Markdown.
- **Temporal decay**: Exponential decay with 30-day half-life. Evergreen files never decay.
- **Automatic memory flush**: Before context compaction, agent is reminded to persist important facts.
- **Storage**: Markdown files (source of truth) + SQLite (derived index with FTS5 + embedding cache).

**Strengths**: Pragmatic portability. Plugin architecture supports experimentation. Tight coupling with compaction/session lifecycle. Human-readable and git-trackable. Offline-first.

**Tradeoffs**: Markdown needs disciplined curation to avoid stale/noisy knowledge. Multiple providers require clear precedence rules.

**Relevance to Android Agent**: **HIGH (retrieval + lifecycle)**. BM25 + FTS is proven and lightweight. Temporal decay is directly applicable. The "automatic memory flush before compaction" pattern maps to our session-end extraction concept. However, Markdown-as-storage is less suitable than SQLite for an Android app — we already have Room. We take the hybrid retrieval logic (BM25/FTS + scoring), temporal decay, and the pre-compaction write trigger.

---

## 6. OpenViking

**Core idea**: Memory as a filesystem with hierarchical directories, URI-based addressing, and progressive content loading (L0/L1/L2).

**Architecture**:
- **Three context types**: Resource (external knowledge), Memory (agent-learned, 6 subcategories), Skill (callable tools).
- **L0/L1/L2 progressive loading**: L0 (~100 tokens, abstract/title), L1 (~2k tokens, overview), L2 (full content on demand).
- **VikingFS**: Virtual filesystem with URI abstraction (`viking://resources/...`).
- **Dual-layer storage**: AGFS (content) + Vector Index (semantic search).
- **Hierarchical retrieval**: Directory recursive search with score propagation.
- **Session-based consolidation**: Message compression + LLM extraction of 6 memory categories + deduplication.

**Strengths**: Progressive loading is the key innovation — controls token budget precisely. Observable retrieval trajectories. Unified filesystem paradigm for all memory types. Good for enterprise/team use.

**Tradeoffs**: Filesystem metaphor can be heavy for high-churn conversational memory. Recursive retrieval needs pruning to avoid context bloat. Strong tenant modeling adds complexity for single-user use.

**Relevance to Android Agent**: **HIGH (progressive loading)**. L0/L1/L2 is the most important retrieval pattern for mobile — we must control token budget precisely. L0 (key/title, ~10 tokens) injected proactively, L1 (summary, ~50 tokens) via tool, L2 (full content) only on explicit request. We borrow this progressive loading concept directly. The 6-category taxonomy also influenced our category design.

---

## 7. PageIndex

**Core idea**: Vectorless retrieval — builds structural tree index over documents and uses LLM reasoning to navigate the tree, not embedding similarity.

**Architecture**:
- **Structural tree index**: Documents parsed into hierarchical nodes (sections/subsections) with page boundaries.
- **LLM tree search**: LLM reasons through hierarchy to find relevant nodes.
- **Three-mode flexibility**: Adapts to documents with explicit TOC, implicit TOC, or no TOC.
- **No vectors needed**: Pure LLM reasoning over structure.

**Strengths**: Strong when queries require understanding document layout or long-form dependencies. Transparent intermediate reasoning steps. 98.7% accuracy on FinanceBench.

**Tradeoffs**: LLM-costly for both indexing and search. Quality depends on tree construction quality. Not suitable for ultra-low-latency memory lookup.

**Relevance to Android Agent**: **LOW**. This is a document retrieval system, not an agent memory system. The LLM-based tree search burns tokens just to find memories — prohibitive on mobile where every LLM call has latency and cost. The hierarchical concept is interesting but better served by category/tag filtering (SQL WHERE clause) than by LLM tree navigation.

---

## 8. Second-Me

**Core idea**: Memory as identity preservation — creating a "digital twin" through hierarchical abstraction from raw data to semantic profiles to fine-tuned models.

**Architecture**:
- **L0 (Raw)**: Documents and chunks with embeddings.
- **L1 (Semantic)**: "Shades" (interest domains with timelines) + Bio (global identity profile).
- **L2 (Model)**: Fine-tuned LLM via DPO to match user's communication style.
- **Storage**: File system + SQLAlchemy ORM + ChromaDB for embeddings.
- **Versioning**: Track memory evolution over time.

**Strengths**: Explicit abstraction pipeline useful for personalization. Good balance of granular evidence (L0) and stable profile (L1). Long-horizon identity continuity.

**Tradeoffs**: Pipeline quality sensitive to extraction and clustering quality. Personal-memory focus needs rework for other scenarios. Fine-tuning requires GPU infrastructure.

**Relevance to Android Agent**: **NOT RELEVANT**. The "digital twin" / identity modeling goal is orthogonal to task automation. Fine-tuning on device is not feasible. The persona/shade approach doesn't map to "navigate UI elements and complete tasks". No patterns borrowed.

---

## 9. Supermemory

**Core idea**: Living knowledge graph where memories are dynamically interconnected with explicit relationship types.

**Architecture**:
- **Two memory types**: Static (permanent facts) and Dynamic (contextual, time-sensitive).
- **Three relationship types**: Updates (versioning), Extends (enrichment), Derives (inference).
- **Storage**: HNSW vector index + graph storage for relationships.
- **Six-stage pipeline**: Queued → Extracting → Chunking → Embedding → Indexing → Done.
- **Retrieval**: Semantic search + graph relationship expansion + multi-factor ranking.
- **Multi-modal**: Unified pipeline for text, PDF, images, video, audio.

**Strengths**: Integration-friendly for apps using AI SDK frameworks. Hybrid retrieval over memory + content. Profile layer for personalization.

**Tradeoffs**: Middleware-driven automation can hide side effects. Connector-heavy system needs strong governance. SaaS-style abstraction needs adaptation for on-device/offline.

**Relevance to Android Agent**: **LOW**. Graph storage and HNSW vector index are too heavy for on-device. Multi-modal pipeline is out of scope. The one interesting concept is the **Updates/Extends relationship types** — but a simpler `supersedes_id` column achieves the "updates" case, and we don't need the full graph.

---

## Consensus: Top Influences for Android Agent

Both Claude and Codex converge on the same top pattern sources:

| Pattern | Primary Sources | Confidence |
|---------|----------------|------------|
| Working/archival memory split | Letta | Strong consensus |
| Fact extraction model (ADD/UPDATE/DELETE) | mem0 | Strong consensus |
| Progressive loading (L0/L1/L2) | OpenViking | Strong consensus |
| FTS + temporal decay retrieval | OpenClaw | Strong consensus |
| Memory category taxonomy | memU, OpenViking | Strong consensus |
| Explicit writes + lifecycle-triggered consolidation | Letta, OpenClaw, memU | Strong consensus |
| Minimal write guardrails (validate/sanitize/dedup) | mem0, memU | Consensus |
| Session-end memory flush | OpenClaw | Strong consensus |
| Scope-first design | memU, mem0 | Consensus |
| Room/SQLite storage (not vector DB) | OpenClaw pattern adapted | Strong consensus |

**Unanimously excluded**:
- Vector databases requiring on-device embedding models (MemOS, mem0, supermemory infrastructure)
- Graph databases (mem0 Neo4j, MemOS NebulaGraph, supermemory)
- LLM-based tree search for retrieval (PageIndex)
- Fine-tuning / parametric memory (Second-Me, MemOS)
- Multi-tenant / multi-user complexity (MemOS MemCubes, OpenViking tenant model)
- Multi-modal content pipelines (supermemory, memU media processing)
- Always-on async schedulers as a hard dependency (MemOS Redis scheduler)
