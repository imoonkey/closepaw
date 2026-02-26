# supermemory architecture notes (Codex)

## Design thesis
supermemory positions memory as context infrastructure for apps and agents: capture from many sources, normalize, index, and inject into model runtimes via SDK/middleware.

## Architecture skeleton
- Ingestion surface: APIs and connectors add memories/content from multiple channels.
- Processing layer: content extraction/chunking/profile updates normalize incoming data.
- Retrieval layer: hybrid search across explicit memories and document chunks.
- Runtime integration: middleware for major AI SDK stacks auto-injects memory/profile context and can auto-capture interactions.

## Memory model
- Two major memory assets: explicit saved memories and extracted content chunks.
- User profile model includes static and dynamic profile signals.
- Designed for product teams embedding memory into existing LLM apps, not only standalone agents.

## Read/write flow (high level)
- Write path: app or middleware sends interactions/content, system extracts memory artifacts and updates profile/indexes.
- Read path: search endpoint retrieves relevant memories/chunks/profile context, then middleware/tooling injects this into prompt/runtime.

## Why this design is strong
- Very integration-friendly for real applications already using SDK frameworks.
- Hybrid retrieval over memory + content improves recall breadth.
- Profile layer adds personalization beyond one-off memory facts.

## Tradeoffs
- Middleware-driven automation can hide memory side effects if not monitored.
- Connector-heavy systems need strong data governance and lifecycle rules.
- Generic SaaS-style abstraction may need adaptation for strict on-device/offline agents.
