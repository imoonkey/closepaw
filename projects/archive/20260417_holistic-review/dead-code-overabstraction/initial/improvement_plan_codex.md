# Improvement Plan

## Principles

- Delete verified dead code before refactoring live code
- Preserve real abstractions:
  - multi-platform execution
  - multi-backend LLM support
  - trace/no-trace implementations
- Do not change behavior while simplifying

## Phase 1: Safe Deletions

Goal: remove code with zero value and near-zero risk.

### 1.1 Delete orphan and unused files/functions

Delete:

- `app/src/main/kotlin/com/moonkey/androidagent/.DS_Store`
- `app/src/main/kotlin/com/moonkey/androidagent/util/StatusUtils.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/handlers/DataQueryInvocation.kt`
- `ApiKeysSection` from `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/ApiKeyFields.kt`
- `BackendSelector` from `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsDropdowns.kt`
- `SettingsDropdownOptionWithDescription` from `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsDropdown.kt`
- `refreshOAuthToken()` from `app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAiSignIn.kt`

### 1.2 Remove dead parameters and dead API surface

Remove:

- `OnboardingViewModel.context`
- `DefaultOnboardingDemoController.modelCatalog`
- `AgentDef.id`
- `AgentRegistry.getAll()`

Shrink `SessionHistoryManager`:

- Make `loadSessionByFileName()` private
- Delete `deleteSessionByFileName()`
- Delete `getMostRecentSession()`
- Delete `hasActiveSession()`
- Delete `endSession()`

### 1.3 Verify

Run:

- `./gradlew :app:compileDebugKotlin`
- `./gradlew test`
- `rg` checks for the removed symbols to confirm zero leftovers

## Phase 2: Simplify Onboarding Wiring

Goal: remove single-implementation interfaces and two-phase injection.

### 2.1 Collapse demo controller abstraction

Current state:

- `OnboardingViewModel` owns nullable mutable `demoController`
- `MainActivity` constructs `DefaultOnboardingDemoController`
- There is only one implementation

Recommended change:

1. Remove `OnboardingDemoController.kt`
2. Rename `DefaultOnboardingDemoController` to `OnboardingDemoController` or keep the concrete name, but make it the only type
3. Pass it through the `OnboardingViewModel` constructor instead of assigning `vm.demoController = ...` later
4. Remove the nullability from the view model field

Why this order:

- It reduces indirection and eliminates invalid partially-wired state
- It keeps behavior identical

### 2.2 Collapse credential validator abstraction

Current state:

- `LlmCredentialValidator` has one implementation: `HttpLlmCredentialValidator`
- `OnboardingViewModel.createValidatorForProvider()` instantiates the concrete type directly

Recommended change:

1. Remove `LlmCredentialValidator.kt`
2. Move the `Result` sealed interface next to `HttpLlmCredentialValidator`, or rename it to a concrete onboarding-specific result type
3. Make `createValidatorForProvider()` return the concrete validator directly
4. Update `validateApiKey()` to pattern-match on the concrete result type

### 2.3 Verify

Run:

- `./gradlew :app:compileDebugKotlin`
- onboarding-related tests if present later
- manual onboarding smoke:
  - permission steps
  - API key validation
  - demo step start/cancel

## Phase 3: Simplify Sub-Agent Catalog Without Removing Planner/Executor

Goal: keep planner/executor mode, remove fake catalog flexibility.

### 3.1 Remove single-choice `agent_name`

Current state:

- `delegate_task` requires `agent_name`
- runtime registry contains only `"executor"`

Recommended change:

1. Remove `agent_name` from `DelegateTaskTool.parameterSchema`
2. Remove `agent_name` validation and lookup logic
3. Hardcode delegation target to executor
4. Simplify tool description so it no longer lists “Available agents”

Outcome:

- planner prompt gets simpler
- tool call surface gets smaller
- no behavior loss, because there is only one valid target today

### 3.2 Collapse the registry/definition layer

Current state:

- `AgentRegistry`
- `AgentDefinition`
- `ExecutorAgent`
- `AgentRegistry.createDefault()`

Recommended change:

1. Replace `AgentRegistry.createDefault()` with a direct executor config path in `SessionAgentRunner`
2. Either:
   - keep a small `ExecutorSubAgentConfig` data holder, or
   - inline executor constants where they are used
3. Remove `AgentRegistry`
4. Remove `ExecutorAgent`
5. Remove any registry-derived directory prompt generation

Important:

- keep `IsolatedSubAgentRunner`
- keep `SubAgentRequest` / `SubAgentResult` if they still help boundary clarity
- keep planner/executor architecture

### 3.3 Remove dead config knobs

Remove:

- `AgentDefinition.narrativeSummaryOnLimit`

Recommended replacement:

- hardcode the current `true` behavior inside the executor step-limit path

### 3.4 Verify

Run:

- `./gradlew test --tests '*DelegateTaskToolTest'`
- `./gradlew test --tests '*SubAgentRunnerTest'`
- `./gradlew :app:compileDebugKotlin`

Manual smoke:

- basic mode still starts standalone agent
- pro mode still registers and uses `delegate_task`
- delegated executor still completes and reports failure/success correctly

## Phase 4: Optional Tool Router Cleanup

Goal: only if already touching the tool stack.

Candidate simplification:

- replace `ToolRouterContext` + `SimpleToolRouterContext` with either:
  - a single concrete data class, or
  - direct args to `ToolRouter.execute()`

Do this only if it materially simplifies the call chain. It is lower priority than Phases 1-3.

## Recommended Execution Order

1. Phase 1
2. Phase 2
3. Phase 3
4. Phase 4 only if convenient

## Expected Result

After Phases 1-3, the codebase should be smaller and easier to reason about in these areas:

- settings UI leftovers removed
- onboarding wiring becomes direct instead of interface-driven
- sub-agent delegation reflects the actual architecture: one planner, one executor
- dead API surface and dead config knobs are gone
