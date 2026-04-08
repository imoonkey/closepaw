# Cross-Review of Claude's Dead Code & Over-Abstraction Design

Reviewer: Codex
Date: 2026-04-08

---

## Overall Assessment

**Better base: CODEX**

`design_codex.md` is the safer base document. Claude found several real cleanup items that should be merged in, but `design_claude.md` and especially `improvement_plan_claude.md` contain multiple high-confidence false positives in the "safe deletion" bucket.

The main problem is not coverage. It is accuracy. Claude's plan proposes early deletion of code that is live today:

- `tool/ToolCallState.kt` is actively used by `tool/ToolRouter.kt`
- `tool/action/ObservationBuilder.kt` and `perception/ScreenSummary.kt` are actively used
- `model/ScreenSnapshot.hasElements` is actively used
- `model/Bounds.width/height/centerX/centerY` are actively used
- `ToolCallResult.Success.data` and `ToolExecutionResult.Success.data` are not dead

That makes Claude's improvement plan unsafe as an execution base. Codex's design is narrower, but it is materially more reliable: obvious dead code is separated from lower-confidence architectural simplifications, and I did not find comparable "delete live code" errors in it.

Claude's document is still useful as a supplement. The right merged approach is: use CODEX as the base, then add Claude's validated helper/API cleanup items.

---

## Items Claude Found That Codex Missed

These are the Claude findings that validated cleanly against the codebase and should be merged into the Codex plan:

1. `session/SessionServices.kt`: `updateApprovalMode()` has no callers.
2. `session/SessionServices.kt`: `getSummary()` has no callers.
3. `session/SessionServicesSummaryFormatter.kt`: effectively dead because its only caller is `SessionServices.getSummary()`.
4. `tool/AppClassifier.kt`: `addUserOverride()` has no callers. If removed, the backing `userOverrides` path also becomes dead.
5. `tool/ToolCallResult.kt`: `isSuccess()` and `getOutputOrNull()` have no callers.
6. `tool/ToolSpec.kt`: `toFunctionSchema()` has no callers.
7. `platform/ActionResult.kt`: `isSuccess()` has no callers.
8. `model/Models.kt`: `ScreenSnapshotDebug.captureQualityPath` is write-only. It is set in `platform/AccessibilityPlatform.kt` and never read anywhere in app/tests.
9. `agent/cognition/policy/ExecutorStepPolicy.kt`: `WarnApproaching` is produced and tested, but `agent/AgentTurnRunner.kt` only reacts to `ForceStop`, so the warning branch is dead in production behavior.
10. Low priority: `protocol/AgentEventDomains.kt` looks like pure marker-interface taxonomy. I found event declarations implementing it, but no consumers filtering on those marker types.

---

## Items Codex Found That Claude Missed

These are the validated Codex findings that do not appear in Claude's design/plan:

1. Orphan binary artifact in source tree: `app/src/main/kotlin/com/moonkey/androidagent/.DS_Store`
2. Unused settings UI leftovers:
   - `ui/settings/ApiKeyFields.kt`: `ApiKeysSection`
   - `ui/settings/SettingsDropdowns.kt`: `BackendSelector`
   - `ui/settings/SettingsDropdown.kt`: `SettingsDropdownOptionWithDescription`
3. `auth/OpenAiSignIn.kt`: `refreshOAuthToken()` is unused.
4. Unused onboarding constructor parameters:
   - `onboarding/OnboardingViewModel.kt`: `context`
   - `onboarding/DefaultOnboardingDemoController.kt`: `modelCatalog`
5. `history/SessionHistoryManager.kt` dead public API:
   - `loadSessionByFileName()` is only called internally and should be private
   - `deleteSessionByFileName()`, `getMostRecentSession()`, `hasActiveSession()`, and `endSession()` have no callers in app/tests
6. `agent/definition/AgentDef.kt`: `id` is written by concrete defs and never read.
7. `agent/subagent/SubAgentRunner.kt`: `AgentRegistry.getAll()` is unused.
8. Sub-agent catalog over-abstraction:
   - production registers exactly one sub-agent (`executor`)
   - `delegate_task` still requires `agent_name`
   - `AgentRegistry`, directory prompt generation, and lookup logic model a fake marketplace that does not exist in production
9. `agent/subagent/SubAgentRunner.kt` / `agent/AgentTurnRunner.kt`: `narrativeSummaryOnLimit` is dead configurability in production. Behavior is effectively fixed to `true`.
10. `tool/ToolRouter.kt`: `ToolRouterContext` plus `SimpleToolRouterContext` is an extra pass-through layer worth flattening later. This is lower confidence than the dead-code items above, but it is a real simplification candidate.

---

## Points of Disagreement

These are the places where I disagree with Claude's current design/plan, based on the code:

1. `tool/ToolCallState.kt` is not dead.
   `ToolRouter.kt` stores `ToolCallState` in `activeToolCalls`, emits `Validating`, `AwaitingApproval`, `Scheduled`, `Executing`, `Success`, `Error`, and `Cancelled`, and exposes `getState()`. Deleting it would break core tool execution.

2. `tool/action/ObservationBuilder.kt` and `perception/ScreenSummary.kt` are not dead.
   `buildObservation()` is called from `tool/handlers/UIActionInvocation.kt`, `tool/impl/OpenAppTool.kt`, and `tool/action/PostActionAnalysis.kt`. `buildObservation()` in turn calls `snapshot.toSummary()`.

3. `model/Bounds.width/height/centerX/centerY` are not dead.
   They are used in production code by `tool/action/ScrollExecutor.kt`, not just tests.

4. `model/ScreenSnapshot.hasElements` is not dead.
   It is used in `tool/action/TargetResolver.kt` and `tool/action/ObservationBuilder.kt`.
   Partial agreement only: `hasScreenshot` does appear unused, but Claude grouped it together with `hasElements`, which makes the deletion proposal too broad.

5. `ToolCallResult.Success.data` and `ToolExecutionResult.Success.data` are not dead.
   `WriteTodosTool`, `DelegateTaskTool`, `CompleteTaskTool`, and `AskUserTool` populate them. `ToolRouter.kt` forwards `executionResult.data` into `ToolCallResult.Success.data`. Claude's claim that these are "always null" is false against current code.

6. `model/ScreenSnapshot.textEnriched` is not dead.
   `Perceptor.kt` writes it and branches on it, and `UiChangeDetector.kt` includes it in the fingerprint.

7. `tool/MobileActionName` pruning is not yet justified.
   `PolicyEngine.isEscape()` relies on `Back` and `Home`. `ui/common/ToolUi.kt` maps all of the questioned variants to display/icon behavior. If we want to trim these variants, we need stronger evidence than "currently rare."

8. `protocol/AgentError.kt` may be part of an unused path, but Claude's current claim is incomplete.
   `SessionLifecycleEvents.kt` still types `SessionError.error` as `AgentError`, and several event consumers handle `SessionError`. The stronger hypothesis is that the whole `SessionError` emission path may be dead, not that `AgentError.kt` alone can be deleted safely.

9. `improvement_plan_claude.md` is unsafe as written.
   It schedules live code for early deletion: `ToolCallState`, `Bounds` helpers, `ScreenSnapshot.hasElements`, and the `Success.data` payloads. That is enough by itself to reject CLAUDE as the base execution plan.

---

## Recommended Merged Direction

Use `design_codex.md` and `improvement_plan_codex.md` as the base.

Then merge in Claude's validated helper/API cleanup items:

1. dead `SessionServices` debug helpers
2. dead convenience methods (`ToolCallResult`, `ActionResult`)
3. dead `AppClassifier.addUserOverride()` path
4. unread `captureQualityPath`
5. the `WarnApproaching` inconsistency
6. optionally, the marker-interface cleanup in `AgentEventDomains.kt`

Everything else from Claude's plan should be re-verified before any code is removed.
