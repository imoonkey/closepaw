# Review: custom-endpoint milestone (PR1 + PR2)

Reviewed `8865760d..HEAD` on branch `task/custom-endpoint`, with extra depth on
discovery commit `7e0715a5`.

## Summary

The foundation, NOVITA -> OTHER plumbing, auto-flip fix, visible-name scoping, tool
filter, namespacing, conservative vision defaults, preflight deep-linking, slim
cache shape, and searchable picker are directionally solid. The PR2 discovery
layer adds the right core pieces: `ModelDiscovery`, `ModelDiscoveryCache`,
`ModelCatalogRepository.refresh`, and a pure picker model with focused tests.

I am not approving the milestone yet because discovery refresh still has a
security race: the UI validates the live typed OTHER base URL, but the refresh
network request resolves the persisted debounced base URL. There is also a cache
secret-leak gap if a base URL contains user-info, query, or fragment components.

## Critical

1. OTHER refresh can send the current key to a stale persisted base URL.

   In `ApiKeyTabContent`, the refresh gate is evaluated against
   `otherBaseUrlText`, the live text field value (`LlmAuthSettingsPage.kt:447-455`,
   `RefreshButtonGate.kt:33-39`). But the refresh action calls
   `repo.refresh(selectedProvider, apiKeyText)` without passing that validated URL.
   `ModelCatalogRepository.refresh()` then calls `effectiveBaseUrlFor(OTHER)`,
   which reads `settingsStore.load().otherBaseUrl` (`ModelCatalogRepository.kt:75`,
   `:85`, `:106-108`). OTHER base URL persistence is debounced by 300 ms
   (`LlmAuthSettingsPage.kt:233-237`, `:308-318`), so this sequence is possible:
   persisted endpoint A, user edits text field to endpoint B, refresh button
   enables for B, user taps before debounce commits, refresh sends the current
   `apiKeyText` to A.

   This bypasses the visible-name scoping guard because the leak is in model
   discovery itself, not later model selection. Fix by making refresh use the same
   normalized base URL that the button gate validated, or by flushing/canceling the
   debounce before refresh and verifying persisted normalized URL equals the live
   value before any network call. Add a regression test with persisted A/live B
   proving the discover function receives B or refresh is disabled.

   Blocking status: not fixed in reviewed commits. Proposed task ID:
   `custom-endpoint-review-fix-001-refresh-base-url-source-of-truth`.

## High

1. Secret-bearing base URLs can be persisted into the discovery cache and echoed in refresh errors.

   `OtherBaseUrlValidator` only checks scheme, host, and debug/release HTTP policy
   (`OtherBaseUrlValidator.kt:27-65`). It accepts URLs such as
   `https://user:pass@example.com/v1`, `https://example.com/v1?api_key=...`, and
   `https://example.com/v1#token`. Discovery then uses the normalized raw string
   as the cache key (`ModelDiscoveryCache.kt:86-87`) and stores it in each bucket
   (`ModelDiscoveryCache.kt:60-63`, `:100-104`). On HTTP failure,
   `ModelDiscovery.fetch()` includes the full request URL in the surfaced error
   message (`ModelDiscovery.kt:260-265`).

   The cache is slim for upstream metadata and does not store the bearer key, but
   this still violates the "no key leaked into cache file" requirement for users
   who paste a credential-bearing URL. Reject `rawUserInfo`, `rawQuery`, and
   `rawFragment` in `OtherBaseUrlValidator`, and avoid echoing full URLs in
   user-visible refresh errors. Add tests for user-info/query/fragment rejection.

   Blocking status: not fixed in reviewed commits. Proposed task ID:
   `custom-endpoint-review-fix-002-reject-secret-bearing-base-urls`.

## Medium

1. Refresh status is provider-scoped, not base-URL scoped, so stale in-flight refreshes can lie about the current endpoint.

   `DiscoveryState` stores `refreshing`, `lastFetchedAt`, and `lastError` only by
   provider (`ModelCatalogRepository.kt:220-235`). If OTHER refresh A is in flight,
   the user changes to base URL B, and A finishes later, `withSuccess(OTHER, now)`
   can set "Last refreshed" and clear errors for B even though the successful
   request was for A (`ModelCatalogRepository.kt:83-98`). The catalog scoping at
   `ModelCatalogRepository.kt:128-144` prevents old A entries from becoming
   selectable, but the status row can still mislead the user.

   Fix together with Critical #1: track refresh state by `(provider, baseUrl)` or
   ignore completion if the current effective base URL no longer matches the URL
   captured at refresh start. Use `MutableStateFlow.update` for state mutations so
   concurrent provider refreshes do not drop each other's status updates.

2. Manual OTHER model ids are not validated with the same rule as discovered model ids.

   Discovery rejects ids containing whitespace or starting with `/` or `:`
   (`ModelDiscovery.kt:166-170`), matching the design. The manual
   `otherModelId` path only trims at synth time and persists raw intent/UI input
   (`ModelCatalogRepository.kt:147-165`, `MainActivityIntentApplier.kt:96-99`).
   That leaves the manual `other-custom` path less strict than discovery and the
   documented setting rule.

   Fix by extracting a tiny shared model-id validator and applying it in
   `synthOtherEntry`, UI auto-flip/preflight, and the intent applier.

## Low

1. `git diff --check 8865760d..HEAD` reports new blank lines at EOF in
   `AppSettingsState.kt` and `MainActivityModelValidation.kt`. This is not
   behavioral, but it should be cleaned before merge.

## Confirmed Good

- Tool-calling filter is mandatory when `supported_parameters` is present, and
  upstreams without that field are accepted (`ModelDiscovery.kt:205-209`).
- Non-chat filtering prefers structured fields before id heuristics
  (`ModelDiscovery.kt:211-240`).
- Discovered entries are namespaced and carry `baseUrl = sourceBaseUrl`
  (`ModelDiscovery.kt:143-155`).
- `supportsVision` defaults false and only flips true on explicit image modality
  (`ModelDiscovery.kt:192-203`).
- Visible-name scoping hides stale OTHER cached entries after base URL changes
  (`ModelCatalogRepository.kt:128-144`), and tests cover URL A -> B hiding.
- Picker behavior matches the requested shape: blank search is grouped,
  non-blank search is flattened into one search group, sort is by `created`
  descending, and selected row indexing/auto-expansion are covered.
- `findMissingCloudKeys()` short-circuits stale discovered `other:*` selections
  to an OTHER deep link with the refresh/select hint (`MainActivityModelValidation.kt:46-54`).
- URL validation parity from `9111320a` is preserved for invalid plain URLs in
  repo synth, bootstrap, and intent paths.
- Production code/docs/scripts/eval no longer reference `NOVITA`, `novita_api_key`,
  or `NOVITA_API_KEY`; remaining hits are in this project design README/history.

## Verification

- `./gradlew test` passed.
- `./gradlew lint` passed.
- `git diff --check 8865760d..HEAD` failed only on the two EOF whitespace issues
  listed under Low.

VERDICT: REQUEST_CHANGES

## Round 2

Reviewed fixup commit `02597ac7` (`fix(llm): close refresh-URL race + reject secret-bearing URLs + share modelId validator`) with `git show 02597ac7`, then rechecked the five prior findings.

### Re-Verification

1. CRITICAL refresh URL race: resolved.

   `ModelCatalogRepository.refresh()` now requires `refresh(provider, key, baseUrl)` and never chooses the network target by reading `settingsStore` (`ModelCatalogRepository.kt:83-115`). `ApiKeyTabContent` passes `LLMProvider.OPENROUTER.defaultBaseUrl` for OpenRouter and `OtherBaseUrlValidator.validate(otherBaseUrlText)` from the live text field for OTHER (`LlmAuthSettingsPage.kt:454-467`). The remaining `settingsStore` read inside `refresh()` is the post-fetch stale-completion guard (`ModelCatalogRepository.kt:117-124`), which drops results instead of selecting a request URL. The race regression test exists: `refresh uses caller-provided baseUrl, NOT persisted settings (race fix)`.

2. HIGH secret-bearing URL handling: implementation resolved; test coverage incomplete.

   `OtherBaseUrlValidator` rejects `rawUserInfo`, `rawQuery`, and `rawFragment` without echoing the secret segment (`OtherBaseUrlValidator.kt:56-73`), and validator tests cover all three cases (`OtherBaseUrlValidatorTest.kt:109-146`). `ModelDiscovery.fetch()` now formats non-2xx errors with `url.host` instead of the full URL (`ModelDiscovery.kt:256-263`).

   However, I do not find a `ModelDiscovery.discover()` / MockWebServer non-2xx regression test proving the fetch error message is host-only and does not include a secret-bearing path/user-info/query from the input URL. `ModelDiscoveryTest` still only covers the 200 HTTP integration path (`ModelDiscoveryTest.kt:250-264`). Add this test before approval.

3. MEDIUM stale completion + concurrent state updates: resolved.

   Discovery state mutations now go through `MutableStateFlow.update` via `updateDiscoveryState()` (`ModelCatalogRepository.kt:54-57`, `:128-140`). The stale-completion guard checks `effectiveBaseUrlFor(provider)` before cache write/state success and drops stale results (`ModelCatalogRepository.kt:117-124`). The drop test exists: `stale refresh completion is dropped when effective baseUrl moved on`.

4. MEDIUM shared model id validator: resolved.

   There is one `ModelIdValidator` (`ModelIdValidator.kt`) and it is used at the requested five sites: `synthOtherEntry` (`ModelCatalogRepository.kt:189-195`), `MainActivityIntentApplier` (`MainActivityIntentApplier.kt:97-108`), `ModelDiscovery` (`ModelDiscovery.kt:117-121`), `shouldAutoFlipToOtherCustom` (`LlmAuthSettingsPage.kt:543-548`), and `findOtherMissing` (`MainActivityModelValidation.kt:89-94`). Unit tests exist in `ModelIdValidatorTest`, and the intent invalid-id path is covered.

5. LOW EOF whitespace: resolved.

   `git diff --check 8865760d..HEAD` now exits cleanly.

### Smoke

- Targeted affected tests passed:
  `./gradlew testDebugUnitTest --tests ai.closepaw.llm.ModelCatalogRepositoryTest --tests ai.closepaw.llm.OtherBaseUrlValidatorTest --tests ai.closepaw.llm.ModelIdValidatorTest --tests ai.closepaw.app.MainActivityIntentApplierSecurityTest --tests ai.closepaw.llm.ModelDiscoveryTest`.
- No new HIGH or CRITICAL issue found in the fixup commit.

Must-fix items:
1. Add a `ModelDiscovery.discover()` HTTP error sanitization regression test for a non-2xx response, asserting the surfaced exception contains only the host/source label and does not contain secret-bearing URL components.

VERDICT: REQUEST_CHANGES

## Round 3

Reviewed fixup commit `66bd4ceb` (`test(llm): pin ModelDiscovery error message to host-only`) with `git show 66bd4ceb`.

### Re-Verification

- The Round 2 must-fix is resolved. `ModelDiscoveryTest` now has a MockWebServer non-2xx regression test, `non-2xx error message contains host only, never URL secrets (Codex r2)` (`ModelDiscoveryTest.kt:267-296`).
- The test enqueues a `503` response, calls `ModelDiscovery.discover(LLMProvider.OTHER, base, "sk-test")`, and asserts the surfaced `IOException` message contains the expected host and status code.
- The test uses a sentinel secret-bearing path segment in the input base URL (`/v1/SUPERSECRET-do-not-leak`) and asserts the exception message does not contain either the sentinel or the full path. Query and fragment rejection remain covered at the validator boundary; the discovery regression pins the non-2xx fetch path to host/status rather than URL components.

### Verification

- `./gradlew testDebugUnitTest --tests ai.closepaw.llm.ModelDiscoveryTest` passed.

VERDICT: APPROVE
