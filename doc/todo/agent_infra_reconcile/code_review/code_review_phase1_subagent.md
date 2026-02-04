# Code Review: Phase 1 Sub-Agent Infrastructure

> **Reviewer**: Claude
> **Date**: 2026-02-04
> **Scope**: Uncommitted changes for Phase 1 (sub-agent delegation + swipe bug fix)

---

## Summary

This PR implements the Phase 1 Sub-Agent Infrastructure as specified in `phase1_subagent_claude.md`. The changes introduce:

1. **Sub-agent delegation infrastructure**: `AgentDefinition`, `AgentRegistry`, `SubAgentRunner`, `DelegateTaskTool`
2. **Tool filtering**: Per-agent tool allowlists at `AgentConfig`, `Turn`, and `AgentPromptBuilder` levels
3. **Planner-Executor split**: Main agent becomes a "planner" that delegates atomic UI actions to an "executor" sub-agent
4. **Protocol events**: `SubAgentStarted`, `SubAgentActivity`, `SubAgentCompleted`
5. **Swipe bug fix**: Changed overlay windows from `TYPE_APPLICATION_OVERLAY` to `TYPE_ACCESSIBILITY_OVERLAY` (fixes untrusted touch blocking on Android 12+)

**Files changed**: 20 files, +673/-398 lines

---

## Critical

None found.

---

## High (Should Fix)

### 1. Scratchpad shared by reference may cause concurrent modification

**Where**: `SubAgentRunner.kt:42-44`

```kotlin
sessionState = AgentSessionState(
    scratchpad = parentServices.sessionState.scratchpad
)
```

The `scratchpad` is a `ThreadSafeStringMap` (presumably), but the reference is shared directly. If the parent and child agents run any concurrent code that accesses scratchpad (unlikely in current serial design but possible with future changes), there's potential for subtle bugs.

**Fix**: Document that this is intentional for parent-child data passing. Consider adding a comment clarifying the shared reference is deliberate. Or, if `ThreadSafeStringMap` isn't actually thread-safe across coroutines, copy the map.

### 2. Missing `app_control` in planner's allowed tools despite prompt mentions

**Where**: `SessionAgentRunner.kt:29-35` vs `AgentRuntime.kt:63`

The prompt says:
> "- app_control: For fast app launch (use directly without delegation if simpler)."

But `PLANNER_ALLOWED_TOOLS` includes `app_control`. This is correct! However, the executor's `ExecutorAgent.definition.toolNames` also includes `app_control`:

```kotlin
toolNames = listOf("mobile_action", "app_control", "scratchpad", "complete_task")
```

**Consideration**: Should `app_control` be executor-only, planner-only, or both? The current design allows both, which is flexible but may lead to confusion. Clarify in the design doc or add a comment.

### 3. `SmartCapsuleManager` was rewritten inline, duplicating `SmartCapsuleLayoutBuilder`

**Where**: `SmartCapsuleManager.kt:85-220`

The `SmartCapsuleLayoutBuilder` exists to build the capsule layout, but the new code in `SmartCapsuleManager.show()` now builds the layout inline, duplicating many concepts. The builder is still used elsewhere (via `createLayoutParams()`), but the main layout construction was replaced.

**Fix**: Either:
1. Remove `SmartCapsuleLayoutBuilder` if it's no longer needed, OR
2. Update `SmartCapsuleLayoutBuilder` to use `TYPE_ACCESSIBILITY_OVERLAY` and keep using it

This duplication increases maintenance burden.

---

## Medium (Consider)

### 4. Prompt is very long and may exceed context limits on small models

**Where**: `AgentRuntime.kt:29-90`, `ExecutorAgent.kt:10-79`

The planner prompt is ~50 lines, executor prompt is ~80 lines. Combined with screen state JSON, this may consume significant context on smaller local models.

**Suggestion**: Consider extracting examples to a separate reference section or compressing the prompts for local model configs.

### 5. `Turn.buildSystemPrompt()` conditionally generates different prompts based on tool visibility

**Where**: `Turn.kt:230-282`

```kotlin
val hasDelegate = "delegate_task" in visibleTools
val hasMobileAction = "mobile_action" in visibleTools

val roleRules = if (hasDelegate && !hasMobileAction) { ... } else { ... }
```

This implicit role detection via tool presence is clever but could be confusing. If someone adds `mobile_action` to planner's allowed tools for debugging, the prompt silently switches to "Executor Rules".

**Suggestion**: Consider passing an explicit `agentRole: AgentRole` enum instead of inferring from tools.

### 6. `DelegateTaskTool.description` embeds `registry.getDirectoryPrompt()` at construction time

**Where**: `DelegateTaskTool.kt:27-45`

```kotlin
override val description: String =
    """
    ...
    ${registry.getDirectoryPrompt()}
    ...
    """.trimIndent()
```

If agents are registered after the tool is created, the description won't update. Currently fine since `AgentRegistry.createDefault()` is called before tool construction, but could be a gotcha if registry becomes dynamic.

**Suggestion**: Document this constraint or make description lazy.

### 7. `AccessibilityPlatform` removed `buildElementLabelSuffix` — action results less informative

**Where**: `AccessibilityPlatform.kt:349-412`

Before: `"Clicked element 5: \"Send Button\""`
After: `"Clicked element 5"`

The label suffix helped debugging and understanding what element was clicked.

**Suggestion**: Consider keeping the label suffix for observability, or document why it was removed.

### 8. Missing test for `AgentPromptBuilder.visibleToolNames` filtering

**Where**: `AgentPromptBuilderTest.kt` exists but only one test

The test covers the basic case but doesn't test:
- `visibleToolNames = null` (should show all tools)
- Edge case: empty `visibleToolNames`

**Suggestion**: Add edge case tests.

### 9. `complete_task` result parsing checks "answer" then "summary"

**Where**: `AgentTurnRunner.kt:292-294`

```kotlin
completeTaskCall?.arguments?.optString("answer")?.takeIf { it.isNotBlank() }
    ?: completeTaskCall?.arguments?.optString("summary")
```

This dual-field support suggests the schema evolved. The `complete_task` tool should have a single canonical field.

**Suggestion**: Standardize on one field name across all prompts and schemas.

---

## Low (Nice-to-Have)

### 10. `SubAgentRunner.kt` has private extension functions at file level

**Where**: `SubAgentRunner.kt:118-180`

The `CompletionPayload` data class and extension functions are at file level with `private` visibility. This is fine, but could be organized into a companion object or separate file if the file grows.

### 11. `AgentRegistry` uses `linkedMapOf` but order preservation isn't documented

**Where**: `AgentRegistry.kt:7`

Using `linkedMapOf` suggests order matters for `getDirectoryPrompt()`, but this isn't documented.

### 12. Icon choice for `DelegateTask` could be more semantic

**Where**: `ToolUi.kt:40`

```kotlin
ToolName.DelegateTask -> ToolDisplay(tool.displayName, Icons.Rounded.Apps)
```

`Icons.Rounded.Apps` is generic. Consider `Icons.Rounded.AccountTree` or `Icons.Rounded.Group` to better represent delegation/sub-agents.

### 13. `SubAgentActivity` events are captured but not logged

**Where**: `AgentService.kt:249-251`

```kotlin
is AgentEvent.SubAgentActivity -> {
    // Activity events can be very frequent; keep UI/log noise low.
}
```

The comment is good, but no logging at all may make debugging harder. Consider `Log.v()` (verbose) for these.

---

## Swipe Bug Fix (Overlay Flags)

### Changes
- `EdgeGlowManager.kt`: `TYPE_APPLICATION_OVERLAY` → `TYPE_ACCESSIBILITY_OVERLAY`
- `SmartCapsuleLayoutBuilder.kt`: Same + removed `FLAG_NOT_TOUCH_MODAL`
- `SmartCapsuleManager.kt`: Same (inline construction)
- `ActionVisualizerManager.kt`: Same

### Assessment
This is the correct fix for Android 12+ (S/API 31) "untrusted touch" blocking. `TYPE_ACCESSIBILITY_OVERLAY` windows from accessibility services are trusted and exempt from the security policy.

**No issues found with this fix.** The comments added explaining the rationale are helpful.

---

## Android-Specific Checks

- [x] Coroutines scoped correctly? — Yes, `withTimeoutOrNull` in `SubAgentRunner` is properly structured
- [x] No Context leaks? — No static Context refs found
- [x] Main thread safe? — Tool execution uses coroutines, overlay operations on main handler
- [x] Permissions checked? — Accessibility service has necessary permissions
- [x] A11y service best practices? — Using `TYPE_ACCESSIBILITY_OVERLAY` is correct

---

## Test Coverage

New tests added:
- `AgentRegistryTest` — 3 tests covering register/get/directory
- `SubAgentRunnerTest` — 4 tests covering success/timeout/answer extraction
- `DelegateTaskToolTest` — 3 tests covering validation/events/failure handling
- `TurnToolFilteringTest` — 2 tests for tool filtering
- `AgentPromptBuilderTest` — 1 test for visible tools
- `ToolRegistryTest` addition — 1 test for `createFilteredCopy`

**Coverage is good for the new code.** Consider adding:
- Edge cases for empty/null `allowedToolNames`
- Integration-style test for full planner→executor delegation flow (may need more mocking)

---

## Recommendation

**CHANGES_REQUESTED** — Address High items #1-3 before merge:

1. Add comment clarifying intentional scratchpad sharing in `SubAgentRunner`
2. Clarify `app_control` availability in design doc or add comment
3. Remove or update `SmartCapsuleLayoutBuilder` to avoid duplication

Medium items are suggestions for follow-up PRs.
