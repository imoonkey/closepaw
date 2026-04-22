# Dead Code & Over-Abstraction: Final Improvement Plan

Date: 2026-04-08 (original), 2026-04-16 (reassessed)
Status: **REASSESSED** — trimmed after post-change verification

---

## Principles

- Delete verified dead code before refactoring live code
- Preserve real abstractions (multi-platform, multi-backend LLM, trace/no-trace)
- Do not change behavior while simplifying
- Verify after each phase

---

## Phase 1: Safe Deletions

Goal: Remove code with zero value and near-zero risk.

### 1.1 Delete entire dead files

- `app/src/main/kotlin/com/moonkey/androidagent/.DS_Store`
- `app/src/main/kotlin/com/moonkey/androidagent/util/StatusUtils.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/session/SessionServicesSummaryFormatter.kt`

### 1.2 Delete dead methods from live files

- `SessionServices.getSummary()` — `session/SessionServices.kt`
- `SessionServices.updateApprovalMode()` — `session/SessionServices.kt`
- `AppClassifier.addUserOverride()` + `userOverrides` field — `tool/AppClassifier.kt`
- `ToolCallResult.isSuccess()` — `tool/ToolCallResult.kt`
- `ToolCallResult.getOutputOrNull()` — `tool/ToolCallResult.kt`
- `ToolSpec.toFunctionSchema()` — `tool/ToolSpec.kt`
- `ToolSpec.ValidationResult.isValid()` — `tool/ToolSpec.kt`
- `ToolSpec.ToolExecutionResult.isSuccess()` — `tool/ToolSpec.kt`
- `ActionResult.isSuccess()` — `platform/ActionResult.kt`

### 1.3 Delete dead composables / UI

- `ApiKeysSection` — `ui/settings/ApiKeyFields.kt` (keep `ApiKeyField`)
- `BackendSelector` — `ui/settings/SettingsDropdowns.kt`
- `SettingsDropdownOptionWithDescription` — `ui/settings/SettingsDropdown.kt`

### 1.4 Delete dead auth

- `refreshOAuthToken()` — `auth/OpenAiSignIn.kt`

### 1.5 Delete dead fields

- `ScreenSnapshotDebug.captureQualityPath` — remove field and setter in `AccessibilityPlatform`

### 1.6 Verify

```
./gradlew :app:compileDebugKotlin
./gradlew test
```

---

## Phase 2: Dead Parameters & API Surface

Goal: Remove unused parameters, shrink public API, remove dead payload plumbing.

### 2.1 Remove dead constructor parameters

- `OnboardingViewModel.context` — remove param and call-site arg in `MainActivity`
- `DefaultOnboardingDemoController.modelCatalog` — remove param and call-site arg in `MainActivity`

### 2.2 Shrink `SessionHistoryManager`

- Make `loadSessionByFileName()` private
- Delete `deleteSessionByFileName()`
- Delete `getMostRecentSession()`
- Delete `hasActiveSession()`
- Delete `endSession()`
- Remove unused `scope` stored property

### 2.3 Remove dead success-payload plumbing

- Remove `data` field from `ToolCallResult.Success` — no production reader
- Remove `data` field from `ToolExecutionResult.Success` — no production reader
- Remove the now-pointless writers/forwarders in tools and `ToolRouter`

### 2.4 Verify

```
./gradlew :app:compileDebugKotlin
./gradlew test
```

---

## Phase 3: Onboarding Abstraction Fix

Goal: Remove single-implementation interface and two-phase injection.

### 3.1 Collapse `OnboardingDemoController`

Current state:
- `OnboardingViewModel` holds `var demoController: OnboardingDemoController? = null`
- `MainActivity` constructs `DefaultOnboardingDemoController` and assigns post-construction
- One implementation, no test doubles

Change:
1. Delete `OnboardingDemoController.kt` (interface file)
2. Rename `DefaultOnboardingDemoController` → `OnboardingDemoController` (concrete class)
3. Pass via `OnboardingViewModel` constructor (not late assignment)
4. Remove nullable mutable field from view model
5. Update `MainActivity` construction site

### 3.2 Verify

```
./gradlew :app:compileDebugKotlin
./gradlew test
```

Manual smoke: permission steps, API key validation, demo step start/cancel

---

## Phase 4: Simplify `delegate_task` Tool Boundary

Goal: Remove fake agent-name choice while keeping `AgentDefRegistry` for real role selection.

### 4.1 Remove `agent_name` from `delegate_task`

1. Remove `agent_name` from `DelegateTaskTool.parameterSchema`
2. Remove `agent_name` validation and lookup logic
3. Hardcode delegation target to executor
4. Simplify tool description (no "Available agents" listing)

Note: Keep `AgentRoleDef` / `AgentDefRegistry` — they now resolve 3 real top-level roles.

### 4.2 Verify

```
./gradlew test --tests '*DelegateTaskToolTest'
./gradlew :app:compileDebugKotlin
```

Manual smoke: pro mode uses delegate_task, delegated executor completes correctly

---

## Deferred (Low ROI — Only If Adjacent)

- `LlmCredentialValidator` → tiny single-impl interface, no wiring harm
- `ScreenSnapshot.hasScreenshot` → one dead computed property
- `AgentEventDomains.kt` marker interfaces → cosmetic, touches many files
- `ToolRouterContext` flattening → tests rely on it

---

## Separate Follow-Up: `AgentError.kt`

Not part of this plan. Requires scoped investigation:
- Only `PlatformError` is constructed in production
- `from()` has no callers, `isRecoverable` never read
- But `SessionError.error` is typed as `AgentError`
- Need to trace the full `SessionError` emission path before deciding scope

---

## Execution Order

```
Phase 1 (file/method/field deletions)     — safest, do first
Phase 2 (params, API surface, payload)    — safe after Phase 1
Phase 3 (onboarding interface merge)      — requires constructor changes
Phase 4 (delegate_task simplification)    — tool boundary change
```

---

## Estimated Impact

- **3 entire files deleted**
- **12 dead methods removed**
- **3 dead composables removed**
- **5 dead SessionHistoryManager methods cleaned up**
- **2 dead constructor params removed**
- **3 dead fields removed (including success-payload plumbing)**
- **1 interface collapsed**
- **delegate_task simplified to single target**
