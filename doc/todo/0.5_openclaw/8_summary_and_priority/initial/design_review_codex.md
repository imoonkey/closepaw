# Design Review: `8_summary_and_priority` (Codex on Claude)

## Findings

### 1. High: T1-1 is built on an incorrect read of the current prompt architecture

Claude says the system prompt is assembled in `PromptBuilder` and should be externalized there (`design_claude.md:55-78`). That is not how this repo works today.

Current ownership is:
- role prompts live in `agent/definition/StandaloneAgentDef.kt`, `PlannerAgentDef.kt`, and `ExecutorAgentDef.kt`;
- `PromptBuilder` only assembles turn input items (history, memory, app skill, observation);
- `TurnPlanningPhaseRunner` passes the already-resolved system prompt into `Turn`.

Why this matters:
- the proposal moves ownership to the wrong layer;
- it underestimates scope: this is not a 0.5-day `PromptBuilder` extraction;
- splitting prompt content into `system.md`, `tools_guide.md`, and `safety.md` risks prompt drift across standalone/planner/executor roles unless persona metadata moves with it.

Recommendation:
- review this as **persona asset externalization**, not `PromptBuilder` refactoring;
- externalize prompt body together with role, allowed tools, and delegation requirements;
- keep turn assembly and persona definition as separate layers.

### 2. High: T1-3 duplicates functionality the repo already has, so its priority is inflated

Claude frames tool risk levels as missing (`design_claude.md:134-182`). That is materially inaccurate for the current repo.

Current repo state already includes:
- `PolicyEngine` with `ApprovalMode`;
- `RiskLevel` enum in `protocol/ApprovalTypes.kt`;
- default risk levels per tool in `tool/PolicyEngine.kt`;
- action-specific risk resolution for `mobile_action` via `MobileActionName`.

So the real gap is not "add risk levels to ToolSpec." The real gaps are:
- make policy data-owned rather than hardcoded;
- expand beyond a single coarse dimension;
- connect risk policy to runtime capability and future entry-source trust levels.

Why this matters:
- the design treats an existing subsystem as net-new work;
- the 1-day estimate is misleading because the remaining value is in re-ownership and integration, not in adding an enum;
- it pushes a partially solved item ahead of more fundamental gaps.

Recommendation:
- rename this item to **policy externalization and richer risk model**;
- position it after, or jointly with, runtime capability modeling.

### 3. Medium-High: T2-1 over-prioritizes session persistence even though the repo already has most of it

Claude puts "Session persistence & identity" in Tier 2 (`design_claude.md:32`, `188-192`) as if this is still a main missing foundation.

Current repo state already has:
- stable `SessionId`;
- session recording via `SessionHistoryManager` and `SessionRecordingService`;
- checkpoint snapshots via `SessionCheckpointCoordinator` and `SessionStorage`;
- reload via `AgentSession.reload(...)`;
- hot-idle reuse and auto-rebind via `SessionCoordinator` and `MainActivity`.

The remaining gap is narrower:
- unify session identity across more entry points and surfaces;
- make session/workspace metadata first-class;
- support future remote or voice entry consistently.

Why this matters:
- the current wording risks reopening an architecture area that is already largely implemented;
- it can lead to duplicate models instead of promoting the existing checkpoint/history/session stack into a cleaner workspace abstraction.

Recommendation:
- demote raw session persistence as a priority;
- reframe the real item as **session workspace unification / multi-entry session identity**.

### 4. Medium: T1-2 chooses the wrong abstraction boundary for dynamic capability

The proposal adds `ToolSpec.isAvailable(): Boolean` and lets each tool self-report availability (`design_claude.md:87-130`).

Directionally, the problem is real. The abstraction is too weak for this repo.

Availability here is not just a property of a tool:
- some limits come from session config (`AgentMode`, excluded tools);
- some come from platform mode (`ACCESSIBILITY` vs `VIRTUAL_DISPLAY`);
- some come from permissions/runtime state;
- some are action-level constraints rather than whole-tool constraints.

A bare `isAvailable()` on `ToolSpec` has no context for those decisions, so it either:
- becomes inaccurate, or
- forces session/platform/config knowledge into each tool implementation.

Recommendation:
- introduce a session-scoped capability object or provider;
- drive tool exposure, prompt advertising, and policy from that shared runtime contract;
- let `ToolRegistry` stay simple and avoid turning `ToolSpec` into a global state reader.

### 5. Medium: T2-2 memory design needs stronger constraints before it is implementation-ready

The memory direction is promising, but "Markdown files per app package written by the LLM via a new `MemoryTool`" (`design_claude.md:194-199`) is underspecified.

Missing decisions:
- who curates or validates writes;
- how learned memory differs from static `app_skills`;
- how retrieval is bounded to avoid prompt bloat;
- how to prevent prompt poisoning or accidental storage of brittle per-screen trivia;
- whether memory is per-device, per-user, per-app, or per-task-family.

Recommendation:
- keep memory in the roadmap, but do not treat this as coding-ready;
- add write constraints and retrieval rules before implementation.

## What Claude Got Right

- The two meta-principles are correct and fit this repo: declarative capability and state externalization are the right long-term direction.
- Dynamic tool exposure is a real need. The repo still has mostly static registration plus lazy add-ons.
- Prompt/content externalization is directionally useful, especially for eval speed.
- Voice input belongs behind the existing session submission path rather than as a parallel orchestration stack.

## Priority Assessment

Claude's ordering is directionally good on two points:
- dynamic tool availability belongs near the top;
- voice, rich UI, and canvas should stay below core runtime improvements.

But the current ordering does not fit this repo as well as it could because it:
- promotes prompt externalization too early and at the wrong ownership layer;
- promotes risk levels as if missing when they already exist;
- promotes session persistence again even though most of that stack is already in place.

## Suggested Reorder

If we optimize for the current codebase, the cleaner order is:

1. Runtime capability contract / dynamic availability
2. Policy externalization tied to that contract
3. Persona and prompt asset externalization
4. Experience memory
5. Session workspace unification
6. Voice input
7. Rich message / canvas / remote control surfaces

## Overall Assessment

Claude's document is strong as a product roadmap summary, and the Tier-1/Tier-2 framing is easy to act on. The main issue is architectural accuracy: several items are described as if the repo were earlier in maturity than it actually is.

The best correction is not to discard the design, but to tighten it around the existing seams:
- `AgentDef` owns persona today;
- `PromptBuilder` owns turn assembly;
- `PolicyEngine` already owns coarse risk;
- session persistence/reload already exists.

Once those facts are reflected, the roadmap becomes sharper and the priority order improves.
