# Dead Code & Over-Abstraction Review

## Scope

- Reviewed `app/src/main/kotlin/com/moonkey/androidagent/`
- Verified usages with `rg` in `app/src/main/kotlin` and `app/src/test/kotlin`
- Treated code and tests as source of truth for reachability
- Focused only on dead code and abstractions that are unnecessary in the current codebase

## Summary

The codebase has a real set of multi-backend and multi-platform abstractions that should stay, but it also carries a smaller cluster of genuinely dead helpers and a few layers of fake extensibility.

High-confidence dead code:

1. Orphan source artifact: `app/src/main/kotlin/com/moonkey/androidagent/.DS_Store`
2. Entire unused helper file: `util/StatusUtils.kt`
3. Entire unused tool wrapper: `tool/handlers/DataQueryInvocation.kt`
4. Three unused settings composables/helpers left behind after UI changes
5. One unused OAuth helper function
6. Two unused onboarding constructor parameters
7. Several unused `SessionHistoryManager` public methods
8. One unused field in `AgentDef`
9. One unused method in `AgentRegistry`

High-confidence over-abstraction:

1. `OnboardingDemoController` interface has one implementation and no consumers that need polymorphism
2. `LlmCredentialValidator` interface has one implementation and is instantiated directly by the view model anyway
3. The sub-agent catalog (`AgentRegistry` + `AgentDefinition` + required `agent_name`) models a multi-agent marketplace, but production only registers one executor agent
4. `narrativeSummaryOnLimit` is a dead configuration knob; current behavior is effectively hardcoded
5. `ToolRouterContext` is probably one layer too many, though this is lower urgency than the items above

## Findings

### Dead Code

#### 1. Orphan binary file inside source tree

- File: `app/src/main/kotlin/com/moonkey/androidagent/.DS_Store`
- Evidence: it is a Finder artifact sitting directly in the Kotlin source tree and is not source code.
- Verdict: delete.

#### 2. `StatusUtils` is fully unused

- File: `app/src/main/kotlin/com/moonkey/androidagent/util/StatusUtils.kt:9`
- Verification:
  - `rg -n '\bStatusUtils\b' app/src/main/kotlin/com/moonkey/androidagent app/src/test/kotlin/com/moonkey/androidagent`
  - Result: declaration only, no imports or call sites in main/tests
- Verdict: entire file is dead.

#### 3. `DataQueryInvocation` is fully unused

- File: `app/src/main/kotlin/com/moonkey/androidagent/tool/handlers/DataQueryInvocation.kt:19`
- Verification:
  - `rg -n '\bDataQueryInvocation\b' app/src/main/kotlin/com/moonkey/androidagent app/src/test/kotlin/com/moonkey/androidagent`
  - Result: hits only inside its own file/comments
- Notes:
  - The class comment still refers to “tools like list_apps”, but no current tool creates this wrapper.
- Verdict: entire file is dead.

#### 4. `ApiKeysSection` is unused

- File: `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/ApiKeyFields.kt:30`
- Verification:
  - `rg -n '\bApiKeysSection\b' app/src/main/kotlin/com/moonkey/androidagent app/src/test/kotlin/com/moonkey/androidagent`
  - Result: declaration only
- Verdict: delete the composable; keep `ApiKeyField`, which is still used.

#### 5. `BackendSelector` is unused

- File: `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsDropdowns.kt:22`
- Verification:
  - `rg -n '\bBackendSelector\b' app/src/main/kotlin/com/moonkey/androidagent app/src/test/kotlin/com/moonkey/androidagent`
  - Result: declaration only
- Verdict: delete.

#### 6. `SettingsDropdownOptionWithDescription` is unused

- File: `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsDropdown.kt:116`
- Verification:
  - `rg -n '\bSettingsDropdownOptionWithDescription\b' app/src/main/kotlin/com/moonkey/androidagent app/src/test/kotlin/com/moonkey/androidagent`
  - Result: declaration only
- Verdict: delete.

#### 7. `refreshOAuthToken` is unused

- File: `app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAiSignIn.kt:92`
- Verification:
  - `rg -n '\brefreshOAuthToken\(' app/src/main/kotlin/com/moonkey/androidagent app/src/test/kotlin/com/moonkey/androidagent`
  - Result: declaration only
- Verdict: delete unless a refresh flow is about to be added immediately.

#### 8. `OnboardingViewModel.context` is an unused constructor parameter

- File: `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:29-36`
- Verification:
  - `rg -n '\bcontext\b' app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt`
  - Result: only the constructor declaration at line 30
- Supporting evidence:
  - `MainActivity` still passes `applicationContext` at `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:142-149`
- Verdict: remove the parameter and the corresponding call-site argument.

#### 9. `DefaultOnboardingDemoController.modelCatalog` is an unused constructor parameter

- File: `app/src/main/kotlin/com/moonkey/androidagent/onboarding/DefaultOnboardingDemoController.kt:39-42`
- Verification:
  - `rg -n '\bmodelCatalog\b' app/src/main/kotlin/com/moonkey/androidagent/onboarding/DefaultOnboardingDemoController.kt`
  - Result: constructor declaration only
- Supporting evidence:
  - `MainActivity` still passes `modelCatalog` at `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:152-155`
- Verdict: remove the parameter and the corresponding call-site argument.

#### 10. `SessionHistoryManager` exposes several unused public methods

- File: `app/src/main/kotlin/com/moonkey/androidagent/history/SessionHistoryManager.kt`
- Findings:
  - `loadSessionByFileName()` at line 104 is only called internally from `loadSession()` at line 95
  - `deleteSessionByFileName()` at line 134 has no usages in main/tests
  - `getMostRecentSession()` at line 147 has no usages in main/tests
  - `hasActiveSession()` at line 197 has no usages in main/tests
  - `endSession()` at line 209 has no usages in main/tests
- Verification:
  - `rg -n 'loadSessionByFileName\(' app/src/main/kotlin/com/moonkey/androidagent app/src/test/kotlin/com/moonkey/androidagent`
  - `rg -n 'deleteSessionByFileName|getMostRecentSession|hasActiveSession\(|endSession\(' app/src/main/kotlin/com/moonkey/androidagent app/src/test/kotlin/com/moonkey/androidagent`
- Verdict:
  - make `loadSessionByFileName()` private
  - delete the other four unless an upcoming feature needs them

#### 11. `AgentDef.id` is written but never read

- Files:
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/AgentDef.kt:11`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt:6`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt:6`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt:6`
- Verification:
  - `rg -n 'StandaloneAgentDef\.id|PlannerAgentDef\.id|ExecutorAgentDef\.id|agentDef\.id|executorDef\.id|\bAgentDef\b.*\bid\b' app/src/main/kotlin/com/moonkey/androidagent app/src/test/kotlin/com/moonkey/androidagent`
  - Result: no reads found
- Verdict: remove the property from the base class and all subclasses.

#### 12. `AgentRegistry.getAll()` is unused

- File: `app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/SubAgentRunner.kt:88`
- Verification:
  - `rg -n 'registry\.getAll\(|\.getAll\(\)' app/src/main/kotlin/com/moonkey/androidagent/agent/subagent app/src/test/kotlin/com/moonkey/androidagent/agent/subagent app/src/test/kotlin/com/moonkey/androidagent/tool/impl/DelegateTaskToolTest.kt`
  - Result: no `AgentRegistry.getAll()` call sites
- Verdict: delete as dead API surface.

### Over-Abstraction

#### 13. `OnboardingDemoController` is a single-implementation interface with awkward two-phase injection

- Files:
  - `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingDemoController.kt:9`
  - `app/src/main/kotlin/com/moonkey/androidagent/onboarding/DefaultOnboardingDemoController.kt:39`
  - `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:61`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:152-155`
- Verification:
  - `rg -n 'OnboardingDemoController|DefaultOnboardingDemoController' app/src/main/kotlin/com/moonkey/androidagent app/src/test/kotlin/com/moonkey/androidagent`
  - Result: one implementation, no test doubles, no alternate runtime implementations
- Why this is over-abstracted:
  - The view model holds `var demoController: OnboardingDemoController? = null` and expects it to be assigned after construction.
  - The only concrete controller is created directly in `MainActivity`.
  - The interface adds indirection, nullability, and two-phase wiring without adding real flexibility.
- Verdict: merge the interface into a concrete dependency and pass it via constructor.

#### 14. `LlmCredentialValidator` is a single-implementation interface

- Files:
  - `app/src/main/kotlin/com/moonkey/androidagent/onboarding/LlmCredentialValidator.kt:8`
  - `app/src/main/kotlin/com/moonkey/androidagent/onboarding/HttpLlmCredentialValidator.kt:20`
  - `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:209-216`
  - `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:483-486`
- Verification:
  - `rg -n 'LlmCredentialValidator|HttpLlmCredentialValidator' app/src/main/kotlin/com/moonkey/androidagent app/src/test/kotlin/com/moonkey/androidagent`
  - Result: one implementation, no tests or code that depend on polymorphism
- Why this is over-abstracted:
  - The view model constructs `HttpLlmCredentialValidator` directly.
  - The interface does not decouple any real backend choice.
- Verdict: collapse to a concrete validator type or a private helper function next to onboarding.

#### 15. The sub-agent catalog is generic, but production only has one sub-agent

- Files:
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/SubAgentRunner.kt:29-99`
  - `app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt:129-136`
  - `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/DelegateTaskTool.kt:27-95`
- Verification:
  - `AgentRegistry.createDefault()` registers exactly one entry: `ExecutorAgent.definition` at `SubAgentRunner.kt:94-97`
  - `SessionAgentRunner` always uses `AgentRegistry.createDefault()` at `SessionAgentRunner.kt:132`
  - `delegate_task` still requires `agent_name` in schema and validation at `DelegateTaskTool.kt:44-95`
- Why this is over-abstracted:
  - Real architecture: planner delegates to one executor. That is valid.
  - Extra architecture: registry, directory prompt, generic `agent_name` selection, and catalog lookup. That is not earning its cost today.
  - The planner is forced to choose among one valid option.
- Verdict:
  - keep planner/executor
  - remove the fake marketplace/catalog layer until a second real sub-agent exists

#### 16. `narrativeSummaryOnLimit` is dead configurability

- Files:
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/SubAgentRunner.kt:36`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/SubAgentRunner.kt:180`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:48`
- Verification:
  - `rg -n '\bnarrativeSummaryOnLimit\b' app/src/main/kotlin/com/moonkey/androidagent`
  - Result: only declaration and two reads
  - Production `ExecutorAgent.definition` never overrides the default `true`
  - Main executor policy separately hardcodes `true`
- Why this is over-abstracted:
  - The system behaves as if this flag were constant.
  - The type system suggests a supported variation that the production codebase never exercises.
- Verdict: remove the parameter and keep the current behavior hardcoded.

#### 17. `ToolRouterContext` is likely one unnecessary layer

- Files:
  - `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:379-398`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:93-100`
- Verification:
  - Only production instantiation is `SimpleToolRouterContext(...)` in `TurnExecutionPhaseRunner`
  - `ToolRouter.execute()` immediately re-wraps it into an anonymous `ToolExecutionContext`
- Why this is over-abstracted:
  - One interface, one implementation, one production caller, then immediate adaptation into another context type.
  - The behavior could likely be expressed by direct parameters or a single concrete context type.
- Verdict: lower-priority simplification candidate, not a must-fix.

## Abstractions Checked And Kept

These abstractions are real and should not be flagged as dead or over-engineered:

- `AndroidPlatform`
  - Justified by two production implementations:
    - `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt`
    - `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt`

- `LLMClient`
  - Justified by multiple production implementations:
    - `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt`
    - `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt`
    - `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt`
    - `app/src/main/kotlin/com/moonkey/androidagent/llm/LFMLLMClient.kt`

- `TraceRecorder`
  - Justified by `NoopTraceRecorder` and `FileTraceRecorder`

- `AgentDef` overall persona split
  - Planner, executor, and standalone personas are real.
  - The problem is the unused `id` property, not the whole separation.

## Recommended Direction

Do the cleanup in this order:

1. Delete the obvious dead code and dead API surface
2. Simplify onboarding seams (`OnboardingDemoController`, `LlmCredentialValidator`, unused constructor args)
3. Simplify the sub-agent catalog while preserving the planner/executor architecture
4. Only then decide whether `ToolRouterContext` is worth flattening
