# Dead Code & Over-Abstraction: Final Review

Date: 2026-04-08 (original), 2026-04-16 (reassessed)
Process: Double-design (Claude + Codex), cross-review, /align, post-change reassessment
Status: **REASSESSED** — updated after multiple rounds of code changes

---

## Scope

- Reviewed `app/src/main/kotlin/com/moonkey/androidagent/`
- Verified usages with `rg` in `app/src/main/kotlin` and `app/src/test/kotlin`
- Reassessed 2026-04-16: every item re-verified against current codebase

---

## Confirmed Dead Code

### Entire Dead Files

| # | File | Evidence |
|---|------|----------|
| 1 | `util/StatusUtils.kt` | Zero imports outside own file |
| 2 | `session/SessionServicesSummaryFormatter.kt` | Only caller is dead `getSummary()` |
| 3 | `.DS_Store` in source tree | Finder artifact in Kotlin source tree |

### Dead Methods

| # | Method | File | Evidence |
|---|--------|------|----------|
| 4 | `SessionServices.getSummary()` | `session/SessionServices.kt` | Zero callers |
| 5 | `SessionServices.updateApprovalMode()` | `session/SessionServices.kt` | Zero callers |
| 6 | `AppClassifier.addUserOverride()` + `userOverrides` | `tool/AppClassifier.kt` | Zero callers |
| 7 | `ToolCallResult.isSuccess()` | `tool/ToolCallResult.kt` | Zero callers |
| 8 | `ToolCallResult.getOutputOrNull()` | `tool/ToolCallResult.kt` | Zero callers |
| 9 | `ToolSpec.toFunctionSchema()` | `tool/ToolSpec.kt` | Zero callers |
| 10 | `ToolSpec.ValidationResult.isValid()` | `tool/ToolSpec.kt` | Zero callers |
| 11 | `ToolSpec.ToolExecutionResult.isSuccess()` | `tool/ToolSpec.kt` | Zero callers |
| 12 | `ActionResult.isSuccess()` | `platform/ActionResult.kt` | Zero callers |

### Dead Composables / UI

| # | Item | File | Evidence |
|---|------|------|----------|
| 13 | `ApiKeysSection` | `ui/settings/ApiKeyFields.kt` | Declaration only, zero callers |
| 14 | `BackendSelector` | `ui/settings/SettingsDropdowns.kt` | Declaration only, zero callers |
| 15 | `SettingsDropdownOptionWithDescription` | `ui/settings/SettingsDropdown.kt` | Declaration only, zero callers |

### Dead Auth / Onboarding

| # | Item | File | Evidence |
|---|------|------|----------|
| 16 | `refreshOAuthToken()` | `auth/OpenAiSignIn.kt` | Declaration only, zero callers |
| 17 | `OnboardingViewModel.context` | `onboarding/OnboardingViewModel.kt` | Unused constructor param |
| 18 | `DefaultOnboardingDemoController.modelCatalog` | `onboarding/DefaultOnboardingDemoController.kt` | Unused constructor param |

### Dead Fields

| # | Item | File | Evidence |
|---|------|------|----------|
| 19 | `ScreenSnapshotDebug.captureQualityPath` | `model/Models.kt` | Set in AccessibilityPlatform, never read |
| 20 | `ToolCallResult.Success.data` | `tool/ToolCallResult.kt` | Written by ToolRouter but never read by any consumer (NEW — status changed since original review) |
| 21 | `ToolExecutionResult.Success.data` | `tool/ToolSpec.kt` | Written by tools but never read downstream (NEW — status changed since original review) |
| 22 | `SessionHistoryManager.scope` | `history/SessionHistoryManager.kt` | Stored at construction, never read (NEW) |

### Dead Public API Surface

| # | Item | File | Evidence |
|---|------|------|----------|
| 23 | `SessionHistoryManager.deleteSessionByFileName()` | `history/SessionHistoryManager.kt` | Zero callers |
| 24 | `SessionHistoryManager.getMostRecentSession()` | `history/SessionHistoryManager.kt` | Zero callers |
| 25 | `SessionHistoryManager.hasActiveSession()` | `history/SessionHistoryManager.kt` | Zero callers |
| 26 | `SessionHistoryManager.endSession()` | `history/SessionHistoryManager.kt` | Zero callers |
| 27 | `SessionHistoryManager.loadSessionByFileName()` | `history/SessionHistoryManager.kt` | Only called internally → make private |

---

## Confirmed Over-Abstraction

### Single-Implementation Interface (Worth Fixing)

| # | Interface | Impl | Action |
|---|-----------|------|--------|
| 28 | `OnboardingDemoController` | `DefaultOnboardingDemoController` | Merge; pass via constructor; eliminate nullable late-assignment |

### delegate_task Fake Choice

| # | Item | Evidence | Action |
|---|------|----------|--------|
| 29 | `agent_name` in `delegate_task` | `ExecutorRoleDef` is the only delegatable role (test asserts `delegatableRoles().hasSize(1)`) | Remove parameter, hardcode target |

---

## Explicitly NOT Dead (Verified Live)

| Item | Why It's Live |
|------|---------------|
| `ToolCallState.kt` | Actively used by `ToolRouter.kt`, covered by tests |
| `Bounds.width/height/centerX/centerY` | Used by `ScrollExecutor.kt` |
| `ScreenSnapshot.hasElements` | Used by `TargetResolver.kt`, `ObservationBuilder.kt` |
| `MobileActionName.Back/Home/Wait/SystemButton` | Used by `PolicyEngine.isEscape()`, `ToolUi.kt` |
| `ObservationBuilder.kt` / `ScreenSummary.kt` | Called from `UIActionInvocation`, `OpenAppTool`, `PostActionAnalysis` |
| `ScreenSnapshot.textEnriched` | Used by `Perceptor.kt` and `UiChangeDetector.kt` |

---

## No Longer Applicable (Code Changed)

| Item | What Changed |
|------|--------------|
| `tool/handlers/DataQueryInvocation.kt` | Already deleted |
| `AgentRegistry.getAll()` | `AgentRegistry` no longer exists; replaced by `AgentDefRegistry` |
| `AgentDef.id` | Old hierarchy replaced; `AgentRoleDef` has no `id` field |
| `ExecutorStepDecision.WarnApproaching` | Old step-limit policy removed; replaced by `LoopDetectionPolicy` |
| `AgentRegistry` + catalog collapse | `AgentDefRegistry` now resolves 3 real roles; registry is no longer fake |
| `narrativeSummaryOnLimit` | Already removed; narration hardwired in `DelegationSummaryFormatter` |

---

## ROI Too Low (Defer Unless Adjacent)

| Item | Tradeoff |
|------|----------|
| `LlmCredentialValidator` single-impl interface | Tiny abstraction, no two-phase wiring problem; saves almost nothing |
| `ScreenSnapshot.hasScreenshot` | Dead in prod (only used in test), but removing one computed property buys nothing |
| `AgentEventDomains.kt` marker interfaces | 12 markers no consumer filters on; removing touches many event files for cosmetic gain |
| `ToolRouterContext` flattening | One interface/impl, but tests rely on it; not worth churning alone |

---

## Needs Investigation (Separate Follow-Up)

| Item | Issue |
|------|-------|
| `AgentError.kt` | Mostly dead taxonomy — only `PlatformError` is constructed in production, `from()` has no callers, `isRecoverable` never read. But `SessionError.error` is typed as `AgentError`. Needs scoped investigation of the `SessionError` emission path. |

---

## Abstractions Verified & Kept

| Item | Justification |
|------|---------------|
| `LLMClient` | 4 production implementations |
| `AndroidPlatform` | 2 production implementations |
| `TraceRecorder` | `FileTraceRecorder` + `NoopTraceRecorder` |
| `AgentRoleDef` hierarchy | 3 real roles (standalone, planner, executor) |
| `AppSkillRepository` | Null-object pattern for sub-agent services |
| `ToolSpec` / `ToolInvocation` | Clean spec/execution separation, multiple tools |
