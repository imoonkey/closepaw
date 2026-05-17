# Code Review: 9932edcc

Commit: `9932edcc` (`refactor(llm): rename NOVITA -> OTHER and add OTHER provider plumbing`)

## Summary

The core backend plumbing is sound for Sub 1b. The key-leak guard is at the factory boundary, `other-custom` is synthesized only from a complete settings pair, and the production `AppSettingsState.create(...)` path wires OTHER settings updates to catalog invalidation.

## Critical

None.

## High

None.

## Medium

1. Active non-app files still contain stale Novita plumbing and docs.
   - `scripts/debug-run.sh:375-377` still reads `NOVITA_API_KEY` and forwards `novita_api_key`, but this commit removed `MainActivity.EXTRA_NOVITA_API_KEY` and `MainActivityIntentPayload.novitaApiKey`, so the extra is now dead.
   - `eval/aw_bridge/runner.py:66-73` still treats `NOVITA` / `NOVITA_API_KEY` as a supported provider/key.
   - `eval/aw_bridge/native_agent_bridge.py:212-221` still maps `NOVITA_API_KEY` to `novita_api_key`.
   - `doc/main/app/settings.md:26,161` and `doc/main/infra/llm.md:83,98` still document `LLMProvider.NOVITA`.
   - If scripts/eval/doc updates are intentionally deferred to Sub 1c, this is a tracked handoff rather than a 1b blocker. If 1b is expected to be grep-clean outside app code, these need to be removed or replaced with the OTHER trio.

## Low

1. `OtherBaseUrlValidator` has no production caller yet.
   - `OtherBaseUrlValidator.validate(...)` defaults `allowDebugHttp` from `BuildConfig.DEBUG`, and release `ChatCompletionClient` construction still rejects non-HTTPS via release `InsecureSslConfig.validateBaseUrl(...)`.
   - The validator policy itself is currently only test-exercised. When Sub 1c adds UI/intent call sites, they should call the defaulted overload or pass `allowDebugHttp = BuildConfig.DEBUG`, not a caller-controlled value from user input.

2. The OTHER success test mostly asserts construction.
   - `LLMClientFactoryTest` asserts `OTHER` returns a `ChatCompletionClient`, and separately asserts blank `entry.baseUrl` throws `MissingCredential(OTHER)`.
   - That covers the critical no-silent-fallback path, but the success case would be stronger if it asserted the constructed client actually targets `entry.baseUrl`.

## Focus Checks

- SECURITY: Pass. `LLMClientFactory` checks `entry.baseUrl.isNullOrBlank()` in the `LLMProvider.OTHER` branch and throws `MissingCredential(LLMProvider.OTHER)` before reading the OTHER key or constructing `ChatCompletionClient`.
- INVARIANT: Pass. `AppSettingsState.updateOtherBaseUrl(...)` and `updateOtherModelId(...)` both save through `AppSettingsStore` and call `onOtherSettingsChanged()`. Production `AppSettingsState.create(context)` wires that hook to `ModelCatalogRepositoryHolder.get(appContext).invalidate()`.
- SYNTH RULE: Pass. `ModelCatalogRepository.synthOtherEntry()` trims both settings and returns `null` if either is blank; no synth entry and no crash for partial settings.
- NAMING: Pass for PR2 collision shape. The synthesized catalog key is `other-custom`; the API `modelId` remains the user-provided model id. Future discovered OTHER entries are planned as `other:*`, so they do not collide with the fixed synth key.
- VALIDATOR: Pass with the Low note above. The default source is `BuildConfig.DEBUG`; release client construction also rejects non-HTTPS base URLs.
- NOVITA cleanup: App/Kotlin enum references are clean, but active `scripts/`, `eval/`, and `doc/main/` still have stale Novita references.
- KISS: No concerning abstraction in the hot path. The only new standalone helper without a production caller yet is `OtherBaseUrlValidator`, which is narrow and clearly intended for Sub 1c.
- Tests: Mostly behavior assertions, not just construction. Repository tests assert emitted catalog state and absence rules; settings tests assert persisted values and invalidation count; factory tests assert exception type/provider for the security guard. The OTHER success test is the one weaker construction-only assertion.

## Verification

- Ran `git show 9932edcc`.
- Ran broad grep for `NOVITA|novita_api_key|NOVITA_API_KEY` across `app scripts eval doc/main README.md`.
- Ran focused tests: `./gradlew :app:testDebugUnitTest --tests 'ai.closepaw.llm.LLMClientFactoryTest' --tests 'ai.closepaw.llm.ModelCatalogRepositoryTest' --tests 'ai.closepaw.llm.OtherBaseUrlValidatorTest' --tests 'ai.closepaw.app.AppSettingsStoreTest'` passed.
- Note: `./gradlew test --tests ...` is not supported by this aggregate task. `git show --check 9932edcc` reports one low-level whitespace issue: `app/src/main/kotlin/ai/closepaw/app/AppSettingsState.kt:150: new blank line at EOF`.

VERDICT: APPROVE
