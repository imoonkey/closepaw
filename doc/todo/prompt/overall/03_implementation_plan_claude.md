# Implementation Plan

> Code changes to transform the prompt pipeline from its current state to the proposed design.
> All file paths relative to `app/src/main/kotlin/com/moonkey/androidagent/`.

---

## 0. Guiding Principle: Surgery, Not Rewrite

The codebase is not broken — it works. The goal is to simplify the prompt-building path while preserving all existing behavior outside of prompt construction. Touch as few files as possible; make each change independently testable.

---

## 1. Current Code Flow (The Problem)

```
AgentTurnRunner.executeTurn()
  → capturePreTurnSnapshot()                    // good: captures screen
  → prepareTurn()                               // good: loop/step detection
  → runPlanningPhase()
      → buildPromptContext()                     // 🔴 assembles PromptContext (intermediate)
          → buildAdditionalContextBlocks()       // 🔴 mixes memory into context blocks
      → PromptUtils.buildUserMessage(context)    // 🔴 packs EVERYTHING into one message
          → buildBaseText()                      // 🔴 screen + tools + memory + image hint
          → buildReminders()                     // 🔴 loop + budget + todo reminders
      → Turn.buildInputItems(userMessage)        // 🔴 history + single user message
          → historyManager.forPrompt()           // good: gets history
          → buildUserContextItem()               // 🔴 wraps text+image into input item
      → Turn.runStreaming(...)                    // good: calls LLM
  → executeActions()
      → executeSingleToolCall()
          → resolveObservation()                 // 🟡 captures screen observation
          → formatToolResult(result, observation) // 🔴 combines result + screen summary
          → addItem(FunctionCallOutput)          // stores combined string in history
```

**Problems:**
1. **7 steps** across 3 files to build input items
2. `PromptContext` exists only as a data shuttle between `buildPromptContext()` and `buildUserMessage()`
3. `buildBaseText()` packs screen + tools + memory + image hint into one string
4. Memory (todo/scratchpad) is buried inside "additional context blocks"
5. Tool results include screen observation summaries
6. Screen state only exists for the current turn

---

## 2. Target Code Flow (The Solution)

```
AgentTurnRunner.executeTurn()
  → captureAndRecordScreen()                    // capture + store observation in history
  → prepareTurn()                               // loop/step detection (unchanged)
  → runPlanningPhase()
      → PromptBuilder.buildInputItems()          // ONE entry point, all logic here
          → buildHistorySection()                // history items + screen compression
          → buildMemorySection()                 // scratchpad + todos as one message
          → buildObservationSection()            // current screen + warnings + image
      → Turn.run(systemPrompt, inputItems)       // simplified: takes pre-built items
  → executeActions()
      → executeSingleToolCall()
          → resolveObservation()                 // still captures for internal use
          → formatToolResult(result)             // meta only, no observation
          → addItem(FunctionCallOutput)          // stores clean result
```

**3 steps** in 1 new file (`PromptBuilder`) + simplified callsites.

---

## 3. File-by-File Changes

### 3.1 NEW: `agent/cognition/prompt/PromptBuilder.kt`

The single entry point for prompt construction. Replaces `PromptUtils` + `PromptContext` + the prompt-building logic in `Turn.buildInputItems()`.

```kotlin
/**
 * Builds the complete input items for one LLM turn.
 *
 * Three sections, assembled in order:
 * 1. HISTORY — past turns with screen state management
 * 2. MEMORY — working memory (scratchpad + todos)
 * 3. OBSERVATION — current screen state + warnings + screenshot
 */
internal class PromptBuilder(
    private val historyManager: HistoryManager,
    private val sessionState: AgentSessionState,
    private val llmBackend: LLMBackendType,
    private val recentFullScreenTurns: Int = 3
) {
    fun buildInputItems(
        snapshot: ScreenSnapshot,
        image: ScreenImage?,
        warnings: List<String> = emptyList()
    ): List<ResponseInputItem> {
        return buildList {
            addAll(buildHistorySection())
            buildMemorySection()?.let { add(it) }
            add(buildObservationSection(snapshot, image, warnings))
        }
    }

    /** History items with screen observation compression. */
    private fun buildHistorySection(): List<ResponseInputItem> {
        val history = historyManager.forPrompt()
        val compressed = compressOldScreenObservations(history)
        return compressed.map { it.toResponseInputItem() }
    }

    /** Scratchpad + Todos as a single user message. Null if both empty. */
    private fun buildMemorySection(): ResponseInputItem? {
        val todoContext = sessionState.todos.toPromptContext()
        val scratchpadContext = sessionState.scratchpad.toPromptContext()
        val hasTodos = todoContext.isNotEmpty()
        val hasScratchpad = true // always show scratchpad (even empty hint)

        if (!hasTodos && scratchpadContext.startsWith("- (empty)")) return null

        val text = buildString {
            appendLine("## Working Memory")
            if (hasTodos) {
                appendLine()
                appendLine("### Todo List")
                appendLine(todoContext)
            }
            appendLine()
            appendLine("### Scratchpad")
            append(scratchpadContext)
        }
        return textUserMessage(text.trim())
    }

    /** Current screen + warnings + screenshot. */
    private fun buildObservationSection(
        snapshot: ScreenSnapshot,
        image: ScreenImage?,
        warnings: List<String>
    ): ResponseInputItem {
        val screenJson = Perceptor.toPromptJson(snapshot)
        val text = buildString {
            // Warnings at the top
            for (warning in warnings) {
                appendLine(warning)
            }
            if (warnings.isNotEmpty()) appendLine()

            // Screen state
            appendLine("Screen state (${snapshot.elements.size} elements):")
            appendLine("```json")
            appendLine(screenJson)
            appendLine("```")

            // Screenshot hint
            if (image != null && llmBackend == LLMBackendType.OPENAI) {
                appendLine()
                append("Screenshot attached (compressed).")
            }
        }
        return if (image != null && llmBackend == LLMBackendType.OPENAI) {
            imageUserMessage(text.trim(), image)
        } else {
            textUserMessage(text.trim())
        }
    }

    // --- Helpers ---

    private fun compressOldScreenObservations(
        items: List<ResponseItem>
    ): List<ResponseItem> {
        val screenIndices = items.withIndex()
            .filter { (_, item) ->
                item is ResponseItem.Message && item.isScreenObservation
            }
            .map { it.index }

        if (screenIndices.size <= recentFullScreenTurns) return items

        val toCompress = screenIndices.dropLast(recentFullScreenTurns).toSet()
        return items.mapIndexed { idx, item ->
            if (idx in toCompress) {
                val msg = item as ResponseItem.Message
                msg.copy(content = compressScreenContent(msg.content))
            } else {
                item
            }
        }
    }

    private fun compressScreenContent(fullContent: String): String {
        // Extract element count from content, build compact summary
        // For now: just take first line or generate summary
        val elementCountMatch = Regex("""Screen state \((\d+) elements\)""")
            .find(fullContent)
        val count = elementCountMatch?.groupValues?.get(1) ?: "?"
        return "Screen: $count elements (details compressed)"
    }
}
```

**~100 lines.** Clean, flat, no intermediate data classes.

### 3.2 MODIFY: `history/HistoryManager.kt`

Add `isScreenObservation` to `ResponseItem.Message`:

```kotlin
data class Message(
    val role: String,
    val content: String,
    val name: String? = null,
    val isScreenObservation: Boolean = false  // NEW
) : ResponseItem() {
    override fun estimateTokens(): Long =
        (content.length * 0.25f).toLong() + 4
}
```

No other changes to HistoryManager. The compression logic in `PromptBuilder` handles screen-specific compression at prompt build time; `HistoryManager.compress()` continues to handle general token budget management as before.

### 3.3 MODIFY: `agent/AgentTurnRunner.kt`

**A. Add screen observation to history at turn start:**

In `capturePreTurnSnapshot()` (or a new method called after it):

```kotlin
private fun recordScreenObservation(snapshot: ScreenSnapshot, image: ScreenImage?) {
    val screenJson = Perceptor.toPromptJson(snapshot)
    val text = buildString {
        appendLine("Screen state (${snapshot.elements.size} elements):")
        appendLine("```json")
        appendLine(screenJson)
        appendLine("```")
    }
    services.historyManager.addItem(
        ResponseItem.Message(
            role = "user",
            content = text.trim(),
            isScreenObservation = true
        )
    )
}
```

**B. Simplify `runPlanningPhase()`:**

```kotlin
private suspend fun runPlanningPhase(
    turnId: String,
    turnNumber: Int,
    snapshot: ScreenSnapshot,
    warnings: List<String>   // simplified: just warning strings
): PlanningPhaseResult {
    eventDispatcher.turnPhaseChanged(turnId, TurnPhase.PLANNING)
    eventDispatcher.status("🧠 Thinking...")

    val promptBuilder = PromptBuilder(
        historyManager = services.historyManager,
        sessionState = services.sessionState,
        llmBackend = services.config.llmBackend
    )
    val systemPrompt = requireNotNull(config.systemPrompt) {
        "System prompt must be provided by AgentDef."
    }
    val inputItems = promptBuilder.buildInputItems(
        snapshot = snapshot,
        image = snapshot.image,
        warnings = warnings
    )

    // ... rest of LLM call logic (unchanged)
}
```

**C. Remove `buildPromptContext()` and `buildAdditionalContextBlocks()`** — no longer needed.

**D. Simplify `formatToolResult()`:**

```kotlin
private fun formatToolResult(result: ToolCallResult): String {
    return when (result) {
        is ToolCallResult.Success -> "Success: ${result.output}"
        is ToolCallResult.Error -> "Error: ${result.error}"
        is ToolCallResult.Cancelled -> "Cancelled: ${result.reason}"
    }
}
```

Remove the `observation` parameter. The observation is still captured and used internally (for snapshot refresh and loop detection), but not formatted into the history output.

**E. Update `executeSingleToolCall()`** to call `formatToolResult(toolResult)` without observation.

### 3.4 MODIFY: `agent/Turn.kt`

Simplify to accept pre-built input items only. Remove `buildInputItems()` and `buildUserContextItem()`:

```kotlin
class Turn(
    private val toolRegistry: ToolRegistry,
    private val llmClient: LLMClient,
    private val allowedToolNames: Set<String>? = null
) {
    // Remove: buildInputItems(), buildUserContextItem()
    // Keep: run(), runStreaming(), prepareRequest() (simplified)

    private fun prepareRequest(
        inputItems: List<ResponseInputItem>,
        modelName: String
    ): TurnRequest {
        val tools = toolRegistry.generateResponsesApiTools { spec ->
            allowedToolNames?.contains(spec.name) != false
        }
        val model = modelNameToChatModel(modelName)
        return TurnRequest(inputItems = inputItems, tools = tools, model = model)
    }

    suspend fun run(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        modelName: String = "gpt-5.2"
    ): TurnResult { ... }

    fun runStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        modelName: String = "gpt-5.2"
    ): Flow<TurnStreamEvent> = flow { ... }
}
```

`Turn` no longer depends on `HistoryManager`. It's a pure LLM-calling wrapper. Input construction is entirely in `PromptBuilder`.

### 3.5 MODIFY: `agent/cognition/prompt/PromptUtils.kt`

**Delete or reduce to a minimal utility file.** Most of its logic moves to `PromptBuilder`. If any shared utilities remain (like the `UserMessage` data class), keep them; otherwise delete.

### 3.6 MODIFY: Warning Construction

Move warning string construction from `AgentTurnRunner.buildStepReminder()` to simpler inline format:

```kotlin
private fun buildWarnings(
    loopWarning: LoopWarning?,
    stepDecision: ExecutorStepDecision,
    turnNumber: Int
): List<String> {
    return buildList {
        loopWarning?.let {
            val emoji = if (it.severity == LoopWarningSeverity.CRITICAL) "🚨" else "⚠️"
            add("$emoji ${it.message}")
        }
        when (stepDecision) {
            ExecutorStepDecision.Continue -> {}
            ExecutorStepDecision.WarnApproaching -> {
                add("⏰ Turn $turnNumber of ${config.maxTurns} — prioritize decisive action.")
            }
            is ExecutorStepDecision.ForceStop -> {
                add("🛑 FINAL TURN (${config.maxTurns}). Complete now or report progress.")
            }
        }
    }
}
```

No `<system_reminder>` tags. No `trimIndent()` template strings. Just strings.

### 3.7 NO CHANGE (preserved as-is)

| File | Reason |
|------|--------|
| `perception/Perceptor.kt` | Screen capture and JSON generation unchanged |
| `perception/ScreenSummary.kt` | Used for compressed screen observations |
| `session/TodoState.kt` | `toPromptContext()` format unchanged |
| `session/ScratchpadState.kt` | `toPromptContext()` format unchanged |
| `agent/cognition/policy/LoopDetectionPolicy.kt` | Loop detection logic unchanged |
| `agent/cognition/policy/ExecutorStepPolicy.kt` | Step policy logic unchanged |
| `agent/cognition/policy/TurnToolPolicy.kt` | Tool arbitration unchanged |
| `agent/Agent.kt` | Turn loop unchanged |
| `agent/subagent/SubAgentRunner.kt` | Sub-agent spawning unchanged |
| `tool/impl/*` | Tool implementations unchanged |
| `tool/ToolRouter.kt` | Routing/approval unchanged |

---

## 4. Migration Strategy

### Phase 1: Foundation (No behavior change)

1. Add `isScreenObservation` flag to `ResponseItem.Message`
2. Create `PromptBuilder` alongside existing code (don't wire it up yet)
3. Write unit tests for `PromptBuilder` sections

### Phase 2: Wire PromptBuilder

4. Update `AgentTurnRunner.runPlanningPhase()` to use `PromptBuilder`
5. Add screen observation recording at turn start
6. Verify: run a session, compare trace artifacts against previous runs

### Phase 3: Clean Up Tool Results

7. Simplify `formatToolResult()` — remove observation parameter
8. Verify: tool results in trace no longer contain screen summaries

### Phase 4: Simplify Turn + Remove Dead Code

9. Simplify `Turn` — remove `buildInputItems()`, `buildUserContextItem()`
10. Remove `PromptUtils` (or reduce to minimal utilities)
11. Remove `PromptContext` data class
12. Remove `UserMessage` data class (if no longer needed)
13. Remove `buildPromptContext()` and `buildAdditionalContextBlocks()` from `AgentTurnRunner`

### Phase 5: Verify

14. Run full verification: `./gradlew clean assembleDebug lint test`
15. Run a real agent session and compare trace artifacts
16. Verify token counts are within expected range

---

## 5. Files Touched Summary

| Action | File | Lines Changed (est.) |
|--------|------|---------------------|
| **NEW** | `agent/cognition/prompt/PromptBuilder.kt` | ~120 |
| MODIFY | `history/HistoryManager.kt` (ResponseItem.Message) | ~5 |
| MODIFY | `agent/AgentTurnRunner.kt` | ~80 (remove/simplify) |
| MODIFY | `agent/Turn.kt` | ~60 (remove prompt building) |
| DELETE/REDUCE | `agent/cognition/prompt/PromptUtils.kt` | ~-130 |
| DELETE | `agent/cognition/prompt/PromptContext` (inline in PromptUtils) | ~-24 |
| **Net** | | **~-10 lines** (fewer total lines despite new file) |

The refactoring is a net **reduction** in code. We're not adding complexity — we're removing indirection.

---

## 6. Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Screen observations in history inflate tokens | Medium | `compressOldScreenObservations()` limits to N full + rest compressed; existing `HistoryManager.compress()` provides safety net |
| Removing screen from tool outputs confuses LLM | Low | LLM already sees full screen at turn start; the summary in tool output was redundant |
| Consecutive user messages (memory + observation) | Low | OpenAI Responses API handles this fine; tested in other agent frameworks |
| Missing todo/scratchpad reminders | Low | Memory section shows both explicitly; XML reminder was redundant |
| Regression in existing behavior | Medium | Phase-by-phase migration with trace comparison at each step |

---

## 7. Definition of Done

- [ ] `PromptBuilder.buildInputItems()` produces correct input items for all 3 agent roles
- [ ] Screen observations stored in history with `isScreenObservation = true`
- [ ] Last 3 turns' screen observations are full JSON; older are compressed
- [ ] Tool results contain no screen state information
- [ ] No `<system_reminder>` tags in output
- [ ] `PromptUtils.kt` deleted or reduced to shared utilities only
- [ ] `Turn.kt` has no prompt-building logic — only LLM calling
- [ ] Build passes: `./gradlew clean assembleDebug lint test`
- [ ] Trace artifacts show correct prompt structure
- [ ] Token count per turn is within expected range (see §1 budget analysis)
