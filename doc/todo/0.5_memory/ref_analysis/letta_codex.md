# Letta architecture notes (Codex)

## Design thesis
Letta treats memory as explicit, editable state owned by an agent. Memory is represented as structured blocks with tool-mediated read/write operations and archival recall.

## Architecture skeleton
- Core memory blocks: typed/labeled blocks are the canonical in-context memory representation.
- Block management service: CRUD, versioning, and tagging around memory blocks.
- Archival memory services: separate store and APIs for long-term retrieval (search/insert/delete).
- Recall memory path: conversation/history retrieval is exposed alongside archival memory.
- Configured storage domains: archival, recall, and metadata can be independently configured.

## Memory model
- Distinguishes actively rendered "core memory" from larger archival stores.
- Memory editing is a first-class tool action, not an implicit side effect.
- Block-level structure gives deterministic control over what enters context windows.

## Read/write flow (high level)
- Write path: agent/tool updates core blocks or inserts archival records; state changes are persisted with metadata.
- Read path: runtime renders core blocks into prompt context, then augments with archival/recall retrieval when needed.

## Why this design is strong
- High controllability and debuggability of agent memory state.
- Practical separation of always-on memory vs on-demand recall.
- API-first architecture enables external memory governance.

## Tradeoffs
- Requires disciplined block schema design; poor schemas create brittle prompts.
- Manual/stateful edits can introduce drift if not validated.
- More explicit memory operations can increase agent policy complexity.
