# Review: multi-llm support (`a0ea492528936adc10f6e4117169916e323409c2..HEAD`)

## Scope
- Reviewed only code diff against `a0ea492528936adc10f6e4117169916e323409c2`.
- Did **not** read existing code-review docs under `doc/todo/0.02_multi_llms/`.
- Validation run: `./gradlew :app:testDebugUnitTest` (pass).

## Critical
1. Non-OpenAI cloud providers are blocked unless `OPENAI_API_KEY` is also set.
- Where:
  - `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:311`
  - `app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt:95`
- Why:
  - `MainActivity` hard-checks `settingsState.apiKey` (OpenAI key) whenever backend is `OPENAI`.
  - `SessionServices.create` always requires `apiKeys["OPENAI_API_KEY"]` for cloud backend and constructs `OpenAIResponseClient` eagerly.
  - This breaks expected flow for OpenRouter/Novita-only configs (even when their keys are present).
- Impact:
  - Multi-provider support is not actually usable without OpenAI key.
- Fix:
  - Resolve provider from selected model (`mainModel`/`executorModel`) and validate required key per provider.
  - Remove unconditional `OPENAI_API_KEY` gate from cloud session startup.

2. `LOCAL` backend execution path is regressed (turn execution always routed through cloud factory).
- Where:
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:217`
  - `app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt:103`
- Why:
  - `AgentTurnRunner` always does `modelCatalog.resolve(...)` + `llmClientFactory.create(...)`.
  - The local client (`LFMLLMClient`) built in `SessionServices.create` is no longer used for turn planning/execution.
- Impact:
  - Local mode can fail on missing cloud key, or accidentally use cloud client if key exists.
- Fix:
  - Branch in `AgentTurnRunner` by backend: `LOCAL` should use `services.llmClient` (or provide a local-capable factory path).

## High
1. Trace metadata records wrong model after `mainModel/executorModel` migration.
- Where:
  - `app/src/main/kotlin/com/moonkey/androidagent/trace/AgentTrace.kt:54`
  - `app/src/main/kotlin/com/moonkey/androidagent/trace/AgentTrace.kt:224`
  - `app/src/main/kotlin/com/moonkey/androidagent/trace/TraceRecorderFactory.kt:39`
- Why:
  - Still logging deprecated `config.model`; current flow sets `mainModel`/`executorModel`.
- Impact:
  - Trace artifacts can report `gpt-5.2` while runtime actually used another provider/model, degrading debugging and auditability.
- Fix:
  - Log `AgentExecutionConfig.modelName` per turn/agent and `SessionConfig.mainModel` + `executorModel` in run meta.

## Medium
1. Settings UI cannot edit provider-specific keys (OpenRouter/Novita), despite state/storage support.
- Where:
  - `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:170`
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsSheet.kt:67`
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsSheet.kt:181`
- Why:
  - UI only exposes one `apiKey` field (OpenAI), while `openRouterApiKey` / `novitaApiKey` exist in state/store.
- Impact:
  - Provider feature is effectively CLI/intent-driven, not fully operable from app settings.
- Fix:
  - Add provider-key inputs and/or provider-aware key field switching based on selected model.

2. Missing regression tests for provider-key routing and local-backend turn execution.
- Where:
  - Current new tests focus on `ModelCatalog` and `LLMClientFactory`, but no test covers session/turn behavior with:
    - cloud provider key without OpenAI key
    - `LLMBackendType.LOCAL` through `AgentTurnRunner`
- Impact:
  - Critical regressions above passed CI undetected.
- Fix:
  - Add integration-style unit tests around `SessionServices + SessionAgentRunner + AgentTurnRunner` for both scenarios.

## Recommendation
- `CHANGES_REQUESTED`
