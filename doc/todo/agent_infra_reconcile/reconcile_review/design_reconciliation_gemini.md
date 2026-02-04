# Multi-Agent Design Reconciliation

> **Date**: 2026-02-03
> **Source Documents**: 
> 1. `doc/todo/reference/two_level_multiagent_design.md` (The "Pattern")
> 2. `doc/todo/multiagent_infra/design.md` (The "Platform")

## Executive Summary

The two documents address different layers of the problem:
- **`design.md`** provides the **Infrastructure Layer**: How to technically run, connect, and bridge multiple agents (Registry, Runners, Event/Approval Bridges).
- **`two_level_multiagent_design.md`** provides the **Application Layer**: A specific, highly effective pattern (Planner-Executor) to *use* that infrastructure for robustness.

**The Reconciliation**: We will build the **Infrastructure** from `design.md` to support the **Pattern** from `two_level_multiagent_design.md`.

---

## 1. Divergence Analysis

| Feature | `two_level_multiagent_design.md` (Pattern) | `design.md` (Platform) | Status |
| :--- | :--- | :--- | :--- |
| **Scope** | Specific Planner-Executor Logic | Generic Multi-Agent Support | **Complementary** |
| **State** | **Shared Mutable State** (`AgentState`) accessed by both agents. | **Strict Isolation**. Parent passes inputs; Child returns outputs. | **Conflict** |
| **Loop** | Custom `PlannerLoop` and `ExecutorSession`. | Reuses standard `Agent` ReAct loop recursively. | **Conflict** |
| **Executor** | Stateless, distinct "Session" object. | Standard `Agent` instance, just short-lived. | **Alignable** |
| **Tools** | Semantic tools (Planner) vs. Grounding tools (Executor). | Tool Registries and Allow-lists. | **Aligned** |

---

## 2. Recommendation: The "Platform-Pattern" Merge

We will implement the **Platform** (`design.md`) as the foundation, but strictly configure it to support the **Pattern** (`two_level`).

### 2.1 Resolution 1: State Management (The "Context Passing" Compromise)

**We reject the Shared Mutable State** proposed in `two_level` implementation details, as it violates the isolation guarantees of `design.md` and introduces race conditions/complexity.

**Solution**:
- The **Planner** owns the "Long-Term State" (Subgoals, Scratchpad, History) in its `Session`.
- When calling the Executor, the Planner **passes the necessary state as Inputs** via `DelegateTaskTool`.
- The Executor runs in **isolation** (as per `design.md`).
- The Executor returns a **Structured Report** as its output, which the Planner folds back into its state.

This achieves the *logical* shared state of `two_level` without the *physical* shared memory, preserving the safety of `design.md`.

### 2.2 Resolution 2: Loop Architecture

**We reject writing custom loops** (`PlannerLoop` class) if possible, to maintain codebase uniformity.

**Solution**:
- The **Planner** is just the standard `Agent` loop, but equipped with high-level tools (`ManageSubgoals`, `DelegateToExecutor`).
- The **Executor** is just the standard `Agent` loop, but equipped with low-level tools (`Click`, `Type`) and a strict `maxTurns` limit (e.g., 10).
- `SubAgentRunner` from `design.md` handles the "Session" lifecycle perfectly, creating the "Stateless Executor" effect by spinning up a new Runner for each delegation.

---

## 3. Action Plan

### Step 1: Implement Infrastructure (`design.md`)
1. **AgentRegistry**: To register the `ExecutorAgent`.
2. **SubAgentRunner**: To run the Executor in an isolated scope.
3. **DelegateTaskTool**: The bridge logic.

### Step 2: Implement The Planner-Executor Pattern (`two_level`)
1. **Define `ExecutorAgent`**: 
   - A specialized agent in the registry.
   - Tools: All low-level Android interaction tools.
   - Prompt: "You are an Executor... achieve this specific query..." (from `two_level`).
2. **Define `PlannerAgent` (The Default Agent)**:
   - Tools: `DelegateTaskTool` (configured to call `ExecutorAgent`), `SubgoalTools` (from `two_level`).
   - Prompt: "You are a Strategic Planner... Use the Executor to interact with the device..."
3. **Migrate State Logic**:
   - Port `Subgoal` data structures to be managed by `PlannerAgent`'s memory/tools (not a global singleton).

### Step 3: Verify with "AutoDev" Flow
- Verify that `Planner` creates a plan -> Delegates step 1 to `Executor` -> `Executor` runs 5 steps -> Returns Report -> `Planner` updates plan.

---

## 4. Modified Architecture Diagram

```mermaid
graph TD
    User[User Goal] --> PlannerSession
    
    subgraph PlannerSession [Planner Agent (Standard Loop)]
        P_Mem[Memory: Subgoals, History]
        P_Logic[Planner Logic]
        P_Tool[Tool: DelegateTask(query, context)]
        
        P_Logic <--> P_Mem
        P_Logic --> P_Tool
    end
    
    P_Tool -- "1. Spawn with Inputs" --> ExecutorRunner
    
    subgraph ExecutorRunner [SubAgentRunner]
        direction TB
        E_Session[Executor Agent (Standard Loop)]
        E_Scope[Isolated Scope]
        E_Tools[Tools: Click, Scroll, Etc]
        
        E_Session --> E_Tools
    end
    
    ExecutorRunner -- "2. Return Report" --> P_Tool
```

## 5. Next Steps

1. **Approve this reconciliation strategy.**
2. **Begin Phase 1 of `design.md` implementation.**
3. **Draft the formal `ExecutorAgent` definition** based on `two_level`'s prompt engineering but adapted for `AgentRegistry`.
