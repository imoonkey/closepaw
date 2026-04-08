# Security & Privacy Review (Aligned)

**Date:** 2026-04-08
**Authors:** Claude (initial + cross-review), Codex (initial + cross-review)
**Status:** FINAL (aligned after 2 rounds)
**Base:** Codex architectural framing with Claude granular findings merged

---

## Overall Assessment

The codebase has good security primitives: `allowBackup="false"`, release cleartext disabled, encrypted preferences as default credential store, a blocked/cautious/normal app classifier with only-tightens user overrides, TOCTOU re-checks in the approval system, PKCE+CSRF in OAuth, and password-masking in UI inputs.

The problems are not missing primitives. They are **boundary placement and privilege composition**:

1. Too much trust is placed in the exported launcher intent
2. Privacy masking happens after capture instead of before
3. The same accessibility payload is reused for prompting, history, traces, and debug with no field-level privacy policy
4. Secret storage downgrades silently to plaintext
5. A screen-centric approval model governs a file/system tool

The current design is development-grade, not hardened for distribution.

---

## Architecture and Trust Boundaries

### Control Plane
`MainActivity` is exported as the launcher. It also parses and applies security-sensitive intent extras: API keys, base URL overrides, agent mode, backend, debug/trace flags, excluded tools, max turns, and goal text. Several are persisted; goal text triggers automatic execution.

### Privileged Automation Plane
The accessibility service retrieves window content, takes screenshots, and performs gestures. Virtual-display mode adds Shizuku-mediated display and shell-level capabilities.

### Perception and Prompting Plane
`AccessibilityPlatform.captureScreen()` and the virtual-display stack collect accessibility trees and screenshots, converted to `ScreenSnapshot` and `Perceptor.toPromptJson()`. That data feeds: live LLM requests, screen-observation history, post-action observations, trace artifacts, and debug artifacts.

### Persistence Plane
- **Secrets:** `AppSettingsStore`, `OAuthCredentialStore`, `OnboardingStore` (all encrypted prefs with plaintext fallback)
- **Session state:** JSON and context snapshots under internal app storage; memory markdown under internal storage
- **Traces/debug:** JSONL, raw trees, sanitized trees, screenshots under `getExternalFilesDir()`

### Network Plane
Release cleartext disabled. Cloud traffic goes to OpenAI/OpenRouter/Novita or base-URL override. OAuth uses a local HTTP callback listener on port 1455. Debug builds can disable TLS validation.

---

## Findings

### CRITICAL-1: Exported Launcher as Unauthenticated Control Plane

**Source:** Codex Finding 1 + Claude Finding 6
**Files:** `AndroidManifest.xml:29-37`, `MainActivityIntentPayload.kt:28-147`, `MainActivityIntentApplier.kt:17-86`, `MainActivity.kt:280-317`, `SessionConfig.kt:71-77`, `AgentDefRegistry.kt:5-10`, `StandaloneAgentDef.kt:8-20`

Any co-installed app can send an intent to the exported `MainActivity` that:
- Overrides API keys and base URL (routing LLM traffic to attacker)
- Switches to BASIC mode (enabling shell tool)
- Enables debug/trace flags
- Auto-dispatches a goal via `handleIntent()`
- Persists backend, model, agent mode, and other security-sensitive settings

This is not just a poisoned-URL bug. It is a full unauthenticated local control plane that combines with already-granted accessibility permissions.

**Impact:** Full reconfiguration of LLM routing and agent behavior by untrusted local callers. Downstream data exfiltration through redirected API traffic.

### CRITICAL-2: Blocked-App Privacy Gating Happens After Capture

**Source:** Codex Finding 2 (Claude missed this entirely)
**Files:** `AccessibilityPlatform.kt:61-102,157-187`, `AccessibilityScreenshotCapturer.kt:157-196`, `TraceRecorderFactory.kt:12-22`, `AgentTurnRunner.kt:143-160`, `OpenAppTool.kt:203-220`, `UIActionInvocation.kt:74-84`, `ObservationBuilder.kt:13-28`, `PostActionAnalysis.kt:17-73`

`captureScreen()` captures trees and screenshots and writes raw/sanitized trace artifacts before any blocked-app masking. `AgentTurnRunner` masks only the returned `ScreenSnapshot`. Post-action observations in `OpenAppTool`, `UIActionInvocation`, and `PostActionAnalysis` capture from raw snapshots without masking.

A blocked banking/password-manager screen leaks via: raw tree artifacts, sanitized tree artifacts, screenshots, and post-action observations.

**Impact:** The user-visible policy says blocked-app content is hidden, but it can still be persisted and processed.

### HIGH-1: Accessibility Data Not Sanitized for Privacy

**Source:** Codex Finding 3 (Claude acknowledged as gap)
**Files:** `agent_accessibility_config.xml:2-10`, `Perceptor.kt:243-317`, `Models.kt:108-127`, `TurnPlanningPhaseRunner.kt:181-205`, `SessionRuntimeSnapshot.kt:6-54`

`PerceptionElement` carries raw text, contentDescription, hintText, resourceId, and range values but no `isPassword` or sensitivity metadata. `Perceptor.toPromptJson()` serializes the same model for: live LLM prompts, screen-observation history, checkpoint persistence, and trace artifacts.

This means password fields, OTP codes, typed-but-unsent messages, contacts, and financial data in non-blocked apps flow to cloud LLMs and are persisted without any field-level privacy policy.

**Impact:** Sensitive screen data leaves the device or is durably persisted without privacy controls.

### HIGH-2: Secret Storage Fails Open to Plaintext

**Source:** Claude Finding 1 + Codex Finding 4
**Files:** `AppSettingsStore.kt:75-112,133-138`, `OAuthCredentialStore.kt:31-66`, `OnboardingStore.kt:43-61`

All credential stores silently fall back to plain `SharedPreferences` when `EncryptedSharedPreferences` fails. OAuth refresh tokens, access tokens, id tokens, and API keys are persisted in cleartext with no user notification.

**Impact:** Full credential compromise on physical access or root exploit.

### HIGH-3: id_token Claims and PII Logged to Logcat

**Source:** Claude Finding 2 + Codex Finding 7
**Files:** `OpenAIOAuth.kt:198-210`, `OpenAiSignIn.kt:70-77`, `LlmLogger.kt:12-96`

OAuth code logs decoded id_token claims (email, account ID, org memberships). `OpenAiSignIn` logs user email. `LlmLogger` logs prompts, input items, tool-call arguments, and responses in debug builds.

**Impact:** PII exposure via logcat. Any process with READ_LOGS or developer running adb logcat captures account details and screen content.

### HIGH-4: API Key Loaded from World-Readable External Storage

**Source:** Claude Finding 3 + Codex acknowledgment
**Files:** `AppSettingsStore.kt:299-317`

`loadApiKeyFromFile()` reads from `/sdcard/api_key.txt` (world-readable on Android 10 and below). The key is silently persisted into the same fail-open encrypted storage.

**Impact:** API key theft or poisoned key injection by any co-installed app.

### MEDIUM-1: Shell Tool Capability Mismatch with Approval Model

**Source:** Codex Finding 5 + Claude Finding 5
**Files:** `StandaloneAgentDef.kt:8-20`, `ShellTool.kt:38-120`, `PolicyEngine.kt:43-79`

Shell is exposed in BASIC mode. The blocklist validates only the first token; commands run through `sh -c` with full shell interpretation. But even with perfect input validation, the foreground-app approval model is wrong for a tool that can access app-private files, memory, traces, and preferences regardless of the visible app.

**Impact:** Arbitrary command execution from prompt injection. Policy model does not bound the real capability surface.

### MEDIUM-2: InsecureSslConfig Should Be Compile-Time Debug-Only

**Source:** Claude Finding 4 + Codex Finding 6
**Files:** `InsecureSslConfig.kt:20-48`, `ChatCompletionClient.kt:42-43`, `OpenAIResponseClient.kt:46-47`, `CodexResponseClient.kt:229`

The current `BuildConfig.DEBUG` checks are runtime guards, not compile-time exclusion. The issue is not that release traffic is already bypassing TLS today. The issue is that the insecure helper is still part of the main source set, so future accidental linkage or misuse could make it reachable in release. This should be impossible at compile time.

**Impact:** If the insecure helper is ever wired into a release client, all LLM traffic becomes MITM-able.

### MEDIUM-3: AppClassifier Fails Open on Missing Asset

**Source:** Claude Finding 8
**Files:** `AppClassifier.kt:66-73`

If `app_tiers.json` fails to load, `fromAssets()` returns an empty classifier. All apps become `CAUTIOUS`. In `AUTO_APPROVE` mode, banking apps get auto-approved.

**Impact:** Agent operates on financial apps without safety gates.

### MEDIUM-4: OAuth Callback Uses a Local HTTP Listener Instead of an Android-Native Redirect

**Source:** Claude Finding 7 + Codex P2.1
**Files:** `OpenAIOAuth.kt:87-88,99-150`

OAuth callback uses `ServerSocket(1455)` and accepts the first inbound connection. The high-entropy `state` parameter makes auth-code theft difficult, but the single-accept HTTP listener can still be consumed by a local attacker to deny sign-in. The pattern is also weaker than the normal Android OAuth boundary. If it remains temporarily, the socket should bind explicitly to loopback and validate request shape and host.

**Impact:** Local sign-in denial and a weaker-than-normal Android OAuth boundary. Code-theft risk is limited by state validation.

### MEDIUM-5: Permission Model Broader Than Necessary

**Source:** Codex Finding 8
**Files:** `AndroidManifest.xml:4-10`

Legacy `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` permissions remain despite mostly using app-private storage. Traces written to `getExternalFilesDir()` are lower-assurance than internal storage.

**Impact:** Confusing permission surface; traces accessible via backup or other apps on older Android.

### LOW-1: Trace Files on External Storage Without Full Redaction

**Source:** Claude Finding 10
**Files:** `FileTraceRecorder.kt:47-48`, `AgentTraceArtifacts.kt:181-193`, `CognitionTraceRedactor.kt`

Screenshots stored as raw JPEG without redaction. Phone numbers and financial amounts not covered by the text redactor. Trace dir may be on external storage.

### LOW-2: Debug Broadcast Receivers Exported

**Source:** Claude Finding 12
**Files:** `AgentServiceReceiverHelpers.kt:10-18,30-38`

`STOP_AGENT` and `ACTION_DEBUG_EXEC` receivers are exported on debug builds. Any app can stop the agent or trigger debug actions.

### LOW-3: Shizuku Provider Exported

**Source:** Claude Finding 13
**Files:** `AndroidManifest.xml:59-65`

Standard Shizuku integration pattern. The `INTERACT_ACROSS_USERS_FULL` signature permission limits attack surface.

---

## What Works (Positive Findings)

1. **Network security config correctly configured** - cleartext disabled, no debug overrides
2. **TOCTOU guard in ToolRouter** - re-checks foreground package after approval wait
3. **Trace redactor coverage is good** - emails, bearer tokens, JWTs, sensitive key-value pairs
4. **API key fields use password masking** - `PasswordVisualTransformation()` with toggle
5. **AppClassifier only tightens** - users cannot weaken built-in tier classifications
6. **PKCE and CSRF state properly implemented** - S256 + 16-byte SecureRandom state
7. **allowBackup=false** - reduces backup-based leakage

---

## Design Decisions (resolved during alignment)

1. **Shell disposition:** Remove from production agent modes. Debug/dev-only if retained, with argv-only execution, binary allowlist, filesystem allowlist, and separate data-access policy.
2. **Editable-field suppression:** Split serializers by audience. Password fields always redacted. Non-password editable text redacted from history/checkpoints/traces. In live prompts, raw text only for the currently focused editable field; all others get structure + non-empty/length markers.
3. **AppClassifier fail-closed:** Fail session startup on load failure. No special escape carve-out. Runtime back/home exception unchanged.
4. **Degraded storage:** In-memory only for current session. Never plaintext-persist. Restart requires re-entry or re-auth.
