# PageIndex architecture notes (Codex)

## Design thesis
PageIndex is a vectorless retrieval architecture: it builds a structural index over documents and lets an LLM reason over that structure, rather than relying primarily on embedding similarity.

## Architecture skeleton
- Tree index construction: documents are transformed into hierarchical representations.
- Tree search runtime: retrieval navigates index structure in multiple steps, selecting relevant nodes/pages.
- LLM-centric planner: model reasoning guides candidate expansion and refinement.

## Memory model
- Memory is represented as structured document topology (nodes, sections, page-level organization).
- Retrieval relies on semantic reasoning over structure, not just nearest-neighbor vector distance.

## Read/write flow (high level)
- Write path: ingest docs, extract structured hierarchy, persist index metadata.
- Read path: run query over tree, progressively descend into promising branches, then synthesize final context.

## Why this design is strong
- Strong when queries require understanding document layout, hierarchy, or long-form dependencies.
- Avoids some embedding failure modes in domain-shifted or terminology-heavy corpora.
- Transparent intermediate reasoning steps are easier to inspect than black-box ANN hits.

## Tradeoffs
- Indexing and search can be LLM-costly.
- Quality depends on robust tree construction; weak parsing hurts retrieval deeply.
- Less suitable for ultra-low-latency memory lookup compared with simple vector recall.
