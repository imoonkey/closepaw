# Review of `design_claude.md`

Date: 2026-04-03
Scope: correctness, gaps, and design trade-offs against the approved UX spec and current code.

## Findings

### High: manual OpenAI key and OAuth token are still the same field

Claude’s design says `AppSettingsState` only needs `oauthEmail` added and that no other state changes are required (`design_claude.md:78-95`). But its OAuth flow still writes the OAuth access token through `settingsState.updateApiKey(...)`, and sign-out clears that same field (`design_claude.md:267-279`, `design_claude.md:403-410`).

That is not compatible with the current code. `AppSettingsState.apiKey` is the single persisted OpenAI credential today, and `buildApiKeys()` always exposes that value as `OPENAI_API_KEY` (`app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt:19-20`, `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt:105-107`, `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt:134-139`). The approved UX explicitly says sign-out clears OAuth tokens but does **not** clear any existing API key (`doc/todo/settings_redesign/ux_design.md:221`).

Result: the design cannot preserve a manual OpenAI key across OAuth sign-in/sign-out, so it fails the core requirement of switching auth methods without re-onboarding or data loss.

### High: switching to the Sign In tab can activate OAuth without valid OAuth credentials

Claude makes tab selection immediately set `authMethod = "oauth"` for the Sign In tab (`design_claude.md:232-235`). In the current runtime, that flag is not just UI state. `LLMClientFactory` treats `__AUTH_METHOD_OPENAI == "oauth"` as the signal to use the OAuth-specific OpenAI path (`app/src/main/kotlin/com/moonkey/androidagent/llm/LLMClientFactory.kt:49-61`, `app/src/main/kotlin/com/moonkey/androidagent/llm/LLMClientFactory.kt:87-90`).

Because Claude keeps the single `apiKey` field, a user can switch from API Key to Sign In before actually authenticating, and the app now has `authMethod == "oauth"` with either a manual API key or no valid OAuth token behind it. The design also derives `Active("")` from `authMethod == "oauth"` even when there is no email (`design_claude.md:255-260`), which is another symptom that the proposed state model treats “selected Sign In tab” too similarly to “valid signed-in OAuth session.”

Result: the UI state and the request path can drift apart. Mere tab switching can create an invalid runtime configuration.

### Medium: the Sign In path does not fully constrain models to valid OAuth-compatible choices

Claude correctly filters API Key models by provider (`design_claude.md:296-319`), but the Sign In path is underspecified. It mentions OpenAI-only models in self-review, but not the required `ApiType.RESPONSE` constraint or full canonicalization of main/executor model when entering Sign In. That matters in the current codebase:

- the catalog contains both OpenAI Responses models and OpenAI Chat models (`app/src/main/assets/llm_models.json:2-12`, `app/src/main/assets/llm_models.json:50-60`)
- onboarding already documents and enforces that OAuth users need `RESPONSE` models for the Codex backend (`app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:534-545`)
- cloud validation always checks the currently selected main and executor models, regardless of which settings tab is visible (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityModelValidation.kt:11-29`)

Result: without explicit model canonicalization on Sign In entry, the settings UI can leave a chat model or a non-OpenAI provider model selected while presenting the user with an OpenAI OAuth configuration.

### Medium: a few persistence and UX details are still not closed

Several smaller issues remain open:

- The design calls `onboardingStore.saveAuthMethod(null)` (`design_claude.md:271`, `design_claude.md:408`), but the current API only accepts a non-null `String` (`app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingStore.kt:102-106`).
- Task 3 moves version info into `Permissions & Advanced` (`design_claude.md:481-484`), but the approved UX puts version information on the Home page footer (`doc/todo/settings_redesign/ux_design.md:36-58`).
- The approach says `llmBackend` / `onBackendChange` are removed because the tab replaces the backend selector (`design_claude.md:26`), but the proposed `SettingsSheet` signature still keeps both (`design_claude.md:125-128`).

None of these is fatal by itself, but together they show the design is not fully closed at the app-state and UX-contract level.

## Trade-Offs

Claude makes several strong choices:

- keeping `SettingsPage` local to the sheet and driving transitions with `AnimatedContent`
- not reusing `OnboardingViewModel` directly
- passing `ModelCatalog` into settings instead of a pre-flattened options list
- splitting the UI by page instead of replacing one large `SettingsSheet.kt` with another large `SettingsSheet.kt`

Those choices align with the current codebase and should survive into the aligned draft.

The weaker trade-offs are:

- `ApiKeyProvider` duplicates `LLMProvider` almost 1:1, which adds an unnecessary mapping layer
- minimizing `AppSettingsState` changes keeps the patch shape small, but here that small diff comes at the cost of violating the core auth invariant

That second trade-off is the important one. The design is cleaner than the current UI layout, but its auth state model is still wrong for the product behavior we need.

## Summary

Claude’s design is directionally good on structure: navigation, page decomposition, provider-linked model filtering, and OAuth extraction are all sensible. The main problem is that it tries to preserve the existing single OpenAI credential slot. In this codebase, that is not just an implementation detail; it is the difference between “can switch between OAuth and manual key safely” and “sign-in overwrites or clears the user’s manual key.”

For the first aligned draft, Claude’s file split and phased tasking are worth carrying forward, but the auth/data model needs to be taken from the stronger design.

CODEX is the better base for the first aligned draft.
