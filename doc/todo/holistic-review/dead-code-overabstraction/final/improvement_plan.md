# Dead Code & Over-Abstraction: Final Improvement Plan

Date: 2026-04-08
Process: Double-design (Claude + Codex), aligned
Status: **APPROVED**

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
- `app/src/main/kotlin/com/moonkey/androidagent/tool/handlers/DataQueryInvocation.kt`
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
- `AgentRegistry.getAll()` — `agent/subagent/SubAgentRunner.kt`

### 1.3 Delete dead composables / UI

- `ApiKeysSection` — `ui/settings/ApiKeyFields.kt` (keep `ApiKeyField`, which is used)
- `BackendSelector` — `ui/settings/SettingsDropdowns.kt`
- `SettingsDropdownOptionWithDescription` — `ui/settings/SettingsDropdown.kt`

### 1.4 Delete dead auth

- `refreshOAuthToken()` — `auth/OpenAiSignIn.kt`

### 1.5 Delete dead field

- `ScreenSnapshotDebug.captureQualityPath` — remove field and setter in `AccessibilityPlatform`

### 1.6 Verify

```
./gradlew :app:compileDebugKotlin
./gradlew test
rg 'StatusUtils|DataQueryInvocation|SessionServicesSummaryFormatter|getSummary|updateApprovalMode|addUserOverride|toFunctionSchema|isValid\(\)|getOutputOrNull|ApiKeysSection|BackendSelector|SettingsDropdownOptionWithDescription|refreshOAuthToken|captureQualityPath' app/src/main/kotlin
```

---

## Phase 2: Dead Parameters, API Surface & Branch

Goal: Remove unused parameters, shrink public API, remove dead branch.

### 2.1 Remove dead constructor parameters

- `OnboardingViewModel.context` — remove param and call-site arg in `MainActivity`
- `DefaultOnboardingDemoController.modelCatalog` — remove param and call-site arg in `MainActivity`

### 2.2 Remove dead property from hierarchy

- `AgentDef.id` — remove from `AgentDef.kt`, `StandaloneAgentDef.kt`, `PlannerAgentDef.kt`, `ExecutorAgentDef.kt`

### 2.3 Shrink `SessionHistoryManager`

- Make `loadSessionByFileName()` private
- Delete `deleteSessionByFileName()`
- Delete `getMostRecentSession()`
- Delete `hasActiveSession()`
- Delete `endSession()`

### 2.4 Remove dead branch

- Delete `ExecutorStepDecision.WarnApproaching`
- Simplify `ExecutorStepPolicy.evaluate()` to only emit `Continue` or `ForceStop`
- Verify `AgentTurnRunner.buildWarnings()` still works (it only handles `ForceStop`)

### 2.5 Verify

```
./gradlew :app:compileDebugKotlin
./gradlew test
```

---

## Phase 3: Interface Simplification

Goal: Remove single-implementation interfaces and two-phase injection.

### 3.1 Collapse `OnboardingDemoController`

Current state:
- `OnboardingViewModel` holds `var demoController: OnboardingDemoController? = null`
- `MainActivity` constructs `DefaultOnboardingDemoController` and assigns it post-construction
- One implementation, no test doubles

Change:
1. Delete `OnboardingDemoController.kt` (interface file)
2. Rename `DefaultOnboardingDemoController` → `OnboardingDemoController` (concrete class)
3. Pass via `OnboardingViewModel` constructor (not late assignment)
4. Remove nullable mutable field from view model
5. Update `MainActivity` construction site

### 3.2 Collapse `LlmCredentialValidator`

Current state:
- `LlmCredentialValidator` interface with one implementation: `HttpLlmCredentialValidator`
- `OnboardingViewModel.createValidatorForProvider()` instantiates concrete type directly

Change:
1. Delete `LlmCredentialValidator.kt` (interface file)
2. Move `Result` sealed interface to nest inside `HttpLlmCredentialValidator` (or rename to concrete result type)
3. `createValidatorForProvider()` returns concrete validator
4. `validateApiKey()` pattern-matches on concrete result type

### 3.3 Verify

```
./gradlew :app:compileDebugKotlin
./gradlew test
```

Manual smoke: permission steps, API key validation, demo step start/cancel

---

## Phase 4: Sub-Agent Catalog Simplification

Goal: Keep planner/executor architecture, remove fake catalog flexibility.

### 4.1 Remove `agent_name` from `delegate_task`

1. Remove `agent_name` from `DelegateTaskTool.parameterSchema`
2. Remove `agent_name` validation and lookup logic
3. Hardcode delegation target to executor
4. Simplify tool description (no "Available agents" listing)

### 4.2 Collapse registry/definition layer

1. Replace `AgentRegistry.createDefault()` with direct executor config in `SessionAgentRunner`
2. Either keep a small `ExecutorSubAgentConfig` data holder or inline executor constants
3. Remove `AgentRegistry`
4. Remove `ExecutorAgent` object
5. Remove registry-derived directory prompt generation

Keep:
- `IsolatedSubAgentRunner`
- `SubAgentRequest` / `SubAgentResult`
- Planner/executor architecture

### 4.3 Remove `narrativeSummaryOnLimit`

- Remove from `AgentDefinition` (or wherever defined)
- Hardcode current `true` behavior in executor step-limit path

### 4.4 Verify

```
./gradlew test --tests '*DelegateTaskToolTest'
./gradlew test --tests '*SubAgentRunnerTest'
./gradlew :app:compileDebugKotlin
```

Manual smoke: basic mode starts standalone agent, pro mode uses delegate_task, delegated executor completes correctly

---

## Phase 5: Optional / Deferred

Only pursue if convenient or if touching adjacent code:

- `ToolRouterContext` flattening (single interface/impl, one caller)
- `AgentEventDomains.kt` marker interfaces (12 markers, no filtering consumers)
- `AgentError.kt` (pending `SessionError` emission path verification)
- `ScreenSnapshot.hasScreenshot` (pending independent verification)

---

## Execution Order

```
Phase 1 (file/method/field deletions)     — safest, do first
Phase 2 (params, API surface, dead branch) — safe after Phase 1
Phase 3 (interface merges)                 — requires renaming + constructor changes
Phase 4 (sub-agent catalog)               — architectural, requires careful testing
Phase 5 (optional)                        — defer
```

---

## Estimated Impact

- **~600+ lines removed**
- **4 entire files deleted**
- **2 interfaces collapsed**
- **17+ dead methods/fields/params removed**
- **Sub-agent catalog meaningfully simplified**
- **5 dead sealed class variants / branches removed**
- **Dead settings UI, auth, and onboarding leftovers cleaned up**
