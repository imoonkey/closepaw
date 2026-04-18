# Auth Setting Cleanup — Device QA Report

**Date:** 2026-04-17
**Device:** EP0110MZ0BC101266W (Android 14, ai.closepaw debug)
**Build under test:** working tree at HEAD (auth-setting-cleanup milestone, mid-merge)
**Tester:** Claude (ux-visual-debug)
**Scope:** Per user direction, OAuth (S1) and prior-build upgrade scenarios (S6, S7) skipped. S8 attempted opportunistically by clearing `auth_store.xml`.

## Summary

| # | Scenario | Result | Notes |
|---|---|---|---|
| 1 | Fresh install → Onboarding OAuth → Run Demo | SKIPPED | OAuth requires browser; user declined manual step |
| 2 | Fresh install → Onboarding API key (OpenAI) → Run Demo | **FAIL → FIXED 2026-04-17** | Validator HTTP 400 with mock model `gpt-5.4`; F1 fix routes through `openaiBaseUrl` override; verified HTTP 200 |
| 3 | Fresh install → Onboarding API key (OpenRouter) → Run Demo | PASS-with-note | Validator HTTP 200; demo HTTP 402 = OpenRouter weekly credits exhausted (known) |
| 4 | Novita key via Settings → chat Run | PASS-with-note | Auth wired (HTTP 403 = key authenticated, wallet `NOT_ENOUGH_BALANCE`) |
| 5 | Settings tab switch preserves credentials | **PASS** | Tab/provider switch is inert; keys + model preserved |
| 6 | Upgrade from prior build | SKIPPED | No prior APK artifact; user declined |
| 7 | Old (v1) checkpoint rejection | SKIPPED | Same dependency as S6 |
| 8 | Startup-failure banner → Settings on LLM auth tab | PARTIAL → FIXED 2026-04-17 | Pre-flight `validateCloudKeysForSelectedModels` now sets `pendingSettingsDeepLink`; sheet lands on LLM_AUTH/API Key/OpenAI |

**Counts:** 1 PASS · 2 PASS-with-note · 1 PARTIAL · 1 FAIL · 3 SKIPPED.
**Total time:** ~25 min.

---

## Findings (defects)

### F1 — P1: Onboarding OpenAI API-key validation fails for catalog mock model IDs

- **Where:** `app/src/main/kotlin/ai/closepaw/onboarding/OnboardingViewModel.kt:469`
- **Symptom:** Pasting a valid `OPENAI_API_KEY` against the OpenAI provider in the onboarding API Key tab yields *"Provider configuration issue. Please try again."* and blocks completion of step 4. Logcat: `LlmCredValidator: Validation response: HTTP 400`.
- **Root cause:** `HttpLlmCredentialValidator` is constructed with `entry.effectiveBaseUrl ?: "https://api.openai.com/v1"` — it does **not** consult `AppSettingsState.openaiBaseUrl` (the new debug-only override). Catalog ships forward-versioned mock IDs (`gpt-5.4`, `gpt-5.2`), which `api.openai.com` rejects with HTTP 400.
- **Fix (2026-04-17):** Added `OnboardingViewModel.resolveBaseUrl(entry)` mirroring `LLMClientFactory.build()` — when `entry.provider == OPENAI_API` and `settingsState.openaiBaseUrl` is non-blank, the override wins. JVM test added: `OPENAI_API validator honors AppSettingsState openaiBaseUrl override`.
- **Verification (device EP0110MZ0BC101266W, 21:40):** uninstall → setup.sh → relaunched with `--es openai_base_url $OPENAI_BASE_URL` → onboarding step 4 → OpenAI manual key → Validate. Logcat: `LlmCredValidator: Validation response: HTTP 200` → advanced to step 5 (Run Demo). Screenshot: `qa_screens/s2_fix_aftervalidate.png`.

### F2 — P2: Startup-failure path opens Settings on Home, not LLM_AUTH

- **Where:** `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:697` — `validateCloudKeysForSelectedModels()` set `showSettings = true` directly without populating any deep-link payload. Banner-tap path was already fully wired (deep link plumbed through `ChatViewModel.startupErrorDeepLink` → `MainActivityContent.pendingDeepLink` → `SettingsSheet.initialPage/initialAuthTab`); the gap was the **pre-flight check** that triggers when the user taps Send with no credential.
- **Fix (2026-04-17):**
  1. Added `MainActivity.pendingSettingsDeepLink` state.
  2. `validateCloudKeysForSelectedModels()` now populates it with `SettingsDeepLink(LLM_AUTH, missing.first().provider.mode)` before flipping `showSettings`.
  3. `MainActivityContent` accepts `initialSettingsDeepLink` and seeds its internal `pendingDeepLink` from it (key on the parameter so re-opens use the latest).
  4. `onShowSettingsChange` clears `pendingSettingsDeepLink` on dismiss.
  5. Removed stale TODO comment in `SettingsDeepLink.kt`.
- **Verification (device EP0110MZ0BC101266W, 21:49):** force-stop → `rm shared_prefs/auth_store.xml` → relaunch → Send "Hello". Settings sheet auto-opened on **LLM & Authentication** with **API Key** tab selected and **OpenAI** chip highlighted (matching the missing `OPENAI_API` provider). Screenshot: `qa_screens/s8_fix_v_aftersend.png`.

---

## Per-scenario detail

### S1 — Onboarding OAuth → Run Demo (SKIPPED)

User opted to skip; OAuth flow requires browser interaction not available in this session.

### S2 — Onboarding API key (OpenAI) → Run Demo (FAIL — see F1)

Repro:
1. `adb uninstall ai.closepaw && ./scripts/setup.sh`
2. Onboarding step 3 → "Continue without this"
3. Onboarding step 4 → "or enter API key manually" → OpenAI chip selected by default
4. Paste `$OPENAI_API_KEY` (verified valid against `api.openai.com/v1/models` → HTTP 200, and against the override base URL → HTTP 200)
5. Tap **Validate & Continue**

Expected: validator HTTP 200, advance to Run Demo.
Actual: *"Provider configuration issue. Please try again."* Logcat: `LlmCredValidator: Validation response: HTTP 400`.

Evidence: `s2_01_onboarding.png` … `s2_05_aftervalidate.png`.

### S3 — Onboarding API key (OpenRouter) → Run Demo (PASS-with-note)

Repro:
1. Uninstall + `./scripts/setup.sh`
2. Skip to step 4 → "or enter API key manually" → tap **OpenRouter** chip
3. Paste `$OPENROUTER_API_KEY` → **Validate & Continue**

Result: `LlmCredValidator: Validation response: HTTP 200` → advanced to step 5 (Run Demo). On Run Demo, agent submitted goal "Open the Settings app", reached OpenRouter, returned HTTP 402: *"This request requires more credits, or fewer max_tokens. You requested up to 65536 tokens, but can only afford 12175."*

Verdict: **auth wiring works end-to-end** — key authenticated to OpenRouter, request reached billing layer. Demo failure is unrelated to auth-cleanup; matches the known OpenRouter weekly-credit cap noted in `MEMORY.md`.

Evidence: `s3_01_apikeytab.png` … `s3_06_demorun.png`.

### S4 — Novita key via Settings → chat Run (PASS-with-note)

Repro: from working install (post-S3), main app → menu → Settings → LLM & Authentication → API Key tab → tap **Novita** chip → tap key field → paste `$NOVITA_API_KEY` → tap Model dropdown → pick *AutoGLM Phone 9B Multilingual* → back to chat → send "Open the Settings app".

Result: `AgentTurnRunner` raised `LLM error: PermissionDeniedException - 403`. Direct probe of Novita with the same key & model ID returned `HTTP 403 {"code":403,"reason":"NOT_ENOUGH_BALANCE"}`.

Verdict: **auth wired correctly** — Novita key reached the provider; provider returned 403 due to wallet balance, not auth misconfiguration.

Also confirmed (positive evidence for design §5): switching from OpenAI → Novita chip in Settings did **not** commit a model change — `selectedModel` stayed on `qwen3.5` until I explicitly picked `AutoGLM` from the model dropdown.

Evidence: `s4_01_mainapp.png` … `s4_15_goalrun.png`.

### S5 — Settings tab switch preserves credentials (PASS)

State at start: OpenRouter key (saved during S3), Novita key (saved during S4), no OAuth.

Steps + observations:
1. Settings → LLM Auth → API Key tab → Novita chip selected; **Novita Key** field shows ~46 dots (matches `NOVITA_API_KEY` length).
2. Tap **OpenRouter** chip → key field flips to **OpenRouter Key** with ~73 dots (matches `OPENROUTER_API_KEY` length); model dropdown still shows Novita's `autoglm-phone-9b-multilingual` (no commit on chip change). ✓
3. Tap **Sign In** tab → model unchanged, "Not signed in" shown for OAuth. ✓
4. Tap **API Key** tab again → canonicalized to `selectedModel.provider` = Novita; Novita key dots still present. ✓
5. Tap **Novita** chip → Novita key still present (~46 dots). ✓

Verdict: tab switching is view-only; sub-selector chip switching does not commit model; both OpenRouter and Novita credentials persisted across all switches.

Evidence: `s5_01_apikeytab_novita.png` … `s5_05_novita_back.png`.

### S6 — Upgrade from prior build (SKIPPED)

No prior-build APK available in artifacts; user opted to skip rather than git-stash and rebuild a baseline.

### S7 — v1 checkpoint rejection (SKIPPED)

Same blocker as S6 — requires producing a v1-schema checkpoint, which needs a prior build.

### S8 — Startup-failure banner → Settings on LLM auth tab (PARTIAL — see F2)

Repro:
1. From working install, `adb shell am force-stop ai.closepaw && adb shell run-as ai.closepaw rm shared_prefs/auth_store.xml`
2. `adb shell am start -n ai.closepaw/.app.MainActivity`
3. Send a goal ("Hello") in the chat input.

Observations:
- Startup banner *for missing credential* did not display **before** session bootstrap; only the pre-existing "Setup Issue / Accessibility service is disabled" banner showed (an unrelated regression in this device's a11y enablement post-force-stop, not an auth-cleanup defect).
- After tapping Send, factory threw `MissingCredential` → `reportStartupFailure` fired → host opened `SettingsSheet`. **However**, the sheet opened on **Settings home**, not on **LLM & Authentication**. This matches the explicit TODO at `SettingsDeepLink.kt:15-17` — the deep-link payload (`SettingsPage.LLM_AUTH`, `authTab`) is plumbed to `MainActivityContent.kt:71` but `SettingsSheet` does not yet consume it.

Verdict: banner→Settings routing fires; the precise tab landing is incomplete pending the in-flight `settings-ui-align` task. Recommend completing that task before milestone close.

Evidence: `s8_05_clean2.png`, `s8_06_after_send.png`.

---

## Recommendations

1. **Block milestone close on F1** — onboarding validator must consult `openAiBaseUrlOverride`, otherwise debug builds with the proxy base URL cannot finish onboarding via OpenAI. Real-OpenAI-id catalog entries would mask this in production, but the regression surface is real.
2. **Complete F2** as part of `settings-ui-align` (task 5 per design dependency graph). The TODO in `SettingsDeepLink.kt` is a known gap.
3. **Re-run S1, S6, S7, S8** with prior-build APK + manual OAuth before declaring the milestone done. S2 must be re-run after F1 fix.
4. **Optional polish:** Surface a "Refill your OpenRouter / Novita balance" toast on HTTP 402 / 403 NOT_ENOUGH_BALANCE so the QA-from-cold path doesn't look like an auth failure.

## Artifacts

All screenshots: `doc/todo/auth-setting-cleanup/qa_screens/s{2..8}_*.png`.
