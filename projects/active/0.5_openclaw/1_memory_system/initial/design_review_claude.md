# Review of Codex Memory System Design

Reviewer: Claude
Date: 2026-03-10

## Overall Assessment

Strong design. It correctly identifies the integration seams, respects existing architecture boundaries, and makes sound "what not to do" decisions. The main concerns are **over-engineering for V1** and a few **codebase alignment gaps**.

---

## Strengths

**1. Excellent problem framing (§1–2)**
The distinction between static app skills vs. runtime-learned memory is precise. The constraint analysis of existing seams (`handleAgentComplete`, `PromptBuilder` ordering, `SessionStorage` patterns) shows real codebase understanding.

**2. Correct "no" decisions (§9)**
Not writing to assets, not using SQLite, not letting the LLM rewrite whole files, not blocking the completion path — all correct and well-reasoned.

**3. Async retain (§6.1)**
Non-blocking retain via `handleAgentComplete` is the right call. Memory is enhancement, not correctness.

**4. Task slice for retain input (§6.2)**
Only feeding the current task's history to the retain LLM call (from last `USER_INTENT` to end) is smart — avoids re-compressing and keeps token cost low.

---

## Concerns

### 1. Retain mechanism: extra LLM call vs. tool (High)

The design proposes a **separate background LLM call** after task completion to extract memories. This is the biggest divergence from my design and the most consequential choice.

**Trade-offs:**

| | Background LLM call (Codex) | Tool call during task (Claude) |
|---|---|---|
| Quality | Sees full task slice in hindsight | LLM decides in-context during task |
| Cost | Extra LLM call per task (~500-2K input tokens) | Zero extra calls |
| Latency | Async, but still real compute | None |
| Complexity | New LLM call path, JSON schema validation, error handling | One tool registration |
| Failure mode | Silent failure OK (async) | Silent failure OK (tool output ignored) |

The background-call approach is higher quality in theory (hindsight > in-the-moment), but it adds a **second LLM invocation path** that doesn't exist in the codebase today. Every turn currently follows one flow: `PromptBuilder → Turn.runStreaming() → ToolRouter`. A separate retain call creates a parallel path with its own prompt, schema, error handling, and model selection.

**Recommendation:** Start with the tool approach for V1. If memory quality proves insufficient, the background-call can be added as a Phase 2 enhancement. The tool approach is strictly simpler and aligns with how the agent already interacts with the world.

### 2. Over-structured entry format (Medium)

The design introduces 5 `kind` values (`workflow`, `pitfall`, `verification`, `preference`, `device`), `confidence` levels, `appliesWhen` fields, structured source attribution, and a task-mode classifier (`ACTION` vs `QUERY`) that influences recall ranking.

This is a lot of taxonomy for V1. Concerns:

- **Kind-based ranking** (§7.2) adds complexity but may not matter — with a 4-entry-per-app cap, the agent will see all entries anyway regardless of sort order.
- **Confidence levels** require the LLM to self-assess reliability, which LLMs do poorly. A timestamp (implicit staleness signal) is more useful than a `medium` vs `high` label.
- **`appliesWhen`** is a good idea but adds another field the LLM must populate correctly. At V1 scale (≤40 entries/app), the LLM can judge applicability from reading the full list.
- **`MemoryKind` enum + `MemoryScope` sealed class + `MemoryEntry` data class + `MarkdownMemoryCodec`** — that's 4 types for what could be a string append.

**Recommendation:** Flatten to untyped bullet points with timestamps. Let the LLM write natural language. Add structure only when the data volume or quality proves it necessary.

### 3. Component count (Medium)

The design lists 8 new types in §5.1:

```
MemoryEntry, MemoryScope, MemoryKind, DurableMemoryRepository,
FileDurableMemoryRepository, MarkdownMemoryCodec, MemoryRecallService,
MemoryRetainService, TaskMemoryCapture
```

For comparison, the entire existing tool system is ~5 types (`Tool`, `ToolRegistry`, `ToolRouter`, `ToolCallRequest`, `PolicyEngine`). This memory system would be larger than the tool system.

**What can be cut:**
- `MemoryKind` / `MemoryScope` — not needed if entries are untyped text
- `MarkdownMemoryCodec` — not needed if format is just bullet-point append
- `DurableMemoryRepository` interface — only one implementation; just use the concrete class
- `TaskMemoryCapture` — if using a tool instead of background retain, the LLM already knows what it learned; no need to collect metadata separately

This would leave: `MemoryStore` (file I/O), `MemoryRecaller` (prompt injection), `RememberExperienceTool` — 3 components.

### 4. Recall: goal-based app resolution (Low-Medium)

§7.1 point 4 proposes resolving the target app from the task goal text on the first turn (before the agent navigates there). This reuses `open_app` name matching.

This is clever but adds complexity for a narrow benefit. On turn 1, the agent hasn't failed yet — there's nothing to recall that would change its plan. By turn 2+, the agent is already in the target app and `currentPackageName` works. The gap is small.

**Recommendation:** Defer. Use `currentPackageName` only for V1.

### 5. Merge/dedup strategy (Low)

§6.4 proposes `scope + kind + normalized summary` dedup with confidence promotion. "Normalized summary" is undefined — string equality? Fuzzy? LLM-judged?

For V1, this is premature. With an 8KB file cap and ≤40 entries, duplicates are visible to the human and to the LLM (which can write "already known" instead of re-saving). Add dedup when duplication actually becomes a problem.

### 6. Missing: how does recall interact with existing working memory section?

§7.3 places recalled memory between working memory (todos/scratchpad) and app skill. But `PromptBuilder` currently builds a single "Working Memory" section containing todos + scratchpad. The design should clarify: is recalled memory a **subsection** of the existing Working Memory block, or a **separate top-level section**?

Given that recalled memory is conceptually different (cross-session knowledge vs. current-session state), a separate section is correct — but the design should state this explicitly and note the `PromptBuilder` edit required.

---

## Codebase Alignment Issues

### handleAgentComplete doesn't exist by that exact name

The design references `AgentSession.handleAgentComplete(...)` as the retain hook. Need to verify the actual method name and signature — the agent completion flow goes through `Agent.run()` returning a `CompletionReason`, which `AgentSession` processes. The hook point is correct conceptually, but the exact wiring needs to match real code.

### No mention of SessionServices wiring

The design describes components but doesn't specify how they're instantiated and wired. The codebase uses a `SessionServices` container with explicit bootstrappers (`SessionLlmBootstrapper`, `SessionToolingBootstrapper`, etc.). New components need to be wired through this pattern.

---

## Summary of Recommendations

| # | Issue | Severity | Recommendation |
|---|---|---|---|
| 1 | Background LLM call for retain | High | Use a tool instead; add background call in Phase 2 if needed |
| 2 | Over-structured entry format | Medium | Flatten to timestamped bullet points |
| 3 | Too many components | Medium | Cut to 3: MemoryStore, MemoryRecaller, RememberExperienceTool |
| 4 | Goal-based app resolution on turn 1 | Low-Med | Defer; use currentPackageName only |
| 5 | Merge/dedup strategy | Low | Defer; rely on human pruning and LLM awareness |
| 6 | Working memory section interaction | Low | Clarify as separate PromptBuilder section |

## Bottom Line

The Codex design has the right instincts on boundaries and integration points but over-engineers the V1 implementation. Strip the taxonomy, replace the background retain call with a tool, and cut the component count in half. The result will be ~300 lines of code that ships fast and provides real data to inform Phase 2 decisions.
