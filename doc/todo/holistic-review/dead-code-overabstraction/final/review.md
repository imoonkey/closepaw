# Dead Code & Over-Abstraction: Final Review

Date: 2026-04-08
Process: Double-design (Claude + Codex), cross-review, /align
Base: CODEX design, enriched with Claude's validated findings
Status: **ALIGNED**

---

## Scope

- Reviewed `app/src/main/kotlin/com/moonkey/androidagent/` (267 files)
- Verified usages with `rg` in `app/src/main/kotlin` and `app/src/test/kotlin`
- Both agents independently reviewed, cross-reviewed, and aligned on findings
- Items flagged by one agent and refuted by the other were excluded

---

## Confirmed Dead Code

### Entire Dead Files

| # | File | Lines | Evidence |
|---|------|-------|----------|
| 1 | `util/StatusUtils.kt` | ~104 | Zero imports outside own file |
| 2 | `tool/handlers/DataQueryInvocation.kt` | ~51 | Zero imports, designed for non-existent `list_apps` tool |
| 3 | `session/SessionServicesSummaryFormatter.kt` | ~31 | Only caller is dead `getSummary()` |
| 4 | `.DS_Store` in source tree | — | Finder artifact in Kotlin source tree |

### Dead Methods

| # | Method | File | Evidence |
|---|--------|------|----------|
| 5 | `SessionServices.getSummary()` | `session/SessionServices.kt` | Zero callers |
| 6 | `SessionServices.updateApprovalMode()` | `session/SessionServices.kt` | Zero callers |
| 7 | `AppClassifier.addUserOverride()` | `tool/AppClassifier.kt` | Zero callers; `userOverrides` field also dead |
| 8 | `ToolCallResult.isSuccess()` | `tool/ToolCallResult.kt` | Zero callers; code uses `is Success` pattern matching |
| 9 | `ToolCallResult.getOutputOrNull()` | `tool/ToolCallResult.kt` | Zero callers |
| 10 | `ToolSpec.toFunctionSchema()` | `tool/ToolSpec.kt` | Zero callers; schemas use `generateResponsesApiTools()` |
| 11 | `ToolSpec.ValidationResult.isValid()` | `tool/ToolSpec.kt` | Zero callers |
| 12 | `ToolSpec.ToolExecutionResult.isSuccess()` | `tool/ToolSpec.kt` | Zero callers |
| 13 | `ActionResult.isSuccess()` | `platform/ActionResult.kt` | Zero callers; code uses exhaustive `when` |
| 14 | `AgentRegistry.getAll()` | `agent/subagent/SubAgentRunner.kt` | Zero callers |

### Dead Composables / UI

| # | Item | File | Evidence |
|---|------|------|----------|
| 15 | `ApiKeysSection` | `ui/settings/ApiKeyFields.kt` | Declaration only, zero callers |
| 16 | `BackendSelector` | `ui/settings/SettingsDropdowns.kt` | Declaration only, zero callers |
| 17 | `SettingsDropdownOptionWithDescription` | `ui/settings/SettingsDropdown.kt` | Declaration only, zero callers |

### Dead Auth / Onboarding

| # | Item | File | Evidence |
|---|------|------|----------|
| 18 | `refreshOAuthToken()` | `auth/OpenAiSignIn.kt` | Declaration only, zero callers |
| 19 | `OnboardingViewModel.context` | `onboarding/OnboardingViewModel.kt` | Unused constructor param (still passed from MainActivity) |
| 20 | `DefaultOnboardingDemoController.modelCatalog` | `onboarding/DefaultOnboardingDemoController.kt` | Unused constructor param (still passed from MainActivity) |

### Dead Fields / Parameters

| # | Item | File | Evidence |
|---|------|------|----------|
| 21 | `AgentDef.id` | `agent/definition/AgentDef.kt` + subclasses | Written, never read |
| 22 | `ScreenSnapshotDebug.captureQualityPath` | `model/Models.kt` | Set in AccessibilityPlatform, never read |

### Dead Public API Surface

| # | Item | File | Evidence |
|---|------|------|----------|
| 23 | `SessionHistoryManager.deleteSessionByFileName()` | `history/SessionHistoryManager.kt` | Zero callers |
| 24 | `SessionHistoryManager.getMostRecentSession()` | `history/SessionHistoryManager.kt` | Zero callers |
| 25 | `SessionHistoryManager.hasActiveSession()` | `history/SessionHistoryManager.kt` | Zero callers |
| 26 | `SessionHistoryManager.endSession()` | `history/SessionHistoryManager.kt` | Zero callers |
| 27 | `SessionHistoryManager.loadSessionByFileName()` | `history/SessionHistoryManager.kt` | Only called internally → make private |

### Dead Branch

| # | Item | File | Evidence |
|---|------|------|----------|
| 28 | `ExecutorStepDecision.WarnApproaching` | `agent/cognition/policy/ExecutorStepPolicy.kt` | Produced but never consumed; `AgentTurnRunner` only handles `ForceStop` |

---

## Confirmed Over-Abstraction

### Single-Implementation Interfaces

| # | Interface | Impl | Action |
|---|-----------|------|--------|
| 29 | `OnboardingDemoController` | `DefaultOnboardingDemoController` | Merge; pass via constructor; eliminate nullable late-assignment |
| 30 | `LlmCredentialValidator` | `HttpLlmCredentialValidator` | Merge; return concrete type from `createValidatorForProvider()` |

### Sub-Agent Catalog (Fake Marketplace)

| # | Item | Evidence | Action |
|---|------|----------|--------|
| 31 | `agent_name` in `delegate_task` | Only one valid target ("executor") | Remove parameter, hardcode target |
| 32 | `AgentRegistry` + catalog lookup | Registers exactly one entry | Collapse; direct executor config |
| 33 | `narrativeSummaryOnLimit` | Never overridden from default `true` | Remove; hardcode behavior |

---

## Explicitly NOT Dead (Cross-Review Corrections)

These items were flagged by one or both designs but refuted during cross-review:

| Item | Why It's Live |
|------|---------------|
| `ToolCallState.kt` | Actively used by `ToolRouter.kt` for state tracking |
| `Bounds.width/height/centerX/centerY` | Used by `ScrollExecutor.kt` |
| `ScreenSnapshot.hasElements` | Used by `TargetResolver.kt`, `ObservationBuilder.kt` |
| `ToolCallResult.Success.data` | Populated by `WriteTodosTool`, `DelegateTaskTool`, etc. |
| `ToolExecutionResult.Success.data` | Forwarded by `ToolRouter.kt` |
| `MobileActionName.Back/Home/Wait/SystemButton` | Used by `PolicyEngine.isEscape()`, `ToolUi.kt` |
| `ObservationBuilder.kt` / `ScreenSummary.kt` | Called from `UIActionInvocation`, `OpenAppTool`, `PostActionAnalysis` |
| `ScreenSnapshot.textEnriched` | Used by `Perceptor.kt` and `UiChangeDetector.kt` |

---

## Deferred (Requires Further Investigation)

| Item | Issue |
|------|-------|
| `AgentError.kt` | `SessionError.error` is typed as `AgentError`; need to verify if entire `SessionError` emission path is dead |
| `ScreenSnapshot.hasScreenshot` | May be unused but needs independent verification (was grouped with live `hasElements`) |
| `AgentEventDomains.kt` marker interfaces | Low-value domain taxonomy, no consumer filters on them; cosmetic only |
| `ToolRouterContext` | Single interface/impl, one caller; lower priority simplification |

---

## Abstractions Verified & Kept

| Item | Justification |
|------|---------------|
| `LLMClient` | 4 production implementations |
| `AndroidPlatform` | 2 production implementations |
| `TraceRecorder` | `FileTraceRecorder` + `NoopTraceRecorder` |
| `AgentDef` hierarchy | 3 real personas (planner, executor, standalone) |
| `AppSkillRepository` | Null-object pattern for sub-agent services |
| `ToolSpec` / `ToolInvocation` | Clean spec/execution separation, multiple tools |
