# MobileAgent V3: Kotlin vs Python Comparison

**Author**: Engineering Team  
**Date**: January 2026

This document compares our Kotlin implementation against the reference Python implementation from `.reference/MobileAgent/Mobile-Agent-v3/`.

---

## 1. Executive Summary

Our Kotlin implementation is a **faithful adaptation** of MobileAgent V3 with platform-specific modifications for Android. The core multi-agent loop (Manager → Executor → Reflector) is preserved. Key differences arise from:

1. **Element identification**: We use accessibility node IDs instead of raw coordinates
2. **Vision model**: We use GPT-4O with text-based screen representation instead of vision API
3. **Notetaker omission**: We skip the optional Notetaker agent
4. **Error escalation**: Simplified from 3-level (A/B/C) to binary (success/failure)

---

## 2. Architecture Comparison

### Agent Pipeline

| Component | Python Reference | Kotlin Implementation | Notes |
|-----------|------------------|----------------------|-------|
| Manager | ✅ Yes | ✅ Yes | High-level planning |
| Executor | ✅ Yes | ✅ Yes | Action selection |
| Reflector | ✅ Yes | ✅ Yes | Outcome verification |
| Notetaker | ✅ Optional | ❌ No | Not implemented |
| Grounding | ✅ Optional | ❌ No | Uses element IDs instead |

### Loop Structure

**Python** (`run_mobileagentv3.py`):
```
for step in range(max_step):
    1. Get screenshot
    2. Check error escalation → set error_flag_plan
    3. If not skip_manager: Manager.predict() → plan
    4. If "Finished" in plan: break
    5. Executor.predict() → action
    6. Execute action
    7. Get screenshot (after)
    8. Reflector.predict() → outcome (A/B/C)
    9. If outcome == "A" and notetaker: Notetaker.predict()
```

**Kotlin** (`MobileV3Orchestration.kt`):
```
while (shouldContinue()):
    1. checkPause()
    2. Check cancellation
    3. Check max turns
    4. executeTurn():
       a. PERCEPTION: captureScreen()
       b. REFLECTION: if history exists, Reflector.validate()
       c. PLANNING: if shouldReplan(), Manager.think()
       d. EXECUTION: Executor.think() → action
       e. performAction()
```

**Key Difference**: Kotlin checks reflection BEFORE planning (comparing previous snapshot), while Python does reflection AFTER execution. Both achieve the same goal but Kotlin's approach is cleaner.

---

## 3. InfoPool / State Management

### Python InfoPool
```python
@dataclass
class InfoPool:
    instruction: str = ""
    additional_knowledge_manager: str = ""
    additional_knowledge_executor: str = ""
    
    summary_history: list
    action_history: list
    action_outcomes: list  # "A", "B", "C"
    error_descriptions: list
    
    last_summary: str
    last_action: str
    last_action_thought: str
    important_notes: str
    
    error_flag_plan: bool
    error_description_plan: bool
    
    plan: str
    completed_plan: str
    progress_status: str
    current_subgoal: str
    err_to_manager_thresh: int = 2
```

### Kotlin InfoPool
```kotlin
data class InfoPool(
    var instruction: String = "",
    var plan: String = "",
    var currentSubgoal: String = "",
    var errorFlagPlan: Boolean = false,
    var textMemory: String = "",
    
    val actionHistory: MutableList<AgentAction>,
    val outcomes: MutableList<ReflectionOutcome>,
    val errorDescriptions: MutableList<String>
)
```

**Key Differences**:
- Kotlin uses structured `AgentAction` and `ReflectionOutcome` types instead of raw JSON/strings
- Python has separate `summary_history` and `action_history`; Kotlin embeds reason in action
- Python tracks `important_notes` for Notetaker; Kotlin uses simpler `textMemory`
- Python tracks 3-level outcomes (A/B/C); Kotlin uses binary success/failure

---

## 4. Action Types

### Python Actions (Mobile)
```python
ATOMIC_ACTION_SIGNITURES_noxml = {
    'answer': {"arguments": ["text"]},
    'click': {"arguments": ["coordinate"]},      # [x, y]
    'long_press': {"arguments": ["coordinate"]}, # [x, y]
    'type': {"arguments": ["text"]},
    'system_button': {"arguments": ["button"]},  # "Back", "Home"
    'swipe': {"arguments": ["coordinate", "coordinate2"]}  # [x1,y1] → [x2,y2]
}
```

### Kotlin Actions
```kotlin
sealed class AgentAction {
    data class AtomicAction(
        val type: String,        // "click", "type", "scroll", "system", "wait"
        val elementId: Int?,     // For click, type
        val text: String?,       // For type
        val direction: String?,  // For scroll
        val button: String?,     // For system
        val reason: String?
    )
    data class FinishAction(val reason: String?)
    data class InvalidAction(val reason: String?)
}
```

**Key Differences**:
| Python | Kotlin | Difference |
|--------|--------|------------|
| `click` with `[x, y]` | `click` with `elementId` | Element ID instead of coordinates |
| `swipe` with coordinates | `scroll` with direction | Direction-based scrolling |
| `long_press` | Not implemented | Omitted |
| `answer` | `FinishAction` | Semantic naming |

**Rationale**: Using element IDs is more reliable on Android via AccessibilityService. Coordinates can miss due to screen density variations.

---

## 5. Prompt Differences

### Manager Prompts

**Python** (more elaborate):
- Includes task-specific notes (e.g., "Audio Recorder", ".html files")
- Has guidelines section with additional knowledge
- Tracks "Historical Operations" separately
- Explicit instructions for answer actions

**Kotlin** (simplified):
```kotlin
val SYSTEM_PROMPT = """
    You are a Manager Agent for an Android phone.
    Your job is to create and maintain a plan to achieve the user's ACTUAL GOAL.
    
    Output strictly in JSON format:
    {
        "thought": "...",
        "plan": "...",
        "completed_subgoal": "..."
    }
    ...
""".trimIndent()
```

**Key Difference**: Kotlin uses structured JSON output, Python uses markdown sections.

### Executor Prompts

**Python**:
- Includes progress_status tracking
- Has detailed guidelines section
- Uses coordinate-based actions

**Kotlin**:
- Focuses on current screen state
- Uses element IDs for targeting
- Simpler action vocabulary

### Reflector Prompts

**Python** (3-level outcomes):
```
A: Successful or Partially Successful
B: Failed - wrong page, need to return
C: Failed - no changes
```

**Kotlin** (binary with description):
```kotlin
data class ReflectionOutcome(
    val success: Boolean,
    val description: String
)
```

**Impact**: Python's B/C distinction allows different recovery strategies. Kotlin treats both as failures.

---

## 6. Error Escalation

### Python
```python
if len(info_pool.action_outcomes) >= err_to_manager_thresh:
    latest_outcomes = info_pool.action_outcomes[-err_to_manager_thresh:]
    count = 0
    for outcome in latest_outcomes:
        if outcome in ["B", "C"]:
            count += 1
    if count == err_to_manager_thresh:
        info_pool.error_flag_plan = True
```

### Kotlin (`SessionExecutionState.kt`)
```kotlin
fun shouldReplan(): Boolean {
    if (plan.isEmpty()) return true
    if (errorFlagPlan) return true
    // Check consecutive failures
    val recentOutcomes = outcomes.takeLast(2)
    if (recentOutcomes.size >= 2 && recentOutcomes.all { !it.success }) {
        return true
    }
    return false
}
```

**Similarity**: Both escalate to Manager after consecutive failures (Python: 2, Kotlin: 2).

---

## 7. Screen Representation

### Python
- Uses raw screenshots sent to vision-capable LLM
- Coordinates are relative (0-1000 normalized) or absolute

### Kotlin
- Uses `Perceptor.toPromptJson()` to create text representation
- Elements include: index, className, text, description, bounds, isClickable
- Actions reference elements by index

**Example Kotlin Screen Context**:
```json
{
  "elements": [
    {"index": 0, "class": "Button", "text": "Settings", "clickable": true, "bounds": [0,0,100,50]},
    {"index": 1, "class": "TextView", "text": "Welcome", "clickable": false}
  ]
}
```

---

## 8. Missing Features

Features in Python not implemented in Kotlin:

| Feature | Python | Kotlin | Priority |
|---------|--------|--------|----------|
| Notetaker agent | ✅ | ❌ | Low - useful for long tasks |
| Grounding model | ✅ | ❌ | N/A - uses element IDs |
| RAG knowledge | ✅ | ❌ | Medium - could improve planning |
| Progress status | ✅ | Partial | Low |
| Long press | ✅ | ❌ | Medium |
| Swipe gestures | ✅ | Scroll only | Medium |

---

## 9. Behavioral Differences

### When Plans Diverge

1. **Complex UI**: Python with vision may see elements Kotlin misses if not in accessibility tree
2. **Dynamic content**: Kotlin's element IDs may shift; Python's coordinates are absolute
3. **Answer tasks**: Python explicitly handles "answer" action; Kotlin uses generic FinishAction

### Error Recovery

- **Python**: B-outcome triggers "return to previous state" heuristic
- **Kotlin**: All failures treated equally, re-plans after consecutive failures

---

## 10. Recommendations

### Short-term (Low effort, high value)
1. ✅ **Done**: Add rate limit handling to LLM client
2. Consider adding `long_press` action for context menus
3. Add swipe action for precise gesture control

### Medium-term
1. Add Notetaker agent for tasks requiring memory
2. Implement progress_status tracking for better context
3. Consider B/C outcome distinction for smarter recovery

### Long-term
1. Evaluate vision API for better element detection
2. Consider RAG integration for domain knowledge
3. Add grounding model for coordinate-based fallback

---

## 11. Conclusion

Our Kotlin implementation is **functionally equivalent** to the Python reference for most use cases. The main differences are:

1. **Element identification** (IDs vs coordinates) - Actually an improvement for Android
2. **Prompt structure** (JSON vs markdown) - Functionally equivalent
3. **Outcome granularity** (binary vs A/B/C) - Minor impact

The implementation faithfully preserves the Manager → Executor → Reflector loop, error escalation logic, and overall agent behavior. Platform-specific adaptations were made thoughtfully.

