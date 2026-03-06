# Supermemory Memory System: High-Level Architecture

## 1. Core Philosophy & Metaphor

**Living Knowledge Graph** -- Supermemory fundamentally differs from traditional document storage by constructing a *dynamically interconnected knowledge graph* rather than maintaining static files. Content doesn't just sit in folders; it becomes part of an evolving network where relationships and meaning automatically develop over time.

The system treats memory as *living*, not static -- it grows, adapts, and builds context as new information arrives.

## 2. Memory Types

Supermemory defines two primary memory categories:

- **Static Memories** (`isStatic: true`): Permanent facts that don't change (name, profession, birthday, core preferences). High priority in retrieval.
- **Dynamic Memories** (`isStatic: false`): Contextual, episodic information (recent conversations, activities, current projects). Time-sensitive and mutable.

Both types exist within a **versioning system** via "Updates" relationships, creating memory chains that preserve history.

## 3. Memory Storage

The system uses a **dual-layer storage** approach:

- **Vector Storage (HNSW)**: Uses the Hierarchical Navigable Small World algorithm for embedding vectors (~1536 dimensions), enabling O(log n) semantic search with sub-millisecond lookups
- **Graph Storage**: Maintains explicit relationships between memories, supporting three relationship types (see below)
- **Container Tags**: Isolation mechanism that creates separate "spaces" for different users/organizations, enforcing privacy boundaries and enabling multi-tenancy

## 4. Memory Retrieval/Recall

Supermemory uses **semantic + structural search**:

1. **Query Embedding**: Convert user query to vector
2. **Similarity Calculation**: Compute cosine similarity against all memory vectors
3. **Threshold Filtering**: Apply relevance cutoff (typically 0.3-0.5)
4. **Relationship Expansion**: Follow graph edges (updates, extends, derives) from matched memories
5. **Multi-factor Ranking**: Score results by similarity, recency, static vs. dynamic priority, relationship strength, and metadata

This combines pure semantic search with graph-aware context expansion.

## 5. Memory Update & Consolidation

Three explicit relationship types manage evolution:

- **Updates**: New information supersedes old knowledge (tracks version chains like "prefers React 17" -> "prefers React 18")
- **Extends**: Enriching information adds context without replacing ("User likes TypeScript" <- "User completed advanced TypeScript course")
- **Derives**: Inferred connections from pattern analysis ("reads ML papers daily" + "asks about neural networks" -> "is an ML researcher")

These relationships create an evolving graph structure where derived insights automatically emerge from accumulated memories.

## 6. Overall Architecture

**Six-Stage Content Processing Pipeline:**

1. **Queued**: Content type detection, validation, queue assignment
2. **Extracting**: Format-specific extraction (PDF parsing, OCR, transcription, image understanding) into normalized text
3. **Chunking**: Semantic (not fixed-size) segmentation at conceptual boundaries with context overlap
4. **Embedding**: Vector generation (1536 dimensions) capturing semantic meaning for similarity matching
5. **Indexing**: Relationship discovery and establishment (updates/extends/derives)
6. **Done**: Fully searchable, integrated into knowledge graph

**Core Components:**

- **API Layer**: REST endpoints for documents, search, user profiles, connections
- **Extraction Engine**: Multi-format support (text, URLs, PDFs, images, videos, audio)
- **Semantic Chunker**: Intelligent boundary detection instead of fixed-size chunks
- **Embedding Engine**: Vector generation via state-of-the-art models
- **Graph Indexer**: Relationship detection and establishment
- **Retrieval Engine**: Combined semantic + graph search with ranking
- **Profile Generator**: Dynamic user profile assembly from static + dynamic memories

## 7. Unique/Novel Concepts

- **Living Knowledge Graph**: Memories aren't isolated; they form an evolving interconnected network with automatic relationship discovery
- **Three Explicit Relationship Types**: Updates (versioning), Extends (enrichment), Derives (inference) -- creates semantic structure beyond just embeddings
- **Dynamic User Profiles**: Automatically synthesized from accumulated memories, combining permanent facts with recent activity
- **Container Tag Isolation**: Clean multi-tenancy boundary enforcement without complex access control logic
- **Graph Evolution**: Knowledge graphs deliberately mature over time; new memories automatically connect to existing ones, creating emergent insights
- **Semantic + Structural Search**: Combines vector similarity with relationship traversal, not just RAG similarity matching
- **Multi-modal Content**: Single unified pipeline for text, PDFs, images, videos, URLs, audio transcriptions

## Data Flow Summary

```
Add Document -> Extract -> Chunk -> Embed -> Index Relationships -> Knowledge Graph
                                                                        |
Search Query -> Embed -> Vector Similarity -> Filter -> Expand via Graph -> Rank -> Result
```

The system processes content (1-2 minutes for 100-page PDFs) and enables sub-50ms p95 search latency at scale. It's designed as an alternative to traditional RAG (which lacks versioning, relationships, and profiles) and vector databases (which lack end-to-end pipelines and automatic structure).
