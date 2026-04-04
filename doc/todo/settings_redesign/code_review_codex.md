# Review: Settings Page Restructure Implementation

Date: 2026-04-04
Commit range: `dc8cd16..HEAD`
Scope: settings restructure review of the requested files, with focus on credential split correctness, tab side effects, model canonicalization, OAuth lifecycle/threading, and Compose state wiring.

## Summary

I reviewed the requested settings, auth, store, activity, onboarding, and model-catalog changes. I also ran:

```bash
./gradlew testDebugUnitTest --tests 'com.moonkey.androidagent.app.AppSettingsStateTest' --tests 'com.moonkey.androidagent.llm.ModelCatalogTest'
```

Those targeted unit tests pass, but they only cover the pure `buildApiKeys()` and `ModelCatalog` helpers. The main regressions are in the wiring between `MainActivity`, onboarding, the settings UI, and the new split credential fields.

## High

### High: the OpenAI manual-key / OAuth split is not actually wired through the settings UI or onboarding

The new split exists in `AppSettingsState` / `AppSettingsStore`, but the callers that matter still use the legacy `apiKey` field:

- `MainActivityContent` passes the API Key tab `settingsState.apiKey` and `settingsState::updateApiKey` instead of `openAiManualApiKey` and `updateOpenAiManualApiKey` (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityContent.kt:88-91`).
- Onboarding still writes OpenAI credentials through `updateApiKey(...)` for both OAuth success and manual OpenAI save (`app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:119`, `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:491`).
- The one-time cleanup hook `migrateCredentialSplit(...)` is implemented but never called anywhere (`app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:228-244`).

Result: `openAiManualApiKey` is effectively dead state, existing OAuth users can still see bearer tokens in the manual API key field, and the new split does not really protect the “manual key survives OAuth sign-in/sign-out” invariant outside the synthetic `AppSettingsStateTest`.

### High: OAuth token state is fragmented across `OAuthCredentialStore`, `openAiOAuthAccessToken`, and legacy `apiKey`, so restart and refresh both break

The new code has no single sync path for the active OAuth token:

- Settings sign-in saves `OAuthCredentialStore` and the transient `openAiOAuthAccessToken`, but not the legacy `apiKey` fallback (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:671-678`).
- App startup restores only `authMethod` and the auth-card UI state; it never reloads the stored access token into `AppSettingsState` (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:121-128`, `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:646-652`).
- `buildApiKeys()` prefers `openAiOAuthAccessToken` in OAuth mode and only falls back to `apiKey` if that transient field is blank (`app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt:155-161`).
- Refresh goes the opposite direction: `refreshOAuthTokenIfNeeded()` writes the refreshed token to `apiKey`, but does not update `openAiOAuthAccessToken` (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:706-716`).

This creates two concrete failures:

- A user who signs in from the new settings page can restart the app, still look “signed in”, and then fail cloud-key validation because no OpenAI token is exposed from `buildApiKeys()`.
- A successfully refreshed token can be ignored in the same process, because session creation still uses the stale `openAiOAuthAccessToken`.

## Medium

### Medium: tab switching updates `authMethod` only in memory, so the “tabs are the durable selector” contract is false after process recreation

`LlmAuthSettingsPage` correctly changes backend/auth mode on tab click (`app/src/main/kotlin/com/moonkey/androidagent/ui/settings/LlmAuthSettingsPage.kt:91-110`), but only backend is durably persisted:

- `onAuthMethodChange` is wired to `settingsState::updateAuthMethod` (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityContent.kt:88-89`).
- `updateAuthMethod()` only mutates in-memory Compose state (`app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt:140-142`).
- Startup later restores auth mode from `OnboardingStore` (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:127`).

Result: switching from `Sign In` to `API Key` or `Local` works only for the current process. After restart, the app can silently snap back to the previously persisted auth method, which violates the intended “tab selection is the saved config” behavior.

### Medium: canceling OAuth no longer tears down the callback server promptly, so cancel/retry can fail with “port in use”

The shared helper changed the cancellation behavior in a bad way:

- `openAiSignIn()` blocks in `withContext(Dispatchers.IO) { server.waitForCallback() }` (`app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAiSignIn.kt:47-48`).
- `waitForCallback()` itself blocks in `ServerSocket.accept()` (`app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAIOAuth.kt:99-103`).
- The only cleanup is `finally { server.stop() }` after the suspend function returns (`app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAiSignIn.kt:75-76`).
- Both cancel paths now only cancel the coroutine / update UI (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:689-692`, `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:138-141`).

Because the blocking `accept()` is not being interrupted by caller-side shutdown anymore, cancel is not immediate. The old server can keep port `1455` bound until the socket timeout, and the next sign-in attempt can immediately fail with the “Could not start local server” error.

### Medium: “preferred model” is inconsistent between settings and onboarding, so canonicalization depends on entry point

The restructure added `modelsFor()` / `preferredModelFor()`, but there is still no single source of truth for which model is “preferred”:

- `preferredModelFor()` returns the first matching model (`app/src/main/kotlin/com/moonkey/androidagent/llm/ModelCatalog.kt:122-128`).
- Settings canonicalization also picks the first matching model (`app/src/main/kotlin/com/moonkey/androidagent/ui/settings/LlmAuthSettingsPage.kt:339-345`).
- Onboarding still picks `lastOrNull()` for provider defaults (`app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:475-480`).

With the current catalog order (`app/src/main/assets/llm_models.json:2-60`), those choices diverge:

- OpenAI RESPONSE: settings canonicalization prefers `gpt-5.2`, onboarding prefers `gpt-5.4`
- OpenRouter: settings canonicalization prefers `glm-5`, onboarding prefers `qwen3.5`

That makes provider/auth switching non-deterministic across entry points, which is the opposite of “canonicalization”.

## Test Gaps

- There is no automated coverage for the real `MainActivity` settings OAuth path: sign-in from settings, restart rehydrate, refresh re-sync, or auth-card state after onboarding completion.
- There is no test for `AppSettingsStore.migrateCredentialSplit()`, and it would currently be hard to catch its missing call-site because the UI is still bound to the legacy `apiKey`.
- There is no test for cancel/retry on the shared OAuth helper, which is where the callback-server lifecycle regression came from.

## Recommendation

CHANGES_REQUESTED
