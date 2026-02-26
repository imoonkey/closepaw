# Second-Me architecture notes (Codex)

## Design thesis
Second-Me frames memory as a personal cognitive substrate. It uses staged abstraction layers to turn raw personal data into reusable identity/knowledge representations.

## Architecture skeleton
- L0 layer: extracts and stores concrete insights from raw multimodal/user data.
- L1 layer: clusters and abstracts L0 outputs into higher-level "shades" and profile-like representations.
- Kernel services: ingestion, memory transformation, and retrieval are exposed through API-driven services.
- Storage split: relational state plus vector retrieval backend for semantic recall.

## Memory model
- Multi-layer abstraction pipeline, not just short-term vs long-term buckets.
- Memory objects evolve from concrete observations to higher-order persona/knowledge structures.
- Retrieval can selectively include lower-level memory depending on request mode.

## Read/write flow (high level)
- Write path: ingest files/conversation artifacts, generate L0 insights, aggregate into L1 abstractions.
- Read path: choose retrieval strategy (with or without L0), then merge profile-level and instance-level memory for response.

## Why this design is strong
- Explicit abstraction pipeline is useful for "self" or personalized assistant use cases.
- Balances granular evidence (L0) and stable profile memory (L1).
- Good conceptual fit for long-horizon identity continuity.

## Tradeoffs
- Pipeline quality is sensitive to extraction and clustering quality.
- Layer maintenance adds operational and model complexity.
- Personal-memory focus may need rework for multi-user shared-agent scenarios.
