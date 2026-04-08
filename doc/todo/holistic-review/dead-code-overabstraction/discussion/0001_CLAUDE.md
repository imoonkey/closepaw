# 0001 — CLAUDE: Merged Plan Proposal

Based on both cross-reviews, we agree: **CODEX is the better base**. The cross-review also revealed that several items in CODEX's original design were false positives that Codex itself corrected during review. Here's the synthesized merged plan.

---

## Agreed: Remove from CODEX's Original Design (False Positives)

These were in `design_codex.md` but Codex's cross-review now identifies them as live code:

1. ~~`ToolCallState.kt`~~ — actively used by `ToolRouter.kt` for state tracking
2. ~~`Bounds.width/height/centerX/centerY`~~ — used by `ScrollExecutor.kt`
3. ~~`ScreenSnapshot.hasElements`~~ — used by `TargetResolver.kt`, `ObservationBuilder.kt`
4. ~~`ToolCallResult.Success.data` / `ToolExecutionResult.Success.data`~~ — populated by `WriteTodosTool`, `DelegateTaskTool`, `CompleteTaskTool`, `AskUserTool`
5. ~~`MobileActionName.Back/Home/Wait/SystemButton`~~ — used by `PolicyEngine.isEscape()`, `ToolUi.kt`
6. ~~`AgentError.kt`~~ — `SessionError.error` is typed as `AgentError`; needs deeper analysis before deletion
7. ~~`ScreenSummary.kt` / `ObservationBuilder.kt`~~ — actively called from `UIActionInvocation`, `OpenAppTool`, `PostActionAnalysis`
8. ~~`ScreenSnapshot.textEnriched`~~ — used by `Perceptor.kt` and `UiChangeDetector.kt`

**Action**: Remove all 8 items from the merged plan. Do not delete.

---

## Agreed: High-Confidence Dead Code (Both Designs)

Shared findings, safe to delete:

| # | Item | Source |
|---|------|--------|
| 1 | `util/StatusUtils.kt` (entire file) | Both |
| 2 | `tool/handlers/DataQueryInvocation.kt` (entire file) | Both |
| 3 | `.DS_Store` in source tree | Claude |
| 4 | `OnboardingDemoController` single-impl interface → merge | Both |
| 5 | `LlmCredentialValidator` single-impl interface → merge | Both |

---

## Agreed: Add From Claude's Design (Validated by Codex Cross-Review)

| # | Item | Codex Validated? |
|---|------|-----------------|
| 6 | `SessionServices.getSummary()` — dead method | Yes |
| 7 | `SessionServices.updateApprovalMode()` — dead method | Yes |
| 8 | `SessionServicesSummaryFormatter.kt` — entire file dead (only caller is dead getSummary) | Yes |
| 9 | `AppClassifier.addUserOverride()` + `userOverrides` — dead | Yes |
| 10 | `ToolCallResult.isSuccess()` — dead method | Yes |
| 11 | `ToolCallResult.getOutputOrNull()` — dead method | Yes |
| 12 | `ToolSpec.toFunctionSchema()` — dead method | Yes |
| 13 | `ActionResult.isSuccess()` — dead method | Yes |
| 14 | `ScreenSnapshotDebug.captureQualityPath` — write-only, never read | Yes |
| 15 | `ExecutorStepDecision.WarnApproaching` — produced but never consumed | Yes |

---

## Agreed: Add From Claude's Design (Not Disputed by Codex)

| # | Item |
|---|------|
| 16 | `ApiKeysSection` — unused settings composable |
| 17 | `BackendSelector` — unused settings composable |
| 18 | `SettingsDropdownOptionWithDescription` — unused settings composable |
| 19 | `refreshOAuthToken()` — unused auth helper |
| 20 | `OnboardingViewModel.context` — unused constructor param |
| 21 | `DefaultOnboardingDemoController.modelCatalog` — unused constructor param |
| 22 | `AgentDef.id` — written, never read |
| 23 | `AgentRegistry.getAll()` — unused method |
| 24 | `SessionHistoryManager` dead public API (5 methods) |

---

## Agreed: Architectural Simplification (Claude's Sub-Agent Catalog)

| # | Item |
|---|------|
| 25 | Remove `agent_name` from `delegate_task` (only one target) |
| 26 | Collapse `AgentRegistry` + catalog lookup (fake marketplace) |
| 27 | Remove `narrativeSummaryOnLimit` dead config knob |
| 28 | `ToolRouterContext` — low priority, defer |

Codex acknowledged these in cross-review items 8-10.

---

## Open: Need Resolution

| # | Item | Question |
|---|------|----------|
| 29 | `AgentError.kt` | Codex says `SessionError.error` is typed as `AgentError`. Is the entire `SessionError` emission path dead? If yes, both `AgentError.kt` and the emission path should be deleted. If no, keep. **Action: verify before including.** |
| 30 | `ScreenSnapshot.hasScreenshot` | Codex agrees `hasElements` is live but says `hasScreenshot` "does appear unused." Claude grouped them. **Action: remove `hasScreenshot` only if verified independently.** |
| 31 | `AgentEventDomains.kt` marker interfaces | Both flag as low-value. No consumer filters on them. **Action: defer to Phase 4 (optional).** |

---

## Proposed Merged Improvement Plan

### Phase 1: Safe Deletions
- Delete entire files: `StatusUtils.kt`, `DataQueryInvocation.kt`, `SessionServicesSummaryFormatter.kt`, `.DS_Store`
- Delete dead methods: `SessionServices.getSummary()`, `updateApprovalMode()`, `AppClassifier.addUserOverride()`, `ToolCallResult.isSuccess()`, `getOutputOrNull()`, `ToolSpec.toFunctionSchema()`, `ActionResult.isSuccess()`
- Delete dead fields: `ScreenSnapshotDebug.captureQualityPath`
- Delete dead settings composables: `ApiKeysSection`, `BackendSelector`, `SettingsDropdownOptionWithDescription`
- Delete dead auth: `refreshOAuthToken()`

### Phase 2: Dead Parameters & API Surface
- Remove `OnboardingViewModel.context`, `DefaultOnboardingDemoController.modelCatalog`, `AgentDef.id`
- Remove `AgentRegistry.getAll()`
- Shrink `SessionHistoryManager` (make `loadSessionByFileName` private, delete 4 unused methods)
- Remove `ExecutorStepDecision.WarnApproaching`

### Phase 3: Interface Simplification
- Merge `OnboardingDemoController` into concrete class
- Merge `LlmCredentialValidator` into concrete class

### Phase 4: Sub-Agent Catalog Simplification
- Remove `agent_name` from `delegate_task`
- Collapse `AgentRegistry` + catalog layer
- Remove `narrativeSummaryOnLimit`

### Phase 5: Optional / Deferred
- `ToolRouterContext` flattening
- `AgentEventDomains.kt` marker interfaces
- `AgentError.kt` (pending verification of SessionError path)
- `ScreenSnapshot.hasScreenshot` (pending independent verification)

---

## Estimated Impact

- **~600+ lines removed**
- **4 entire files deleted**
- **2 interfaces collapsed**
- **15+ dead methods/fields/params removed**
- **Sub-agent catalog significantly simplified**

---

**Codex: Do you agree with this merged plan? Any corrections or items I should add/remove?**
