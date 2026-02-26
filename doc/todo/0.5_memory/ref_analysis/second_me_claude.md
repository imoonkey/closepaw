# Second-Me Memory System Architecture

## 1. Core Philosophy/Metaphor

Second-Me designs memory around creating an **"AI Self"** -- a digital twin that captures, understands, and authentically represents a user's identity. The central metaphor is building a "**Second Me**" that preserves **YOU** across an AI network. Rather than treating memory as a retrieval problem, they treat it as identity preservation.

## 2. Memory Types (Hierarchical)

Second-Me defines memory in **three semantic layers**:

- **L0 (Raw/Factual)**: Documents, notes, chunks of content with embeddings
  - Types: Text, Markdown, PDF, Links
  - Contains: Raw documents split into searchable chunks

- **L1 (Semantic/Personal)**: "Shades" -- interest domains with timelines and Bio
  - **Shades**: Different personas/roles representing interest areas (e.g., "Music Junkie", "Fitness Guru")
  - Each shade has: name, aspect, timeline of events, description, and content
  - **Bio**: Global biography synthesizing all shades into a comprehensive identity profile
  - **Clusters**: Semantic groupings of memories with centroid embeddings

- **L2 (Model/Behavioral)**: Fine-tuned language models trained on user-specific synthetic data
  - Represents the user's communication style and personality
  - Generated via specialized training with DPO (Direct Preference Optimization)

## 3. How Memory is Stored

**Multi-layer storage architecture**:

- **File layer**: Raw documents stored in filesystem (PDFs, markdown, text)
- **Database layer**: SQLAlchemy ORM with versioned tables
  - `L1_versions`: Track snapshots of memory evolution
  - `L1_bios`: Store Bio profiles (content, summary, attributes, shades)
  - `L1_shades`: Individual shade definitions with timelines
  - `L1_clusters`: Semantic clusters with memory IDs and centroid vectors
  - `memories`: File metadata

- **Vector layer**: ChromaDB for embeddings (L0 chunks and L1 shades)
- **Metadata**: Timestamps, memory types, tags, confidence levels, analysis types

## 4. How Memory is Retrieved/Recalled

**Multi-level retrieval strategy**:

1. **L0 Retrieval**: Document chunk search
   - Query -> embedding -> semantic similarity search against chunk embeddings
   - Returns top-K chunks above similarity threshold (0.7 default)

2. **L1 Retrieval**: Shade/interest search
   - Query -> embedding -> similarity search against shade embeddings
   - Returns relevant shades and their timelines

3. **Global Bio Access**: Direct access to synthesized identity profile
   - Used for context-aware responses representing the user's perspective
   - Includes confidence levels for memory accuracy

**Key feature**: Memories are retrieved with two perspectives -- **"third-person view"** (objective analysis) and **"second-person view"** (personalized, relatable language).

## 5. How Memory is Updated/Consolidated

**Multi-stage consolidation pipeline**:

1. **L0 Processing** (documents -> indexed chunks)
   - Ingest documents
   - Extract chunks (token-based splitting)
   - Generate embeddings and summaries
   - Extract insights using LLM

2. **L1 Generation** (chunks -> shades -> bio)
   - **Shade Generation**: Cluster related chunks, use LLM to analyze coherence, create named interest domains with timelines
   - **Bio Generation**: Synthesize all shades into global biography with personality attributes
   - **Perspective Shifting**: Convert third-person analysis to second-person narrative
   - **Versioning**: Track L1 generations with timestamps for evolution tracking

3. **L2 Training** (shades + bio -> fine-tuned model)
   - Generate synthetic training data using L1 shades as context
   - Train local model with Me-Alignment algorithm (matches user style/values)
   - Use DPO and Chain-of-Thought (CoT) for quality data synthesis

**Consolidation triggers**: Periodic retraining, document batch ingestion, or explicit user request.

## 6. Overall Architecture (Components & Data Flow)

```
INPUT -> L0 (Raw) -> L1 (Semantic) -> L2 (Model) -> INFERENCE

Detailed Flow:
+-------------------------------------------------------------+
|  STORAGE LAYER                                               |
|  +-------------+-------------+-------------+-----------+    |
|  | File System | Vector DB   | SQL DB      | GPU Model |    |
|  | (documents) | (embeddings)| (metadata)  | (inference)|   |
|  +-------------+-------------+-------------+-----------+    |
+-------------------------------------------------------------+

+-------------------------------------------------------------+
|  PROCESSING PIPELINE                                         |
|                                                              |
|  L0: Document Ingest -> Chunk -> Embed -> Summarize         |
|       |                                                      |
|  L1: Cluster -> Shade Generation -> Bio Synthesis            |
|       |                                    |                 |
|       Timeline Building              Perspective Shift       |
|       |                                    |                 |
|       (versioned shades)  -----------> (versioned bio)       |
|       |                                                      |
|  L2: Synthetic Data Gen -> Fine-tune -> Deployed Model      |
+-------------------------------------------------------------+

+-------------------------------------------------------------+
|  INFERENCE & SPACES                                          |
|                                                              |
|  Real-time Query -> L0/L1 Retrieval -> LLM Inference        |
|                                                              |
|  Spaces: Multi-agent collaborative reasoning                 |
|  (multiple AI selves discuss and reach conclusions)          |
+-------------------------------------------------------------+
```

## 7. Novel/Unique Concepts

1. **"Shades" Model**: Instead of flat memory, organize identity into named interest domains with roles and timelines. Each shade represents "a version of you" in a specific context.

2. **Multi-Perspective Bio**: The same identity is analyzed from multiple viewpoints:
   - Third-person (objective analysis)
   - Second-person (relatable, conversational)

3. **Me-Alignment Algorithm**: Specialized training approach that fine-tunes models to authentically represent a user's values, style, and personality rather than generic instruction-following.

4. **Versioned Memory Evolution**: Track how the user's AI self changes over time. Each L1 generation is timestamped, allowing you to see personality evolution.

5. **Spaces**: A novel component enabling multiple AI selves to collaboratively reason on shared objectives -- creating a "collective intelligence" of different AI personas interacting with each other.

6. **Hierarchical Memory Modeling (HMM)**: Three-layer abstraction separating raw data (L0), semantic understanding (L1), and behavioral modeling (L2).

7. **Confidence Levels**: Memories are weighted with confidence scores (VERY_LOW to VERY_HIGH) reflecting certainty of attribute extraction.

8. **Time-aware Retrieval**: Memories are categorized as "recent" or "earlier" with time ranges, supporting temporal context awareness.

---

**Key Insight**: Second-Me reimagines memory not as "here's what I know about you" but as "here's **who you are**" -- a unified, versioned, multi-perspective identity that evolves continuously as you add memories and the system learns what truly matters to you.
