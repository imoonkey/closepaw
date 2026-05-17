# Review: b2ca58ed - OTHER provider UI/intent/eval/docs glue

Reviewed `git show b2ca58ed` in `.worktrees/custom-endpoint`, focusing on the requested security, race, preflight parity, deep-link, eval, and docs paths.

## Critical

None found. Release builds still ignore the sensitive intent extras before any credential/settings writes.

## High

1. Stale `other-custom` catalog entries can make the auto-flip run before the current OTHER settings are persisted.

   `ApiKeyTabContent` updates `otherBaseUrlText` / `otherModelIdText` immediately, then persists them through separate 300 ms debounced jobs. The flip gate only checks that `modelCatalog.resolveOrNull("other-custom")` is non-null before calling `onModelChange("other-custom")` (`app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:385`, `:412`, `:423`, `:467`). That enforces the intended "debounce flush -> repo.invalidate -> catalog contains -> flip" order only for the first-ever OTHER setup. If the repo already contains an old `other-custom` entry, editing Base URL or Model Id to a new valid value can flip `selectedModel` immediately against the old catalog row, before the new debounce flush and invalidate complete. A quick launch can then use the previous endpoint/model, because `ModelCatalogRepository` publishes exactly what was last persisted (`app/src/main/kotlin/ai/closepaw/llm/ModelCatalogRepository.kt:62`).

   Fix: make the flip gate compare the catalog entry to the normalized current UI values, e.g. `entry.baseUrl == OtherBaseUrlValidator.validate(otherBaseUrlText).getOrNull()` and `entry.modelId == otherModelIdText.trim()`, or drive the flip from persisted settings/repo emissions instead of transient text. Add a regression test for editing an existing valid OTHER config while a stale `other-custom` entry is already present.

2. OTHER base URL validation is not applied consistently across preflight and session bootstrap.

   `findMissingCloudKeys` correctly checks `OtherBaseUrlValidator` and reports `Other: base URL invalid` (`app/src/main/kotlin/ai/closepaw/app/MainActivityModelValidation.kt:63`). `SessionLlmBootstrapper.ensureRequiredCredentials`, however, only checks that the `other-custom` catalog row exists and that `AuthStore` has an OTHER key (`app/src/main/kotlin/ai/closepaw/session/SessionLlmBootstrapper.kt:85`). `ModelCatalogRepository` synthesizes `other-custom` for any non-blank base URL/model id without validation (`app/src/main/kotlin/ai/closepaw/llm/ModelCatalogRepository.kt:64`). That means bootstrap can accept an invalid OTHER endpoint that the UI preflight would reject, then fail later in the client/builder path with a non-credential error and no clean OTHER deep link. This violates the requested parity between `findMissingCloudKeys` and `ensureRequiredCredentials`.

   Fix: use the same validator before materializing or accepting the synth entry. The simplest shape is to have `ModelCatalogRepository.synthOtherEntry()` return null unless `OtherBaseUrlValidator.validate(baseUrl)` succeeds and store the normalized URL; then `ensureRequiredCredentials` will treat invalid/blank base URL and blank model id through the same MissingCredential(OTHER) path. Alternatively, validate the resolved `ModelEntry.baseUrl` directly in `ensureRequiredCredentials` and `LLMClientFactory`.

3. The intent applier persists `other_base_url` without upstream validation.

   `MainActivityIntentPayload.from()` only filters OTHER extras for non-blank strings, and `applyIntentPayloadToSettings()` writes `payload.otherBaseUrl` straight into `AppSettingsState.updateOtherBaseUrl()` before invalidating the catalog (`app/src/main/kotlin/ai/closepaw/app/MainActivityIntentPayload.kt:49`, `app/src/main/kotlin/ai/closepaw/app/MainActivityIntentApplier.kt:80`). So the answer to the security prompt is: no, `MainActivityIntentApplier` does not call `OtherBaseUrlValidator` before persisting. MainActivity's normal launch preflight will usually catch this before a session starts, but the bad value is still stored and can materialize into the catalog because of the repository issue above.

   Fix: validate and normalize `payload.otherBaseUrl` at the intent boundary. On failure, skip the write and log a non-secret reason. Add a test that an invalid `other_base_url` extra leaves `settingsState.otherBaseUrl` unchanged and does not trigger a usable `other-custom` catalog entry.

## Medium

1. Tests cover the happy path and blank fields, but not the stale-catalog race or invalid-bootstrap parity.

   The new tests assert blank OTHER fields, invalid UI preflight, missing synth row, missing key, and intent invalidation. They do not exercise the highest-risk cases above: a stale existing `other-custom` row during field edits, and `SessionLlmBootstrapper.create()` with a catalog row whose OTHER `base_url` is invalid but non-blank.

2. Docs still have OTHER/NOVITA drift and a storage-path contradiction.

   `doc/main/infra/llm.md:43` still lists "Novita" as a named OpenAI-compatible endpoint example after the provider removal. `doc/main/app/settings.md:24` says `openaiBaseUrl` is persisted, which matches `AppSettingsStore`, but `doc/main/app/settings.md:148` still says it is transient and not persisted. The OTHER provider tables and `AuthStore` key examples are otherwise updated.

## Low

1. Eval preflight error wording is imprecise for OTHER extras.

   `runner.py` correctly loads `OTHER_API_KEY`, `OTHER_BASE_URL`, and `OTHER_MODEL_ID` from `.env` / environment (`eval/aw_bridge/runner.py:392`), requires all three for `main_model == "other-custom"` (`eval/aw_bridge/runner.py:477`), and `native_agent_bridge.py` forwards all three as intent extras (`eval/aw_bridge/native_agent_bridge.py:213`). The thrown message still says "Missing required API key(s)" even when the missing values are `OTHER_BASE_URL` or `OTHER_MODEL_ID` (`eval/aw_bridge/runner.py:423`). The listed variable names are clear enough to debug, but the label should become "credential/config value(s)".

## Confirmed Good

- `applyIntentPayloadToSettings()` calls `invalidateCatalog()` after all present OTHER fields are applied when any OTHER field changed (`app/src/main/kotlin/ai/closepaw/app/MainActivityIntentApplier.kt:80`). If only one or two fields are present, it updates those fields and leaves the missing third field at its previous persisted value.
- Settings deep links do select the OTHER sub-tab: `MainActivity` sets `SettingsDeepLink.provider`, `MainActivityContent` passes `initialProvider`, `SettingsSheet` forwards it, and `ApiKeyTabContent` seeds `selectedProvider` from it (`app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:545`, `app/src/main/kotlin/ai/closepaw/app/MainActivityContent.kt:153`, `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsSheet.kt:146`, `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:367`).
- Eval/native bridge wiring forwards the OTHER trio end-to-end and removes the old uppercase NOVITA env mapping.

VERDICT: REQUEST_CHANGES

Must-fix items:
1. Enforce current-value catalog matching before auto-flipping `selectedModel` to `other-custom`.
2. Apply `OtherBaseUrlValidator` consistently in the intent/repository/bootstrap path so invalid OTHER base URLs do not persist, materialize, or proceed differently from UI preflight.

## Round 2

Reviewed fixup commit `9111320a` (`fix(llm): close OTHER auto-flip race + URL validation parity`) against the 3 HIGH, 2 MEDIUM, and 1 LOW items above.

### Resolved

1. HIGH #1 auto-flip race: resolved.

   `ApiKeyTabContent` now delegates to `shouldAutoFlipToOtherCustom(...)`, which validates the current UI base URL, normalizes it, trims the UI model id, resolves the `other-custom` catalog entry, and only flips when `entry.baseUrl == normalizedUrl` and `entry.modelId == trimmedModelId`. It no longer treats "row exists" as sufficient (`app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt`). This directly closes the stale-catalog auto-flip case from Round 1.

2. HIGH #2 preflight/bootstrap validation parity: resolved.

   `ModelCatalogRepository.synthOtherEntry()` now refuses to synthesize `other-custom` unless `OtherBaseUrlValidator.validate(settings.otherBaseUrl)` succeeds, and it stores the normalized URL in the catalog entry. That makes invalid persisted base URLs appear as a missing synth row, so the existing `ensureRequiredCredentials` branch returns `MissingCredential(OTHER)` instead of allowing a later client-construction error (`app/src/main/kotlin/ai/closepaw/llm/ModelCatalogRepository.kt`).

3. HIGH #3 intent boundary validation: resolved.

   `MainActivityIntentApplier` now validates `payload.otherBaseUrl` with `OtherBaseUrlValidator` before calling `settingsState.updateOtherBaseUrl(...)`. Invalid values are skipped and logged without echoing the rejected URL; valid values are persisted in normalized form (`app/src/main/kotlin/ai/closepaw/app/MainActivityIntentApplier.kt`).

4. MEDIUM missing tests: resolved.

   Added `LlmAuthOtherAutoFlipTest` for the stale catalog base URL/model id cases, missing row, invalid URL, blank key, wrong provider, already-selected guard, and trailing-slash normalization. Added invalid URL synth tests in `ModelCatalogRepositoryTest`, invalid-bootstrap parity coverage in `SessionLlmBootstrapperTest`, and invalid intent URL coverage in `MainActivityIntentApplierSecurityTest`.

5. MEDIUM doc drift: resolved.

   `doc/main/infra/llm.md` no longer cites Novita as an example provider, and `doc/main/app/settings.md` now consistently describes `openaiBaseUrl`, `otherBaseUrl`, and `otherModelId` as persisted SharedPreferences-backed settings.

6. LOW eval wording: resolved.

   `eval/aw_bridge/runner.py` now reports missing "credential/config value(s)" rather than only "API key(s)", which fits the OTHER trio (`OTHER_API_KEY`, `OTHER_BASE_URL`, `OTHER_MODEL_ID`).

### Remaining Findings

None.

VERDICT: APPROVE
