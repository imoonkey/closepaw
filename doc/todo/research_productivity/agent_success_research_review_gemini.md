# Design Review: Agent Research Productivity

> **Date**: 2026-02-04
> **Reviewer**: Antigravity (Model 3)
> **Subject**: Review of `agent_success_research_{1,2,3}.md`

## Executive Summary

All three designs correctly identify the core problem: **Agent "Success Logic" (prompts, heuristics) is too coupled with "Infra Logic" (runtime, tools), making iteration slow and blind.**

*   **Design 1** proposes a pragmatic, phased implementation focused on centralization and logging.
*   **Design 2** proposes a comprehensive "Success Hub" with explicit Profiles for A/B testing.
*   **Design 3** proposes a "Lab vs Factory" architectural split with a focus on strict roles and tracing.

**Recommendation**: Adopt a hybrid approach. Use the **Architecture of Design 2 (Success Hub & Profiles)** as the target state, but implement it via the **Phased Plan of Design 1**. Design 3's "Lab/Factory" philosophy is a good mental model to enforce during code review.

---

## Detailed Review

### Design 1: Phased Research Layer
*   **Approach**: Incremental refactoring. Move strings to `PromptTemplates.kt`, then add logging.
*   **Strengths**:
    *   **Low Barrier**: Can start immediately with "Phase 1" (move strings).
    *   **Risk-Free**: Doesn't require changing the `TurnRunner` logic significantly.
*   **Weaknesses**:
    *   **Naive A/B**: `PromptTemplates.ACTIVE` is a singleton solution; hard to run parallel experiments or configure via CLI args/config without a proper "Profile" concept.
    *   **Scalability**: A single `PromptTemplates.kt` file will become unmaintainable quickly.

### Design 2: Success Hub (Codex)
*   **Approach**: Create a dedicated module `agent/success/` containing Profiles, Assemblers, and Policies.
*   **Strengths**:
    *   **`SuccessProfile`**: This is the winner feature. Explicitly defining a "Profile" (Prompt variant + Context Policy + Turn Policy) enables true scientific A/B testing.
    *   **Policy Engine**: Decouples "what to do when 2 tools are called" from the generic runner.
*   **Weaknesses**:
    *   **Complexity**: Requires more boilerplate to set up the Registry and Profile system.

### Design 3: Lab & Factory
*   **Approach**: Strict separation of concerns. `agent/prompt/` (The Lab) vs `agent/` (The Factory).
*   **Strengths**:
    *   **`AgentRole`**: Explicitly modeling `PLANNER` vs `EXECUTOR` roles instead of inferring them from tool lists is a significant architectural improvement.
    *   **TraceWriter**: Emphasis on the "Black Box Recorder" is crucial.
*   **Weaknesses**:
    *   Less concrete on the configuration mechanism (how to switch between experiments).

---

## Ratings

| Criterion | Design 1 | Design 2 | Design 3 |
| :--- | :---: | :---: | :---: |
| **Iterability** (Speed of change) | 4/5 | **5/5** | 4/5 |
| **Observability** (Debug clarity) | 4/5 | **5/5** | 5/5 |
| **Architecture** (Cleanliness) | 3/5 | **5/5** | 4/5 |
| **Feasibility** (Ease of impl) | **5/5** | 3/5 | 4/5 |
| **Overall Score** | **4.0** | **4.5** | **4.25** |

---

## Synthesis & Implementation Recommendation

We should proceed with a plan that implements **Design 2**, but broken down into the manageable steps from Design 1.

### Unified Design Proposal

1.  **Directory Structure**: Adhere to `agent/success/` (from Design 2).
    ```text
    agent/success/
      ├── profile/              # The "Config" for experiments
      │   └── SuccessProfile.kt
      ├── prompt/               # The "Strings" (Design 1 & 3)
      │   ├── PromptTemplates.kt
      │   └── PromptAssembler.kt
      └── trace/                # The "Recorder" (Design 3)
          └── SuccessTracer.kt
    ```

2.  **Key Components**:
    *   **`SuccessProfile`**: The configuration object passed through the session.
    *   **`PromptAssembler`**: Taking `AgentRole` (from D3) + `TurnContext` -> Full String.
    *   **`SuccessTracer`**: Writes `turn_X_full_prompt.txt` and `turn_X_context.json`.

3.  **Migration Path**:
    1.  **Step 1 (Centralize)**: Move all prompt strings to `agent/success/prompt/templates/`.
    2.  **Step 2 (Assemble)**: Create `PromptAssembler` and switch `Turn.kt` to use it.
    3.  **Step 3 (Trace)**: Add `SuccessTracer` to `AgentTurnRunner` to capture inputs/outputs.
    4.  **Step 4 (Profile)**: Introduce `SuccessProfile` to allow switching between prompt sets.

### Next Steps

1.  Create the `agent/success` package structure.
2.  Refactor `ExecutorAgent` and `Turn.kt` prompts into `agent/success/prompt`.
3.  Implement the "Full Prompt Logging" immediately, as it unlocks the visibility needed for further changes.
