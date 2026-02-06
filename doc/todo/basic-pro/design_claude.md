# Basic Mode vs Pro Mode: Agent Execution Strategy Design

> **Author**: Claude  
> **Date**: 2026-02-04  
> **Status**: Draft

---

## Problem Statement

The current two-agent (Planner-Executor) architecture introduces significant latency overhead for simple tasks:

| Task Type | Current Approach | Overhead |
|-----------|-----------------|----------|
| "Tap the Send button" | Planner → delegate_task → Executor → complete_task | 2x LLM calls minimum |
| "Open Gmail" | Planner → app_control → (maybe delegation) | Unnecessary planning |
| "Read the first email and summarize" | Appropriate multi-step delegation | ✓ Good fit |

**Goal**: Provide two execution modes that users can select:
- **Basic Mode**: Single-agent for speed on simple tasks
- **Pro Mode**: Two-agent Planner-Executor for complex multi-step tasks

---

## Design Principles

| Principle | Description |
|-----------|-------------|
| **Mode is User Choice** | User selects mode; no auto-detection initially |
| **Minimal Code Duplication** | Share infrastructure (AgentRuntime, tools, cognition layer) |
| **Cognition Profile Driven** | Mode differences expressed via `CognitionProfile` |
| **Clean Tool Separation** | Each mode has distinct tool availability |
| **Same Agent Loop** | No new loop classes; mode affects config/prompt/tools only |

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                         User Selection                                │
│                     [ Basic Mode ] [ Pro Mode ]                       │
└────────────────────────────┬─────────────────────────────────────────┘
                             │
                             ▼
┌──────────────────────────────────────────────────────────────────────┐
│                        SessionAgentRunner                             │
│  - Reads mode from SessionConfig                                      │
│  - Selects appropriate CognitionProfile                               │
│  - Configures tools based on mode                                     │
└────────────────────────────┬─────────────────────────────────────────┘
                             │
              ┌──────────────┴──────────────┐
              ▼                              ▼
┌─────────────────────────┐    ┌─────────────────────────────────────┐
│      Basic Mode         │    │            Pro Mode                  │
│   Single Agent          │    │     Planner + Executor               │
│                         │    │                                      │
│ Tools:                  │    │ Planner Tools:                       │
│ - mobile_action         │    │ - delegate_task                      │
│ - app_control           │    │ - app_control                        │
│ - scratchpad            │    │ - scratchpad                         │
│ - write_todos           │    │ - write_todos                        │
│ - complete_task         │    │ - complete_task                      │
│                         │    │                                      │
│ Role: STANDALONE        │    │ Executor Tools:                      │
│                         │    │ - mobile_action                      │
│                         │    │ - app_control                        │
│                         │    │ - scratchpad                         │
│                         │    │ - complete_task                      │
└─────────────────────────┘    └─────────────────────────────────────┘
```

---

## Mode Differences

### Tool Availability

| Tool | Basic Mode | Pro Mode (Planner) | Pro Mode (Executor) |
|------|------------|-------------------|---------------------|
| `mobile_action` | ✓ | ✗ | ✓ |
| `app_control` | ✓ | ✓ | ✓ |
| `scratchpad` | ✓ | ✓ | ✓ |
| `write_todos` | ✓ | ✓ | ✗ |
| `delegate_task` | ✗ | ✓ | ✗ |
| `complete_task` | ✓ | ✓ | ✓ |

**Key Difference**: Basic mode agent has direct `mobile_action` access. Pro mode planner must delegate UI actions.

### Prompt Strategy

| Aspect | Basic Mode | Pro Mode |
|--------|------------|----------|
| System Prompt | `BasicAgentPromptTemplate` (NEW) | `PlannerPromptTemplate` + `ExecutorPromptTemplate` |
| Agent Identity | "You are an Android automation agent" | "You are the MAIN PLANNER agent" / "You are an Executor agent" |
| Action Style | Direct execution | Semantic delegation |
| Planning Depth | Lighter (inline) | Explicit (todos for complex multi-step) |

### Cognition Profile

| Parameter | Basic Mode | Pro Mode |
|-----------|------------|----------|
| `promptVariant` | `BASIC` (NEW) | `BASELINE` |
| `delegationEnabled` | `false` | `true` |
| `maxTurns` | 30 | 50 |
| `maxExecutorSteps` | N/A | 5 |
| `loopDetectionEnabled` | `true` | `true` |
| `todoListEnabled` | `true` | `true` |

---

## Implementation Plan

### Phase 1: Data Model Updates

#### 1.1 Add `AgentMode` Enum

**File**: `protocol/SessionConfig.kt`

```kotlin
enum class AgentMode {
    BASIC,   // Single agent with direct UI access
    PRO      // Planner-Executor delegation
}
```

#### 1.2 Update `SessionConfig`

**File**: `protocol/SessionConfig.kt`

```diff
data class SessionConfig(
    val maxTurns: Int = 50,
    val actionDelayMs: Long = 3000,
    val debugMode: Boolean = false,
    val cognitionProfileId: String? = null
+   val agentMode: AgentMode = AgentMode.PRO  // Default to current behavior
)
```

#### 1.3 Update `CognitionProfile`

**File**: `agent/cognition/profile/CognitionProfile.kt`

```diff
data class CognitionProfile(
    val id: String,
    val promptVariant: PromptVariant = PromptVariant.BASELINE,
+   val delegationEnabled: Boolean = true,  // false for Basic mode
    // ... existing fields
)
```

#### 1.4 Add New `PromptVariant`

**File**: `agent/cognition/profile/CognitionProfile.kt`

```diff
enum class PromptVariant {
    BASELINE,
    CONCISE,
+   BASIC     // New variant for single-agent mode
}
```

---

### Phase 2: Prompt Layer

#### 2.1 Create `BasicAgentPromptTemplate`

**File**: `agent/cognition/prompt/BasicAgentPromptTemplate.kt` (NEW)

```kotlin
internal object BasicAgentPromptTemplate {
    val systemPrompt: String = """
        You are an Android automation agent.
        
        ## Your Job
        Execute the user's task by interacting with the Android device.
        You have direct access to UI actions - use them to accomplish goals efficiently.
        
        ## Core Loop
        1. Observe the current screen state (JSON element list)
        2. Decide what action to take
        3. Execute UI action via mobile_action
        4. Observe result and continue until goal achieved
        5. Call complete_task when done
        
        ## Available Tools
        - mobile_action: UI interactions (tap, type, swipe, back, home)
        - app_control: Open apps, list installed apps
        - scratchpad: Store data for later use
        - write_todos: Track progress on multi-step tasks
        - complete_task: Signal task completion
        
        ## Action Guidelines
        
        ### Tapping
        mobile_action(action="click", element_index=N)
        - Use element_index from the screen JSON
        - Prefer resource_id or text over index when available
        
        ### Typing
        mobile_action(action="type", text="...", element_index=N)
        - Target editable fields
        
        ### Scrolling
        mobile_action(action="swipe", direction="up")  // Scroll DOWN
        mobile_action(action="swipe", direction="down") // Scroll UP
        
        ### Navigation
        mobile_action(action="system_button", button="back")
        mobile_action(action="system_button", button="home")
        
        ## Efficiency Tips
        - One action per turn when possible
        - Use scratchpad for multi-step data extraction
        - For complex tasks, use write_todos to track progress
        - Complete promptly when goal is achieved
        
        ## Element Selection Priority
        1. resource_id (most reliable)
        2. text content (for buttons/labels)
        3. element_index (fallback)
        4. coordinates (last resort)
    """.trimIndent()
}
```

#### 2.2 Update `PromptAssembler`

**File**: `agent/cognition/prompt/PromptAssembler.kt`

```diff
fun buildSystemPrompt(role: AgentRole): String {
    return when (role) {
        AgentRole.PLANNER -> {
            when (profile.promptVariant) {
                PromptVariant.BASELINE -> PlannerPromptTemplate.defaultSystemPrompt
                PromptVariant.CONCISE -> PlannerPromptTemplate.conciseSystemPrompt
+               PromptVariant.BASIC -> BasicAgentPromptTemplate.systemPrompt
            }
        }
        AgentRole.EXECUTOR -> ExecutorPromptTemplate.systemPrompt
    }
}
```

---

### Phase 3: Session Layer Changes

#### 3.1 Update `SessionAgentRunner`

**File**: `session/SessionAgentRunner.kt`

```diff
internal class SessionAgentRunner(...) {
    companion object {
        private const val TAG = "SessionAgentRunner"
+       
+       private val BASIC_MODE_TOOLS = setOf(
+           "mobile_action",
+           "app_control",
+           "scratchpad",
+           "write_todos",
+           "complete_task"
+       )
+       
        private val PLANNER_ALLOWED_TOOLS = setOf(
            "app_control",
            "write_todos",
            "scratchpad",
            "delegate_task",
            "complete_task"
        )
    }

    fun start(taskInput: String, taskId: String) {
+       val isProMode = config.agentMode == AgentMode.PRO
+       
+       if (isProMode) {
            ensureDelegationToolRegistered()
+       }
        
        val signal = CompletableDeferred<AgentStopReason>()
        cancellationSignal = signal

        val agentConfig = AgentConfig(
            goal = taskInput,
            sessionId = sessionId,
            taskId = taskId,
-           maxTurns = config.maxTurns,
+           maxTurns = if (isProMode) config.maxTurns else 30,
            uiSettleDelayMs = config.actionDelayMs,
            debugMode = config.debugMode,
-           allowedToolNames = PLANNER_ALLOWED_TOOLS,
+           allowedToolNames = if (isProMode) PLANNER_ALLOWED_TOOLS else BASIC_MODE_TOOLS,
-           cognitionProfileId = config.cognitionProfileId,
+           cognitionProfileId = selectCognitionProfile(isProMode),
            agentId = sessionId.value,
-           agentRole = AgentExecutionRole.PLANNER
+           agentRole = if (isProMode) AgentExecutionRole.PLANNER else AgentExecutionRole.STANDALONE
        )
        // ... rest unchanged
    }
    
+   private fun selectCognitionProfile(isProMode: Boolean): String {
+       // Allow explicit override from config
+       config.cognitionProfileId?.let { return it }
+       
+       return if (isProMode) {
+           BuiltinCognitionProfiles.BASELINE_ID
+       } else {
+           BuiltinCognitionProfiles.BASIC_ID
+       }
+   }
}
```

---

### Phase 4: Cognition Profile Registration

#### 4.1 Add Basic Profile

**File**: `agent/cognition/profile/BuiltinCognitionProfiles.kt`

```diff
object BuiltinCognitionProfiles {
    const val BASELINE_ID: String = "baseline"
    private const val CONCISE_ID: String = "concise"
+   const val BASIC_ID: String = "basic"

+   val basic: CognitionProfile =
+       CognitionProfile(
+           id = BASIC_ID,
+           promptVariant = PromptVariant.BASIC,
+           contextPolicy = ContextPolicy.STANDARD,
+           retryPolicy = RetryPolicy(allowTransientNetworkRetry = true),
+           turnPolicyMode = TurnPolicyMode.PREFER_NON_COMPLETION_SINGLE_TOOL,
+           delegationEnabled = false,
+           maxTurns = 30  // Lower budget for single agent
+       )

    val baseline: CognitionProfile = ...
    val concise: CognitionProfile = ...

-   fun all(): List<CognitionProfile> = listOf(baseline, concise)
+   fun all(): List<CognitionProfile> = listOf(basic, baseline, concise)
}
```

---

### Phase 5: UI Integration

#### 5.1 Add Mode Selector to Chat UI

**File**: `ui/chat/ChatScreen.kt`

Add a toggle or segmented control for mode selection before task submission:

```
┌─────────────────────────────────────────────┐
│  Mode: [Basic ⚡] [Pro 🧠]                   │
├─────────────────────────────────────────────┤
│                                             │
│   Enter your task...                        │
│                                             │
│   [Send]                                    │
└─────────────────────────────────────────────┘
```

**UI Guidelines**:
- Basic: Lightning bolt icon, labeled "Basic ⚡" or "Fast"
- Pro: Brain icon, labeled "Pro 🧠" or "Smart"
- Default: Pro (maintains backward compatibility)
- Persist selection in user preferences

#### 5.2 Update Settings/Preferences

**File**: `settings/UserPreferences.kt`

```kotlin
data class UserPreferences(
    // ... existing
    val defaultAgentMode: AgentMode = AgentMode.PRO
)
```

---

## File Changes Summary

| File | Change Type | Description |
|------|-------------|-------------|
| `protocol/SessionConfig.kt` | MODIFY | Add `AgentMode` enum and field |
| `agent/cognition/profile/CognitionProfile.kt` | MODIFY | Add `delegationEnabled`, `BASIC` variant |
| `agent/cognition/profile/BuiltinCognitionProfiles.kt` | MODIFY | Add `basic` profile |
| `agent/cognition/prompt/BasicAgentPromptTemplate.kt` | NEW | Single-agent prompt |
| `agent/cognition/prompt/PromptAssembler.kt` | MODIFY | Handle `BASIC` variant |
| `session/SessionAgentRunner.kt` | MODIFY | Mode-based tool/config selection |
| `ui/chat/ChatScreen.kt` | MODIFY | Add mode toggle UI |
| `settings/UserPreferences.kt` | MODIFY | Persist mode preference |

---

## Verification Plan

### Unit Tests

1. **Profile Resolution**: `BasicCognitionProfileTest`
   - Verify `BASIC_ID` resolves correctly
   - Verify `delegationEnabled = false` for basic profile

2. **Tool Filtering**: `SessionAgentRunnerTest`
   - Basic mode: `mobile_action` present, `delegate_task` absent
   - Pro mode: `delegate_task` present, `mobile_action` absent (for planner)

3. **Prompt Assembly**: `PromptAssemblerTest`
   - `BASIC` variant returns `BasicAgentPromptTemplate.systemPrompt`

### Integration Tests

1. **Basic Mode E2E**:
   - Simple task: "Tap the Home button"
   - Verify single-agent execution (no delegate_task calls)
   - Verify completion in fewer LLM turns than Pro mode

2. **Pro Mode E2E**:
   - Complex task: "Open Gmail, read first 3 emails, summarize"
   - Verify Planner-Executor delegation flow

### Manual Testing

- UI toggle persists state
- Mode selection reflected in execution logs
- Speed comparison on simple tasks

---

## Migration Notes

> [!IMPORTANT]
> Default behavior is `PRO` mode to maintain backward compatibility.
> Existing users will see no change unless they explicitly select Basic mode.

---

## Future Considerations

### Auto-Select Mode (Not in Scope)

A future enhancement could auto-detect task complexity and suggest/select mode:
- Simple intent detection: single-action keywords → suggest Basic
- Multi-step detection: "and then", "after that" → suggest Pro

Not included in this design to keep scope minimal.

### Hybrid Mode (Not in Scope)

A more advanced mode could start in Basic and escalate to Pro if the agent gets stuck or detects multi-step needs. Deferred for future investigation.

---

## References

- [Agent Overview](../../main/agent/overview.md)
- [Multi-Agent System](../../main/agent/multiagent.md)
- [Agent Loop](../../main/agent/loop.md)
- [Cognition Profiles](../../main/agent/planning.md)
- [Final Multi-Agent Design](../agent_infra_reconcile/final_design_claude.md)
