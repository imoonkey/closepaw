# OpenViking architecture notes (Codex)

## Design thesis
OpenViking models memory as a filesystem-like context system with hierarchical levels and recursive retrieval. The emphasis is multi-tenant, observable, and controllable context assembly.

## Architecture skeleton
- Context hierarchy: L0/L1/L2 layers structure what is immediate vs. persistent vs. broader context.
- Recursive retriever: retrieval walks directory-like structures, then narrows scope through hierarchical search.
- Session core: sessions are first-class and can iterate, archive, compress, and extract context over time.
- Identity boundary: request context carries account/user/role metadata to enforce tenant and role constraints.

## Memory model
- Memory is organized as scoped files/directories rather than only flat embedding chunks.
- Context is composed from hierarchical nodes and session state.
- Multi-tenant boundaries are designed into retrieval and storage decisions.

## Read/write flow (high level)
- Write path: session outputs and artifacts are stored in scoped locations, then optionally compressed into higher-level context.
- Read path: retriever applies tenant filters and recursive traversal to assemble context relevant to the current request.

## Why this design is strong
- Good fit for enterprise/team use where ownership and isolation matter.
- Hierarchical retrieval matches how humans organize long-running project context.
- Strong observability orientation helps diagnose bad retrieval behavior.

## Tradeoffs
- Filesystem metaphor can become heavy for very high-churn conversational memory.
- Recursive retrieval may need careful pruning to avoid context bloat.
- Strong tenant modeling adds complexity for single-user local deployments.
