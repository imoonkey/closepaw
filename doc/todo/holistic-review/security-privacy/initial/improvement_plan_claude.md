# Security & Privacy Improvement Plan

Prioritized action items from the security review. Items ordered by risk (severity x likelihood). Each item references the corresponding finding in `review.md`.

---

## Priority 1: Critical Path (do before next release)

### 1.1 Remove id_token Claims Logging
**Finding:** #2
**Effort:** S (5 min)
**Impact:** Eliminates PII leakage to logcat

Delete the debug block at `OpenAIOAuth.kt:201-210` that logs the decoded id_token claims. If any debugging is needed, log only `Log.d(TAG, "id_token present, length=${idToken.length}")`.

### 1.2 Remove File-Based API Key Loading
**Finding:** #3
**Effort:** S (10 min)
**Impact:** Eliminates world-readable credential exposure

Delete `loadApiKeyFromFile()` and its call site in `AppSettingsStore.load()`. If eval tooling needs this path, gate it behind `BuildConfig.DEBUG` and document in CLAUDE.md that it exists only for emulator testing.

### 1.3 Harden Shell Tool Against Command Injection
**Finding:** #5
**Effort:** S (30 min)
**Impact:** Closes arbitrary command execution vector from LLM prompt injection

Two options (pick one):
- **Option A (simple):** Add a metacharacter blocklist to `validate()`: reject commands containing `;`, `|`, `&`, `$`, `` ` ``, `(`, `)`, `{`, `}`, `>`, `<`. This is a 5-line change.
- **Option B (robust):** Replace `ProcessBuilder("sh", "-c", command)` with direct argument execution. Parse the command into binary + args, validate the binary is in an allowlist (`cat`, `ls`, `stat`, `find`, `head`, `wc`, `grep`), then call `ProcessBuilder(binary, *args)`. This eliminates shell interpretation entirely.

Recommend Option B for correctness. Option A is acceptable as an immediate fix.

### 1.4 Prevent Intent-Based Base URL Injection Persistence
**Finding:** #6
**Effort:** M (1-2 hours)
**Impact:** Closes data exfiltration via redirected API traffic

Changes needed:
1. In `MainActivityIntentApplier.kt`: Do NOT call `store.saveApiKey()` for intent-provided keys. Keep them in `AppSettingsState` in-memory only (they already are for `openaiBaseUrl` -- extend this pattern to all intent keys).
2. In `AppSettingsState.buildApiKeys()`: Already reads from in-memory state, so this works correctly once the persist calls are removed.
3. Add URL validation for `EXTRA_OPENAI_BASE_URL`: reject non-HTTPS URLs and optionally validate against a domain allowlist (`api.openai.com`, `openrouter.ai`, `api.novita.ai`).

---

## Priority 2: High (do within next sprint)

### 2.1 Surface Encrypted Storage Fallback to User
**Finding:** #1
**Effort:** M (2-3 hours)
**Impact:** User awareness of degraded security, prevents silent plaintext credential storage

Changes:
1. Add an observable state flag (e.g., `credentialStorageDegraded: Boolean`) to `AppSettingsState`.
2. When any encrypted prefs fallback triggers, set this flag.
3. In `MainActivityContent`, show a persistent warning banner when the flag is true.
4. For `OAuthCredentialStore`: when `prefsFailed`, do NOT persist the refresh token. Instead, the user will need to re-authenticate when the access token expires. This is the correct security/UX tradeoff.

### 2.2 Make InsecureSslConfig Impossible to Use in Release
**Finding:** #4
**Effort:** S (30 min)
**Impact:** Eliminates risk of release-build SSL bypass

Move `InsecureSslConfig.kt` to a `debug/` source set so it does not compile in release. Replace references in `ChatCompletionClient`, `OpenAIResponseClient`, and `CodexResponseClient` with a conditional pattern:

```kotlin
// In debug source set:
internal fun OkHttpClient.Builder.applyDebugSsl() = apply {
    sslSocketFactory(InsecureSslConfig.sslSocketFactory, InsecureSslConfig.trustManager)
}

// In release source set:
internal fun OkHttpClient.Builder.applyDebugSsl() = this // no-op
```

This makes it a compile-time guarantee, not a runtime check.

### 2.3 Harden AppClassifier Failure Mode
**Finding:** #8
**Effort:** S (30 min)
**Impact:** Prevents agent from operating on financial apps when tier database fails to load

In `AppClassifier.fromAssets()`:
1. Throw on failure instead of returning empty. The session creation code should catch this and surface a user-facing error.
2. Alternatively, embed a minimal hardcoded fallback blocklist (top 10 financial apps) that applies even when the JSON fails to load.

---

## Priority 3: Medium (address in upcoming work)

### 3.1 Migrate OAuth to Custom Tab Redirect URI
**Finding:** #7
**Effort:** L (1-2 days)
**Impact:** Eliminates local network attack surface for OAuth

Replace the localhost callback server with an Android deep link callback:
1. Register a custom URI scheme: `androidagent://oauth/callback`
2. Use `Custom Tab` (Chrome) instead of `ACTION_VIEW` for the auth URL.
3. Receive the callback via the Activity's intent filter.
4. Remove `OAuthCallbackServer` entirely.

This is a larger change but eliminates a whole class of vulnerabilities and is the standard Android OAuth pattern.

### 3.2 Restrict Debug Receivers Further
**Finding:** #12
**Effort:** S (15 min)
**Impact:** Defense-in-depth for debug builds

Add a signature-level custom permission for `ACTION_DEBUG_EXEC`. This ensures only same-signature apps (i.e., your own tooling) can trigger debug actions.

### 3.3 Add Phone Number and Financial Pattern to Trace Redactor
**Finding:** #10
**Effort:** S (20 min)
**Impact:** Reduces PII in trace artifacts

Add patterns to `CognitionTraceRedactor`:
- Phone numbers: `\b\+?[\d\s\-().]{7,15}\b` (with some heuristic to avoid false positives on element indices)
- Currency amounts: `\$[\d,]+\.?\d*` and similar for other currencies

### 3.4 Verify Trace Files Write to Internal Storage Only
**Finding:** #10
**Effort:** S (15 min)
**Impact:** Ensures trace data is not accessible to other apps

Audit all callers of `FileTraceRecorder` to confirm `rootDir` resolves to `context.filesDir` (app-private internal storage), not external storage. Add a check in the constructor: `require(rootDir.absolutePath.startsWith(context.filesDir.absolutePath))`.

---

## Summary Table

| ID | Finding | Severity | Effort | Priority |
|----|---------|----------|--------|----------|
| 1.1 | id_token claims logged | HIGH | S | P1 |
| 1.2 | API key from /sdcard | HIGH | S | P1 |
| 1.3 | Shell command injection | MEDIUM | S | P1 |
| 1.4 | Intent base URL injection | MEDIUM | M | P1 |
| 2.1 | Silent plaintext fallback | HIGH | M | P2 |
| 2.2 | InsecureSsl in release | MEDIUM | S | P2 |
| 2.3 | AppClassifier fails open | MEDIUM | S | P2 |
| 3.1 | OAuth localhost server | MEDIUM | L | P3 |
| 3.2 | Debug receiver permissions | LOW | S | P3 |
| 3.3 | Trace redactor patterns | LOW | S | P3 |
| 3.4 | Trace file location | LOW | S | P3 |
