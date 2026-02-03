# Multi-Agent Design Review

> **Reviewer**: Gemini
> **Date**: 2026-02-03
> **Scope**: Review and rate internal designs `design_1.md`, `design_2.md`, and `design_3.md` for multi-agent infrastructure.

## Executive Summary

I have reviewed three design proposals for extending the Android Agent infrastructure to support multi-agent capabilities. All three designs converge on a similar core concept: **Delegation via Tools** ("Sub-Agent as a Tool"). However, they differ significantly in their level of abstraction, completeness, and future-proofing.

- **Design 1 (Foundation Focused)**: Proposes a pragmatic, phase-based approach centered on a `SubAgentManager`. It strikes a balance between formal structure and rapid implementation.
- **Design 2 (Architecture Focused)**: Offers the most comprehensive architecture with a formal `AgentDefinition` DSL, a typed `InputConfig` system, and a clear path for protocol extensions. It is the most "engineered" solution.
- **Design 3 (MVP Focused)**: A lightweight proposal focused on immediate implementation using the existing `Agent` class with minimal changes. It relies heavily on implicit behavior and simple event bridging.

### Ratings Snapshot

| Design | Clarity | Completeness | Arch. Fit | Extensibility | **Overall Score** |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Design 1** | 8/10 | 7/10 | 9/10 | 7/10 | **7.8/10** |
| **Design 2** | 9/10 | 10/10 | 8/10 | 10/10 | **9.3/10** |
| **Design 3** | 7/10 | 5/10 | 9/10 | 4/10 | **6.3/10** |

---

## Detailed Review

### Design 1: "Managed Delegation"
**Files**: `design_1.md`, `plan_1.md`

#### Analysis
Design 1 proposes a `SubAgentManager` service to encapsulate the lifecycle of sub-agents. It introduces the concept of `SubAgentRuntime` and explicitly handles "Approval Routing" via a bridge.

**Strengths:**
- **Rational Phasing**: The plan breaks down implementation into clear, low-risk phases.
- **Guardrails**: Explicitly mentions `maxDelegationDepth` and tool allowlists to prevent recursion loops.
- **Approval Logic**: Addresses the critical flow of how a child agent requests user approval through the parent session.

**Weaknesses:**
- **Implicit Definitions**: Agents are defined somewhat loosely ("locally defined") without a strict schema compared to Design 2.
- **Limited Protocol Specs**: While it mentions new event types, it handles them somewhat informally compared to Design 2's detailed data classes.

#### Scoring
- **Clarity (8/10)**: Easy to understand high-level flow.
- **Completeness (7/10)**: Misses some details on how sub-agent inputs are validated.
- **Architecture Fit (9/10)**: Fits very naturally into the current `SessionServices` model.
- **Extensibility (7/10)**: Good foundation, but less structure for future remote agents than Design 2.

---

### Design 2: "Formal Agent Registry"
**Files**: `design_2.md` (Self-contained)

#### Analysis
Design 2 is a robust architectural proposal. It introduces a sealed interface `AgentDefinition` to formally define what an agent is (name, inputs, outputs, timeout). It effectively decouples the *definition* of an agent from its *execution* (`SubAgentRunner`).

**Strengths:**
- **Formal Schema**: The `InputConfig` and `AgentDefinition` DSL provide type safety and auto-documentation capabilities. This is crucial for scaling to many agents.
- **Protocol Maturity**: Proposes concrete `AgentEvent` extensions (`SubAgentStarted`, `SubAgentActivity`) that are fully typed.
- **Built-in Agents**: Provides concrete examples (`ScreenAnalysisAgent`, `AppExplorerAgent`) that demonstrate the utility of the design immediately.
- **Isolation**: Clear strategy for service isolation (cloning `SessionServices`, filtering `ToolRegistry`).

**Weaknesses:**
- **Complexity**: Higher initial implementation cost due to the introduction of the Registry, Definitions, and Schemas.
- **Over-engineering?**: Might be slightly overkill if we only ever have 1-2 sub-agents, though it pays off quickly.

#### Scoring
- **Clarity (9/10)**: detailed diagrams and code snippets make the intent unambiguous.
- **Completeness (10/10)**: Covers lifecycle, protocol, errors, timeouts, and specific component designs.
- **Architecture Fit (8/10)**: Requires new components (`AgentDefinition`, `InputConfig`) but integrates them well.
- **Extensibility (10/10)**: The `AgentDefinition` interface perfectly sets the stage for future Remote Agents or loaded-from-file agents.

---

### Design 3: "Agent-as-a-Tool MVP"
**Files**: `design_3.md`, `plan_3.md`

#### Analysis
Design 3 is an implementation-focused proposal to get multi-agent behavior working with minimal code. It treats the `Agent` purely as a resource wrapped by a `Tool`.

**Strengths:**
- **Simplicity**: Extremely low barrier to entry. Reuses `Agent` class almost directly.
- **Speed**: Could be implemented in a day.

**Weaknesses:**
- **Lack of Observability Structure**: Relies on "prefixing" log messages for UI, which is brittle and hard to parse programmatically later.
- **Missing Lifecycle**: Does not explicitly handle timeouts, approvals, or complex lifecycle states as robustly.
- **No Registry**: "Agent Config" is created on the fly in the tool, making discovery and reuse harder.

#### Scoring
- **Clarity (7/10)**: Simple concept, but some "hand-waving" on event bridging specifics.
- **Completeness (5/10)**: glosses over approval routing, deep recursion protection, and input validation.
- **Architecture Fit (9/10)**: Uses existing classes efficiently.
- **Extensibility (4/10)**: Would require refactoring to support structured inputs or remote agents later.

---

## Recommendation

**Adopt Design 2**.

While Design 1 is solid, **Design 2** offers a superior architectural foundation that solves the same problems but with better long-term maintainability. The addition of `AgentDefinition` and `AgentRegistry` turns "sub-agents" from an ad-hoc feature into a first-class platform capability.

**Plan adjustments for Design 2**:
1.  We can simplify the `InputConfig` implementation for Phase 1 (skip complex JSON schema validation initially) to speed up delivery.
2.  We should prioritize the **Approval Routing** mechanism (from Design 1/2) as that is critical for safety.

**Next Steps**:
1.  Create `AgentDefinition.kt` and `AgentRegistry.kt`.
2.  Implement `SubAgentRunner` with the service isolation logic.
3.  Update Protocol with `SubAgent*` events.
