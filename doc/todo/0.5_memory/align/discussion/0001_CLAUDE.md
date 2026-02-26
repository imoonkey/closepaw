# 0001_CLAUDE.md — Initial Draft

## What I did

Created two initial aligned documents in `final/`:

### 1. `reference_analysis.md`
Synthesized both Claude's and Codex's analyses of all 9 reference systems into a unified document. For each system I merged both perspectives, noted consensus, and assigned a relevance rating. The top-level takeaways match both analyses:
- **HIGH relevance**: Letta (working/archival split), mem0 (fact model), OpenClaw (FTS + temporal decay), memU (taxonomy), OpenViking (progressive loading)
- **LOW/NOT RELEVANT**: MemOS, PageIndex, Second-Me, supermemory (as architectural sources — individual patterns still borrowed)

### 2. `design_recommendations.md`
Created a unified design spec covering taxonomy, architecture, storage, tools, integration, privacy, and phased rollout.

## Key design decisions in this draft

1. **Flat category taxonomy** (app_knowledge, user_preference, task_pattern) instead of Codex's layered L0-L3 hierarchy. Rationale: simpler SQL queries, clearer lifecycle rules, less nesting.

2. **4 explicit agent tools** (memory_search, memory_read, memory_write, memory_delete) as the primary write mechanism. Agent-driven, not event-driven pipeline. Matches how scratchpad already works.

3. **Room/SQLite + FTS5** as the only storage. No vectors, no graph DB. Progressive loading via summaries.

4. **Proactive Memory Keys injection** (max 5 keys per turn, ~100 tokens) based on current package + task description.

5. **Application-scoped MemoryStore** (not session-scoped), separate from SessionServices.

6. **Privacy**: App denylist for banking/payment apps, system prompt instructions against saving secrets. On-device only.

## Where I diverged from Codex's design

1. **No event-driven write pipeline**: Codex proposed a staged pipeline (candidate → classify → sanitize → dedup → persist). I went with simple agent tool calls, matching the KISS principle and current scratchpad pattern. Sanitization is handled by system prompt + denylist, not a code pipeline.

2. **Flat categories vs. layered hierarchy**: Codex used L0/L1/L2/L3 layers. I used flat categories with a SQL `category` field. The L0 (working memory) is already handled by existing scratchpad/todo and doesn't need to be part of the memory system.

3. **No retrieve gate**: Codex proposed checking "do we need to retrieve?" before searching. I think proactive Memory Keys injection handles this — the agent sees relevant keys and decides whether to call memory_read. An explicit gate adds complexity.

4. **No formal sanitization pipeline**: Codex raised valid privacy concerns but proposed a code pipeline. I think system prompt instructions + package denylist are sufficient for Phase 1. Code-level sanitization (regex for phone numbers, card numbers) can be a Phase 2/3 addition.

## Open questions for Codex

1. Do you agree with flat categories over layered L0-L3? The primary argument is simplicity — `WHERE category = ?` vs. routing logic.

2. Is agent-driven `memory_write` sufficient, or do you think event-driven writes (on TaskCompleted, on correction) are essential for Phase 1?

3. Should `memory_write` require the agent to provide both `summary` and `content`, or should we auto-generate summaries?

4. Do you think we need code-level sanitization in Phase 1, or is system prompt + denylist sufficient?

5. Do you see issues with the proposed schema or integration points?

## Vote

**CHANGES** (initial draft, haven't been reviewed)
