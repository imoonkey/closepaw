# Dead Code & Over-Abstraction — Implementation Summary

**Date:** 2026-04-16
**Status:** DONE
**Design:** `doc/todo/holistic-review/dead-code-overabstraction/final/improvement_plan.md`
**Verification:** `./gradlew assembleDebug test` pass. Codex review APPROVE (zero Critical/High/Medium). Real-device QA 6/6 PASS.

## What was implemented

6 tasks across 4 phases, landed as 7 commits from `43665d44` to `65897e0f`.

### Phase 1 — Safe deletions (`dco-safe-deletions`, `43665d44`)
- Deleted 3 dead files: `util/StatusUtils.kt`, `session/SessionServicesSummaryFormatter.kt`, stray `.DS_Store`.
- Deleted 9 dead methods: `SessionServices.getSummary` / `updateApprovalMode`, `AppClassifier.addUserOverride` + `userOverrides` field, `ToolCallResult.isSuccess` / `getOutputOrNull`, `ToolSpec.toFunctionSchema` + `ValidationResult.isValid` + `ToolExecutionResult.isSuccess`, `ActionResult.isSuccess`.
- Deleted 3 dead composables: `ApiKeysSection`, `BackendSelector`, `SettingsDropdownOptionWithDescription`.
- Deleted `OpenAiSignIn.refreshOAuthToken()` and `ScreenSnapshotDebug.captureQualityPath` field.

### Phase 2 — Dead params & API surface (`dco-dead-params-api`, `2592428b`)
- Dropped `OnboardingViewModel.context` and `DefaultOnboardingDemoController.modelCatalog` constructor params.
- Shrank `SessionHistoryManager`: privatized `loadSessionByFileName`; deleted `deleteSessionByFileName`, `getMostRecentSession`, `hasActiveSession`, `endSession`, unused `scope` stored property.
- Removed `data: Any?` field from `ToolCallResult.Success` and `ToolExecutionResult.Success` plus writers/forwarders in tools and `ToolRouter` — no production reader.

### Phase 3 — Onboarding interface collapse (`dco-onboarding-abstraction`, `f3a66651`)
- Deleted `OnboardingDemoController` interface.
- Promoted `DefaultOnboardingDemoController` → concrete class named `OnboardingDemoController`.
- Switched `OnboardingViewModel` from nullable late-assigned `var demoController` to constructor injection.
- `MainActivity` now constructs the controller inline and passes it to the view model.

### Phase 4 — `delegate_task` simplification (`dco-delegate-task-simplify`, `073a7d0c`)
- Removed `agent_name` parameter + validation + lookup from `DelegateTaskTool.parameterSchema`.
- Hardcoded delegation target to executor (registry still resolves via `AgentDefRegistry.delegatableRoles()`, which resolves to the single executor role).
- Simplified tool description — no more "Available agents" listing.
- `AgentRoleDef` / `AgentDefRegistry` retained for real multi-role resolution.

### Phase 5 — Codex review (`dco-codex-review`, `5931515b`)
- Verdict: **APPROVE**. Zero Critical/High/Medium.
- Low observation: `delegate_task` no longer rejects a stray `agent_name` at runtime. Deliberate — schema no longer advertises the field; `parameterSchema.additionalProperties = false` is not enforced by `ToolRouter`. Left intentional to stay deletion-only.

### Phase 6 — QA (`dco-qa-validation`, `38f98fa3`)
- 6/6 scenarios PASS on device EP0110MZ0BC (nubia P0110, SDK 36): onboarding fresh-install, settings UI, PRO delegation without `agent_name`, session history, single-turn task, logcat crash check.
- Drive-by lint fix `29793c26` for a pre-existing `NewApi` error in untouched `ServiceOverlayController.kt` (discovered during QA lint run).

## Key decisions / non-obvious notes

- **Deferred items are load-bearing or cross-cut:** `LlmCredentialValidator`, `ScreenSnapshot.hasScreenshot`, `AgentEventDomains` marker interfaces, `ToolRouterContext` flatten — left per the plan's "Deferred (Low ROI — Only If Adjacent)" section.
- **`AgentError.kt` kept out of this milestone:** plan flagged it as requiring scoped investigation (only `PlatformError` constructed in production, but `SessionError.error` typed as `AgentError`). Subsequently removed during error-resilience work (`799336d3`).
- **Registry retained over constant:** `DelegateTaskTool` still routes through `AgentDefRegistry` rather than a hardcoded role constant. This is forward-compatible for when multi-role delegation becomes real.
- **Schema-level `additionalProperties` not enforced:** codex Low finding noted that a stale `agent_name` in tool args would be silently ignored rather than rejected. Acceptable since the LLM schema no longer exposes the field.

## Artifacts
- Design: `doc/todo/holistic-review/dead-code-overabstraction/final/improvement_plan.md`
- Review: `codex_review.md` (APPROVE)
- QA: `qa_report.md` + `qa_evidence/`
