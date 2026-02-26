# Synthesis: Reference Memory Systems → Android Agent

This document maps learnings from 9 reference memory systems to the unique constraints and opportunities of an on-device Android automation agent.

---

## 1. Pattern Taxonomy Across Reference Systems

### 1.1 Memory Type Patterns

| Pattern | Systems | Description |
|---------|---------|-------------|
| **Fact/Preference storage** | mem0, supermemory, Second-Me | Extract discrete facts, user preferences, stable knowledge |
| **Episodic/Event logs** | mem0, memU, OpenClaw | Time-stamped narrative of what happened |
| **Procedural/Skill memory** | memU, OpenViking | How to perform specific tasks, learned workflows |
| **Working memory (in-context)** | Letta, OpenViking | Small, editable block always visible to the LLM |
| **Archival memory (out-of-context)** | Letta, OpenClaw, memU | Large store retrieved on-demand via tools |
| **Structural/Navigation memory** | PageIndex, OpenViking | Hierarchical index for efficient traversal |
| **Behavioral/Identity model** | Second-Me | Fine-tuned model capturing user's personality |
| **Relationship graph** | supermemory, mem0, MemOS | Memories connected by typed edges (updates/extends/relates) |

### 1.2 Storage Patterns

| Pattern | Systems | Fit for Android |
|---------|---------|-----------------|
| **Vector DB (Qdrant/Milvus/ChromaDB)** | MemOS, mem0, Second-Me, supermemory | Poor — heavy external deps, GPU expectations |
| **SQLite + embeddings** | OpenClaw, memU | Good — Android-native, lightweight |
| **Markdown/file-based** | OpenClaw, OpenViking, Letta | Moderate — simple but no built-in search |
| **Graph DB (Neo4j)** | mem0, MemOS | Poor — server-side, not mobile-friendly |
| **Key-value store** | (current scratchpad) | Good — already exists, fast, bounded |
| **Room/SQLite with FTS** | (none directly) | Excellent — Android-optimized, full-text search built in |

### 1.3 Retrieval Patterns

| Pattern | Systems | Fit for Android |
|---------|---------|-----------------|
| **Vector similarity search** | Most systems | Moderate — possible with on-device embeddings but costly |
| **BM25/FTS keyword search** | OpenClaw (hybrid) | Good — SQLite FTS5 is lightweight and fast |
| **Hybrid (vector + BM25)** | OpenClaw | Moderate — depends on embedding cost |
| **LLM reasoning over structure** | PageIndex | Poor for retrieval — burns LLM tokens just to find memories |
| **Direct key lookup** | Current scratchpad | Good — O(1), zero overhead |
| **Recency-based** | OpenClaw (temporal decay) | Good — simple timestamp math |
| **Tag/category filtering** | memU, OpenViking | Good — SQL WHERE clause, cheap |

### 1.4 Consolidation Patterns

| Pattern | Systems | Fit for Android |
|---------|---------|-----------------|
| **LLM-driven extraction** | mem0, MemOS, OpenViking | Moderate — requires extra LLM call, adds latency/cost |
| **Async background processing** | MemOS (Redis), OpenClaw (watchers) | Moderate — feasible with WorkManager but battery concern |
| **Session-end summary** | Letta, OpenClaw (pre-compaction) | Good — natural boundary, one-time cost |
| **Manual agent writes** | OpenClaw, Letta, current scratchpad | Good — already supported, zero infra |
| **Version tracking** | Letta (git-backed), MemOS | Low priority — adds complexity without clear mobile benefit |

---

## 2. What's Relevant vs. Irrelevant

### 2.1 Highly Relevant

**From mem0 — Fact-centric consolidation**
- The ADD/UPDATE/DELETE/NONE operation model is elegant for maintaining a clean fact store
- Dual perspective (user facts vs. agent observations) maps well to "user preferences" vs. "app knowledge"
- Key insight: *memories should be facts, not conversation snippets*

**From OpenClaw — Hybrid retrieval + temporal decay**
- BM25 + vector hybrid is pragmatic; BM25 alone may suffice for Android
- Temporal decay prevents stale knowledge from dominating
- "Automatic memory flush before compaction" pattern is directly applicable — the agent could persist important scratchpad entries to long-term memory before session history compression

**From Letta — Working memory / archival memory split**
- The core insight: small, always-visible working memory + large, tool-accessed archival memory
- This is *exactly* what the Android agent already has (scratchpad = working memory, but no archival memory yet)
- Extending the current design with a retrieval tool is the natural path

**From OpenViking — Progressive content loading (L0/L1/L2)**
- L0 (title/key, ~100 tokens), L1 (summary, ~2K tokens), L2 (full content)
- Critical for mobile: load L0 for listing, L1 for context, L2 only when explicitly requested
- Controls token budget precisely

**From memU — Memory categories**
- 6-category taxonomy (profile, event, knowledge, behavior, skill, tool) is well-thought
- For Android agent, a subset is relevant: **app_knowledge**, **user_preference**, **task_pattern**, **procedure**

### 2.2 Moderately Relevant

**From supermemory — Relationship tracking**
- "Updates/extends/derives" relationships between memories could track how app knowledge evolves
- But graph storage is heavy; a simpler `supersedes_id` column may suffice

**From MemOS — Async scheduling**
- MemScheduler concept (queue + async workers) could map to Android WorkManager
- But adds complexity; session-end consolidation may be sufficient initially

**From PageIndex — Hierarchical indexing**
- Tree-structured memory navigation is interesting for app hierarchy (App → Screen → Element)
- But LLM-based tree traversal burns tokens; a simpler tag/category filter is more practical

### 2.3 Not Relevant (for this agent)

**From Second-Me — Identity modeling / fine-tuning**
- Building a "digital twin" is orthogonal to task automation
- Fine-tuning on device is not feasible

**From MemOS — Parametric memory (model weight injection)**
- Requires model access/fine-tuning infrastructure
- Not applicable to API-based or local inference on mobile

**From MemOS — Multi-user isolation (MemCubes)**
- Single-user agent; no need for user isolation

**From supermemory — Multi-modal content pipeline**
- PDF/video/audio ingestion is out of scope; agent operates on screen state

---

## 3. Key Tensions

### 3.1 Token budget vs. memory richness
- Every memory injected into the prompt costs tokens
- Mobile LLM calls are latency-sensitive; larger prompts = slower
- **Resolution**: L0/L1/L2 progressive loading (inject keys/titles by default, full content only on demand)

### 3.2 On-device computation vs. semantic understanding
- Vector embeddings enable semantic search but require embedding model
- On-device embedding models exist (MiniLM, gte-small via ONNX) but add APK size and inference cost
- **Resolution**: Start with FTS5 keyword search + tag filtering; add embeddings later as opt-in

### 3.3 Write cost vs. memory quality
- LLM-driven fact extraction (mem0 style) produces high-quality memories but costs an extra LLM call
- Manual scratchpad writes are free but depend on the agent "choosing" to save
- **Resolution**: Hybrid — agent writes explicitly during session + lightweight extraction at session end

### 3.4 Staleness vs. persistence
- Long-lived memories can become incorrect (app layouts change, user preferences shift)
- **Resolution**: Temporal decay + confidence tracking + explicit update/delete operations

### 3.5 Simplicity vs. capability
- Reference systems are complex (graph DBs, vector stores, async workers, fine-tuning)
- Mobile agent needs to "just work" with minimal infrastructure
- **Resolution**: Start with SQLite (Room) + FTS5; every addition must justify its complexity

---

## 4. Comparison Matrix: Applicability to Android Agent

| Dimension | MemOS | mem0 | Letta | memU | supermemory | Second-Me | OpenViking | PageIndex | OpenClaw |
|-----------|-------|------|-------|------|-------------|-----------|------------|-----------|----------|
| **Memory taxonomy** | Low | **High** | Med | **High** | Med | Low | Med | Low | Med |
| **Storage approach** | Low | Med | Med | **High** | Low | Low | Med | Low | **High** |
| **Retrieval** | Low | Med | Med | Med | Low | Low | Med | Low | **High** |
| **Consolidation** | Med | **High** | **High** | Med | Med | Low | Med | Low | **High** |
| **Working memory model** | Low | Low | **High** | Med | Low | Low | **High** | Low | Med |
| **Progressive loading** | Low | Low | Low | Med | Low | Low | **High** | Med | Low |
| **Mobile feasibility** | Low | Med | Med | **High** | Low | Low | Med | Low | **High** |

**Top influences for this agent**: mem0 (fact model), Letta (working/archival split), OpenClaw (storage + retrieval), OpenViking (progressive loading), memU (taxonomy).
