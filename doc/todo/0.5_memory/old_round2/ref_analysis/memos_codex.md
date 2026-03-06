# MemOS architecture notes (Codex)

## Design thesis
MemOS treats memory as an operating-system-like runtime for agents, not just a vector index. The core idea is to coordinate multiple memory modalities through a scheduler and unified search surface.

## Architecture skeleton
- Memory container abstraction: `MemCube` is the basic unit that bundles multiple memory planes.
- Multi-cube composition: single-cube and composite-cube views let one query fan out across multiple cubes.
- Orchestration layer: `MemOS` config controls identity scope (user/session), memory toggles, and scheduler behavior.
- Scheduler layer: asynchronous processing and queue-based optimization decouple memory writes from foreground interactions.

## Memory model
- Explicitly separated planes: text memory, action memory, parametric memory, and preference memory.
- Backends are pluggable per plane, so storage strategy can vary by memory type.
- Search is unified at the service level even though memory data is physically heterogeneous.

## Read/write flow (high level)
- Write path: incoming interaction is routed by type into one or more memory planes, potentially through async scheduling.
- Read path: query is normalized into a shared context, then dispatched to relevant planes/cubes and merged into a single result set.

## Why this design is strong
- Clean separation between memory semantics (what memory means) and storage mechanism (where memory lives).
- Supports multimodal and agent-behavior memory without forcing one schema.
- Scheduler-first design is practical for production latency and throughput constraints.

## Tradeoffs
- Higher operational complexity than a single-vector-store architecture.
- Quality depends on cross-plane ranking/fusion; weak fusion can reduce recall precision.
- More configuration surface means stronger defaults and observability are required.
