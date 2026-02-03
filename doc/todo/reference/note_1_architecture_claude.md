# Note 1: Multi-Agent Architecture Patterns

> Comparative analysis of agent architectures across DroidRun, AutoDev, Mobile Agent v3, and MiniTap.

---

## Overview

All frameworks use a **two-layer architecture** (Planner/Manager + Executor), with variations in additional specialized agents:

| Framework | Primary Structure | Additional Agents | Total LLM Calls/Step |
|-----------|-------------------|-------------------|----------------------|
| **DroidRun** | Manager → Executor | ScripterAgent, TextManipulator, StructuredOutputAgent | 2-4 |
| **AutoDev** | Planner → Executor | (none, but Executor can call multiple tools) | 2+ |
| **Mobile Agent v3** | Manager → Executor | ActionReflector, Notetaker | 3-4 |
| **MiniTap** | Planner → Orchestrator → Cortex → Executor | Contextor, Summarizer | 4-6 |

---

## Architecture Details

### 1. DroidRun: Manager-Executor

```
DroidAgent (Orchestrator)
    │
    ├─[reasoning=true]──► ManagerAgent ──► ExecutorAgent
    │                          │
    │                    ┌─────┼─────┐
    │                    ▼     ▼     ▼
    │            ScripterAgent  TextManipulator  (normal action)
    │
    └─[reasoning=false]──► CodeActAgent (direct Python execution)
```

**Key Design Decisions:**
- **Scripter delegation**: Off-device operations (HTTP, file I/O) handled by ScripterAgent, not blocking main flow
- **TextManipulator**: Specialized for text editing tasks (prefix: `TEXT_TASK:`)
- **CodeActAgent**: Alternative direct mode that generates Python code instead of JSON actions

### 2. AutoDev: Planner-Executor (Google Research)

```
Planner LLM
    │
    ├──► tap(intent)          ───┐
    ├──► gesture(intent)         │
    ├──► scroll(intent)          ├──► Executor LLM ──► click(x,y) / swipe(...) / ...
    ├──► type_text(text, intent) │
    ├──► scan_for_element(intent)┘
    │
    ├──► open_app(name)         ──► Direct execution (no Executor)
    ├──► go_back()              ──► Direct execution
    ├──► update_todos(...)      ──► Direct execution
    └──► finish_task(success)   ──► Direct execution
```

**Key Design Decisions:**
- **Semantic → Coordinate split**: Planner issues intent-based commands (`tap on login button`), Executor converts to coordinates
- **Direct execution path**: Some actions (navigation, TODO updates) bypass Executor
- **MAX_EXECUTOR_STEPS**: Executor session limited to 10 steps to prevent infinite loops

### 3. Mobile Agent v3: Manager-Executor + Verification

```
Manager ──► Executor ──► ActionReflector ──► Notetaker (optional)
   │                          │
   │                          ▼
   │                   Outcome: A/B/C
   │                          │
   └──────────────────────────┘ (error_flag_plan if consecutive failures)
```

**Key Design Decisions:**
- **ActionReflector for verification**: Before/after screenshot comparison to verify action success
- **Three outcome types**: A (success), B (wrong page), C (no change)
- **Notetaker is optional**: Only enabled for memory-intensive tasks (`--notetaker True`)
- **Error escalation**: 2 consecutive failures → `error_flag_plan` → Manager replans

### 4. MiniTap: State Machine Architecture

```
Planner ──► Orchestrator ◄─────────────────────────────┐
                │                                       │
                ▼                                       │
         Convergence ──► Contextor ──► Cortex ──────────┤
                │              │          │             │
                │              │          ▼             │
         (replan/end)     (App Lock)   Executor ──► Tools
                                          │             │
                                          ▼             │
                                     Summarizer ────────┘
```

**Key Design Decisions:**
- **LangGraph state machine**: Explicit convergence checks and conditional routing
- **Orchestrator as traffic controller**: Manages subgoal lifecycle, triggers replanning
- **Contextor for perception + App Lock**: Both screen capture AND app boundary enforcement
- **Cortex is the "brain"**: Makes strategic decisions, outputs structured JSON for Executor
- **Summarizer for context management**: Trims message history to prevent overflow

---

## Division of Responsibility

| Responsibility | DroidRun | AutoDev | Mobile Agent v3 | MiniTap |
|---------------|----------|---------|-----------------|---------|
| **Goal decomposition** | Manager | Planner | Manager | Planner |
| **Progress tracking** | Manager (plan field) | TODO List | Manager (completed_plan) | Orchestrator |
| **Element grounding** | Executor | Executor | Executor (coordinates) | Cortex + Executor |
| **Action verification** | (implicit) | Executor (report) | ActionReflector | Tool feedback |
| **Memory/Notes** | memory field | Scratchpad | Notetaker | scratchpad + agents_thoughts |
| **Error recovery** | error_flag_plan | MAX_EXECUTOR_STEPS | error_flag_plan | convergence_gate |

---

## Orchestration Patterns

### Loop Structure

| Framework | Loop Type | Max Steps | Termination |
|-----------|-----------|-----------|-------------|
| DroidRun | Event-driven (LlamaIndex workflow) | 15 | `<request_accomplished>` in Manager output |
| AutoDev | While loop | Task-dependent (~2x human time) | `finish_task()` tool call |
| Mobile Agent v3 | For loop | 25 | `"Finished" in plan and len(plan) < 15` |
| MiniTap | State machine | `remaining_steps` param | All subgoals SUCCESS or FAILURE |

### Error Escalation Thresholds

| Framework | Threshold | Escalation Target |
|-----------|-----------|-------------------|
| DroidRun | `err_to_manager_thresh = 2` | Manager replanning |
| AutoDev | `MAX_EXECUTOR_STEPS = 10` | Return to Planner |
| Mobile Agent v3 | `err_to_manager_thresh = 2` | Manager replanning |
| MiniTap | Subgoal FAILURE status | Orchestrator → Planner (replan) |

---

## Key Insights

### 1. Cortex vs Executor Separation (MiniTap)
MiniTap uniquely separates **decision-making** (Cortex) from **tool invocation** (Executor). Cortex outputs structured JSON decisions; Executor is a "dumb" translator to tool calls. This prevents the Executor from making strategic mistakes.

### 2. Semantic vs Coordinate Grounding (AutoDev)
AutoDev's Planner never sees coordinates. It issues semantic commands like `tap(intent="click the login button")`. The Executor handles coordinate extraction from screenshots. This keeps the Planner focused on strategy.

### 3. Verification Agent (Mobile Agent v3)
Dedicated ActionReflector with before/after screenshots catches failed actions better than relying on the next planning step to notice. The A/B/C outcome taxonomy provides clear signals:
- **A**: Continue normally
- **B**: Need recovery (wrong page)
- **C**: Action had no effect (retry different approach)

### 4. Stateless Executor Sessions (AutoDev)
Each Planner tool call creates a fresh Executor session (no memory). This forces the Planner to write complete, self-contained instructions—improving robustness but requiring more tokens.

---

## Recommendations for Design

1. **Two-layer minimum**: Always have Planner (WHAT) + Executor (HOW) separation
2. **Add verification**: ActionReflector-style verification catches failures early
3. **Limit Executor steps**: Prevent infinite loops with MAX_STEPS per executor session
4. **Semantic abstraction**: Planner should not deal with coordinates/indices
5. **Structured decisions**: Cortex-style JSON decisions reduce parsing errors
6. **Conditional specialists**: Only invoke Notetaker/Scripter when needed
