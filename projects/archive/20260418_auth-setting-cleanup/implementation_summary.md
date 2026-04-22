# auth-setting-cleanup — Implementation Summary

**Date:** 2026-04-18
**Status:** done (S2/S3/S4/S5/S8 verified on device; S1/S6/S7 skipped — need OAuth browser + prior-build APK)
**Commits:** `894d10f3..900b606f` (10 commits including 2 inline F1/F2 fixes)
**Design:** `design_claude.md` (v4 KISS, no-migration upgrade)

## What was built

A single-source-of-truth credential model. The selected model determines the provider; the provider deterministically determines the credential source and client class. No fallback chains, no signal keys, no duplicate persistence.

```
selectedModel ──► provider ──► AuthStore.get(provider) ──► LLMClient
   (key)        (flat enum)    (single store)              (factory routing)
```

## Architecture deltas vs prior

| Concern | Before | After |
|---|---|---|
| Credential storage | `AppSettingsState` fields + `OAuthCredentialStore` + `OnboardingStore` encrypted draft | Single `AuthStore` (`EncryptedSharedPreferences`, app-scoped via `AuthStoreHolder`) |
| Provider enum | `OPENAI`/`OPENROUTER`/`NOVITA`/`LOCAL` (auth mode tangled) | Flat `OPENAI_API`/`OPENAI_CODEX`/`OPENROUTER`/`NOVITA`/`LOCAL_LFM` (mode encoded per entry) |
| OAuth routing | `__AUTH_METHOD_OPENAI == "oauth"` signal key + `isOAuth(entry)` sniff | `entry.provider == OPENAI_CODEX` |
| OAuth in CodexClient | Captured `accessToken` + `chatgptAccountId` fields | `suspend () -> CodexHeaders` supplier — read fresh per request |
| Factory cache | `(provider, baseUrl, api, oauth)` tuple | `(modelName → Entry(generation, client))` atomic compute, `AuthStore.generation(provider)` invalidation |
| Factory ctor | `apiKeyResolver: (String) -> String?` | `authStore: AuthStore`, `baseUrlOverrides: Map<LLMProvider, String>` |
| Settings tab switch | Could land in OAuth-provider-in-ApiKey-tab state | Canonicalization rule: provider derives from `selectedModel.provider` if mode matches, else tab default |
| Settings deep-link | None | Banner tap + pre-flight check both populate `SettingsDeepLink(LLM_AUTH, authTab)` consumed by `SettingsSheet(initialPage, initialAuthTab)` |
| Onboarding auth state | `OnboardingStore.authMethod` + encrypted `api_key_draft` | Derived from `AuthStore.has(provider)`; typed text is ViewModel-transient |
| Upgrade migration | Would have required: detect old format, migrate fields, write sentinel, clear legacy | None. Empty `AuthStore` → banner → re-auth (pre-release decision) |
| Checkpoint compatibility | Pre-split snapshots silently re-interpreted as `OPENAI_API` | `SessionRuntimeSnapshot.schemaVersion` 1→2; `AgentSession.reload` rejects v1 with user-visible message |

## Key decisions

1. **Flat enum + `mode` accessor** instead of `(provider, authMode)` pair. Removes one axis at every callsite; UI grouping derives via 1-line `provider.mode`.
2. **Header supplier on `CodexResponseClient`** instead of token-only field. Bundles `accessToken` + `chatgptAccountId` + `email` so account switch invalidates without rebuilding the cached client.
3. **Atomic compute() in factory cache**: `compute(modelName) { _, cur -> if (cur == null || cur.first != gen) Pair(gen, build()) else cur }`. Eliminates the read-then-rebuild race where two callers could both invalidate after a bump.
4. **Abort-protect OAuth refresh**: capture `genBefore` before the network call; only commit refreshed credential if generation unchanged. Lets concurrent `set/clear` win without serializing all writes behind a slow refresh.
5. **Zero migration code**. Pre-release product. The migration test surface (idempotency, durability, crash-point) costs more engineering than asking testers to re-sign-in once. Upgrade is a one-line release note.
6. **`SessionCoordinator.CreateResult` sealed class** distinguishes `Success` / `LockBusy` / `Aborted`. v1-checkpoint rejection clears `pendingInputs` so a stale goal does not auto-run in the next fresh session.
7. **App-scoped `AuthStore`** (via `AuthStoreHolder`) — config rotation, service rebind, and session reload all observe the same instance, so generation counter and in-memory degraded fallback stay coherent.

## Codex review rounds

Three rounds of codex review caught real issues that would have shipped:

- **Round 1 (auth-store-core)**: refresh race with concurrent `set/clear`; `mutableMapOf` not thread-safe in degraded mode → `ConcurrentHashMap` + abort-protected refresh.
- **Round 2 (factory-rewrite)**: separate `lastSeenGen` map allowed two callers to both invalidate after a bump → atomic `(generation, client)` Entry.
- **Round 3 (runtime-wiring)**: `AuthStore` was Activity-scoped → `AuthStoreHolder` singleton; `runBlocking` on main thread for credential intent writes → `lifecycleScope.launch` + `Dispatchers.IO`; deep-link payload built but unconsumed → plumbed `pendingDeepLink` through `MainActivityContent` → `SettingsSheet`.
- **Round 4 (rollup)**: onboarding credential-error CTA was a no-op (`onGoToAuthStep = {}` default) and `apiKey=Done + no AuthStore credential` mapped to a disabled `Valid("")` state → required CTA wiring + reset to `Pending`. v1-reject path leaked input via `createAndSubmit() == false` ambiguity → `CreateResult` sealed class.

## Device QA findings

Two defects found, both fixed:

- **F1 (P1)** `OnboardingViewModel.kt:469` — credential validator ignored `AppSettingsState.openaiBaseUrl` debug override; `api.openai.com` rejected mock `gpt-5.4` IDs with HTTP 400. Fix: `resolveBaseUrl(entry)` mirrors `LLMClientFactory.build()`.
- **F2 (P2)** `MainActivity.validateCloudKeysForSelectedModels()` — pre-flight check opened Settings via raw `showSettings = true` with no deep-link payload. Banner-tap path was already correctly wired; the gap was the pre-flight code path. Fix: populate `pendingSettingsDeepLink` before flipping; `MainActivityContent` accepts `initialSettingsDeepLink`.

## Out of scope (still TODO)

- S1 (fresh OAuth → Run Demo): needs browser interaction
- S6 (upgrade from prior-build APK → empty AuthStore → re-auth): needs baseline APK
- S7 (v1 checkpoint reload rejection in user-visible UI): needs prior-build session

These would close the upgrade-path verification loop. Functionally the code paths exist and unit-test pass; on-device evidence pending an APK baseline.
