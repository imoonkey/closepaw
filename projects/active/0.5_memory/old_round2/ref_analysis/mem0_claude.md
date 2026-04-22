# Mem0 Memory System - High-Level Architecture

## 1. Core Philosophy/Metaphor

Mem0 adopts a **"Fact-Centric Intelligence Layer"** model where raw conversations are automatically distilled into structured, meaningful facts. Rather than storing complete conversation history, it extracts and manages discrete facts that can be updated, consolidated, and retrieved intelligently. The system treats memory as a **smart knowledge base that evolves with continuous interaction**, not a passive archive.

## 2. Memory Types (Three-Tier Model)

- **Semantic Memory**: Factual information about users/agents (preferences, personal details, professional info)
- **Episodic Memory**: Time-anchored events and experiences
- **Procedural Memory**: How-to knowledge, instructions, and learned procedures (typically for agents)

All are implemented similarly in the vector store but semantically distinguished through the extraction process.

## 3. Storage Architecture (Dual Backends)

**Primary: Vector Store** (supports 20+ backends: Pinecone, Qdrant, FAISS, Chroma, Weaviate, Milvus, etc.)
- Stores: Embedded facts as vectors + metadata (user_id, agent_id, run_id, timestamps, hash, etc.)
- Enables semantic similarity search
- Each memory item includes: `{id, embedding, payload: {data, metadata}}`

**Secondary: Graph Store** (Neo4j-based, optional)
- Stores: Entity relationships extracted from conversations
- Enables knowledge graph queries and entity tracking
- Used via `MemoryGraph` class with LLM-driven entity/relation extraction

**Tertiary: SQLite History Database**
- Tracks evolution of each memory: `{memory_id, old_memory, new_memory, event, timestamp}`
- Enables audit trails and rollback capabilities

## 4. Memory Retrieval/Recall

- **Semantic Search**: Query -> Embedding -> Vector similarity search in vector store -> Optional reranking
- **Graph Search**: Extract entities from query -> Neo4j relationship queries
- **Filtering**: Session-scoped (user_id, agent_id, run_id) + custom metadata filters
- **Reranking**: Optional second-pass ranking to improve relevance (separate reranker models)

## 5. Memory Update/Consolidation (Novel Approach)

The system uses **LLM-driven incremental consolidation**:

1. **Fact Extraction**: LLM analyzes new conversation -> extracts discrete facts (JSON format)
2. **Dual-Mode Extraction**:
   - **User Memory Mode**: Extracts only from user messages (what system knows about user)
   - **Agent Memory Mode**: Extracts only from assistant messages (characterization of agent itself)
3. **Four Operations** (LLM decides via function calling):
   - **ADD**: New fact -> create new memory entry
   - **UPDATE**: Fact conflicts with existing -> merge/supersede old memory
   - **DELETE**: Fact is obsolete -> mark as deleted (preserved in history)
   - **NONE**: No change needed
4. **Deduplication**: Maps facts to existing memories using vector similarity (limits to 5 closest matches)
5. **Session Tracking**: Can associate memories with specific users/agents/runs for multi-tenant scenarios

## 6. Overall Architecture (Components & Data Flow)

```
Input Conversation
        |
   [LLM Fact Extraction]
   |- Choose extraction mode (user vs agent)
   |- Generate facts as JSON
        |
  [Vector Similarity Search]
  |- Find related existing memories
  |- Map new facts -> old memories
        |
  [LLM Memory Consolidation]
  |- Compare new facts vs old memories
  |- Decide: ADD / UPDATE / DELETE / NONE
        |
  [Dual Storage]
  |- Vector Store: Insert/Update/Delete embeddings + metadata
  |- Graph Store: Extract entities & relationships (if enabled)
  |- History DB: Log all changes with timestamps
        |
  [Retrieval Path]
  |- User Query -> Embedding
  |- Search Vector Store (+ optional reranking)
  |- Search Graph Store (if enabled)
  +- Return scoped results (user_id, agent_id, run_id filters)
```

## 7. Unique/Novel Concepts

**a) LLM-Driven Consolidation**
- Memories aren't just stored; they're intelligently merged via LLM reasoning
- Avoids duplicate/redundant facts while capturing nuances
- Claims +26% accuracy over raw message storage (per research paper)

**b) Dual-Mode Extraction**
- Separate extraction logic for user vs agent perspectives
- Enables building distinct profiles of both humans and AI systems
- Smart detection: uses `agent_id` + `assistant` role messages to switch modes

**c) Multi-Level Scoping**
- Memories can be scoped to user, agent, run, or combinations
- Enables shared context in multi-agent systems
- Supports actor identification within sessions

**d) Pluggable Components**
- Factory pattern for LLM, embedder, vector store, graph store, reranker
- Supports both sync and async (AsyncMemory) interfaces
- Easily swap backends without changing core logic

**e) History Audit Trail**
- SQLite history table tracks every mutation (ADD/UPDATE/DELETE)
- Enables temporal queries and rollback capability
- Preserves change metadata (actor_id, role, timestamps)

**f) Optional Graph Layer**
- Neo4j integration for relationship extraction
- Enables structured knowledge representation
- Uses LLM to extract entities + relationships from text
- Separate retrieval path for knowledge graph queries

## 8. Performance Claims (Per Research)

- **91% Faster** than full-context retrieval (avoids reprocessing all history)
- **90% Lower Token Usage** (facts are much shorter than raw conversations)
- **+26% Accuracy** on LOCOMO benchmark vs OpenAI Memory

## Key Design Principles

- **Immutability**: Facts stored as immutable records; changes create new versions
- **Composition**: Small, reusable facts vs monolithic long-term memory
- **Context-Aware**: Extracts language-specific facts; respects multi-tenant scoping
- **Extensible**: Custom prompts for extraction/consolidation; pluggable backends
- **Telemetry-Ready**: Captures all operations for analytics and debugging

This architecture enables AI assistants to develop genuine personalization over time while keeping memory costs and complexity manageable at scale.
