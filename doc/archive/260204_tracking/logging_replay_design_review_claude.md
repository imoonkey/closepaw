# Logging & Replay Design Review

> **Date**: 2026-02-04  
> **Reviewer**: Claude  
> **Scope**: Independent review of all three `*_design_*.md` documents

---

## Executive Summary

All three designs correctly identify the core problem: **step-centric replay with hierarchical sub-agent tracking**. However, they differ significantly in depth, precision, and practical applicability. Below is a comparative analysis focused on **design thinking quality**, not writing style.

---

## Scoring Rubric

| Criteria | Weight | Description |
|----------|--------|-------------|
| **Problem Understanding** | 20% | Does it identify what's actually broken vs what works? |
| **Conceptual Clarity** | 25% | Is the mental model clean? Are abstractions well-chosen? |
| **Feasibility** | 20% | Can this actually be built on existing code with minimal churn? |
| **Completeness** | 15% | Does it address logging, storage, sub-agent linking, AND visualization? |
| **Extensibility** | 10% | Does it leave room for future needs (profiling, ablation, comparison)? |
| **Research Utility** | 10% | Will this actually help debug cognition failures? |

---

## Document Reviews

### 1. `logging_replay_design_codex.md` — Codex

**Overall Score: 8.5 / 10**

#### Strengths

1. **Best problem decomposition**. The distinction between "事件粒度偏'流水账'" (event granularity too log-like) vs "step 语义对象" (semantic step units) is the real insight. Current traces are event-centric; research needs are step-centric. This is the key cognitive leap.

2. **Explicit `ctx` envelope design**. The proposed `ctx` block with `session_id`, `agent_id`, `parent_agent_id`, `delegation_call_id`, `turn_id`, `step_id` is the most rigorous linking model. It doesn't rely on parsing `::` delimiters from session IDs—it uses explicit fields. This is more robust.

3. **Derived replay index concept**. The idea of a post-processing `replay_compiler.py` that transforms raw `trace.jsonl` → `steps.jsonl` + `agent_tree.json` is clever. It separates concerns: Android emits raw events; a Python tool builds the navigable structure. This avoids bloating the Android side.

4. **Artifact directory structure by agent/turn/step**. The proposed hierarchy (`artifacts/agent_root/turn_006/step_001/`) is cleaner than flat `artifacts/{kind}/` folders. It makes step-centric access trivial.

5. **Acceptance criteria are testable**. "10 秒内可打开并看到 step timeline" is a measurable SLA.

#### Weaknesses

1. **Overly ambitious `ctx` changes**. Adding `step_id` and `step_index` requires introducing a new "step" concept that doesn't exist in current code. The current model is turn-based, not step-based. This is a larger refactor than acknowledged.

2. **Redaction dual-mode is scope creep**. The `local_full_trace` vs `trace_export_redacted` idea is valid but not essential for research scaffolding MVP.

3. **No code examples for Android changes**. Unlike the other two, it doesn't show Kotlin data class modifications—just describes them conceptually.

#### Key Contribution
The **step-centric derived replay index** paradigm is the unique contribution. This avoids polluting Android code with viewer concerns.

---

### 2. `logging_and_viz_design_claude.md` — Claude

**Overall Score: 8.0 / 10**

#### Strengths

1. **Best current-state analysis**. The "What Already Works Well ✅ / What's Missing ❌" table is practical detective work. It correctly identifies that `FileTraceRecorder`, `AgentTrace`, artifact storage, and redaction are already solid.

2. **Surgical Android changes**. The proposal is explicit: add `parentSessionId: SessionId?` and `agentRole: AgentRole` to `AgentConfig`, update `IsolatedSubAgentRunner` to pass them, and update `AgentTrace.sessionStarted()` to emit them. This is a 30-minute changeset.

3. **Fallback parsing strategy**. The visualizer can parse `sessionId` with `::` delimiter as a fallback if explicit `parent_session_id` is missing. This provides backward compatibility with old traces.

4. **Concrete code snippets**. The document includes JavaScript implementations for `buildSessionTree()`, `ArtifactLoader`, and CSS variables. This is actionable.

5. **Realistic effort estimates**. "Phase 1: ~1 hour, Phase 2: ~2 hours" etc. feels grounded.

#### Weaknesses

1. **No step abstraction**. The design is still turn-centric, not step-centric. A "step" in the UI is just a vertical list of events grouped by turn. There's no explicit notion of "the atomic unit of cognition: perceive → think → act".

2. **Less rigorous linking**. The `parent_session_id` in `session_started` data payload is simpler than Codex's full `ctx` envelope. It works, but it means every event still lacks parent context—you have to look up the session start to find hierarchy.

3. **Viewer structure is conventional**. The 3-column layout with Session Tree / Timeline / Detail is the obvious design. Not bad, but not innovative.

#### Key Contribution
The **backward compatibility strategy** (fallback to `::` parsing) is pragmatic. Also, the **phased implementation plan with effort estimates** is the most realistic.

---

### 3. `logging_and_viz_design_gemini.md` — Gemini

**Overall Score: 6.0 / 10**

#### Strengths

1. **Clear goals section**. "Zero-config logging", "Full Context Capture", "Visualizer 2.0" are crisply stated.

2. **Same core solution**. Adds `parentSessionId` and `agentRole` to `AgentConfig`, which is correct.

3. **Multiplexing insight**. Correctly notes that sub-agents in the same process can write to the same `trace.jsonl` safely via synchronized writes.

#### Weaknesses

1. **Lack of depth**. At 146 lines, this is about 1/3 the length of the Claude doc and 1/2 of Codex. It reads more like a rough sketch than a design.

2. **No new ideas**. Everything here is a subset of what the other two docs propose. There's no unique contribution.

3. **Implementation plan is too vague**. "Task 1: Add `parentSessionId` and `agentRole` to `AgentConfig`." Okay, but what does that look like? No code examples.

4. **Visualizer section is underdeveloped**. The UI layout is a text diagram with "Session Tree | Timeline | Detail View" but no discussion of interactions, edge cases, or implementation approach.

5. **"Lite-html or just DOM API if lazy"**. This reveals the author didn't seriously think about the visualizer. For a tool that needs to render bounding box overlays, tabbed panels, and keyboard navigation, some planning is warranted.

6. **No discussion of step semantics**. Like the Claude doc, it's turn-centric, but unlike Claude, it doesn't even notice that turns might contain multiple sub-events worth navigating.

#### Key Contribution
None unique. This doc serves as a confirmation that the core solution (`parentSessionId` + `agentRole`) is agreed upon.

---

## Comparative Analysis

| Aspect | Codex | Claude | Gemini |
|--------|-------|--------|--------|
| Problem diagnosis | ⭐⭐⭐ "step语义对象" insight | ⭐⭐ Solid but conventional | ⭐ Generic |
| Linking model | ⭐⭐⭐ Full `ctx` envelope | ⭐⭐ `parent_session_id` in data | ⭐⭐ Same as Claude |
| Android feasibility | ⭐⭐ More intrusive | ⭐⭐⭐ Surgical, enumerated | ⭐⭐ Underspecified |
| Visualizer depth | ⭐⭐⭐ Compiler + 3-pane + keyboard | ⭐⭐⭐ Full JS examples | ⭐ Sketch only |
| Step vs Turn model | ⭐⭐⭐ Explicit step concept | ⭐⭐ Implicit (events grouped in timeline) | ⭐ Not addressed |
| Derived artifacts | ⭐⭐⭐ `steps.jsonl`, `agent_tree.json` | ⭐⭐ Lazy load from raw | ⭐ Not addressed |
| Future extensibility | ⭐⭐⭐ Mentions profiling, ablation | ⭐⭐ Mentions comparison, annotations | ⭐ Not addressed |

---

## Critical Design Questions Raised

### Q1: Turn-centric vs Step-centric?

Codex argues for an explicit "step" abstraction. Current code has:
- `turn_started` → `screen_captured` → `llm_request` → `llm_response` → `tool_call` → `tool_result` → `turn_completed`

This is multiple events per turn. For research, we often want to see:
- **Step 1**: Perception (screenshot + a11y)
- **Step 2**: Cognition (LLM input → LLM output)
- **Step 3**: Action (tool call → result)

Codex proposes `step.started` / `step.completed` events. Claude/Gemini just group events in the viewer. The question is: **should step boundaries be emitted by Android, or inferred by the viewer?**

**My take**: Infer in the viewer. Adding `step.started/completed` is invasive to `AgentTurnRunner` and the cognition loop. The viewer is smart enough to recognize the pattern.

### Q2: Every event gets `ctx` vs only session events?

Codex puts a full `ctx` block on every event. Claude puts `parent_session_id` only on `session_started`. The question is: **how expensive is full context per event?**

**My take**: Claude's approach is more practical. You look up the session once; events within it inherit context. Adding 100+ bytes of `ctx` to every event is wasteful for mobile storage.

### Q3: Artifact path by kind vs by agent/turn/step?

Current: `artifacts/{kind}/{turn_N}_{name}.{ext}` (e.g., `artifacts/screenshot/1_turn_1.jpg`)
Codex: `artifacts/{agent_id}/{turn_N}/{step_N}/{name}.{ext}`

**My take**: Codex's structure is cleaner for step-centric navigation, but requires non-trivial changes to `FileTraceRecorder.newArtifactPath()`. For MVP, keep current structure; let the viewer organize by step.

---

## Recommended Synthesis

Take the best from each:

| From Codex | From Claude | From Gemini |
|------------|-------------|-------------|
| Step-centric viewer (derived, not emitted) | Surgical Android changes (`parentSessionId`, `agentRole`) | — |
| Post-processing `replay_compiler.py` | Backward-compatible `::` parsing fallback | — |
| Acceptance criteria ("10s to step timeline") | Realistic effort estimates | — |
| Agent tree / step index as derived artifacts | Full JS code examples for viewer | — |

### Minimal Android Changes (from Claude)
1. Add `agentRole: AgentRole` enum to `AgentConfig`
2. Add `parentSessionId: SessionId?` to `AgentConfig`
3. Update `IsolatedSubAgentRunner` to set these
4. Update `AgentTrace.sessionStarted()` to emit them in `data`

### Derived Replay Index (from Codex)
1. Python script `replay_compiler.py` reads `trace.jsonl`
2. Outputs `agent_tree.json` (hierarchy) + `steps.jsonl` (navigable units)
3. Viewer loads these derived files, not raw trace

### Visualizer (merge Claude detail + Codex concepts)
1. 3-column: Session Tree → Step Timeline → World+Mind split
2. Step = group of events in a turn (Perception, Cognition, Action)
3. Keyboard nav: ←/→ step, ↑/↓ agent
4. No build step: vanilla ES6 + CSS

---

## Final Scores

| Document | Score | Verdict |
|----------|-------|---------|
| `logging_replay_design_codex.md` | **8.5 / 10** | Best conceptual model; slightly over-engineered |
| `logging_and_viz_design_claude.md` | **8.0 / 10** | Most actionable; lacks step abstraction |
| `logging_and_viz_design_gemini.md` | **6.0 / 10** | Sketch only; no unique contribution |

---

## Recommendation

**Use Codex as the conceptual blueprint, Claude as the implementation guide, and ignore Gemini (or use it as a sanity check that the core idea is agreed).**

The ideal approach:
1. **Phase 0**: Surgical Android changes per Claude (30 min)
2. **Phase 1**: Build `replay_compiler.py` per Codex concept (2 hours)
3. **Phase 2**: Build viewer v2 with Claude's code examples + Codex's step model (4 hours)
4. **Phase 3**: Integrate into `debug-run.sh` for "开箱即看" (30 min)

Total: ~7 hours to a research-grade debugging loop.
