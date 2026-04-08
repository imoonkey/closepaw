# Dead Code & Over-Abstraction Review

Date: 2026-04-08
Scope: `app/src/main/kotlin/com/moonkey/androidagent/` (267 files)

---

## Perspective A: Dead Code

### A1. Fully Dead Files (orphan/unreferenced)

| # | File | Evidence | Lines |
|---|------|----------|-------|
| 1 | `util/StatusUtils.kt` | Zero imports outside its own file. No file in the codebase imports `StatusUtils`. The overlay/capsule layer has its own inline status logic. | ~104 |
| 2 | `tool/ToolCallState.kt` | Only referenced inside `ToolRouter.kt` at the type level in one import, but `ToolRouter` actually uses `ToolCallResult` not `ToolCallState` for its public API. The full state machine (Validating/Scheduled/AwaitingApproval/Executing/Success/Error/Cancelled) described here is never instantiated anywhere in the codebase. It was designed for a Gemini-CLI-like scheduler that was never implemented. | ~115 |
| 3 | `tool/handlers/DataQueryInvocation.kt` | The only import is itself. No tool creates a `DataQueryInvocation` instance. It was designed for a `list_apps` tool that does not exist. | ~51 |
| 4 | `perception/ScreenSummary.kt` | `toSummary()` is called from one place: `tool/action/ObservationBuilder.kt`. However, `ObservationBuilder` was superseded by inline observation building in `TurnExecutionPhaseRunner`. Verify: if `ObservationBuilder` itself is still called. | ~52 |

### A2. Unused Sealed Class / Type Hierarchy Members

| # | Item | Evidence |
|---|------|----------|
| 5 | `AgentError` (entire file) | Zero usages anywhere. No file imports `AgentError`, no code constructs any variant (LLMError, PlatformError, etc.), `AgentError.from()` is never called. The agent uses `TurnErrorClassifier` + raw exceptions instead. |
| 6 | `ExecutorStepDecision.WarnApproaching` | Produced by `ExecutorStepPolicy.evaluate()` but never consumed. The caller in `AgentTurnRunner.buildWarnings()` only checks for `ForceStop`; `WarnApproaching` silently falls through as `Continue`. |
| 7 | `ScreenSnapshot.hasElements` / `ScreenSnapshot.hasScreenshot` | Convenience properties on `ScreenSnapshot` -- grep confirms zero usages anywhere in the codebase. |
| 8 | `ScreenSnapshotDebug.captureQualityPath` | Field declared but only set in `AccessibilityPlatform`; never read by any consumer. |
| 9 | `Bounds.width` / `Bounds.height` / `Bounds.centerX` / `Bounds.centerY` | Declared on `Bounds` but never accessed. All code uses `PerceptionElement.center` or reads `left/top/right/bottom` directly. |
| 10 | `MobileActionName.Back`, `.Home`, `.Wait`, `.SystemButton` | Defined as known action names but never matched against. The agent uses separate `system_button` and `wait` tools, not mobile_action sub-actions. These exist only in `from()` dispatch but no caller ever passes these values. |
| 11 | `MobileActionName.fromOrNull()` | Only used in `PolicyEngine.kt` and `ToolUi.kt` for display -- but `PolicyEngine` calls `MobileActionName.from()` (the non-nullable variant), and `ToolUi` calls `fromOrNull` once. Low value. |

### A3. Unused Functions / Methods

| # | Function | Location | Evidence |
|---|----------|----------|----------|
| 12 | `SessionServices.updateApprovalMode()` | `session/SessionServices.kt` | Zero callers. Approval mode is set at session creation and never changed. |
| 13 | `SessionServices.getSummary()` | `session/SessionServices.kt` | Zero callers. The formatter (`SessionServicesSummaryFormatter`) has zero imports outside itself and `SessionServices`. |
| 14 | `SessionServicesSummaryFormatter.format()` | `session/SessionServicesSummaryFormatter.kt` | Only called by `getSummary()` which is itself dead. Entire file is dead. |
| 15 | `AppClassifier.addUserOverride()` | `tool/AppClassifier.kt` | Zero callers. The user-override path was never wired. |
| 16 | `ToolCallResult.isSuccess()` | `tool/ToolCallResult.kt` | Zero external callers. All code uses `is ToolCallResult.Success` pattern matching. |
| 17 | `ToolCallResult.getOutputOrNull()` | `tool/ToolCallResult.kt` | Zero callers. |
| 18 | `ToolSpec.toFunctionSchema()` | `tool/ToolSpec.kt` | Zero callers. Tool schemas are generated via `ToolRegistry.generateResponsesApiTools()` using `jsonObjectToJsonValueMap`, not `toFunctionSchema()`. |
| 19 | `ActionResult.isSuccess()` | `platform/ActionResult.kt` | Zero callers. Code uses `is ActionResult.Success` exhaustive when matching. |

### A4. Unused Fields / Parameters

| # | Item | Evidence |
|---|------|----------|
| 20 | `ScreenSnapshot.textEnriched` | Set in `Perceptor` and read in `UiChangeDetector`, but `UiChangeDetector` only checks it to skip a code path that was already removed. Effectively dead. |
| 21 | `ToolCallResult.Success.data` | Always `null` at every construction site. No consumer reads it. |
| 22 | `ToolExecutionResult.Success.data` | Same as above -- always `null`, never read. |

### A5. Dead Branches / Unreachable Code

| # | Item | Evidence |
|---|------|----------|
| 23 | `ExecutorStepDecision.WarnApproaching` branch | `ExecutorStepPolicy` returns this but `AgentTurnRunner.buildWarnings()` does nothing with it -- only `ForceStop` is handled. The warning branch produces a value that is silently discarded. |

---

## Perspective B: Over-Abstraction

### B1. Single-Implementation Interfaces

| # | Interface | Impl | Verdict |
|---|-----------|------|---------|
| 1 | `OnboardingDemoController` | `DefaultOnboardingDemoController` | Only one implementation exists. No test doubles (mocking could use the class directly). The interface adds indirection without value. **Simplify: merge into one class.** |
| 2 | `LlmCredentialValidator` | `HttpLlmCredentialValidator` | Same pattern. Only one implementation, never mocked. **Simplify: merge.** |
| 3 | `AppSkillRepository` | `AssetAppSkillRepository` + `EmptyAppSkillRepository` | `EmptyAppSkillRepository` is used only as the default in `SessionServices` constructor, which is immediately overwritten by `AssetAppSkillRepository` in `create()`. The interface exists for one real impl. However, **keep**: `EmptyAppSkillRepository` serves as a null-object for sub-agent services that don't need skills. This is legitimate. |

### B2. Unnecessary Type Hierarchies

| # | Item | Analysis |
|---|------|----------|
| 4 | `AgentError` sealed class hierarchy | 11 subclasses, zero usages. This was designed as a comprehensive error taxonomy but is completely unused. The actual error handling uses `TurnErrorClassifier` with raw exceptions. **Delete entirely.** |
| 5 | `ToolCallState` sealed class | 7 states in a formal state machine diagram, but the state machine was never implemented. `ToolRouter` goes directly from params to result. **Delete entirely.** |
| 6 | `ToolName` sealed class | Used extensively and correctly, but `ToolName.Unknown` could theoretically be an enum with a fallback. However, the sealed class approach is idiomatic Kotlin and the `isScreenChanging` property benefits from exhaustive when. **Keep.** |
| 7 | `MobileActionName` sealed class | Same pattern as `ToolName`. Some members are dead (Back, Home, Wait, SystemButton) but the class itself is used. **Keep class, remove dead members.** |
| 8 | `AgentDef` abstract class hierarchy | `AgentDef` -> `StandaloneAgentDef`, `PlannerAgentDef`, `ExecutorAgentDef`. Three concrete objects extend one abstract class. This is clean and justified by the agent mode system. **Keep.** |

### B3. Over-Generic / Over-Designed Code

| # | Item | Analysis |
|---|------|----------|
| 9 | `AgentEventDomains.kt` | 12 sealed marker interfaces (`SessionLifecycleEvent`, `TaskLifecycleEvent`, `PlanningStateEvent`, etc.) that add a domain taxonomy to `AgentEvent`. These are referenced only in the event data classes themselves. No consumer filters by domain interface -- all event handling uses `when` on concrete types. These add zero runtime value. **Consider removing** -- but low cost, so low priority. |
| 10 | `ToolCallResult.Success.data: Any?` | A generic `Any?` data bag. Always null. If needed in the future, add it then. **Remove.** |
| 11 | `ToolExecutionResult.Success.data: Any?` | Same. Always null. **Remove.** |

### B4. Pass-Through Layers

| # | Item | Analysis |
|---|------|----------|
| 12 | `SessionServicesSummaryFormatter` | A separate object to format one debug string. Called by one dead function. **Delete entirely (along with the dead `getSummary()` method).** |
| 13 | `DataQueryInvocation` | A `ToolInvocation` wrapper for non-UI data queries. No tool uses it. The existing pattern of tool impls returning `textToolSuccess()` is simpler. **Delete.** |

### B5. Premature Abstractions That Are Justified (Keep)

| # | Item | Why Keep |
|---|------|----------|
| - | `LLMClient` abstract class | Two real implementations (OpenAI + LFM local). Correctly abstract. |
| - | `AndroidPlatform` interface | Two implementations (Accessibility + VirtualDisplay). Correctly abstract. |
| - | `AppSkillRepository` interface | Null-object pattern used in sub-agent services. Low cost. |
| - | `TraceRecorder` interface | `FileTraceRecorder` + no-op for non-trace sessions. Justified. |
| - | `ToolSpec` / `ToolInvocation` | Clean separation of spec (schema) from execution. Multiple tool impls. |

---

## Synthesis: Combined Priority List

### High Priority (clear dead code, safe to remove)

1. **`protocol/AgentError.kt`** -- Entire file is dead. Zero references. (~170 lines)
2. **`tool/ToolCallState.kt`** -- Entire file is dead. Zero instantiations. (~115 lines)
3. **`tool/handlers/DataQueryInvocation.kt`** -- Entire file is dead. (~51 lines)
4. **`util/StatusUtils.kt`** -- Entire file is dead. Zero imports. (~104 lines)
5. **`session/SessionServicesSummaryFormatter.kt`** -- Entire file is dead. (~31 lines)
6. **`SessionServices.getSummary()`** and **`SessionServices.updateApprovalMode()`** -- Dead methods.
7. **`AppClassifier.addUserOverride()`** -- Dead method.
8. **`ToolCallResult.isSuccess()`, `.getOutputOrNull()`, `.data`** -- Dead methods/field.
9. **`ToolExecutionResult.Success.data`** -- Dead field.
10. **`ToolSpec.toFunctionSchema()`** -- Dead method.
11. **`ActionResult.isSuccess()`** -- Dead method.

### Medium Priority (dead members within live files)

12. **`Bounds.width/height/centerX/centerY`** -- Never accessed.
13. **`ScreenSnapshot.hasElements/hasScreenshot`** -- Never accessed.
14. **`ScreenSnapshotDebug.captureQualityPath`** -- Set but never read.
15. **`MobileActionName.Back/Home/Wait/SystemButton`** -- Never matched.
16. **`ExecutorStepDecision.WarnApproaching`** -- Produced but never consumed.

### Low Priority (simplification opportunities)

17. **`OnboardingDemoController` interface** -- Merge with `DefaultOnboardingDemoController`.
18. **`LlmCredentialValidator` interface** -- Merge with `HttpLlmCredentialValidator`.
19. **`AgentEventDomains.kt` marker interfaces** -- Remove if no consumer filters by domain.
20. **`ScreenSummary.kt`** -- Verify if `ObservationBuilder` is still called; if not, both are dead.
