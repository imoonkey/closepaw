# OpenClaw Takeaways — Aligned Priority & Implementation Roadmap

## Design Principles

Two meta-lessons from OpenClaw that both analyses agree on:

1. **Declarative capability** — devices/tools declare what they can do; the system adapts. No central assumptions.
2. **State externalization** — prompts, memory, session, tool policy are data not code. Configurable and replaceable at runtime.

---

## Current Codebase Reality

Before prioritizing, acknowledge what already exists:

| Area | What Exists | What's Missing |
|------|-------------|----------------|
| **Risk levels** | `RiskLevel` enum (LOW/MEDIUM/HIGH), `PolicyEngine` with SMART mode, per-tool defaults in `DEFAULT_RISK_LEVELS`, per-action risk via `MobileActionName.defaultRiskLevel` | Risk levels are hardcoded in companion object, not data-driven. No entry-source trust dimension. |
| **Session persistence** | `SessionCheckpointCoordinator`, `SessionStorage`, `AgentSession.reload()`, hot-idle resume, `SessionHistoryManager` + `SessionRecordingService` | Session is not a user-facing product object. No browsing, resuming by user choice, or cross-entry-point identity. |
| **Prompt architecture** | System prompt lives in `AgentDef.systemPrompt` (hardcoded strings in `StandaloneAgentDef`, `PlannerAgentDef`, `ExecutorAgentDef`). App skills already externalized to `assets/app_skills/<package>/SKILL.md`. | Prompt changes require code change + rebuild. No structured separation of identity/rules/tools guidance. |
| **Tool registration** | `ToolRegistry` is runtime-mutable map. `SessionToolingBootstrapper` registers a fixed set. `ToolRegistry.createFilteredCopy()` for per-session filtering. | No runtime availability check. Static tool universe presented to LLM regardless of actual platform/permission state. |

---

## Unified Priority Roadmap

### P1: Session Capability Profile and Dynamic Tool Exposure

**The problem:** The LLM sees tools that may not work at runtime. This causes wasted turns and confusing failures.

**What exists:** `ToolRegistry` is already runtime-mutable. `createFilteredCopy(allowedNames, excludedNames)` provides static session-level filtering. `AgentDef.allowedTools` further constrains per-role.

**Aligned design:**

Use a small session-scoped `SessionCapabilityProfile`.

It should capture only the runtime facts that matter for tool exposure and prompt advertising:
- platform mode
- granted permissions / service health
- enabled tool names
- key action constraints that matter to reasoning

This is intentionally smaller than a full all-in-one runtime contract. Policy stays separate.

Why this boundary:
- it keeps runtime truth explicit at the session layer;
- it avoids pushing session/platform/config logic down into every tool;
- it avoids coupling capability and policy into one object.

`ToolSpec.isAvailable(context)` is not the primary design. A thin hook like that is acceptable later for truly tool-local checks, but v1 should not make each tool its own capability authority.

**Concrete changes:**
- New: `SessionCapabilityProfile`
- `SessionServices` owns the current capability profile
- `SessionToolingBootstrapper` and/or turn planning filters exposed tools from the profile
- `Turn` receives only enabled tools for schema generation
- `TurnPlanningPhaseRunner` can inject a concise capability summary when it materially affects reasoning

### P2: Policy Externalization

**The problem:** Risk levels are hardcoded in `PolicyEngine.DEFAULT_RISK_LEVELS` companion object. The TODO comment in the code confirms intent to externalize: *"Consider loading risk levels from configuration file for per-deployment customization."*

**What exists:** Full risk infrastructure — `RiskLevel` enum, `PolicyEngine.evaluateRiskLocked()`, per-action risk via `MobileActionName.defaultRiskLevel`, custom overrides via `setRiskLevel()`, allow/deny lists.

**Design:** Move the static `DEFAULT_RISK_LEVELS` map and `MobileActionName.defaultRiskLevel` values into a data file (YAML or JSON in assets). `PolicyEngine` loads this at construction. The file can be overridden per deployment or per session config.

**Stretch:** Add an `entrySource` dimension (local / remote / voice) that modulates risk thresholds. HIGH-risk tools from remote sources always require confirmation.

**Concrete changes:**
- New: `assets/policy/risk_defaults.yaml` (tool → risk level mapping)
- Modify: `PolicyEngine` — load from asset file instead of companion object map
- Modify: `MobileActionName` — move `defaultRiskLevel` into the same data file
- Optional: Add `entrySource: EntrySource` to policy check context

### P3: Persona & Prompt Asset Externalization

**The problem:** System prompts are hardcoded strings in `StandaloneAgentDef.kt` etc. Changing agent behavior requires code change + rebuild + reinstall.

**What exists:** `AgentDef` is an abstract class with `systemPrompt: String`, `allowedTools: Set<String>`, `requiresDelegationToolRegistration: Boolean`. `AgentDefRegistry` resolves defs by role. App skills are already asset-backed.

**Aligned design:**

Phase it.

Phase 1:
- extract prompt text into `assets/persona/<role>/system_prompt.md`
- keep tool allowlists and delegation config in code

Phase 2:
- add a lightweight manifest beside the prompt for role metadata, allowed tools, and delegation requirement

This keeps the ownership layer correct without turning prompt extraction into a bigger refactor than needed.

**Priority note:**

This stays after P1 and P2 in the architectural roadmap.

If eval iteration is actively blocked on prompt-edit speed, Phase 1 can be pulled forward tactically without changing the overall dependency story.

**Phase 1 changes:**
- New: `assets/persona/standalone/system_prompt.md`, `planner/system_prompt.md`, `executor/system_prompt.md`
- New: `PersonaRepository` (interface + asset impl, mirrors `AppSkillRepository`)
- Modify: `AgentDef` subclasses — load `systemPrompt` from `PersonaRepository` instead of inline string
- Modify: `SessionServices` — inject `PersonaRepository`

### P4: Cross-Session Experience Memory

**The problem:** Agent knowledge dies with each session. No learning from past app interactions.

**What exists:** Session-scoped `ScratchpadState` (20 entries, 3000 chars) and `TodoState`. Both cleared between sessions. App skills provide static guidance per package.

**Design:** Add a persistent memory layer alongside session-scoped scratchpad.

**Storage:** Markdown files per app package: `data/memory/apps/<package>.md`. Written by the LLM via a new `MemoryTool`. Loaded into turn context alongside app skills when the matching app is in foreground.

**Write constraints (from Codex review):**
- Only store generalized experience, not per-screen trivia
- LLM decides what to write, but format is structured (fact + confidence + source session)
- Max file size cap per app (e.g., 4KB) with oldest-entry eviction
- Memory is read-only during execution; writes are post-task

**Retrieval:** Injected as a "Relevant Experience" block after app skills in turn context. Bounded to prevent prompt bloat.

**Why after P1-P3:** Memory trained against an unstable tool/policy surface will overfit to transient behavior (Codex's argument, accepted).

### P5: Session Workspace Promotion

**The problem:** Session exists as infrastructure but not as a user-facing product object.

**What exists:** Full checkpoint/reload/hot-idle infrastructure. `SessionRecord` in history layer.

**What's needed:** Session as a browsable, resumable, cross-entry-point identity. This is a product/UI project, not a storage project. Defer detailed design until multi-entry points (web, voice) are closer.

### P6: Voice Input

**Direction:** Android `SpeechRecognizer` API. Push-to-talk in Smart Capsule overlay. STT result feeds into `SessionCoordinator` as `Op.UserInput`. Zero external dependencies. Useful but doesn't improve task success rate — it improves accessibility and hands-free UX.

### P7: Onboarding Wizard

**Direction:** Step-through flow: accessibility permission → overlay permission → battery optimization → LLM API key validation → demo task. Not a settings page — a sequential funnel. Valuable for first-run success rate.

### P8: Security Pairing for Remote Entry Points

**Direction:** Build this on top of P2 policy work and P5 session/workspace identity. It matters primarily once remote or multi-entry control surfaces exist. HIGH-risk actions from remote sources should always require confirmation on-device.

### P9: Rich Message / Canvas Host

**Direction:** Extend chat message types with `Choice`, `Confirmation`, `Summary` using native Compose. WebView canvas only if rich messages prove insufficient. Explicitly last — improve agent core before improving presentation.

---

## Dependency Graph

```
P1 (capability profile) ──→ P2 (policy extern) ──→ P5 (session workspace) ──→ P8 (security pairing)
          │                           │                    │
          └──────────────→ P3 (persona assets)            └────────────→ remote/web surfaces
                                   │
                                   └──────────────→ P4 (memory)

P6 (voice)     P7 (onboarding)     P9 (rich messages)
   [independent]  [independent]       [independent]
```

P1, P6, and P7 can start independently. P2 benefits from P1. P3 can begin after P1 if prompt iteration speed is urgent. P4 benefits from P1+P2 stability.

---

## Trade-offs & Rationale

| Decision | Chosen | Alternative | Why |
|----------|--------|-------------|-----|
| Availability boundary | Small `SessionCapabilityProfile` | `ToolSpec.isAvailable(context)` or full runtime contract | Keeps runtime truth explicit without over-coupling or duplicating session logic in tools. |
| Prompt extraction scope | Phase 1: prompt text only, Phase 2: manifest | Full `AgentDef` asset-backing immediately | Incremental. Fast eval win now, persona metadata later. |
| Risk level work | Externalize existing infrastructure | Build from scratch | Risk infra exists; the gap is data-ownership, not the enum/logic. |
| Memory timing | After P1-P3 | Tier 2 parallel | Memory overfits on unstable tool surface. |
| Session persistence | Demoted (already exists) | Tier 2 priority | Infrastructure is done; remaining work is product/UI. |

---

## Open Questions

1. **Memory write timing:** Should memory writes happen during task execution (risky — may store incomplete experience) or only post-task (safer but misses mid-task insights)? Needs experimentation.
2. **Prompt timing as tactics vs. architecture:** If prompt iteration becomes the bottleneck before P1 finishes, is Phase 1 prompt extraction worth pulling forward as a tactical change? Current answer: yes, but it should still be treated as part of P3, not as a different roadmap order.
