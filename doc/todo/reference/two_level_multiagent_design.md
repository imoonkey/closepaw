# Two-Level Multi-Agent Design: Planner + Executor

> **Author**: Claude  
> **Date**: 2026-02-03  
> **Base Architecture**: AutoDev (Google Research) + best patterns from DroidRun, Mobile Agent v3, MiniTap  
> **Target Codebase**: AndroidAgent Kotlin/Compose

---

## Executive Summary

This document presents a **two-level multi-agent architecture** for AndroidAgent, consisting of:

1. **Planner Agent**: High-level strategic reasoning, task decomposition, and progress tracking
2. **Executor Agent**: Low-level UI grounding, action execution, and result observation

The design draws primarily from **AutoDev's Planner-Executor pattern** while incorporating:
- **Mobile Agent v3's ActionReflector** for verification
- **MiniTap's structured subgoals** for reliable progress tracking
- **DroidRun's memory patterns** for cross-step context

---

## Table of Contents

1. [Design Principles](#1-design-principles)
2. [Architecture Overview](#2-architecture-overview)
3. [Shared State Design](#3-shared-state-design)
4. [Planner Agent Design](#4-planner-agent-design)
5. [Executor Agent Design](#5-executor-agent-design)
6. [Inter-Agent Communication](#6-inter-agent-communication)
7. [Protocol Extensions](#7-protocol-extensions)
8. [Implementation Details](#8-implementation-details)
9. [Integration with Existing Code](#9-integration-with-existing-code)
10. [Verification & Error Recovery](#10-verification--error-recovery)

---

## 1. Design Principles

| Principle | Rationale | Source |
|-----------|-----------|--------|
| **Semantic Abstraction** | Planner focuses on WHAT (intent), Executor on HOW (coordinates) | AutoDev |
| **Stateless Executor Sessions** | Each Planner instruction spawns fresh Executor session, forcing complete instructions | AutoDev |
| **Structured Subgoals** | Machine-parseable subgoal objects enable reliable progress tracking | MiniTap |
| **Centralized Shared State** | Single `AgentState` reduces sync bugs, all agents read/write same object | DroidRun, Mobile Agent v3 |
| **Agent-Specific Message History** | Executor has private message history, Planner sees summaries | MiniTap |
| **Before/After Verification** | Capture screen before and after action for outcome detection | Mobile Agent v3 |
| **Failure Context Propagation** | Show recent errors to Planner to enable strategy change | All |
| **Bounded Executor Steps** | Prevent infinite loops with MAX_EXECUTOR_STEPS per session | AutoDev |

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            AgentSession                                      │
│                                                                              │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │                        Shared AgentState                             │   │
│   │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐    │   │
│   │  │   Goal     │  │  Subgoals  │  │  Memory    │  │   Screen   │    │   │
│   │  └────────────┘  └────────────┘  └────────────┘  └────────────┘    │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                         │
│         ┌──────────────────────────┼──────────────────────────┐             │
│         │                          │                          │             │
│         ▼                          ▼                          ▼             │
│   ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐        │
│   │  Planner Agent  │    │ Executor Agent  │    │   Verifier      │        │
│   │                 │    │   (per call)    │    │   (optional)    │        │
│   │  - Decompose    │    │  - Ground       │    │  - Before/After │        │
│   │  - Track        │    │  - Execute      │    │  - A/B/C Outcome│        │
│   │  - Replan       │    │  - Observe      │    │                 │        │
│   └────────┬────────┘    └────────┬────────┘    └────────┬────────┘        │
│            │                      │                      │                  │
│            │ PlannerInstruction   │ ExecutorReport       │ VerifyResult     │
│            └──────────────────────┼──────────────────────┘                  │
│                                   │                                         │
│                                   ▼                                         │
│         ┌─────────────────────────────────────────────────────────┐        │
│         │                    Tool Router                           │        │
│         │   mobile_action | app_control | complete_task            │        │
│         └─────────────────────────────────────────────────────────┘        │
│                                   │                                         │
│                                   ▼                                         │
│         ┌─────────────────────────────────────────────────────────┐        │
│         │                 Android Platform                         │        │
│         └─────────────────────────────────────────────────────────┘        │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Control Flow

```
User Goal
    │
    ▼
┌──────────────────────────────────────────────────────────────┐
│                    PLANNER LOOP                               │
│                                                               │
│   1. Perceive (read AgentState.screenSnapshot)               │
│   2. Plan/Replan (if needed, create/update subgoals)         │
│   3. Issue PlannerInstruction for current subgoal            │
│   4. Wait for ExecutorReport                                  │
│   5. Update subgoal status based on report                   │
│   6. If all subgoals COMPLETE → finish_task                  │
│   7. If consecutive failures → replan                        │
│   8. Loop to step 1                                          │
│                                                               │
└──────────────────────────────────────────────────────────────┘
    │
    │ PlannerInstruction (semantic intent)
    ▼
┌──────────────────────────────────────────────────────────────┐
│                    EXECUTOR SESSION                           │
│                                                               │
│   1. Receive query (complete, self-contained instruction)    │
│   2. Perceive (fresh screenshot + UI tree)                   │
│   3. Ground intent to specific element/coordinates           │
│   4. Execute action(s) via ToolRouter                        │
│   5. Observe result (post-action screen)                     │
│   6. Verify (optional ActionReflector)                       │
│   7. Return ExecutorReport to Planner                        │
│                                                               │
│   MAX_EXECUTOR_STEPS = 10 (prevents infinite loops)          │
│                                                               │
└──────────────────────────────────────────────────────────────┘
```

---

> **📚 See Also**: [note_loop_patterns.md](./note_loop_patterns.md) for detailed analysis of how AutoDev, DroidRun, and MiniTap implement their Planner-Executor loops.

---

## 3. Shared State Design

### 3.1 AgentState Structure

**File**: `agent/state/AgentState.kt`

```kotlin
/**
 * Centralized state shared between Planner and Executor.
 * 
 * Design rationale:
 * - Mutable dataclass for simplicity (like Mobile Agent v3's InfoPool)
 * - Single source of truth reduces sync bugs
 * - Transient fields cleared per step, persistent fields accumulate
 */
data class AgentState(
    // === TASK CONTEXT (immutable after start) ===
    val sessionId: SessionId,
    val goal: String,
    val startTime: Long = System.currentTimeMillis(),
    
    // === SUBGOAL TRACKING (Planner owns) ===
    val subgoals: MutableList<Subgoal> = mutableListOf(),
    var currentSubgoalIndex: Int = -1,  // -1 = not started
    
    // === SCREEN STATE (refreshed each step) ===
    var screenSnapshot: ScreenSnapshot? = null,
    var screenshotBefore: ScreenSnapshot? = null,  // For verification
    
    // === MEMORY (persistent, append-only) ===
    val scratchpad: MutableMap<String, String> = mutableMapOf(),
    val importantNotes: MutableList<String> = mutableListOf(),
    
    // === ACTION HISTORY (append-only) ===
    val actionHistory: MutableList<ActionRecord> = mutableListOf(),
    val executorReports: MutableList<ExecutorReport> = mutableListOf(),
    
    // === ERROR TRACKING ===
    var consecutiveFailures: Int = 0,
    var errorFlagReplan: Boolean = false,
    val recentErrors: MutableList<ErrorRecord> = mutableListOf(),
    
    // === METRICS ===
    var plannerTurnCount: Int = 0,
    var executorStepCount: Int = 0,
    var totalTokensUsed: Long = 0
) {
    companion object {
        const val ERR_TO_REPLAN_THRESH = 2  // Consecutive failures before replanning
        const val MAX_RECENT_ERRORS = 5     // Keep last N errors for context
    }
    
    fun getCurrentSubgoal(): Subgoal? = 
        subgoals.getOrNull(currentSubgoalIndex)
    
    fun getNextPendingSubgoal(): Subgoal? =
        subgoals.firstOrNull { it.status == SubgoalStatus.NOT_STARTED || it.status == SubgoalStatus.PENDING }
    
    fun recordError(error: ErrorRecord) {
        recentErrors.add(error)
        if (recentErrors.size > MAX_RECENT_ERRORS) {
            recentErrors.removeAt(0)
        }
        consecutiveFailures++
        if (consecutiveFailures >= ERR_TO_REPLAN_THRESH) {
            errorFlagReplan = true
        }
    }
    
    fun clearErrorFlag() {
        consecutiveFailures = 0
        errorFlagReplan = false
    }
}
```

### 3.2 Subgoal Structure

**File**: `agent/state/Subgoal.kt`

```kotlin
/**
 * Structured subgoal for reliable progress tracking.
 * 
 * Design inspired by MiniTap's Pydantic Subgoal model.
 * Machine-parseable status enables automated state management.
 */
data class Subgoal(
    val id: String,                           // Unique identifier (e.g., "sg-1")
    val description: String,                  // Human-readable goal
    var status: SubgoalStatus = SubgoalStatus.NOT_STARTED,
    var completionReason: String? = null,     // Why completed/failed
    var startedAt: Long? = null,
    var endedAt: Long? = null,
    val isVerification: Boolean = false       // Is this a validation step?
)

enum class SubgoalStatus {
    NOT_STARTED,   // In queue
    PENDING,       // Currently working on
    SUCCESS,       // Completed successfully
    FAILURE,       // Failed (triggers replanning)
    SKIPPED        // Skipped due to prior failure
}
```

### 3.3 Supporting Types

```kotlin
/**
 * Records an executed action for history.
 */
data class ActionRecord(
    val turnId: String,
    val toolName: String,
    val params: Map<String, Any?>,
    val description: String,
    val success: Boolean,
    val resultSummary: String?,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Report from Executor back to Planner.
 * 
 * Key insight from AutoDev: Executor provides narrative summary,
 * not raw tool call logs, enabling better Planner recovery.
 */
data class ExecutorReport(
    val instructionId: String,
    val query: String,                    // Original Planner instruction
    val success: Boolean,
    val narrativeSummary: String,         // Human-readable result
    val stepsExecuted: Int,
    val extractedData: Map<String, Any?>? = null,  // For scan_for_element
    val failureReason: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Error record for failure context.
 */
data class ErrorRecord(
    val turnId: String,
    val action: String,
    val outcome: String,
    val feedback: String,
    val timestamp: Long = System.currentTimeMillis()
)
```

### 3.4 State Access Patterns

| Agent | Reads | Writes |
|-------|-------|--------|
| **Planner** | goal, subgoals, screenSnapshot, scratchpad, importantNotes, executorReports, recentErrors, errorFlagReplan | subgoals, currentSubgoalIndex, importantNotes, errorFlagReplan |
| **Executor** | (fresh session): query, screenSnapshot | actionHistory, scratchpad (via tools) |
| **Verifier** | screenshotBefore, screenSnapshot | actionHistory (outcome field) |

---

## 4. Planner Agent Design

### 4.1 Responsibilities

1. **Goal Decomposition**: Break user goal into ordered subgoals
2. **Progress Tracking**: Manage subgoal lifecycle (NOT_STARTED → PENDING → SUCCESS/FAILURE)
3. **Instruction Issuance**: Generate semantic instructions for Executor
4. **Memory Management**: Store/retrieve cross-step information
5. **Replanning**: Revise strategy when consecutive failures occur
6. **Completion Detection**: Determine when goal is achieved

### 4.2 Planner Tools

**File**: `agent/planner/PlannerTools.kt`

```kotlin
/**
 * Semantic-level tools for Planner.
 * 
 * Design: Following AutoDev pattern, Planner issues intent-based commands.
 * Coordinates/indices are Executor's responsibility.
 */

// === EXECUTOR-ROUTED TOOLS (spawn Executor session) ===

/**
 * Semantic tap: Planner describes WHAT to tap, Executor finds WHERE.
 */
data class TapIntent(
    val intent: String    // e.g., "tap on the login button"
)

/**
 * Semantic scroll: Planner describes WHAT to find, Executor scrolls.
 */
data class ScrollIntent(
    val intent: String,   // e.g., "scroll down to find signup link"
    val direction: String // "up" | "down" | "left" | "right"
)

/**
 * Semantic type: Planner provides text and target description.
 */
data class TypeTextIntent(
    val text: String,
    val intent: String    // e.g., "type in the email input field"
)

/**
 * Scan for element(s): Used for counting, searching, extracting data.
 */
data class ScanForElementIntent(
    val intent: String    // e.g., "find all contact names on this screen"
)

// === DIRECT PLANNER TOOLS (no Executor) ===

/**
 * Open app: Direct execution, no grounding needed.
 */
data class OpenAppDirect(
    val appName: String
)

/**
 * Go back: Direct execution.
 */
object GoBackDirect

/**
 * Update subgoals: Planner manages its own plan.
 */
data class UpdateSubgoals(
    val subgoals: List<SubgoalUpdate>
)

data class SubgoalUpdate(
    val id: String,
    val description: String,
    val status: SubgoalStatus
)

/**
 * Scratchpad operations: Cross-step data storage.
 */
data class CreateItem(
    val key: String,
    val title: String,
    val text: String
)

data class FetchItem(
    val key: String
)

/**
 * Complete task: Signal goal achievement/failure.
 */
data class FinishTask(
    val success: Boolean,
    val answer: String? = null,
    val reason: String? = null
)
```

### 4.3 Planner Prompt Structure

**File**: `agent/planner/PlannerPromptBuilder.kt`

```kotlin
object PlannerPromptBuilder {
    
    fun buildSystemPrompt(state: AgentState): String = """
        You are a strategic planner for an Android automation agent.
        
        ## Your Role
        - Decompose the user's goal into clear, achievable subgoals
        - Issue semantic instructions to the Executor (WHAT to do, not HOW)
        - Track progress and update subgoals as work completes
        - Replan when strategies fail
        
        ## Key Rules
        
        1. **Semantic Abstraction**: Never specify coordinates or element indices.
           - ✅ tap(intent="click the login button")
           - ❌ tap(x=540, y=800) or tap(element_index=3)
        
        2. **Self-Contained Instructions**: Executor has NO memory between calls.
           Each instruction must be complete and context-free.
        
        3. **Subgoal Granularity**: Subgoals should be clear checkpoints, not atomic actions.
           - ✅ "Log in to the email app"
           - ❌ "Click the email field"
        
        4. **Verification Subgoals**: For critical tasks, add verification steps:
           - "Verify the email was sent successfully"
           - "Confirm WiFi shows as disabled"
        
        5. **No Loops in Plans**: Unroll repetitive tasks:
           - ❌ "Repeat step 2 three times"
           - ✅ ["Add item 1", "Add item 2", "Add item 3"]
        
        6. **Scratchpad for Multi-Item Tasks**:
           - Extract all items first → createItem to store
           - Navigate to destination
           - fetchItem to retrieve → process one by one
        
        ## Workflow
        ANALYZE → PLAN → EXECUTE (via Executor) → VERIFY → UPDATE → REPEAT
        
        ## Replanning
        When errorFlagReplan is true:
        - Review recent errors in context
        - Preserve completed subgoals (don't redo)
        - Change strategy, don't repeat failed approach
        
        ## Current State
        ${buildStateContext(state)}
    """.trimIndent()
    
    private fun buildStateContext(state: AgentState): String {
        val subgoalContext = state.subgoals.joinToString("\n") { sg ->
            "- [${sg.status}] ${sg.id}: ${sg.description}" +
                (sg.completionReason?.let { " ($it)" } ?: "")
        }
        
        val recentErrors = if (state.errorFlagReplan) {
            """
            ### ⚠️ POTENTIALLY STUCK - REPLANNING REQUIRED
            Recent failures:
            ${state.recentErrors.takeLast(3).joinToString("\n") { 
                "- ${it.action}: ${it.outcome} - ${it.feedback}" 
            }}
            """.trimIndent()
        } else ""
        
        return """
            ### Goal
            ${state.goal}
            
            ### Current Subgoals
            $subgoalContext
            
            ### Current Subgoal
            ${state.getCurrentSubgoal()?.description ?: "(none active)"}
            
            ### Latest Executor Report
            ${state.executorReports.lastOrNull()?.let {
                "Query: ${it.query}\nResult: ${it.narrativeSummary}\nSuccess: ${it.success}"
            } ?: "(no reports yet)"}
            
            ### Important Notes
            ${state.importantNotes.takeLast(5).joinToString("\n") { "- $it" }}
            
            ### Scratchpad Keys
            ${state.scratchpad.keys.joinToString(", ")}
            
            $recentErrors
        """.trimIndent()
    }
}
```

### 4.4 Planner Loop

**File**: `agent/planner/PlannerLoop.kt`

```kotlin
class PlannerLoop(
    private val config: AgentConfig,
    private val state: AgentState,
    private val executorFactory: ExecutorFactory,
    private val llmClient: LLMClient,
    private val eventEmitter: suspend (AgentEvent) -> Unit
) {
    companion object {
        const val MAX_PLANNER_TURNS = 30
    }
    
    suspend fun run(): AgentStopReason {
        var turnCount = 0
        
        while (turnCount < MAX_PLANNER_TURNS) {
            turnCount++
            state.plannerTurnCount = turnCount
            
            // 1. Capture current screen
            state.screenSnapshot = captureScreen()
            
            // 2. Check if replanning needed
            if (state.errorFlagReplan) {
                emitEvent(PlannerReplanning(reason = "Consecutive failures"))
            }
            
            // 3. Run Planner LLM turn
            val plannerResult = runPlannerTurn()
            
            // 4. Process Planner output
            when (val action = plannerResult.action) {
                is FinishTask -> {
                    return if (action.success) {
                        AgentStopReason.GoalAchieved
                    } else {
                        AgentStopReason.TaskImpossible(action.reason ?: "Unknown")
                    }
                }
                
                is UpdateSubgoals -> {
                    updateSubgoals(action.subgoals)
                    advanceToNextSubgoal()
                }
                
                is OpenAppDirect -> {
                    executeDirectAction(action)
                }
                
                is ExecutorInstruction -> {
                    // Spawn Executor session
                    val report = executeWithExecutor(action)
                    state.executorReports.add(report)
                    
                    // Update subgoal based on result
                    handleExecutorReport(report)
                }
                
                is CreateItem -> {
                    state.scratchpad[action.key] = action.text
                }
                
                is FetchItem -> {
                    // Result provided to next Planner turn via context
                }
            }
            
            // 5. Check if all subgoals complete
            if (allSubgoalsComplete()) {
                return AgentStopReason.GoalAchieved
            }
        }
        
        return AgentStopReason.MaxTurnsReached
    }
    
    private suspend fun executeWithExecutor(instruction: ExecutorInstruction): ExecutorReport {
        // Capture before screenshot for verification
        state.screenshotBefore = state.screenSnapshot
        
        // Create fresh Executor session
        val executor = executorFactory.create(
            query = instruction.toQuery(),
            maxSteps = ExecutorSession.MAX_EXECUTOR_STEPS
        )
        
        // Run Executor
        val report = executor.run()
        
        // Capture after screenshot
        state.screenSnapshot = captureScreen()
        
        // Verify outcome (optional)
        val verifiedReport = verifyAction(
            before = state.screenshotBefore!!,
            after = state.screenSnapshot!!,
            report = report
        )
        
        return verifiedReport
    }
    
    private fun handleExecutorReport(report: ExecutorReport) {
        val currentSubgoal = state.getCurrentSubgoal() ?: return
        
        if (report.success) {
            currentSubgoal.status = SubgoalStatus.SUCCESS
            currentSubgoal.completionReason = report.narrativeSummary
            currentSubgoal.endedAt = System.currentTimeMillis()
            state.clearErrorFlag()
            advanceToNextSubgoal()
        } else {
            state.recordError(ErrorRecord(
                turnId = "turn-${state.plannerTurnCount}",
                action = report.query,
                outcome = "FAILURE",
                feedback = report.failureReason ?: report.narrativeSummary
            ))
            
            // Don't mark subgoal as FAILURE yet - allow retries
            // Only mark FAILURE after replanning attempts
        }
    }
    
    private fun advanceToNextSubgoal() {
        val next = state.getNextPendingSubgoal()
        if (next != null) {
            state.currentSubgoalIndex = state.subgoals.indexOf(next)
            next.status = SubgoalStatus.PENDING
            next.startedAt = System.currentTimeMillis()
        }
    }
}
```

---

## 5. Executor Agent Design

### 5.1 Responsibilities

1. **Grounding**: Convert semantic intent to specific UI elements/coordinates
2. **Execution**: Invoke low-level tools (click, type, swipe)
3. **Observation**: Capture post-action screen state
4. **Reporting**: Provide narrative summary to Planner

### 5.2 Executor Tools

**File**: `agent/executor/ExecutorTools.kt`

```kotlin
/**
 * Low-level action tools for Executor.
 * 
 * These map directly to existing AndroidAgent tools.
 * Executor has access to element indices and coordinates.
 */

// Existing tools from ToolRegistry
val EXECUTOR_TOOLS = listOf(
    "mobile_action",   // click, type, swipe, long_press, system_button, wait
    "app_control",     // list_apps, open_app
    // Note: complete_task is Planner-only
)

/**
 * Report tool: Executor's way to return to Planner.
 */
data class Report(
    val notes: String,            // Narrative summary of what happened
    val success: Boolean = true,
    val extractedData: Map<String, Any?>? = null
)

/**
 * Transcribe screen: OCR for text extraction.
 */
object TranscribeScreen
```

### 5.3 Executor Session

**File**: `agent/executor/ExecutorSession.kt`

```kotlin
/**
 * Stateless Executor session.
 * 
 * Key design decision (from AutoDev):
 * - Executor has NO memory of previous sessions
 * - Each session starts fresh with only the query
 * - Forces Planner to write complete, self-contained instructions
 */
class ExecutorSession(
    private val query: String,
    private val services: SessionServices,
    private val llmClient: LLMClient,
    private val eventEmitter: suspend (AgentEvent) -> Unit
) {
    companion object {
        const val MAX_EXECUTOR_STEPS = 10
        const val TAG = "ExecutorSession"
    }
    
    // Session-local state (not shared with Planner)
    private val executorHistory = mutableListOf<ResponseItem>()
    private var stepCount = 0
    
    suspend fun run(): ExecutorReport {
        Log.i(TAG, "Starting Executor session: $query")
        
        // Initial perception
        var currentScreen = captureScreen()
        
        while (stepCount < MAX_EXECUTOR_STEPS) {
            stepCount++
            
            // Build Executor prompt
            val prompt = buildExecutorPrompt(currentScreen)
            
            // Run LLM turn
            val result = runExecutorTurn(prompt)
            
            // Process result
            when (val action = result.action) {
                is Report -> {
                    return ExecutorReport(
                        instructionId = generateId(),
                        query = query,
                        success = action.success,
                        narrativeSummary = action.notes,
                        stepsExecuted = stepCount,
                        extractedData = action.extractedData
                    )
                }
                
                is ToolCallRequest -> {
                    // Execute tool
                    val toolResult = executeToolCall(action)
                    
                    // Add to history
                    executorHistory.add(
                        ResponseItem.ToolResult(
                            callId = action.id,
                            output = toolResult.observation ?: ""
                        )
                    )
                    
                    // Refresh screen
                    currentScreen = captureScreen()
                }
            }
        }
        
        // Max steps reached without explicit report
        return ExecutorReport(
            instructionId = generateId(),
            query = query,
            success = false,
            narrativeSummary = "Executor reached MAX_EXECUTOR_STEPS ($MAX_EXECUTOR_STEPS) without completing",
            stepsExecuted = stepCount,
            failureReason = "Max steps exceeded"
        )
    }
    
    private fun buildExecutorPrompt(screen: ScreenSnapshot): String = """
        You are an Executor agent for Android automation.
        
        ## Your Query
        $query
        
        ## Key Rules
        
        1. **First Turn**: Read the query carefully. It contains your complete objective.
           Remember it for the entire session.
        
        2. **Grounding**: Convert the semantic intent to specific actions:
           - Find the exact element/coordinates to interact with
           - Use element indices from the UI tree when available
           - Fall back to coordinates if needed
        
        3. **Scroll Detection**: If scrolling:
           - Compare content before and after
           - If content unchanged after 3 scrolls → stop and report
        
        4. **Report When Done**: Call report() when:
           - You've completed the query's objective
           - You've determined the objective is impossible
           - You need to return extracted data
        
        5. **Narrative Report**: Provide human-readable summary:
           - ✅ "Found login button and clicked it. Now showing home screen."
           - ❌ Raw tool call logs
        
        ## Current Screen
        ${screen.toPromptJson()}
        
        ## Actions Available
        - mobile_action: click, type, swipe, long_press, system_button, wait
        - app_control: list_apps, open_app
        - report: Return to Planner with narrative summary
    """.trimIndent()
}
```

### 5.4 Executor Prompt Emphasis

```kotlin
/**
 * Additional Executor prompt rules (from AutoDev analysis).
 */
val EXECUTOR_ADDITIONAL_RULES = """
    ## Forced Patterns
    
    1. **Use transcribe_screen() liberally**:
       - Before and after scrolling
       - When stuck or confused
       - To extract text content
    
    2. **Loop Detection**:
       - If you see the same screen content after action → STOP
       - Report the issue to Planner
    
    3. **Multi-Item Queries**:
       - For counting: Report ALL items found
       - For searching: Report if found or not, with details
    
    4. **Failure Reporting**:
       - Explain WHY something failed, not just that it failed
       - "Could not find login button. Screen shows a popup blocking the view."
       - NOT: "Failed to tap"
""".trimIndent()
```

---

## 6. Inter-Agent Communication

### 6.1 Planner → Executor

**PlannerInstruction**: Semantic, self-contained query string.

```kotlin
/**
 * Instruction from Planner to Executor.
 * 
 * Must be COMPLETELY self-contained. Executor has no context.
 */
data class PlannerInstruction(
    val id: String,
    val query: String,              // Complete, context-free instruction
    val relatedSubgoalId: String?,  // For tracking
    val hints: Map<String, String> = emptyMap()  // Optional context hints
)

// Example instructions:
// ✅ "Open the Chrome browser and navigate to google.com. Then search for 'Tokyo weather'."
// ✅ "Find the Settings icon in the app drawer and tap on it."
// ❌ "Continue with step 3"  (no context)
// ❌ "Tap the button I mentioned earlier"  (no context)
```

### 6.2 Executor → Planner

**ExecutorReport**: Narrative summary with structured success/failure.

```kotlin
/**
 * Report from Executor to Planner.
 * 
 * Key insight: Narrative summary helps Planner understand
 * WHAT happened, not just THAT it failed.
 */
data class ExecutorReport(
    val instructionId: String,
    val query: String,
    val success: Boolean,
    val narrativeSummary: String,    // Human-readable result
    val stepsExecuted: Int,
    val extractedData: Map<String, Any?>? = null,
    val failureReason: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

// Example reports:
// Success: "Opened Chrome and navigated to google.com. Search box is now visible."
// Failure: "Could not find the search box. A cookie consent popup is blocking the view."
```

### 6.3 Communication Flow Diagram

```
┌─────────────┐                              ┌─────────────┐
│   Planner   │                              │  Executor   │
└─────┬───────┘                              └─────┬───────┘
      │                                            │
      │ PlannerInstruction                         │
      │ ───────────────────────────────────────►   │
      │ "Open Chrome and search for Tokyo weather" │
      │                                            │
      │                                            │ Execute actions...
      │                                            │ (mobile_action, etc.)
      │                                            │
      │                           ExecutorReport   │
      │   ◄─────────────────────────────────────── │
      │ "Opened Chrome, searched for weather.      │
      │  Results showing: Tokyo is 22°C sunny."    │
      │                                            │
      │ Update subgoal → SUCCESS                   │
      │ Advance to next subgoal                    │
      │                                            │
      │ PlannerInstruction                         │
      │ ───────────────────────────────────────►   │
      │ "Note the current temperature shown"       │
      │                                            │
```

---

## 7. Protocol Extensions

### 7.1 New AgentEvent Types

**File**: `protocol/AgentEvent.kt`

```kotlin
// === Planner Events ===

/**
 * Emitted when Planner creates or updates subgoals.
 */
data class SubgoalsUpdated(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val subgoals: List<SubgoalInfo>
) : AgentEvent

data class SubgoalInfo(
    val id: String,
    val description: String,
    val status: String
)

/**
 * Emitted when current subgoal changes.
 */
data class SubgoalStarted(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val subgoalId: String,
    val description: String
) : AgentEvent

/**
 * Emitted when a subgoal completes.
 */
data class SubgoalCompleted(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val subgoalId: String,
    val success: Boolean,
    val reason: String?
) : AgentEvent

/**
 * Emitted when Planner starts replanning due to failures.
 */
data class PlannerReplanning(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val reason: String,
    val previousFailures: Int
) : AgentEvent

// === Executor Events ===

/**
 * Emitted when Executor session starts.
 */
data class ExecutorSessionStarted(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val executorId: String,
    val query: String
) : AgentEvent

/**
 * Emitted when Executor completes.
 */
data class ExecutorSessionCompleted(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val executorId: String,
    val success: Boolean,
    val summary: String,
    val stepsExecuted: Int
) : AgentEvent
```

### 7.2 Extended Op Types

```kotlin
// No new Op types needed - existing Op.UserInput, Op.Pause, etc. work unchanged.
// Planner/Executor communication is internal and doesn't need user Ops.
```

---

## 8. Implementation Details

### 8.1 File Structure

```
app/src/main/kotlin/com/moonkey/androidagent/
├── agent/
│   ├── Agent.kt                      # Existing (minor refactor)
│   ├── AgentRuntime.kt               # Existing (route to PlannerLoop)
│   │
│   ├── state/                        # NEW: Shared state
│   │   ├── AgentState.kt             # Centralized state
│   │   ├── Subgoal.kt                # Subgoal model
│   │   ├── ActionRecord.kt           # Action history
│   │   └── ExecutorReport.kt         # Executor reports
│   │
│   ├── planner/                      # NEW: Planner agent
│   │   ├── PlannerLoop.kt            # Main planner loop
│   │   ├── PlannerPromptBuilder.kt   # Prompt construction
│   │   ├── PlannerTools.kt           # Semantic tools
│   │   └── SubgoalManager.kt         # Subgoal lifecycle
│   │
│   └── executor/                     # NEW: Executor agent
│       ├── ExecutorSession.kt        # Stateless executor
│       ├── ExecutorPromptBuilder.kt  # Prompt construction
│       └── ExecutorFactory.kt        # Creates executor sessions
│
├── protocol/
│   ├── AgentEvent.kt                 # Extended with new events
│   └── ...
│
└── ...
```

### 8.2 Configuration Extensions

**File**: `agent/AgentConfig.kt`

```kotlin
data class AgentConfig(
    // Existing fields...
    
    // === Multi-Agent Config ===
    val agentMode: AgentMode = AgentMode.SINGLE_REACT,  // or PLANNER_EXECUTOR
    val maxPlannerTurns: Int = 30,
    val maxExecutorSteps: Int = 10,
    val errToReplanThreshold: Int = 2,
    val enableVerification: Boolean = true,
    
    // === Planner-specific ===
    val plannerModel: String = "gpt-5.2",
    val plannerSystemPrompt: String? = null,  // Override default
    
    // === Executor-specific ===
    val executorModel: String = "gpt-5.2",  // Can be smaller/cheaper
    val executorSystemPrompt: String? = null
)

enum class AgentMode {
    SINGLE_REACT,      // Current behavior
    PLANNER_EXECUTOR   // Two-level architecture
}
```

### 8.3 Factory Pattern for Executor

```kotlin
/**
 * Factory to create Executor sessions.
 * 
 * Ensures each session is isolated and has fresh state.
 */
class ExecutorFactory(
    private val services: SessionServices,
    private val config: AgentConfig,
    private val eventEmitter: suspend (AgentEvent) -> Unit
) {
    fun create(query: String, maxSteps: Int = config.maxExecutorSteps): ExecutorSession {
        return ExecutorSession(
            query = query,
            services = services.deriveForExecutor(),
            llmClient = createLLMClient(config.executorModel),
            eventEmitter = eventEmitter
        )
    }
}
```

---

## 9. Integration with Existing Code

### 9.1 Minimal Changes to AgentRuntime

```kotlin
// In AgentRuntime.kt
suspend fun run(): AgentStopReason {
    return when (config.agentMode) {
        AgentMode.SINGLE_REACT -> runSingleReactLoop()  // Existing behavior
        AgentMode.PLANNER_EXECUTOR -> runPlannerExecutorLoop()
    }
}

private suspend fun runPlannerExecutorLoop(): AgentStopReason {
    val state = AgentState(
        sessionId = config.sessionId,
        goal = config.goal
    )
    
    val executorFactory = ExecutorFactory(services, config, eventEmitter)
    
    val plannerLoop = PlannerLoop(
        config = config,
        state = state,
        executorFactory = executorFactory,
        llmClient = services.llmClient,
        eventEmitter = eventEmitter
    )
    
    return plannerLoop.run()
}
```

### 9.2 Reusing Existing Tools

Executor reuses existing tool implementations:

```kotlin
// ExecutorSession uses existing ToolRouter
val toolResult = services.toolRouter.execute(
    toolCall = ToolCallRequest(
        id = generateId(),
        name = action.toolName,
        arguments = action.params
    ),
    snapshot = currentScreen
)
```

### 9.3 Event Bridging

Both Planner and Executor emit events through the same `eventEmitter`:

```kotlin
// Planner events
eventEmitter(SubgoalsUpdated(sessionId, now(), subgoals.map { ... }))
eventEmitter(SubgoalStarted(sessionId, now(), subgoal.id, subgoal.description))

// Executor events (prefixed for clarity)
eventEmitter(ExecutorSessionStarted(sessionId, now(), executorId, query))
eventEmitter(ActionExecuted(sessionId, now(), actionId, toolName, success, result))
```

---

## 10. Verification & Error Recovery

### 10.1 ActionReflector (Optional)

**File**: `agent/verifier/ActionReflector.kt`

```kotlin
/**
 * Verifies action outcomes by comparing before/after screenshots.
 * 
 * Based on Mobile Agent v3's ActionReflector.
 * Outcomes: A (success), B (wrong page), C (no change)
 */
class ActionReflector(
    private val llmClient: LLMClient
) {
    sealed class Outcome {
        object Success : Outcome()                    // A: Action worked
        data class WrongPage(val details: String) : Outcome()  // B: Navigation error
        object NoChange : Outcome()                   // C: Action had no effect
    }
    
    suspend fun verify(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
        actionDescription: String
    ): Outcome {
        // Compare before/after using LLM
        val prompt = """
            Compare these two screen states after the action: "$actionDescription"
            
            Before:
            ${before.toPromptJson()}
            
            After:
            ${after.toPromptJson()}
            
            Determine the outcome:
            A: Action succeeded (visible change toward goal)
            B: Wrong page (navigated somewhere unexpected)
            C: No change (action had no visible effect)
            
            Respond with just the letter and a brief explanation.
        """.trimIndent()
        
        val result = llmClient.chat(prompt)
        return parseOutcome(result)
    }
}
```

### 10.2 Error Recovery Flow

```
┌────────────────────────────────────────────────────────────────┐
│                    ERROR RECOVERY FLOW                          │
│                                                                 │
│   Executor fails                                                │
│       │                                                         │
│       ▼                                                         │
│   state.recordError(...)                                        │
│   state.consecutiveFailures++                                   │
│       │                                                         │
│       ▼                                                         │
│   consecutiveFailures >= ERR_TO_REPLAN_THRESH?                  │
│       │                                                         │
│   ┌───┴───┐                                                     │
│   │ No    │ Yes                                                 │
│   ▼       ▼                                                     │
│   Retry   state.errorFlagReplan = true                          │
│   same    │                                                     │
│   subgoal │                                                     │
│           ▼                                                     │
│   Planner sees `### POTENTIALLY STUCK` in context               │
│           │                                                     │
│           ▼                                                     │
│   Planner issues UpdateSubgoals with new strategy               │
│           │                                                     │
│           ▼                                                     │
│   state.clearErrorFlag()                                        │
│   Continue with revised plan                                    │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

### 10.3 Replanning Prompt Addition

```kotlin
// In PlannerPromptBuilder, when errorFlagReplan is true:
"""
### ⚠️ POTENTIALLY STUCK - REPLANNING REQUIRED

You have encountered ${state.consecutiveFailures} consecutive failures.
Recent error log:
${state.recentErrors.takeLast(3).joinToString("\n") { 
    "- Action: ${it.action}\n  Outcome: ${it.outcome}\n  Feedback: ${it.feedback}" 
}}

CRITICAL RULES FOR REPLANNING:
1. Do NOT repeat the failed approach
2. Preserve completed subgoals (do not redo)
3. Try a completely different strategy
4. Consider: Is the goal actually achievable from this state?

If the goal is impossible, call finish_task(success=false, reason="...")
""".trimIndent()
```

---

## 11. Summary of Design Choices

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **Planner abstraction** | Semantic-only (no coordinates) | Reduces errors, separates concerns (AutoDev) |
| **Executor sessions** | Stateless, fresh each call | Forces complete instructions (AutoDev) |
| **State container** | Single AgentState object | Reduces sync bugs (DroidRun, Mobile Agent v3) |
| **Subgoal format** | Structured objects with status | Reliable tracking, automation (MiniTap) |
| **Progress tracking** | Status enum per subgoal | Clear lifecycle, replanning triggers (MiniTap) |
| **Executor reports** | Narrative summaries | Better Planner recovery (AutoDev) |
| **Failure threshold** | 2 consecutive before replan | Balance between retry and pivot (DroidRun) |
| **Verification** | Optional ActionReflector | Catches failures early (Mobile Agent v3) |
| **Memory** | Scratchpad + importantNotes | Cross-step/cross-app data (AutoDev) |
| **Existing code reuse** | Executor uses ToolRouter | Minimal refactoring, proven tools |

---

## 12. Next Steps

1. **Phase 1**: Add `AgentState`, `Subgoal`, `ExecutorReport` types
2. **Phase 2**: Implement `PlannerLoop` with basic subgoal management
3. **Phase 3**: Implement `ExecutorSession` using existing tools
4. **Phase 4**: Add protocol events and UI integration
5. **Phase 5**: Implement optional ActionReflector
6. **Phase 6**: Testing and tuning (error thresholds, prompts)

---

## References

- [note_1_architecture_claude.md](./note_1_architecture_claude.md) - Architecture patterns
- [note_1_architecture_codex.md](./note_1_architecture_codex.md) - Architecture synthesis
- [note_4_state_claude.md](./note_4_state_claude.md) - State management patterns
- [note_4_state_codex.md](./note_4_state_codex.md) - State comparison
- [autodevice_android_world.md](./autodevice_android_world.md) - AutoDev deep dive
- [../multiagent_infra/design.md](../multiagent_infra/design.md) - Sub-agent infrastructure
