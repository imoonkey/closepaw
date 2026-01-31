# Android Agent Improvement Recommendations

Based on analysis of top AndroidWorld leaderboard solutions:
- **Minitap**: 100% AndroidWorld (6-agent architecture)
- **DroidRun**: 91.4% AndroidWorld (flexible multi-agent with App Cards)
- **AutoDevice**: High performer (dual-agent with TodoList)
- **M3A**: Baseline (single agent with summarization)

## Current State Assessment

Our agent uses a **single ReAct agent** with:
- 3 tools: `mobile_action`, `app_control`, `complete_task`
- Consolidated actions (click, type, swipe) in `mobile_action`
- Element index-based targeting
- Basic conversation history

**What we're missing** compared to top performers:
1. Persistent memory across steps (all top agents have this)
2. Task tracking/todo list (AutoDevice, DroidRun)
3. Multi-selector fallback for targeting (Minitap)
4. Failure recovery and replanning (all top agents)
5. Loop/stuck detection (AutoDevice)
6. App Cards / pre-loaded app knowledge (DroidRun)
7. Error escalation logic (DroidRun)
8. Type with clear option (DroidRun)

---

## Priority 1: Immediate Improvements (High Impact, Low Effort)

### 1.1 Add Scratchpad Memory Tool
**Impact**: Critical for cross-app workflows  
**Effort**: Low (new tool implementation)

```kotlin
// New tool: memory_tool
sealed class MemoryAction {
    data class Save(val key: String, val content: String) : MemoryAction()
    data class Read(val key: String) : MemoryAction()
    object List : MemoryAction()
}
```

**Why**: All top agents use persistent memory. Essential for:
- "Copy data from app A to app B" tasks
- Storing intermediate results
- Persisting information across failed attempts

**DroidRun's approach** (recommended format):
```kotlin
// Store with step context
remember("At step 5, I obtained recipe from app A: Chicken Pasta - ingredients...")
```

This format provides:
- Step number for debugging
- Source location
- Actual content (not just reference)

### 1.2 Add Multi-Selector Targeting
**Impact**: Reduces tap failures significantly  
**Effort**: Medium (modify MobileActionTool)

Current:
```kotlin
data class ClickAction(val element_index: Int)
```

Improved (Minitap-style):
```kotlin
data class ClickAction(
    val element_index: Int? = null,           // Primary: by index
    val bounds: Bounds? = null,               // Fallback 1: by coordinates
    val resource_id: String? = null,          // Fallback 2: by resource ID
    val text: String? = null,                 // Fallback 3: by text content
    val text_index: Int? = null               // For duplicate texts
)

// Execution order: index → bounds → resource_id → text
```

**Why**: Minitap attributes success to fallback targeting. When one selector fails, others can succeed.

### 1.3 Add Agent Thought/Reason Parameter
**Impact**: Better debugging and failure analysis  
**Effort**: Low (parameter addition)

```kotlin
// Every tool action should include
val agent_thought: String  // WHY this action is being performed
```

**Why**: Creates audit trail, helps identify failure patterns.

### 1.4 Add Navigation State Tracking
**Impact**: Prevents infinite loops  
**Effort**: Medium (state machine addition)

```kotlin
data class NavigationState(
    val seenScreens: MutableSet<Int> = mutableSetOf(), // Screen hashes
    val scrollCount: Int = 0,
    val lastElements: List<String> = emptyList()
)

// Check before each scroll
if (navigationState.seenScreens.contains(currentScreenHash)) {
    warn("Screen already visited - consider different approach")
}
```

**Why**: AutoDevice's loop prevention is critical for complex navigation tasks.

### 1.5 Add Error Escalation Logic (from DroidRun)
**Impact**: Prevents infinite retry loops  
**Effort**: Low (state tracking)

```kotlin
data class ErrorState(
    var consecutiveErrors: Int = 0,
    var errorEscalationThreshold: Int = 2
)

// After each action
if (!success) {
    errorState.consecutiveErrors++
    if (errorState.consecutiveErrors >= errorState.errorEscalationThreshold) {
        // Trigger replanning or different strategy
        triggerReplan("Consecutive errors: consider different approach")
    }
} else {
    errorState.consecutiveErrors = 0  // Reset on success
}
```

**Why**: DroidRun uses this to automatically trigger replanning when stuck.

### 1.6 Add Type Clear Option (from DroidRun)
**Impact**: Reduces text input failures  
**Effort**: Low (parameter addition)

```kotlin
data class TypeAction(
    val text: String,
    val element_index: Int,
    val clear: Boolean = false  // NEW: clear field before typing
)
```

**Why**: DroidRun's `clear=True` option handles "replace text" scenarios cleanly:
- URL bars need clearing before typing new URL
- Search fields often need clearing
- Form fields with default values

---

## Priority 2: Architecture Improvements (High Impact, Medium Effort)

### 2.1 Add TodoList Tool for Complex Tasks
**Impact**: Better planning for multi-step tasks  
**Effort**: Medium (new tool + system prompt update)

```kotlin
data class Todo(
    val id: String,
    val content: String,
    val status: TodoStatus,  // PENDING, IN_PROGRESS, COMPLETED
    val priority: Priority   // HIGH, MEDIUM, LOW
)

sealed class TodoAction {
    data class Update(val todos: List<Todo>) : TodoAction()
    object List : TodoAction()
}
```

System prompt addition:
```markdown
## Task Management
For complex tasks (3+ steps), create a todo list to track progress.
Mark items IN_PROGRESS before starting, COMPLETED after verification.
Only one item should be IN_PROGRESS at a time.
```

**Why**: Both Minitap (subgoals) and AutoDevice (todo list) use explicit task tracking.

### 2.2 Add Screen Transcription Tool
**Impact**: Better text extraction, saves tokens  
**Effort**: Low (new tool using existing perception)

```kotlin
sealed class PerceptionAction {
    object TranscribeScreen : PerceptionAction()  // Extract all visible text
    data class FindElement(val description: String) : PerceptionAction()
}
```

**Why**: On-demand transcription (AutoDevice) is more efficient than always including UI tree.

### 2.3 Implement Failure Narrative Reporting
**Impact**: Better error recovery  
**Effort**: Medium (prompt engineering + state tracking)

When action fails, require structured failure report:
```markdown
**Failure Report**:
1. What was attempted: [action description]
2. What went wrong: [error/unexpected result]
3. What was observed: [screen state]
4. Alternative approaches: [suggestions]
```

Then inject this into next turn's context.

**Why**: AutoDevice's narrative summaries enable intelligent pivoting.

### 2.4 Add App Cards (from DroidRun)
**Impact**: Significantly reduces exploration time  
**Effort**: Medium (content creation + loading system)

```kotlin
data class AppCard(
    val packageName: String,
    val navigationHints: List<String>,
    val commonActions: Map<String, String>
)

// Example: Gmail app card
val gmailCard = AppCard(
    packageName = "com.google.android.gm",
    navigationHints = listOf(
        "Compose: Tap floating action button (bottom right)",
        "Search: Use search bar at top",
        "Settings: Menu → Settings"
    ),
    commonActions = mapOf(
        "send_email" to "Tap compose → Fill fields → Tap send",
        "search_email" to "Tap search bar → Type query → Tap result"
    )
)
```

**Why**: DroidRun's App Cards provide pre-loaded knowledge that:
- Reduces wrong turns during navigation
- Provides app-specific shortcuts
- Accelerates common workflows

---

## Priority 3: Multi-Agent Architecture (Highest Impact, High Effort)

### 3.1 Planner-Executor Separation
**Impact**: Clearer responsibilities, better debugging  
**Effort**: High (architecture change)

```
User Goal → Planner Agent → High-level subgoals
                              ↓
                        Executor Agent → Low-level actions
                              ↓
                        Report back to Planner
```

**Benefits**:
- Planner focuses on strategy (no UI details)
- Executor focuses on precise interaction (no high-level planning)
- Failures can be escalated for replanning

### 3.2 Add Orchestrator for Subgoal Management
**Impact**: Handles complex multi-step workflows  
**Effort**: High (new agent layer)

```kotlin
sealed class SubgoalStatus {
    object NOT_STARTED : SubgoalStatus()
    object PENDING : SubgoalStatus()
    object COMPLETED : SubgoalStatus()
    object FAILED : SubgoalStatus()
}

data class Subgoal(
    val id: String,
    val description: String,
    val status: SubgoalStatus
)
```

**Why**: Minitap's orchestrator tracks completion and triggers replanning when stuck.

---

## System Prompt Improvements

### Add Critical Rules Section
```markdown
## CRITICAL RULES

1. **Never Repeat Failed Actions**
   Before retrying, analyze what went wrong. Ask: "How would a human solve this differently?"

2. **Isolate Unpredictable Actions**
   Actions that change screen unpredictably (back, launch_app, navigation taps) 
   should be the ONLY action in that turn. Wait to observe result.

3. **Verify Before Completing**
   Never mark task complete without OBSERVED evidence of success.
   "I clicked submit" ≠ "Form was submitted successfully"

4. **Use Launch App for Apps**
   Always use `app_control.open_app` to launch apps.
   Don't navigate through app drawer unless launch fails.
```

### Add Loop Prevention Instructions
```markdown
## Avoiding Loops

- If you've scrolled 3+ times without finding target, try search instead
- If same screen appears twice, change approach (don't repeat actions)
- If action failed 2x, try alternative method
- Track what you've seen: don't revisit same items
```

### Add Memory Usage Instructions (DroidRun format)
```markdown
## Using Memory

**Format (from DroidRun):**
Always include step context: "At step [number], I obtained [actual content] from [source]"

**Examples:**
- "At step 5, I obtained recipe from RecipeApp: Chicken Pasta - chicken, pasta, cream"
- "At step 12, I successfully added Recipe 1 to target app. Still need to add Recipe 2."

**Rules:**
- Store ACTUAL content, not just references ("found recipes" ✗)
- Memory is append-only (new info added, not replaced)
- Use memory instead of clipboard unless clipboard specifically required
- Update memory to track progress on multi-step tasks
```

### Add Strict Executor Guidelines (from DroidRun)
```markdown
## Executor Rules

You are a LOW-LEVEL ACTION EXECUTOR. You do NOT answer questions.
You ONLY perform individual atomic actions.

### LITERAL EXECUTION RULE ###
Whatever the current subgoal says to do, do that EXACTLY.
- Do not substitute with what you think is better
- Do not optimize
- Parse the subgoal text literally and execute the matching action
- Do NOT repeat previously failed actions - try something different
```

---

## Implementation Roadmap

### Phase 1: Quick Wins (1-2 days)
- [ ] Add memory tool with step context format (DroidRun style)
- [ ] Add agent_thought parameter to all tools (Minitap)
- [ ] Add navigation state tracking (AutoDevice)
- [ ] Add error escalation logic (DroidRun)
- [ ] Add `clear` option to type action (DroidRun)
- [ ] Update system prompt with critical rules

### Phase 2: Robustness (3-5 days)
- [ ] Implement multi-selector targeting (Minitap)
- [ ] Add todo list tool (AutoDevice)
- [ ] Add screen transcription tool (AutoDevice)
- [ ] Implement failure narrative reporting (AutoDevice)
- [ ] Add strict executor guidelines to prompts (DroidRun)

### Phase 3: Architecture (1-2 weeks)
- [ ] Separate Planner and Executor agents
- [ ] Add orchestrator for subgoal management
- [ ] Implement replanning on repeated failures
- [ ] Add App Cards system (DroidRun)
- [ ] Add model selection based on task difficulty

---

## Key Metrics to Track

| Metric | Current | Target | How to Measure |
|--------|---------|--------|----------------|
| Task success rate | ? | 80%+ | % of tasks completed correctly |
| Loop rate | ? | <5% | % of tasks stuck in loops |
| Tap success rate | ? | 95%+ | % of tap actions that work |
| Cross-app success | ? | 70%+ | % of multi-app tasks succeeded |
| Steps to completion | ? | -20% | Avg steps for successful tasks |

---

## Summary of Top Insights

### From All Top Agents
1. **Memory is essential**: Scratchpad/remember() enables cross-app workflows
2. **Separation of concerns helps**: Planner doesn't need UI details, Executor doesn't strategize

### From Minitap (100%)
3. **Multiple selectors beat single**: Fallback targeting (coords → resource_id → text)
4. **Agent thoughts parameter**: Every tool call explains "why"
5. **Isolated navigation actions**: Unpredictable actions need observation

### From DroidRun (91.4%)
6. **App Cards accelerate navigation**: Pre-loaded app knowledge beats exploration
7. **Error escalation triggers replanning**: Consecutive failures → different strategy
8. **Strict executor role**: "Dumb robot" execution prevents overthinking
9. **Memory with context**: "At step X, obtained Y from Z" format
10. **Type clear option**: `clear=True` for replace scenarios

### From AutoDevice
11. **Loop prevention is critical**: Hash/state tracking prevents infinite loops
12. **Explicit task tracking**: TodoList for complex multi-step tasks
13. **On-demand transcription**: More efficient than always including UI tree
14. **Failure narratives**: Structured summaries help pivot strategy

---

## Quick Reference: What Each Agent Does Best

| Feature | Minitap | DroidRun | AutoDevice | Our Agent |
|---------|---------|----------|------------|-----------|
| Multi-selector targeting | ✅ Best | ✅ Good | ❌ | ❌ |
| Memory system | ✅ | ✅ Best format | ✅ | ❌ |
| App knowledge | ❌ | ✅ Best | ❌ | ❌ |
| Error escalation | ✅ | ✅ Best | ✅ | ❌ |
| Loop prevention | ✅ | ✅ | ✅ Best | ❌ |
| Task tracking | ✅ Subgoals | ✅ | ✅ TodoList | ❌ |
| Strict executor | ✅ | ✅ Best | ✅ | Partial |
