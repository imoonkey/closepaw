# Device QA — custom-endpoint workstream

**Worktree:** `.worktrees/custom-endpoint` · branch `task/custom-endpoint`
**Device:** `emulator-5554` (AndroidWorldAvd / Android 13 / 1080x2400)
**Build:** `./gradlew :app:installDebug` from this worktree (commit `66bd4ceb`, +4 codex review docs)
**Date:** 2026-05-16
**Tester:** Claude (driven via ADB + screenshots)

---

## Summary

| # | Scenario | Result | Evidence |
|---|---|---|---|
| 1 | Happy path: OTHER configure → preflight → /models refresh → agent turn | **PASS** | qa1_*.png |
| 2 | Invalid base URL (ftp + http://example.com) | **PASS** | qa2_*.png |
| 3 | /models 404 (empty endpoint) | **PASS** | qa3_404_models.png |
| 4 | Network failure (airplane mode) | **PASS** | qa4_network_failure.png |
| 5 | Cache reuse across app restart | **PASS** | qa5_cache_reuse.png |
| 6 | Deep-link intent (debug-run.sh with OTHER_* env) | **PASS** | qa6_deeplink_*.png |
| R-OAI | OPENAI_API regression | **PASS** | regression_openai.png |
| R-OR | OPENROUTER regression | **PASS** (plumbing) | regression_openrouter.png |
| R-ANT | ANTHROPIC regression | **SKIP** | no ANTHROPIC_API_KEY in .env |

No crashes observed in any scenario. All error states display readable, user-facing copy. Existing providers (OpenAI, OpenRouter) unchanged.

---

## Scenario 1 — Happy path

**Goal:** Configure OTHER end-to-end (Base URL + Custom Model Id + API Key) → preflight passes → /models refresh populates picker → agent runs a turn through OTHER.

**Path used:** OpenRouter as the OTHER target (`https://openrouter.ai/api/v1` + `deepseek/deepseek-v4-flash:free` + `$OPENROUTER_API_KEY`).

> First attempt used `$NOVITA_API_KEY` against `https://api.novita.ai/v3/openai` — discovery worked (10+ provider groups in picker) but chat completion returned **403 NOT_ENOUGH_BALANCE** (verified via direct curl: `{"code":403,"reason":"NOT_ENOUGH_BALANCE"}`). That's an external account issue, not a code defect — the OTHER request was routed correctly and the upstream's error was surfaced verbatim in the capsule (`LLM error: PermissionDeniedException - 403: null`). Re-running with OpenRouter as the OTHER target proved the full path works.

**Steps & evidence:**
- Settings → LLM & Authentication → API Key tab → Other tab → `03_other_tab_initial.png`
- Filled Base URL + Custom Model Id (typed `deepseek/deepseek_v3` initially) → `04_*.png`
- API key entered → picker shows synth `deepseek/deepseek_v3` row, Refresh button enabled → `05_api_key_entered.png`
- Tapped Refresh → discovered model groups populate picker (gryphe, inclusional, kwaipilot, meta-llama, microsoft, minimax, minimaxai, …) → `06_after_refresh.png`
- Sessions menu shows `Settings / other-custom` (selected model auto-flipped to synth) → `09_menu_model_check.png`
- After switching to OpenRouter + `deepseek/deepseek-v4-flash:free`, Refresh repopulated with OpenRouter groups (anthropic, openai, google, …) and a `Last refreshed: 2026-05-16 21:14` timestamp → `qa1_openrouter_refresh.png`
- New conversation → "say hi in 5 words" → agent reasoned (`The text field is focused and ready. I'll type a 5-word greeting.`) → 2 actions, completed in ~14s → `qa1_run_openrouter.png`, `qa1_agent_done.png`
- Final state: "Done! I said 'Hi there, how are you?' — a 5-word greeting sent successfully in ClosePaw." → `qa1_agent_done.png`

**Result:** PASS

---

## Scenario 2 — Invalid base URL

**Result:** PASS (both branches)

**a. Wrong scheme — `ftp://garbage.example`:**
- Base URL field framed red, validation message: `Base URL must use http or https (got 'ftp')`
- Refresh button greyed out
- Screenshot: `qa2_invalid_baseurl.png`

**b. http on a non-loopback host — `http://example.com`:**
- Validation message: `http is allowed only for localhost / 127.0.0.1 / 10.0.2.2 in debug builds`
- Refresh button greyed out
- Screenshot: `qa2_http_example.png`

Both reflect `OtherBaseUrlValidator` (release HTTPS-only; debug loopback http exception). Field-level validation is immediate (not blocked behind refresh).

---

## Scenario 3 — /models 404

**Setup:** Base URL = `https://example.com` (real HTTPS host that returns 404 on `/models`). Direct curl: `HTTP 404 <!doctype html><html lang="en"><head><title>Example Domain</title>...`

**Observation after Refresh:**
- Error line under Refresh: `HTTP 404 from example.com: <!doctype html><html lang="en"><head><title>Example Domain</title><meta name="viewport" content="width=device-width, initial-scale=1"><style>body{background:#eee;width:60vw;margin:15vh auto;font-family:system-ui,sans-serif}h1{font-size:1.5em}div{opacity:0.8}a:link,a:visited{color:#348}</style></head…` (truncated body printed)
- Picker still shows the synth row for the configured custom model id (`deepseek/deepseek-v4-flash:free`) — i.e. configuration still usable
- No crash, no spinner stuck, picker label collapses to just the `deepseek (1)` synth entry (no discovery cache for this baseUrl pair)
- Screenshot: `qa3_404_models.png`

**Polish nit (P3):** the error surfaces the raw HTML body. Functional but ugly — consider trimming to the first ~120 chars or just `HTTP 404` for non-JSON bodies. Not a blocker.

**Result:** PASS

---

## Scenario 4 — Network failure

**Setup:** With OpenRouter configured and discovery cache populated (218 catalog entries), turn off network: `adb shell svc wifi disable && adb shell cmd connectivity airplane-mode enable`. Verified no connectivity (ping fails inside app).

**Observation after Refresh:**
- Error line: `Unable to resolve host "openrouter.ai": No address associated with hostname`
- Last-refresh timestamp preserved: `Last refreshed: 2026-05-16 21:14`
- Cached model groups still visible in picker (tencent, thedrummer, upstage, x-ai, xiaomi, z-ai, ~anthropic, ~openai, …) — prior cache survives a failed refresh
- API key field still shows its dots — no auth state loss
- No crash
- Screenshot: `qa4_network_failure.png`

After re-enabling network (`cmd connectivity airplane-mode disable && svc wifi enable`), subsequent tests (QA3, regression) ran cleanly.

**Result:** PASS

---

## Scenario 5 — Cache reuse across restart

**Setup:** With OpenRouter discovery cache populated, `adb shell am force-stop ai.closepaw` + relaunch via launcher intent (no intent extras).

**Observation:**
- Settings → LLM → Other tab still shows persisted Base URL (`https://openrouter.ai/api/v1`), Custom Model Id (`deepseek/deepseek-v4-flash:free`), API key dots
- Picker immediately populated with cached groups (essentialai, ibm-granite, inception, inclusional, kwaipilot, meta-llama, …) — no network call needed to render
- Selected entry (`deepseek/deepseek-v4-flash:free`) still highlighted
- Screenshot: `qa5_cache_reuse.png`

Cache key correctly survives process death (persisted store, not just in-memory).

**Result:** PASS

---

## Scenario 6 — Deep-link intent

**Setup:** `scripts/debug-run.sh "say hello in 5 words"` with env:
```
OTHER_API_KEY=$OPENROUTER_API_KEY
OTHER_BASE_URL=https://openrouter.ai/api/v1
OTHER_MODEL_ID=deepseek/deepseek-v4-flash:free
```

**logcat evidence (all three intent paths confirmed):**
```
05-16 21:30:31.108 D/MainActivity: OPENROUTER key set from intent via AuthStore
05-16 21:30:31.109 D/MainActivity: OTHER key set from intent via AuthStore
05-16 21:30:31.174 D/MainActivity: OTHER base URL set from intent: https://openrouter.ai/api/v1
05-16 21:30:31.198 D/MainActivity: OTHER model id set from intent: deepseek/deepseek-v4-flash:free
05-16 21:30:31.874 D/SessionLlmBootstrap: Loaded ModelCatalog with 218 models: [..., other-custom, other:anthropic/claude-opus-4.7-fast, other:inclusionai/ring-2.6-1t, other:baidu/cobuddy:free, ...]
```

**Behavior:**
- Agent ran end-to-end, responded "Hello there, hope you're well." in 16s / 1 action
- Catalog auto-included synth `other-custom` plus all OpenRouter-discovered `other:*` entries (218 total)
- Screenshots: `qa6_deeplink_intent.png` (thinking state), `qa6_deeplink_response.png` (final reply)

**Result:** PASS

---

## Regression smoke

| Provider | Model | Result | Notes |
|---|---|---|---|
| `OPENAI_API` | `gpt-5.4` | **PASS** | 1 action, 8.2s, said "hi". `regression_openai.png`. Existing path unchanged. |
| `OPENROUTER` | `glm-5` | **PASS (plumbing)** | Returned `UnexpectedStatusCodeException - 402: This request requires more credits…` from OpenRouter — a real upstream response, no client-side regression. Key in .env is rate/credit limited. `regression_openrouter.png`. |
| `ANTHROPIC` | — | **SKIP** | No `ANTHROPIC_API_KEY` in `.env`; nothing to exercise. |

No code regression in either provider that was actually tested. The two 4xx responses we saw (OpenRouter 402, Novita 403) were genuine upstream account-state issues, confirmed by independent curl calls.

---

## Findings & nits

| Sev | Finding | Where |
|---|---|---|
| P3 | `/models` error surfaces raw response body (e.g. full HTML on 404). Truncate or normalize. | `qa3_404_models.png` |
| P3 | Refresh-models error band lives below the picker — easy to miss on a long picker list. Consider an inline banner near the Refresh button itself. | QA3, QA4 |
| Note | OTHER provider gracefully refuses to send chat completion when Base URL is blank (`MissingCredential(OTHER)` surfaces in UI), preventing the documented "leak OTHER key to default OpenAI URL" failure mode. | LLMClientFactory.kt:118 |
| Note | Settings home card displays the active OTHER model as `deepseek/deepseek-v4-flash:free · API key` (without `other:` prefix) when the model is the synth entry; sub-page menu shows it as `other:deepseek/deepseek-v3.1` when a discovered entry is selected. Behavior is consistent with the runtime catalog naming. | `12_a11y_on_main.png` vs `16_menu_v31.png` |

---

## What was blocked / not covered

- **Anthropic regression:** no `ANTHROPIC_API_KEY` in `.env`. Need a human with a working Anthropic key to run a single-turn test.
- **Novita end-to-end agent turn:** account balance exhausted (verified via curl: `403 NOT_ENOUGH_BALANCE`). Discovery and request routing both verified — only the chat completion itself was rejected by upstream. Re-run after topping up balance, or use a different Novita-style endpoint, to validate the Novita-specific code path beyond the OpenRouter-as-OTHER coverage already in QA1.
- **`/models` empty array** (vs `/models` 404): tested 404 only. An endpoint returning HTTP 200 with `{"data":[]}` was not exercised — code path likely matches 404 (UI just shows no rows), but worth a follow-up.
- **`OPENROUTER` chat completion at full credit:** the .env key hit the per-key credit cap on the first chat-completion attempt. The regression call still proved request routing + auth + error surfacing all work; a one-shot smoke test with a fresh-credit key would close the gap.
- **`/models` over HTTP loopback in debug build:** validator allows it but not exercised here.
- **VD mode:** all tests were in default accessibility mode. Custom-endpoint workstream has no VD-specific code paths, so this is low risk but not verified.

## Screenshots

All under `projects/active/custom-llm-endpoint/qa_screenshots/`:

- Happy-path discovery & run: `00_app_launch.png`, `02_llm_auth.png`, `03_other_tab_initial.png`, `04_other_fields_url_model.png`, `05_api_key_entered.png`, `06_after_refresh.png`, `09_menu_model_check.png`, `qa1_openrouter_refresh.png`, `qa1_run_openrouter.png`, `qa1_agent_done.png`
- Invalid base URL: `qa2_invalid_baseurl.png` (ftp), `qa2_http_example.png` (http://example.com)
- 404 /models: `qa3_404_models.png`
- Network failure: `qa4_network_failure.png`
- Cache reuse: `qa5_cache_reuse.png`
- Deep-link intent: `qa6_deeplink_intent.png`, `qa6_deeplink_response.png`
- Regression: `regression_openai.png`, `regression_openrouter.png`

---

## Real-device follow-up (Nubia P0110 / Android 16)

**Device:** `100.64.43.95:5555` (Nubia P0110 / Android 16 / arm64-v8a / 1264×2800 @ 560dpi, reached via Tailscale)
**Build:** `./gradlew assembleDebug` from this worktree (commit `073984b1`), installed via `adb install -r -d app/build/outputs/apk/debug/app-debug.apk` — same universal APK that runs on the emulator (no abiFilters in `app/build.gradle.kts`).
**Date:** 2026-05-16
**Tester:** Claude (driven via ADB + uiautomator dumps; in-line screenshot review hit a session-cumulative image-size cap, so later scenarios are evidenced by XML dumps + logcat with screenshots saved to disk as artifacts)

### Summary

| # | Scenario | Result | Diff vs emulator | Evidence |
|---|---|---|---|---|
| 1 | Happy path: OTHER configure → preflight → /models refresh → agent turn | **PASS** | None — flow identical. Catalog populated with OpenRouter groups; agent completed in 9.1s / 1 action via `other-custom`. | `real_qa1_*.png`, `real_03_app_after_setup.png` |
| 2 | Invalid base URL (ftp + http://example.com) | **PASS** with **P2 finding** (stale `lastError`) | Validator fires correctly (`Base URL must use http or https (got 'ftp')`), Refresh stays disabled. But the gate `reason` line below Refresh is shadowed by a sticky `discoveryState.lastError` that **never clears** when the user types a different invalid URL or even a valid URL — only a successful refresh clears it. Not noticed on emulator because that flow went straight from invalid → valid → successful refresh. | `real_qa2a_ftp_with_key.png`, `real_qa2b_http_example.png` |
| 3 | /models 404 (https://example.com) | **PASS** | None. Surfaced as `HTTP 404 from example.com: <!doctype html><html lang="en">…` (truncated body), matching emulator output. Same P3 polish nit applies. | `real_qa3_404_models.png` |
| 4 | Network failure (airplane mode) | **SKIP — infrastructure** | Toggling airplane mode kills the Tailscale link this device is reached over, so ADB control vanishes. Not a code path; falls back to the emulator pass for coverage. | n/a |
| 5 | Cache reuse across force-stop | **PASS** | None. After `am force-stop ai.closepaw` + launcher relaunch the Other tab restored Base URL = `https://openrouter.ai/api/v1`, Custom Model Id = `deepseek/deepseek-v4-flash:free`, and the OpenRouter discovery cache (essentialai, ibm-granite, inception, …) without a network call. | `real_qa5_cache_reuse.png` |
| 6 | Deep-link intent via `scripts/debug-run.sh` | **PASS** | All three intent paths fired (`OTHER key set from intent via AuthStore`, `OTHER base URL set from intent: https://openrouter.ai/api/v1`, `OTHER model id set from intent: deepseek/deepseek-v4-flash:free`). `Loaded ModelCatalog with 218 models` — same number and shape as emulator (synth `other-custom` + 217 `other:*` entries from cached OpenRouter catalog). Agent task completed in 1 turn. Note `scripts/debug-run.sh` honours `ANDROID_SERIAL`; no script change needed. | `real_qa6_deeplink_intent.png`, `real_qa6_deeplink_response.png`, `debug-output/run_20260516_230544/` |

No crashes, no regressions vs emulator on the OTHER-provider path.

### Real-device-specific findings

**OEM accessibility lockdown (Nubia / nubia UI on Android 16).** Setting `enabled_accessibility_services` via `adb shell settings put secure …` is unreliable on this build:
- A single `settings put` typically **reverts to `null`** within a second of the next `am start` / `am force-stop` of the package (the OEM appears to scrub non-system accessibility entries when the target app launches).
- A second `settings put` issued *after* the package is in the foreground sticks. Empirically, `scripts/setup.sh`'s sequence (build → install → grant overlay → put a11y settings → launch) succeeds because the put runs while the app is being launched, and the OEM scrub races against it favorably.
- Every `am force-stop ai.closepaw` requires re-issuing the two `settings put` calls before the next launch. This affected QA5 and the QA6 setup; in both cases re-issuing the settings was sufficient — no manual user toggle was needed once the right ordering was used.
- Practical takeaway: `scripts/setup.sh` works on this OEM today; don't expect bare `adb shell settings put` to be enough on its own across a force-stop/relaunch cycle.

**Stale `discoveryState.lastError` (also P2).** In `LlmAuthDiscoveryUi.kt:251–266`, the displayed "below Refresh" message resolves `discoveryState.lastError[provider]` before the `RefreshButtonGate` reason. The error map is never cleared when the user changes Base URL — so once a refresh has failed (or a URL has been judged invalid by the gate's reason path) the message stays pinned to the *old* URL's failure mode even after the field text changes. Repro on real device: in QA2 the displayed message stayed `Base URL must use http or https (got 'ftp')` after typing `http://example.com` and after typing `https://openrouter.ai/api/v1`. Not a regression — likely present on emulator too, but easy to miss because the emulator flow never exercised an invalid→invalid→valid transition. Fix candidate: clear `lastError[provider]` whenever the persisted Base URL or API Key for that provider changes.

### Anything else differs from emulator?

- **Display scaling (1264×2800 @ 560dpi)** renders all of LLM & Authentication, Other tab, picker, capsule, and chat history at the same proportions as the emulator. No clipping, no overlap, no font-size regressions. Compose layout absorbed the higher density cleanly.
- **Touch handling.** Edge swipes near `y ≥ 2500` trigger the system back gesture (Android 13's gesture-nav default), which silently closes screens during scrolling. Worked around by keeping scroll swipes in the safer mid-region (`y` 1100–2200). Affects nobody but ADB-driven testing.
- **`uiautomator dump` truncation.** When the LLM page is in mid-edit, `uiautomator dump` consistently truncates at `y≈1537` and omits the Cloud Model picker, Refresh button, and API Key field nodes from the XML, even though they are visible in the screenshot. This is a uiautomator/Compose interaction quirk on this OEM build, not an app bug; it complicates ADB-driven testing but does not affect a real user.
- **No new SELinux denials** beyond the existing `syseventsw` socket denials already present on the emulator (Nubia adds the same audit lines under a Qualcomm tag).

### Coverage gaps on real device (not blockers)

- QA4 (airplane mode) couldn't be exercised because of the Tailscale dependency; emulator pass stands.
- Regression smoke (OPENAI_API / OPENROUTER / ANTHROPIC) not re-run on real device — emulator coverage was treated as authoritative per task brief.
- VD platform mode (virtual display) not exercised.

### Real-device screenshots

All under `projects/active/custom-llm-endpoint/qa_screenshots/`:

- Setup: `real_01_app_launch.png` (a11y-disabled first launch), `real_03_app_after_setup.png` (a11y enabled after setup.sh)
- QA1: `real_qa1_02_key_entered.png`, `real_qa1_03_after_refresh.png`, `real_qa1_04_menu_model.png`, `real_qa1_05_sent.png`, `real_qa1_06_agent_done.png`
- QA2: `real_qa2a_ftp_with_key.png` (ftp scheme), `real_qa2b_http_example.png` (http non-loopback — note both show the same stale `lastError`)
- QA3: `real_qa3_404_models.png`
- QA5: `real_qa5_cache_reuse.png`
- QA6: `real_qa6_deeplink_intent.png` (run start screen), `real_qa6_deeplink_response.png` (post-completion)

Linked trace artifacts for QA6: `debug-output/run_20260516_230544/` (agent.log, logcat_full.log, turn screenshots, replay-v2 trace).
