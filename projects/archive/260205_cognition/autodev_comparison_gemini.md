# AutoDev vs. Cognition: Comparison & Improvement Plan

This document compares the reference "AutoDev" agent implementation (found in `.reference/.../autodevice_android_world`) with our current `cognition` agent, and proposes specific improvements to close the gap in capabilities and reliability.

## Executive Summary

**AutoDev** utilizes a robust **Planner-Executor** architecture with highly directive, specialized system prompts that enforce rigorous workflows, state tracking, and failure recovery. It treats the LLM as a component in a larger state machine.

**Cognition** (Current) uses a **Single-Loop** architecture where a single LLM turn handles perception, reasoning, and action selection. It relies more on the LLM's inherent capabilities rather than structured engineering scaffolding.

**Key Recommendation**: Adopt AutoDev's "Engineering-Heavy" approach to prompts and state management only where it adds value, specifically in **Loop Detection**, **Explicit Workflow Enforcement**, and **Failure Recovery**. We should also consider the Planner-Executor split for complex tasks.

---

## 1. Top-Level Architecture

| Feature | AutoDev (Reference) | Cognition (Current) |
| :--- | :--- | :--- |
| **Model Structure** | **Two-Layer**: `Planner` (High-layer) → `Executor` (Low-layer). | **Single-Layer**: One agent loop handles everything. |
| **Turn Logic** | Planner calls `executor_tools` (sub-goals). Executor runs up to 10 low-level steps (click, scroll) to fulfill sub-goal. | Single loop: Perception → Thought → 1 Action → Repeat. |
| **Context Scope** | Planner sees high-level state & todos. Executor sees immediate screen details. | Agent sees everything in every turn (potentially overwhelming context). |
| **State Tracking** | Explicit `NavigationState` (history of hashes, scroll counts) + `Scratchpad`. | `AgentSessionState` (todos, scratchpad) but less integrated into loop control. |

### Analysis
The **Planner-Executor** model allows AutoDev to "compress" low-level interactions. The Planner doesn't need to see every "scroll down" action, only the result of the "find X" sub-task. This is far more token-efficient and stable for long tasks.

## 2. Prompt Engineering

| Feature | AutoDev (Reference) | Cognition (Current) |
| :--- | :--- | :--- |
| **System Prompt** | **Extensive (~120 lines each)**. Clearly defined roles, workflows, and "CRITICAL" rules. | **Dynamic/Template-based**. Assembled by `PromptAssembler`. Simpler instructions. |
| **Workflow Enforcement** | Explicit steps: "ANALYZE → PLAN → EXECUTE → VERIFY". | Implied generic "What action to take next?". |
| **Conditionals** | Detailed logic for "If X, do Y". Specific instructions for dates, file naming, etc. | General instruction to achieve goal. |
| **Error Handling** | "MAX STEPS REACHED" handling, "Loop Prevention" logic burned into prompt. | Generic error feedback loop. |

### Analysis
AutoDev's prompts are "Operating Manuals" for the model. They leave little to chance.
-   **Planner Prompt**: Enforces updating todos, verifying limits, and strategic thinking.
-   **Executor Prompt**: Enforces "transcribe before scroll", confirm inputs, and detailed reporting.

## 3. Tooling & Perception

| Feature | AutoDev (Reference) | Cognition (Current) |
| :--- | :--- | :--- |
| **Perception** | **Dual**: Screenshot (Visual) + `transcribe_screen` (OCR text). | **A11y Tree**: `ScreenSnapshot` converted to JSON/Text. |
| **Navigation** | Smart scrolling (scroll detection, binary search for dates). | Standard `scroll` tool. |
| **Memory** | **Scratchpad**: `createItem`/`fetchItem` with "PAD-1" key format. Enforced usage. | **Scratchpad**: Available but usage is less strictly enforced by prompt. |
| **Loop Detection** | **Programmatic**: Hashes of screens/text are tracked. Comparison injects WARNINGS into prompt. | Relies on LLM to notice it is looping (often fails). |

### Analysis
AutoDev's **Programmatic Loop Detection** (`_has_seen_content`, `_update_navigation_state`) is a critical reliability feature. It injects warnings like *"WARNING: You have scrolled 5 times... STOP scrolling"* directly into the prompt context.

---

## 4. Improvement Proposals

We can adopt the best parts of AutoDev without necessarily rewriting our entire architecture immediately.

### Phase 1: hardening the Loop (Implementation Plan)

1.  **Implement Programmatic Loop Detection**:
    *   Track `ScreenSnapshot` hashes or A11y tree hashes in `AgentSessionState`.
    *   Track repetitive actions (e.g., 5 consecutive scrolls).
    *   **Action**: Modify `AgentPromptBuilder` to inject `## Warnings` section if loops are detected (e.g., "SYSTEM WARNING: You are revisiting the same screen state. STOP.").

2.  **Enhance System Prompts (`CognitionProfile`)**:
    *   Port the **"CRITICAL"** rules sections from AutoDev.
    *   Add explicit **"Workflow"** steps: "1. PERCEIVE... 2. PLAN... 3. ACT... 4. VERIFY".
    *   Add specific **"Failure Recovery"** instructions: "If tool fails X times, try Y."

3.  **Strict Scratchpad Enforcement**:
    *   Update prompt to force `Scratchpad` usage for any multi-step data extraction.
    *   Use AutoDev's "PAD-N" key convention to reduce hallucinated keys.

### Phase 2: Architecture Evolution (Design Plan)

1.  **Evaluate Planner-Executor Split**:
    *   For complex "Mission" type tasks, introduce a `PlannerAgent` that delegates to our current `CognitionAgent` (acting as Executor).
    *   Refactor `AgentTurnRunner` to support "Sub-Session" execution (run until Goal X achieved).

2.  **Hybrid Perception**:
    *   Evaluate adding an OCR tool (`transcribe_screen`) if A11y tree is insufficient for certain apps (games, custom UI).

## 5. Detailed Prompt "Theft" (Concepts to borrow)

We should incorporate these specific directives from AutoDev's `prompts.py` into our `BuiltinCognitionProfiles`:

> "You NEVER directly interact with the device. You write clear tool calls..."

> "CRITICAL: You receive screenshots directly - analyze them yourself first... If you need to read sensitive text... call transcribe..."

> "SCROLLING STRATEGY: Use search/filter first... Stop if transcription appears identical..."

> "FAILURE RECOVERY: If executor reports 'Max steps reached', READ the narrative... try alternative approach."

---

## Next Steps

1.  Update `CognitionProfile` with a harder, more directive system prompt.
2.  Add `NavigationState` tracking to `AgentTurnRunner` and pass warnings to `PromptAssembler`.
3.  Refactor `Scratchpad` tool description to match AutoDev's robust definition.
