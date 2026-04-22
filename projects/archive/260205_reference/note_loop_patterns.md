# Reference Implementation Loop Patterns (Deep Dive)

> **Author**: Claude  
> **Date**: 2026-02-03  
> **Related**: [two_level_multiagent_design.md](./two_level_multiagent_design.md)

---

Understanding how existing frameworks implement the Planner-Executor loop is critical for our design. Here's a detailed analysis from actual code:

## Pattern A: Nested Loops (AutoDev)

AutoDev uses **nested synchronous loops** where the outer Planner loop waits for the inner Executor loop to complete.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  EXTERNAL FRAMEWORK (android_world episode runner)                          │
│                                                                             │
│  for step_n in range(max_n_steps):                                          │
│      result = agent.step(goal)  ← PLANNER TURN                              │
│      if result.done: break                                                  │
│                                                                             │
└──────────────────────────┬──────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  agent.step() - ONE PLANNER TURN                                            │
│                                                                             │
│  1. Capture screenshot                                                      │
│  2. planner_llm.chat(goal, screenshot, tools)  ← Planner LLM call          │
│  3. For each tool_call in response:                                         │
│      if tool_call is "finish_task": return done=True                        │
│      if tool_call is "update_todos": update TODO list                       │
│      if tool_call is semantic (tap, scroll, type):                          │
│          self.execute_step(tool_call)  ← BLOCKS HERE until Executor done   │
│                                                                             │
│  4. return done=False                                                       │
│                                                                             │
└──────────────────────────┬──────────────────────────────────────────────────┘
                           │ (synchronous call)
                           ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  execute_step() - EXECUTOR SESSION (INNER LOOP with MAX_EXECUTOR_STEPS=10) │
│                                                                             │
│  executor_llm = AutoDevLLM(...)  ← Fresh LLM instance, no prior history    │
│                                                                             │
│  for i in range(MAX_EXECUTOR_STEPS):  ← EXECUTOR HAS ITS OWN LOOP          │
│      screenshot = capture_screen()                                          │
│      response = executor_llm.chat(query, screenshot, tools)                 │
│                                                                             │
│      for exec_call in response.tool_calls:                                  │
│          if exec_call is "report":                                          │
│              planner_llm.add_tool_result(result)  ← Return to Planner      │
│              return  ← EXIT EXECUTOR LOOP                                   │
│                                                                             │
│          # Execute low-level action (click, type, scroll)                   │
│          execute_action(exec_call)                                          │
│          executor_llm.add_tool_result("Done")  ← Executor sees result      │
│                                                                             │
│  # If MAX_EXECUTOR_STEPS reached without report()                           │
│  planner_llm.add_tool_result({"status": "failed", "summary": ...})          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Key Characteristics:**
- **Executor has memory within session**: Executor accumulates message history during its loop
- **Executor is stateless across sessions**: Fresh executor_llm instance per Planner tool call
- **Planner blocks**: Planner waits synchronously for Executor to complete
- **Single process**: Everything runs in one process, loops are nested
- **Executor can take multiple actions**: Up to MAX_EXECUTOR_STEPS=10 per semantic instruction

**Code Evidence** (`autodev_agent.py:546-737`):
```python
def execute_step(self, planner_tool_call: ToolCall) -> None:
    """Run the executor loop for a single planner tool call."""
    executor_llm = AutoDevLLM(...)  # Fresh instance
    
    for i in range(MAX_EXECUTOR_STEPS):  # Executor's own loop
        screenshot = self.get_post_transition_state()
        execution_step = executor_llm.chat(query, screenshot, tools)
        
        for exec_call in execution_step["tool_calls"]:
            if fname == "report":
                self.planner_llm.add_tool_result(...)  # Return to Planner
                return  # Exit executor loop
            
            # Execute action
            self.env.execute_action(json_action)
            executor_llm.add_tool_result(exec_call["id"], "Done")
```

---

## Pattern B: Workflow Events (DroidRun)

DroidRun uses **workflow-based async event streaming** with llama_index Workflow. Manager and Executor are separate workflow steps that emit events.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  DroidAgent (Workflow class)                                                │
│                                                                             │
│  StartEvent                                                                 │
│      │                                                                      │
│      ▼                                                                      │
│  ┌────────────────┐                                                         │
│  │  run_manager   │ ← @step decorator, Manager workflow                     │
│  │   (planning)   │                                                         │
│  └───────┬────────┘                                                         │
│          │ ManagerPlanEvent                                                 │
│          ▼                                                                  │
│  ┌─────────────────────────┐                                                │
│  │  handle_manager_plan    │ ← Routes to Executor or Scripter               │
│  └───────────┬─────────────┘                                                │
│              │ ExecutorInputEvent                                           │
│              ▼                                                              │
│  ┌────────────────┐                                                         │
│  │  run_executor  │ ← @step decorator, spawns ExecutorAgent workflow        │
│  │  (ONE action)  │                                                         │
│  └───────┬────────┘                                                         │
│          │ ExecutorResultEvent                                              │
│          ▼                                                                  │
│  ┌──────────────────────────┐                                               │
│  │  handle_executor_result  │ ← Checks for error escalation                 │
│  └───────────┬──────────────┘                                               │
│              │ ManagerInputEvent (loops back)                               │
│              └──────────────────────────────────────┐                       │
│                                                     │                       │
│              ┌──────────────────────────────────────┘                       │
│              ▼                                                              │
│  ┌────────────────┐                                                         │
│  │  run_manager   │ ← Next iteration                                        │
│  └────────────────┘                                                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Key Characteristics:**
- **Single action per Executor call**: Executor takes ONE action, returns, Manager decides next
- **Event-driven**: Steps communicate via typed events (ManagerPlanEvent, ExecutorInputEvent, etc.)
- **Async streaming**: Events streamed to parent for UI updates
- **Shared state object**: `DroidAgentState` passed to both Manager and Executor
- **Loop via workflow**: No explicit for-loop; workflow returns to Manager via events

**Code Evidence** (`droid_agent.py:806-838`):
```python
@step
async def run_executor(self, ctx: Context, ev: ExecutorInputEvent) -> ExecutorResultEvent:
    """Run Executor action phase."""
    handler = self.executor_agent.run(subgoal=ev.current_subgoal)
    
    async for nested_ev in handler.stream_events():
        self.handle_stream_event(nested_ev, ctx)  # Stream to UI
    
    result = await handler  # Executor completes after ONE action
    
    self.shared_state.action_history.append(result["action"])
    return ExecutorResultEvent(action=result["action"], ...)

@step 
async def handle_executor_result(self, ctx: Context, ev: ExecutorResultEvent) -> ManagerInputEvent:
    """Process result and loop back to Manager."""
    # Check error escalation
    if error_count >= err_thresh:
        self.shared_state.error_flag_plan = True
    
    return ManagerInputEvent()  # Loop back to Manager
```

---

## Pattern C: LangGraph State Machine (MiniTap)

MiniTap uses **LangGraph** with a state machine graph where nodes are agents and edges are conditional routing.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  LangGraph State Machine                                                    │
│                                                                             │
│  START ──► planner ──► orchestrator ──► convergence                        │
│                                              │                              │
│                              ┌───────────────┼────────────────┐             │
│                              │               │                │             │
│                              ▼               ▼                ▼             │
│                          "continue"      "replan"           "end"           │
│                              │               │                │             │
│                              ▼               │                ▼             │
│                         contextor            │              END             │
│                              │               │                              │
│                              ▼               │                              │
│                           cortex ────────────┘                              │
│                              │                                              │
│                     ┌────────┴────────┐                                     │
│                     ▼                 ▼                                     │
│              "review_subgoals"  "execute_decisions"                         │
│                     │                 │                                     │
│                     ▼                 ▼                                     │
│               orchestrator        executor                                  │
│                                       │                                     │
│                              ┌────────┴────────┐                            │
│                              ▼                 ▼                            │
│                       "invoke_tools"        "skip"                          │
│                              │                 │                            │
│                              ▼                 ▼                            │
│                       executor_tools      summarizer                        │
│                              │                 │                            │
│                              └────────┬────────┘                            │
│                                       ▼                                     │
│                                  summarizer                                 │
│                                       │                                     │
│                                       ▼                                     │
│                                  convergence (loops back)                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Key Characteristics:**
- **State passed between nodes**: `State` object flows through the graph
- **Conditional edges**: `convergence_gate` decides "continue", "replan", or "end"
- **Multiple specialized agents**: Planner, Orchestrator, Contextor, Cortex, Executor, Summarizer
- **Replanning via graph edge**: If subgoal fails, edge routes back to Planner
- **No explicit loops**: Loop emerges from graph structure

**Code Evidence** (`graph.py:39-57`):
```python
def convergence_gate(state: State) -> Literal["continue", "replan", "end"]:
    """Check if all subgoals are completed at convergence point."""
    if one_of_them_is_failure(state.subgoal_plan):
        return "replan"  # Route back to Planner
    
    if all_completed(state.subgoal_plan):
        return "end"
    
    return "continue"  # Continue to Contextor → Cortex → Executor
```

---

## Comparison Summary

| Aspect | AutoDev (Nested Loops) | DroidRun (Workflow Events) | MiniTap (LangGraph) |
|--------|------------------------|----------------------------|---------------------|
| **Loop Structure** | Explicit for-loops, nested | Workflow returns event to loop | Graph edges form cycle |
| **Executor Per Call** | Up to 10 actions | Single action | Multiple via tool calls |
| **Executor Memory** | Session-local history | N/A (single action) | Session-local messages |
| **Blocking** | Synchronous block | Async await | Async graph execution |
| **Replanning** | Planner reads error | error_flag_plan in state | Graph edge to Planner |
| **Concurrency** | Single thread | Async streaming | LangGraph async |
| **Complexity** | Medium | Medium-High | High |

---

## Recommendation: Nested Loops (AutoDev-style)

For AndroidAgent, we recommend **AutoDev's nested loop pattern** because:

1. **Clearer control flow**: Explicit loops are easier to debug than workflow events or graph edges
2. **Executor flexibility**: Executor can take multiple actions per instruction (important for complex grounding)
3. **Simpler implementation**: Maps well to our existing `AgentRuntime` structure
4. **Kotlin coroutines fit well**: `suspend fun` with `coroutineScope` for Executor session

**Proposed Implementation:**
```kotlin
// PlannerLoop.kt - Outer loop (called by AgentRuntime)
suspend fun run(): AgentStopReason {
    while (plannerTurnCount < MAX_PLANNER_TURNS) {
        // 1. Planner LLM turn
        val result = runPlannerTurn()
        
        // 2. If semantic tool call, spawn Executor session
        if (result.isExecutorInstruction) {
            val report = executeWithExecutor(result.instruction)  // BLOCKS HERE
            handleExecutorReport(report)
        }
        
        // 3. Check completion
        if (allSubgoalsComplete()) return AgentStopReason.GoalAchieved
    }
}

// ExecutorSession.kt - Inner loop (fresh instance per call)
suspend fun run(): ExecutorReport {
    while (stepCount < MAX_EXECUTOR_STEPS) {
        stepCount++
        val result = runExecutorTurn()
        
        when (result.action) {
            is Report -> return ExecutorReport(result.summary)  // EXIT
            is ToolCall -> {
                executeToolCall(result.action)
                addToolResult("Done")  // Executor sees result for next turn
            }
        }
    }
    return ExecutorReport(success = false, reason = "Max steps exceeded")
}
```

---

## Key Insight

**Planner synchronously waits for Executor session to complete**, then proceeds to next Planner turn. They are NOT independent processes or threads. The Executor session is a nested loop that runs within a single Planner tool call execution.
