# openclaw memory architecture notes (Codex)

## Design thesis
openclaw designs memory as a plugin-capable subsystem with markdown-first source memory, optional vector backends, and explicit context-compaction integration.

## Architecture skeleton
- Core memory manager: centralized config and retrieval orchestration (provider, fallback, chunking, hybrid/MMR, temporal decay, cache).
- Plugin model: `memory-core` and `memory-lancedb` extensions expose memory tools and can hook lifecycle events.
- Multi-backend support: markdown/indexed memory, QMD sidecar storage, and optional sqlite-vec style vector path.
- CLI and ops hooks: commands for status/index/search plus maintenance workflows.
- Context lifecycle integration: memory flush/search ties into compaction and session-pruning flows.

## Memory model
- Primary knowledge can live in markdown documents with index/search acceleration.
- Optional vectorized retrieval augments semantic recall when configured.
- Memory sources and scopes are explicit, enabling backend switching without changing agent behavior contracts.

## Read/write flow (high level)
- Write path: conversational/context artifacts are captured through plugin hooks; pre-compaction flush can persist transient context into memory stores.
- Read path: memory tools (`memory_search`, `memory_get`, or backend-specific tools) fetch context based on configured retrieval strategy and source priority.

## Why this design is strong
- Pragmatic portability: works with lightweight local markdown and can scale to vector backends.
- Pluginized architecture supports experimentation without rewriting the core agent loop.
- Tight coupling with compaction/session lifecycle helps long-running agents keep continuity.

## Tradeoffs
- Flexibility introduces configuration complexity and potential behavior variance across backends.
- Markdown-first memory needs disciplined curation to avoid stale/noisy knowledge.
- Multiple memory providers require clear precedence rules to keep retrieval deterministic.
