# Dead Code & Over-Abstraction Reassessment (Codex)

Date: 2026-04-16

I re-read both `review.md` and `improvement_plan.md`, then checked every referenced symbol/file against the current codebase. The improvement plan mostly repeats the review, so the verdicts below apply to both.

## Still Valid

These are still real dead-code / over-abstraction issues in the current tree and are worth solving.

| Item | Evidence |
|---|---|
| `util/StatusUtils.kt` | `rg -n "StatusUtils|cleanStatusText|getStatusType|isTerminalStatus" app/src/main/kotlin app/src/test/kotlin` returns only `util/StatusUtils.kt`. |
| `session/SessionServicesSummaryFormatter.kt` | `rg -n "SessionServicesSummaryFormatter|getSummary\\(" app/src/main/kotlin app/src/test/kotlin` finds only `SessionServicesSummaryFormatter.kt` and `SessionServices.getSummary()`; nothing else imports/calls it. |
| `.DS_Store` under Kotlin sources | `find app/src/main/kotlin/com/moonkey/androidagent -name '.DS_Store'` returns `app/src/main/kotlin/com/moonkey/androidagent/.DS_Store`. |
| `SessionServices.getSummary()` | `rg -n "getSummary\\(" app/src/main/kotlin app/src/test/kotlin` shows `SessionServices.kt:201` plus unrelated `ToolRegistry` / `HistoryManager` summaries; no callers of `SessionServices.getSummary()`. |
| `SessionServices.updateApprovalMode()` | `rg -n "updateApprovalMode\\(" app/src/main/kotlin app/src/test/kotlin` returns only `SessionServices.kt:195`. |
| `AppClassifier.addUserOverride()` + `userOverrides` | `rg -n "addUserOverride\\(|userOverrides" app/src/main/kotlin app/src/test/kotlin` only hits `tool/AppClassifier.kt`; no external callers. |
| `ToolCallResult.isSuccess()` | `rg -n "\\.isSuccess\\(" app/src/main/kotlin app/src/test/kotlin` returns no matches. |
| `ToolCallResult.getOutputOrNull()` | `rg -n "\\.getOutputOrNull\\(" app/src/main/kotlin app/src/test/kotlin` returns no matches. |
| `ToolSpec.toFunctionSchema()` | `rg -n "toFunctionSchema\\(|generateResponsesApiTools" app/src/main/kotlin app/src/test/kotlin` shows only the definition in `ToolSpec.kt`; `Turn.kt` uses `toolRegistry.generateResponsesApiTools(...)` instead. |
| `ToolSpec.ValidationResult.isValid()` | `rg -n "\\.isValid\\(" app/src/main/kotlin app/src/test/kotlin` returns no matches. |
| `ToolSpec.ToolExecutionResult.isSuccess()` | `rg -n "\\.isSuccess\\(" app/src/main/kotlin app/src/test/kotlin` returns no matches; code uses sealed-type matching instead. |
| `ActionResult.isSuccess()` | `rg -n "ActionResult\\.isSuccess\\(|\\.isSuccess\\(" app/src/main/kotlin app/src/test/kotlin` shows no callers; action handling uses `when` on `ActionResult`. |
| `ApiKeysSection` | `rg -n "ApiKeysSection\\(" app/src/main/kotlin app/src/test/kotlin` returns only `ui/settings/ApiKeyFields.kt:30`. |
| `BackendSelector` | `rg -n "BackendSelector\\(" app/src/main/kotlin app/src/test/kotlin` returns only `ui/settings/SettingsDropdowns.kt:22`. |
| `SettingsDropdownOptionWithDescription` | `rg -n "SettingsDropdownOptionWithDescription\\(" app/src/main/kotlin app/src/test/kotlin` returns only `ui/settings/SettingsDropdown.kt:116`. |
| `refreshOAuthToken()` | `rg -n "refreshOAuthToken\\(" app/src/main/kotlin app/src/test/kotlin` returns only `auth/OpenAiSignIn.kt:92`. |
| `OnboardingViewModel.context` | `rg -n "\\bcontext\\b" app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt` only hits the constructor parameter. The file body never uses it. |
| `DefaultOnboardingDemoController.modelCatalog` | `rg -n "\\bmodelCatalog\\b" app/src/main/kotlin/com/moonkey/androidagent/onboarding/DefaultOnboardingDemoController.kt app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt` shows only the constructor field and the `MainActivity` call-site argument. |
| `ScreenSnapshotDebug.captureQualityPath` | `rg -n "captureQualityPath" app/src/main/kotlin app/src/test/kotlin` only finds the field in `model/Models.kt` and the setter in `platform/AccessibilityPlatform.kt`. |
| `SessionHistoryManager.deleteSessionByFileName()` | `rg -n "deleteSessionByFileName\\(" app/src/main/kotlin app/src/test/kotlin` returns only `history/SessionHistoryManager.kt:134`. |
| `SessionHistoryManager.getMostRecentSession()` | `rg -n "getMostRecentSession\\(" app/src/main/kotlin app/src/test/kotlin` returns only `history/SessionHistoryManager.kt:147`. |
| `SessionHistoryManager.hasActiveSession()` | `rg -n "hasActiveSession\\(" app/src/main/kotlin app/src/test/kotlin` shows `SessionHistoryManager.kt` plus `SessionRecordingService.kt`; no caller uses the manager wrapper. |
| `SessionHistoryManager.endSession()` | `rg -n "endSession\\(" app/src/main/kotlin app/src/test/kotlin` returns only `history/SessionHistoryManager.kt:209`. |
| `SessionHistoryManager.loadSessionByFileName()` should be private | `rg -n "loadSessionByFileName\\(" app/src/main/kotlin app/src/test/kotlin` shows one internal call from `loadSession()` plus the method definition; no external callers. |
| `OnboardingDemoController` single-impl abstraction | `rg -n "OnboardingDemoController" app/src/main/kotlin app/src/test/kotlin` shows one interface file, one implementation (`DefaultOnboardingDemoController`), one nullable field in `OnboardingViewModel`, and post-construction wiring in `MainActivity`. The late-injected `demoController?.run(...)` path still exists. |
| `delegate_task.agent_name` fake choice | `ExecutorRoleDef` is still the only delegatable role (`ExecutorAgentDef.kt:18` sets `delegatable = true`; `AgentDefRegistryTest` asserts `delegatableRoles().hasSize(1)`), but `DelegateTaskTool` still requires `agent_name` in schema/validation/execution. |

## No Longer True

These old findings/planned changes no longer match the current code.

| Old item | What changed |
|---|---|
| `tool/handlers/DataQueryInvocation.kt` dead file | The file is already gone. `sed` on the old path fails with `No such file or directory`, and `rg -n "DataQueryInvocation" app/src/main/kotlin app/src/test/kotlin` returns no matches. |
| `AgentRegistry.getAll()` dead method | There is no `AgentRegistry` anymore. Current code uses `agent/definition/AgentDefRegistry.kt`, and `rg -n "AgentRegistry" ...` returns no matches. |
| `AgentDef.id` dead hierarchy property | The old hierarchy was replaced. Current `AgentRoleDef` has `name`, `executionRole`, `systemPrompt`, `allowedTools`, `delegatable`, `description`, `maxTurns`, and `timeoutMs`; there is no `id` field to remove. |
| `ExecutorStepDecision.WarnApproaching` dead branch | The old step-limit policy is gone. `rg -n "ExecutorStepDecision|ExecutorStepPolicy|WarnApproaching" ...` returns no matches. Current warning logic lives in `LoopDetectionPolicy` + `AgentTurnRunner.buildWarnings(...)`. |
| Broad `AgentRegistry`/catalog collapse from Phase 4.2 | The current registry is no longer a fake one-entry catalog. `AgentDefRegistry` now resolves three real top-level roles (`StandaloneRoleDef`, `PlannerRoleDef`, `ExecutorRoleDef`) and also filters delegatable roles. The right simplification now is only `delegate_task.agent_name`, not deleting the registry/role layer. |
| `narrativeSummaryOnLimit` dead flag | `rg -n "narrativeSummaryOnLimit" app/src/main/kotlin app/src/test/kotlin` returns no matches. Max-turn narration is now hard-wired through `DelegationSummaryFormatter` inside `SubAgentRunner`. |

## ROI Too Low

These are still technically true or partially true, but I would not prioritize them unless I am already editing the same area.

| Item | Tradeoff |
|---|---|
| `LlmCredentialValidator` single-impl interface | `rg -n "LlmCredentialValidator|HttpLlmCredentialValidator" ...` confirms there is only one implementation, but the abstraction is tiny and does not create the same nullable/two-phase wiring problem that `OnboardingDemoController` does. Collapsing it saves little. |
| `ScreenSnapshot.hasScreenshot` | Verified: `rg -n "hasScreenshot" app/src/main/kotlin app/src/test/kotlin` finds the property in `Models.kt` and one test assertion in `CapturePrivacyGateTest`; no production reads. It is dead in prod, but removing one computed property buys almost nothing. |
| `AgentEventDomains.kt` marker interfaces | Verified: events still implement these marker interfaces, but `rg -n "is SessionLifecycleEvent|is TaskLifecycleEvent|..." app/src/main/kotlin app/src/test/kotlin` returns no consumer filters. Removing them would touch many event files for a cosmetic cleanup. |
| `ToolRouterContext` flattening | Verified: only one production caller (`TurnExecutionPhaseRunner`) uses `SimpleToolRouterContext`, but tests rely on the small wrapper heavily. The current interface/impl pair is local, tiny, and not worth churning by itself. |

## New Findings

These did not survive the old review correctly, or are new dead/over-abstracted areas I noticed while checking the current tree.

| Item | Evidence |
|---|---|
| `ToolCallResult.Success.data` is dead now | `ToolRouter.kt:294` forwards `executionResult.data`, but `rg -n "toolResult\\.data|success\\.data|result\\.data" app/src/main/kotlin app/src/test/kotlin` finds no production reads of the `ToolCallResult.Success.data` payload. |
| `ToolExecutionResult.Success.data` is also dead | Writers still exist (`WriteTodosTool`, `DelegateTaskTool`, `CompleteTaskTool`, `AskUserTool`, `textToolSuccess`), but `rg -n "executionResult\\.data|success\\.data|result\\.data" ...` finds no production readers. The old review explicitly kept this payload alive, but current code does not consume it. |
| `SessionHistoryManager.scope` stored property is unused | `rg -n "\\bscope\\b" app/src/main/kotlin/com/moonkey/androidagent/history/SessionHistoryManager.kt` only hits the constructor field and factory wiring; the manager never reads the stored `scope`. |
| `AgentError` is mostly dead taxonomy now | `SessionError` emission is live, but `rg -n "AgentError\\.|AgentError.from\\(|isRecoverable" ...` shows only `AgentError.PlatformError(...)` is ever constructed in production, `AgentError.from(...)` has no callers, and `isRecoverable` is never read. The file is carrying far more variants/metadata than the current app uses. |

## Recommended Revised Plan

1. Do one safe deletion sweep for the obvious dead code: `StatusUtils.kt`, `.DS_Store`, `SessionServicesSummaryFormatter.kt`, `SessionServices.getSummary()`, `SessionServices.updateApprovalMode()`, `AppClassifier.addUserOverride()` + `userOverrides`, dead helper methods on `ToolCallResult` / `ToolSpec` / `ActionResult`, dead settings composables, `refreshOAuthToken()`, `OnboardingViewModel.context`, `DefaultOnboardingDemoController.modelCatalog`, `ScreenSnapshotDebug.captureQualityPath`, and the dead `SessionHistoryManager` API surface.
2. Fix the onboarding abstraction that is actually hurting the code: inject a concrete demo controller into `OnboardingViewModel` at construction time and remove the nullable late-assignment path. Keep this separate from broader onboarding refactors.
3. Simplify `delegate_task` only at the tool boundary: remove `agent_name` from the schema and invocation path, hardcode the executor target, but keep `AgentRoleDef` / `AgentDefRegistry` for real top-level role selection.
4. Remove the dead success-payload plumbing: delete `data` from `ToolExecutionResult.Success` and `ToolCallResult.Success`, then remove the now-pointless writers/forwarders.
5. Defer low-ROI cleanup (`LlmCredentialValidator`, `ScreenSnapshot.hasScreenshot`, `AgentEventDomains`, `ToolRouterContext`) unless adjacent code is already being edited.
6. Treat `AgentError` as a separate follow-up, not part of the original plan. There is cleanup available there, but it is a new finding and should be scoped intentionally.

## Verification Coverage

These old review items were re-checked and are still correctly excluded from the cleanup plan, except where noted.

### Old "Explicitly NOT Dead" Items

| Item | Current verdict | Evidence |
|---|---|---|
| `ToolCallState.kt` | Still live | `ToolRouter.execute(...)` constructs `ToolCallState.*`, and tests still cover it (`ToolRouterTest`, `ToolCallStateTest`). |
| `Bounds.width/height/centerX/centerY` | Still live | `ScrollExecutor` uses all four accessors for swipe computation and scroll targeting. |
| `ScreenSnapshot.hasElements` | Still live | `TargetResolver.resolveElementIndex/resolveText` and `ObservationBuilder.buildObservation` still branch on it. |
| `ToolCallResult.Success.data` | Old review is now stale | See **New Findings** above. |
| `ToolExecutionResult.Success.data` | Old review is now stale | See **New Findings** above. |
| `MobileActionName.Back/Home/Wait/SystemButton` | Still live | `PolicyEngine.isEscape()` and `ui/common/ToolUi.kt` still reference them. |
| `ObservationBuilder.kt` / `ScreenSummary.kt` | Still live | `OpenAppTool` and post-action analysis still build observations and summaries from them. |
| `ScreenSnapshot.textEnriched` | Still live | `Perceptor.toPromptJson(...)` and `UiChangeDetector.fingerprint(...)` still branch on it. |

### Old "Abstractions Verified & Kept" Items

| Item | Current verdict | Evidence |
|---|---|---|
| `LLMClient` | Still a real abstraction | Four production implementations remain: `ChatCompletionClient`, `OpenAIResponseClient`, `CodexResponseClient`, `LFMLLMClient`. |
| `AndroidPlatform` | Still a real abstraction | Two production implementations remain: `AccessibilityPlatform`, `VirtualDisplayPlatform`. |
| `TraceRecorder` | Still a real abstraction | `FileTraceRecorder` and `NoopTraceRecorder` both exist and are used. |
| `AgentDef` hierarchy | Still a real abstraction, but under a new shape | The old hierarchy became `AgentRoleDef` + three concrete role defs (`StandaloneRoleDef`, `PlannerRoleDef`, `ExecutorRoleDef`). |
| `AppSkillRepository` | Still a real abstraction | `AssetAppSkillRepository` and `EmptyAppSkillRepository` both exist. |
| `ToolSpec` / `ToolInvocation` | Still a real abstraction | The tool system still depends on these interfaces across many tool implementations. |
