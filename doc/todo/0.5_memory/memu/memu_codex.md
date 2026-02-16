# memU - repo study summary + ideas for Android Agent

## Sources (local)
- `.reference/mem/memU/README.md`
- `.reference/mem/memU/CHANGELOG.md`
- `.reference/mem/memU/src/memu/app/service.py`
- `.reference/mem/memU/src/memu/app/memorize.py`
- `.reference/mem/memU/src/memu/app/retrieve.py`
- `.reference/mem/memU/src/memu/app/settings.py`
- `.reference/mem/memU/src/memu/app/crud.py`
- `.reference/mem/memU/src/memu/database/models.py`
- `.reference/mem/memU/src/memu/database/inmemory/vector.py`
- `.reference/mem/memU/src/memu/database/sqlite/repositories/memory_item_repo.py`
- `.reference/mem/memU/src/memu/database/postgres/repositories/memory_item_repo.py`
- `.reference/mem/memU/src/memu/utils/references.py`
- `.reference/mem/memU/src/memu/client/openai_wrapper.py`
- `.reference/mem/memU/tests/test_salience.py`
- `.reference/mem/memU/tests/test_references.py`
- `.reference/mem/memU/tests/test_tool_memory.py`
- `.reference/mem/memU/docs/sqlite.md`
- `.reference/mem/memU/docs/langgraph_integration.md`

## What memU is
- Python memory framework for long-running/proactive agents.
- Core model is hierarchical memory: `Resource -> MemoryItem -> MemoryCategory` with `CategoryItem` links.
- Supports multimodal ingest (conversation/document/audio/image/video), memory extraction, retrieval, and CRUD/patch APIs.

## Architecture from code
- `MemoryService` composes 3 mixins: `MemorizeMixin`, `RetrieveMixin`, `CRUDMixin`.
- Internally it uses configurable workflow pipelines (`WorkflowStep` graph) instead of one monolithic function.
- It exposes extension points:
  - LLM call interceptors (`before`, `after`, `on_error`)
  - workflow-step interceptors (`before`, `after`, `on_error`)
  - runtime pipeline mutation (`insert_step_before/after`, `replace_step`, `configure_pipeline`).

## Data model details worth copying
- `MemoryItem.extra` is an extensible metadata bag used for:
  - salience/reinforcement (`content_hash`, `reinforcement_count`, `last_reinforced_at`)
  - source references (`ref_id`)
  - tool-memory fields (`when_to_use`, `metadata`, `tool_calls`).
- Scope is model-driven via `UserConfig.model` and merged into all DB models.
- Default scope model is only `user_id`; `agent_id/session_id` are not enabled by default.

## Memorize pipeline
1. Ingest resource (`resource_url`, `modality`).
2. Preprocess per modality (including audio transcription and optional vision processing).
3. Extract memory items by memory type using prompt templates.
4. `dedupe_merge` step exists but is currently placeholder/no-op.
5. Embed and persist items/resources, map item->category relations.
6. Update category summaries with new memories.
7. Optional: write inline references `[ref:ITEM_ID]` into summaries and back-fill `item.extra.ref_id`.

## Retrieve pipeline
- Input is `queries` (conversation context + final query) and optional `where` scope filter.
- Two methods:
  - `rag`: vector ranking
  - `llm`: LLM ranking over formatted category/item/resource text
- Tiered retrieval strategy:
  1. Route intention (`RETRIEVE` vs `NO_RETRIEVE`) + query rewrite
  2. Retrieve categories
  3. Sufficiency check; if insufficient, continue
  4. Retrieve items
  5. Sufficiency check; if insufficient, continue
  6. Retrieve resources
- `where` filters are validated against scope model fields before query execution.

## Salience + reinforcement (new in v1.4.0)
- Dedup hash: `sha256(memory_type + normalized_summary)[:16]`.
- Reinforcement mode updates existing item instead of duplicating it.
- Salience ranking formula:
  - `score = similarity * log(reinforcement_count + 1) * recency_decay`
  - recency decay uses half-life (`recency_decay_days`, default 30).
- Works for SQLite and Postgres; Postgres falls back to local scoring for salience mode.

## Reference-aware memory (new in v1.4.0)
- Category summaries can include `[ref:...]` citations.
- Retrieval can optionally parse refs from category summaries and fetch items by `extra.ref_id`.
- Utilities include extraction/stripping/citation-format conversion helpers.

## Storage and runtime tradeoffs
- Backends:
  - `inmemory`: simplest, ephemeral
  - `sqlite`: local persistence, brute-force vector search
  - `postgres + pgvector`: scalable vector index
- SQLite guidance in docs is practical and clear (up to around 100k items before brute-force pain).

## Recent timeline (from changelog)
- `2026-02-06` (`v1.4.0`): inline refs, salience/reinforcement, tool-memory type.
- `2026-01-29` (`v1.3.0`): LangGraph adapter, more provider integrations.
- `2026-01-14` (`v1.2.0`): SQLite backend + workflow interceptor support.

## Gaps / risks observed
- `DEFAULT_MEMORY_TYPES` is currently `['profile', 'event']`; other types exist but are not default.
- `dedupe_merge` workflow step is a placeholder (no semantic merge yet).
- OpenAI wrapper exposes `ranking/top_k`, but retrieval call path currently does not pass them through.
- Category init state is cached in shared `Context`; this implies a service instance should be treated as single-scope to avoid cross-scope category-ID leakage risk.
- Short `ref_id` strategy (`item_id` compressed to 6 chars) has collision risk at larger scale.

## Concrete adaptation for our Android agent
### P0 (should implement)
- Adopt 3-layer memory schema (`resource/item/category/relation`) in local DB.
- Add scoped memory filtering by at least `user_id + session_id + agent_id/device_id`.
- Implement tiered retrieval with sufficiency gating (category -> item -> resource).
- Add salience ranking with reinforcement metadata.
- Add tool-memory style logs for action outcomes (success/time/error patterns).

### P1 (good next)
- Add reference-aware summaries so high-level category summaries remain auditable.
- Add workflow interceptors for observability and safety policy hooks.
- Add configurable memory-type prompts per task domain (automation, troubleshooting, preferences).

### P2 (later)
- LLM-only ranking path for edge cases where embedding quality is weak.
- Multimodal memory ingest for screenshots/video snippets.

## Practical rules to keep when borrowing
- Keep memory scope strict (never cross user/session boundaries by default).
- Keep retrieval fallback deterministic (vector first, LLM rerank optional).
- Keep summary statements traceable to source items.
- Keep memory update idempotent (reinforcement instead of duplicate growth).
