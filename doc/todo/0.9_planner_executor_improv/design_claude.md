# Planner-Executor Architecture Design

## Overview

This document describes the design for a MAI-UI style Planner-Executor architecture that separates high-level reasoning from low-level UI manipulation using different models optimized for each task.

## Problem Statement

Current architecture uses the same LLM for both planning and execution. This creates tension:

| Concern | Planner Needs | Executor Needs |
|---------|---------------|----------------|
| **Reasoning** | Complex, multi-step | Simple, reactive |
| **Context** | Task goal, progress | Current screen only |
| **Grounding** | Semantic (what to do) | Spatial (where to click) |
| **Model Type** | General VLM (GPT/Claude) | Grounding VLM (UI-Ins, AutoGLM) |

MAI-UI solves this by using separate models:
- **Navigation Agent**: General VLM for task reasoning → outputs action intent
- **Grounding Agent**: Specialized VLM for coordinate prediction → outputs `[x, y]`

## Design Goals

1. **Use the right model for each job** - General VLM for planning, grounding VLM for execution
2. **KISS** - Minimal abstractions, clear data flow
3. **Flexible granularity** - Support both atomic grounding and multi-step executor
4. **No backward compatibility concerns** - Clean slate design

## Architecture

### System Components

```
┌─────────────────────────────────────────────────────────────────┐
│                        Agent Session                            │
│  ┌──────────────────────┐       ┌─────────────────────────────┐ │
│  │    Planner Agent     │       │      Executor Agent         │ │
│  │  (General VLM: GPT)  │       │  (Grounding VLM: UI-Ins)    │ │
│  │                      │       │                             │ │
│  │  Tools:              │       │  Tools:                     │ │
│  │  - delegate_task ────┼──────▶│  - mobile_action            │ │
│  │  - open_app          │       │  - system_button            │ │
│  │  - scratchpad        │       │  - wait                     │ │
│  │  - complete_task     │◀──────┼─ - complete_task            │ │
│  │                      │       │                             │ │
│  └──────────────────────┘       └─────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

### Key Design Decision: Executor Granularity

Based on the analysis in `planner_executor_reference_evidence_analysis.md`, there are multiple executor levels:

| Level | Granularity | Executor Input | Executor Output | Reference |
|-------|-------------|----------------|-----------------|-----------|
| L0 | Grounding only | action + target description | coordinates | MobileWorld |
| L1 | Single action | subgoal text | one atomic action | DroidRun, MobileAgent |
| L2 | Multi-action | decisions list | tool call sequence | minitap |
| L3 | Mini-loop | semantic intent | completion report | AutoDev |

**Recommendation: L1-Grounding Hybrid**

The optimal design combines:
1. **L1 behavior for most cases**: Executor takes one semantic intent → executes one action
2. **Grounding-aware**: Executor uses grounding-capable VLM that understands UI coordinates
3. **Optional fallback loop**: If grounding fails, executor can try 1-2 alternatives

This avoids over-complication while leveraging grounding VLM capabilities.

## Proposed Changes

### 1. Executor Tool Redesign

Current `mobile_action` tool uses semantic selectors (`element_index`, `text`, `resource_id`). For grounding VLM, we add coordinate-first targeting:

```kotlin
// Current: semantic selectors
mobile_action(action="click", element_index=5)

// New: coordinate-first for grounding VLM
mobile_action(action="click", coordinate=[0.5, 0.3])  // Normalized 0-1
```

Schema update for `MobileActionTool`:

```kotlin
data class MobileActionParams(
    val action: String,
    // Coordinate-first (preferred for grounding VLM)
    val coordinate: List<Float>? = null,  // [x, y] normalized 0-1
    // Fallback selectors (kept for compatibility)
    val elementIndex: Int? = null,
    val text: String? = null,
    val resourceId: String? = null,
    // Other params
    val inputText: String? = null,
    val direction: String? = null
)
```

**Selector priority**:
1. `coordinate` (if grounding VLM)
2. `element_index` (if accessibility tree available)
3. `text` or `resource_id` (semantic fallback)

### 2. Executor Agent Definition Update

```kotlin
object ExecutorAgentDef : AgentDef() {
    override val id = "executor"
    override val executionRole = AgentExecutionRole.EXECUTOR
    
    // New: grounding-focused prompt
    override val systemPrompt = """
        You are a UI grounding agent. Given a screenshot and action intent,
        output the exact screen coordinates to interact with.
        
        ## Input
        You receive:
        - A screenshot of the current screen
        - An action intent (e.g., "tap the search button", "scroll down")
        
        ## Output
        For targeting actions (click, long_press), predict coordinates:
        - mobile_action(action="click", coordinate=[x, y])
        - Coordinates are normalized 0-1 (top-left = [0,0], bottom-right = [1,1])
        
        For non-targeting actions, just execute:
        - mobile_action(action="swipe", direction="up")
        - system_button(button="back")
        
        ## Rules
        1. Analyze the screenshot to find the target element
        2. Predict the CENTER coordinates of the target
        3. Call complete_task after ONE action
        """.trimIndent()
    
    override val allowedTools = setOf(
        "mobile_action",
        "system_button",
        "wait", 
        "complete_task"
    )
}
```

### 3. Delegation Flow

```kotlin
// Planner calls delegate_task with semantic intent
delegate_task(
    agent_name = "executor",
    query = "Tap the 'Inbox' tab at the bottom of the screen"
)

// Executor (grounding VLM) receives:
// - Screenshot of current screen
// - Query: "Tap the 'Inbox' tab at the bottom of the screen"

// Executor outputs:
mobile_action(
    action = "click",
    coordinate = [0.2, 0.95]  // Grounded coordinates
)

// Result returned to planner:
// "Tapped 'Inbox' tab at coordinates [0.2, 0.95]"
```

### 4. What Actions Need Grounding?

| Action | Needs Grounding | Reason |
|--------|-----------------|--------|
| `click` | Yes | Target specific element |
| `long_press` | Yes | Target specific element |
| `type` | Maybe | May need to tap field first |
| `swipe` | No | Direction-based, no target |
| `system_button` | No | Fixed system buttons |
| `wait` | No | No interaction |
| `open_app` | No | Uses app launcher API |

**Design**: Planner decides which actions need grounding. Non-grounding actions (swipe, back, wait) can be executed directly by planner OR delegated.

**Simpler approach**: Always delegate UI actions to executor, let executor handle trivially:

```kotlin
// Planner
delegate_task(query = "Press the back button")

// Executor (trivial - no grounding needed)
system_button(button = "back")
complete_task(status = "success", answer = "Pressed back")
```

This keeps the planner "hands-off" and executor as the single point of UI interaction.

### 5. SubAgentRunner Updates

Minimal changes needed - just connect to the correct LLM:

```kotlin
class IsolatedSubAgentRunner(...) {
    override suspend fun run(request: SubAgentRequest): SubAgentResult {
        // ... existing setup ...
        
        val childServices = parentServices.copy(
            // New: Use executor-specific LLM client
            llmRegistry = parentServices.llmRegistry,  // Shared registry
            // ... rest unchanged ...
        )
        
        val childAgent = Agent(
            config = AgentExecutionConfig(
                // ... existing config ...
                agentRole = definition.executionRole ?: AgentExecutionRole.EXECUTOR
                // AgentTurnRunner will pick the right LLM based on role
            ),
            // ...
        )
    }
}
```

### 6. Screen State for Executor

Grounding VLMs work best with screenshots, not accessibility trees. Update perception for executor:

```kotlin
// In AgentTurnRunner
private fun captureScreenState(): ScreenSnapshot {
    val role = config.agentRole
    
    return when (role) {
        AgentExecutionRole.EXECUTOR -> {
            // Grounding VLM: screenshot-primary
            ScreenSnapshot(
                screenshot = captureScreenshot(),
                accessibilityTree = null  // Or minimal subset
            )
        }
        else -> {
            // Planner: accessibility tree + optional screenshot
            ScreenSnapshot(
                accessibilityTree = captureAccessibilityTree(),
                screenshot = if (config.includeScreenshot) captureScreenshot() else null
            )
        }
    }
}
```

## File Changes Summary

| Action | File | Description |
|--------|------|-------------|
| **Dependency** | `doc/todo/0.5_multi_llms/` | Multi-LLM infrastructure (see separate doc) |
| MODIFY | `tool/impl/MobileActionTool.kt` | Add `coordinate` parameter with normalization |
| MODIFY | `agent/definition/ExecutorAgentDef.kt` | Update prompt for grounding focus |
| MODIFY | `agent/AgentTurnRunner.kt` | Select LLM based on role, adjust screen capture |
| MODIFY | `agent/subagent/SubAgentRunner.kt` | Pass LLM registry to child agent |
| MODIFY | `perception/ScreenSnapshot.kt` | Support screenshot-only mode |

## Migration Path

1. **Phase 1**: Implement multi-LLM infrastructure 
2. **Phase 2**: Add coordinate support to `mobile_action`
3. **Phase 3**: Update executor prompt for grounding focus
4. **Phase 4**: Adjust screen capture per agent role
5. **Phase 5**: Test with grounding VLM (UI-Ins or similar)

## Verification

### Manual Testing
1. Run agent with planner-only config (current behavior) - verify no regression
2. Run agent with separate grounding model on localhost:8080 - verify delegation works
3. Test coordinate-based clicks match expected UI elements

### What We Cannot Easily Test
- Quality of grounding (depends on chosen grounding VLM)
- Latency tradeoffs (two model calls vs one)

## Open Questions for User

1. **Grounding model availability**: Do you have access to a grounding VLM like UI-Ins? Or should we design for future integration?

2. **Planner execution of simple actions**: Should planner be able to directly execute `system_button(back)` without delegation? Or always delegate for consistency?

3. **Accessibility tree for executor**: Should executor receive accessibility tree as supplementary info, or screenshot-only for pure visual grounding?

---

*Depends on: `doc/todo/0.5_multi_llms/multi_llm_design.md`*
