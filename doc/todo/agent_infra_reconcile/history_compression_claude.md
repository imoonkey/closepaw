# History Compression Design

> **Core Insight**: Mobile-use agents don't need past screenshots in context

**Codex addition**: Add `screen_summary` (1-3 lines) to each turn for minimal context.

---

## The Problem

```
Turn 1: [screenshot_1, a11y_tree_1] → action_1 → result_1
Turn 2: [screenshot_2, a11y_tree_2] → action_2 → result_2
Turn 3: [screenshot_3, a11y_tree_3] → action_3 → result_3
...
Turn N: [screenshot_N, a11y_tree_N] → action_N → ???
```

Currently, LLM receives ALL screenshots in history. But:
- Past screenshots are **irrelevant** after screen changes
- They consume **massive context** (images + a11y trees)
- They add **noise**, not signal

**Coding agent**: Past file contents matter (edits build on edits)  
**Mobile agent**: Past screens don't matter (only current screen matters)

---

## The Solution

### Text-Only History + Current Screen + Screen Summary

```kotlin
// Build prompt with compressed history
fun buildPromptMessages(history: HistoryManager, currentScreen: ScreenSnapshot): List<Message> {
    val messages = mutableListOf<Message>()
    
    // 1. Add text summaries of past turns (includes screen_summary)
    for (turn in history.turns) {
        messages.add(Message.User(turn.toTextSummary()))
        messages.add(Message.Assistant(turn.assistantSummary()))
    }
    
    // 2. Add ONLY current screen (not in history)
    messages.add(Message.User(
        content = "Current screen state:",
        images = listOf(currentScreen.screenshot),
        attachments = listOf(currentScreen.a11yTree.toJson())
    ))
    
    return messages
}
```

### Turn Summary Format (Codex-enhanced)

```kotlin
fun TurnRecord.toTextSummary(): String = buildString {
    appendLine("Turn ${index}:")
    appendLine("- Screen: ${screenSummary}")  // NEW: 1-3 line screen description
    appendLine("- Thought: ${thought}")
    appendLine("- Action: ${action.name}(${action.params})")
    appendLine("- Result: ${result.summary}")
    // NO screenshot, NO a11y tree JSON
}

// Screen summary generation (can be LLM-generated or heuristic)
fun ScreenSnapshot.toSummary(): String {
    // Option A: Extract from a11y tree
    val appName = a11yTree.rootNode.contentDescription ?: packageName
    val focusedElement = a11yTree.findFocused()?.text ?: ""
    return "$appName - $focusedElement visible"
    
    // Option B: Ask LLM (more expensive but better)
    // return llm.summarize("Summarize this screen in 1-2 lines: ${a11yTree.toJson()}")
}
```

---

## Implementation

### Option A: Modify HistoryManager

```kotlin
class HistoryManager {
    private val turns = mutableListOf<TurnRecord>()
    
    fun addTurn(turn: TurnRecord) {
        // Store full turn internally
        turns.add(turn)
    }
    
    fun toPromptMessages(): List<Message> {
        // Return text-only summaries
        return turns.map { turn ->
            Message.User(turn.toTextSummary())
        }
    }
    
    // Current screen provided separately, not from history
}
```

### Option B: Modify Prompt Builder (Less Invasive)

```kotlin
class AgentPromptBuilder {
    fun build(
        systemPrompt: String,
        history: HistoryManager,
        currentScreen: ScreenSnapshot
    ): List<Message> {
        val messages = mutableListOf<Message>()
        
        messages.add(Message.System(systemPrompt))
        
        // Compress history to text
        for (turn in history.turns) {
            messages.add(Message.User("Previous action: ${turn.toTextSummary()}"))
        }
        
        // Current screen as latest user message
        messages.add(Message.User(
            content = buildCurrentScreenContext(currentScreen),
            images = listOf(currentScreen.screenshotData)
        ))
        
        return messages
    }
}
```

---

## Benefits

| Metric | Before | After |
|--------|--------|-------|
| Context tokens (10 turns) | ~50k | ~5k |
| Image tokens | 10 images | 1 image |
| Relevance | Low (old screens) | High (current only) |
| Noise | High | Low |

---

## Trade-offs

**What we lose**:
- LLM can't "look back" at what previous screens looked like
- Can't compare current vs previous visually

**Why that's OK**:
- Summary text captures what mattered
- If comparison is needed, agent can use `scratchpad` tool to note observations
- Executor is stateless anyway (by design)

---

## Connection to Multi-Agent

This design aligns perfectly with Planner-Executor:
- **Executor**: Fresh session, only sees current screen
- **Planner**: Sees text summaries of Executor reports
- No shared screenshot history between them

---

## Implementation Priority

**High**: This can be done BEFORE multi-agent infra and provides immediate value.

1. Add `TurnRecord.toTextSummary()` method
2. Modify `AgentPromptBuilder` to use summaries
3. Pass current screen separately from history
4. Test with existing single-agent flow

**Estimated effort**: 0.5-1 day
