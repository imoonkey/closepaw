# memU architecture notes (Codex)

## Design thesis
memU is a hierarchical and proactive memory engine. It emphasizes pipelineized processing, explicit user scoping, and retrieval strategies that can switch between RAG and LLM-heavy modes.

## Architecture skeleton
- Layered hierarchy: memory is modeled across multiple abstraction/time layers.
- Pipeline architecture: memorize and retrieve are stage-based workflows with validation gates.
- Pluggable storage: backend-dependent vector strategies are abstracted behind interfaces.
- Scope-first schema: user identity fields are embedded in memory records and filter validation.

## Memory model
- Memory is organized to support both reactive recall and proactive surfacing.
- Retrieval can use pure RAG-like paths or LLM-assisted paths depending on query requirements.
- Filtering is explicitly validated to avoid scope leakage.

## Read/write flow (high level)
- Write path: staged ingestion processes interactions, extracts/stores memory, and enforces schema/scope constraints.
- Read path: retrieval pipeline selects strategy, executes scoped search, runs sufficiency checks, and optionally escalates retrieval depth.

## Why this design is strong
- Workflow-driven design is maintainable and testable.
- Built-in support for proactive behavior aligns with assistant experiences.
- Scope handling is treated as architecture, not an afterthought.

## Tradeoffs
- Pipeline depth can add latency and tuning burden.
- More stages means more failure points unless observability is strong.
- Proactive surfacing requires careful policy controls to avoid noisy memory injection.
