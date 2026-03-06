# mem0 architecture notes (Codex)

## Design thesis
mem0 is a memory platform abstraction: one API composes LLM extraction, embedding/reranking, vector storage, optional graph memory, and scoped identity controls.

## Architecture skeleton
- Composition root (`Memory`): initializes model, embedder, vector DB, reranker, history DB, and optional graph backend.
- Scope model: memory operations include user/agent/run scope as first-class fields.
- Dual memory backends: semantic vector memory plus optional relationship graph memory.
- API productization: OpenMemory wraps memory operations with access control, state/history, and app-facing APIs.

## Memory model
- Memory records are extracted from interactions and normalized into searchable units.
- Supports both unstructured semantic recall and structured relational recall.
- Identity scoping is consistent across add/search/update/delete operations.

## Read/write flow (high level)
- Write path: LLM extracts candidate facts/preferences, deduplicates/updates records, persists to vector (and optionally graph) stores.
- Read path: scoped search retrieves memories via hybrid ranking, then returns filtered context.

## Why this design is strong
- Modular adapter architecture makes backend swapping straightforward.
- Good default balance between developer usability and production deployability.
- Graph option covers relation-heavy memory use cases beyond pure semantic recall.

## Tradeoffs
- Orchestration quality depends on extraction prompts and merge/update heuristics.
- Multi-backend deployments increase consistency and ops burden.
- Generic platform design may require domain tuning for highly specialized agents.
