# Security & Privacy Improvement Plan (Aligned)

**Date:** 2026-04-08
**Status:** FINAL (aligned after 2 rounds)
**Companion:** `review.md`

---

## Priority 0: Contain Current Exposure

### P0.1 Close the Exported-Intent Control Plane
**Findings:** CRITICAL-1
**Effort:** M (1-2 hours)

1. Keep `MainActivity` exported only for launcher semantics
2. In production: ignore all security-sensitive extras from external callers (API keys, base URL, backend, mode, debug/trace flags, excluded tools)
3. Never persist intent-provided credentials or routing configuration
4. Require explicit in-app confirmation before executing externally supplied goals
5. If internal/test automation needs overrides: use a separate activity protected by signature permission or `BuildConfig.DEBUG`

**Acceptance criteria:**
- External `ACTION_MAIN` launch cannot persist API keys or routing changes
- External launch cannot force BASIC mode
- External launch cannot auto-start agent without user confirmation

**Files:** `AndroidManifest.xml`, `MainActivityIntentPayload.kt`, `MainActivityIntentApplier.kt`, `MainActivity.kt`

### P0.2 Move Privacy Gating Into the Capture Layer
**Findings:** CRITICAL-2
**Effort:** L (2-3 days)

1. Determine current package and tier inside the capture stack, before any artifact is created
2. If foreground app is BLOCKED: return masked snapshot immediately, do not write raw/sanitized trees or screenshots, do not emit post-action observations with captured content
3. Remove ad-hoc post-action capture sites; replace with one shared helper that always returns a policy-filtered observation

**Acceptance criteria:**
- With tracing enabled on a blocked app, no screenshot or tree artifact is written
- `open_app` and `UIActionInvocation` cannot return raw blocked-app observations
- Blocked-app sessions produce only masked snapshots and policy warnings

**Files:** `AccessibilityPlatform.kt`, `VirtualDisplayCaptureCoordinator.kt`, `AccessibilityScreenshotCapturer.kt`, `VirtualDisplayScreenshotProcessor.kt`, `OpenAppTool.kt`, `UIActionInvocation.kt`, `ObservationBuilder.kt`

### P0.3 Fail Closed for Long-Lived Secrets
**Findings:** HIGH-2, HIGH-4
**Effort:** M (2-3 hours)

1. Remove plaintext fallback for OAuth refresh tokens, id tokens, and manual API keys
2. If encrypted storage fails: keep access tokens and user-entered API keys in memory only for the current process, require re-entry or re-auth on restart, show prominent degraded-security banner
3. Remove `loadApiKeyFromFile()` from `/sdcard/api_key.txt` entirely (gate behind `BuildConfig.DEBUG` if needed for eval)
4. Remove stale `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` permissions

**Acceptance criteria:**
- No long-lived secret written to plain `SharedPreferences`
- Encryption failure visible to user
- Current-session credentials can still work without persistence after explicit user entry
- No production path reads `api_key.txt` from shared storage

**Files:** `AppSettingsStore.kt`, `OAuthCredentialStore.kt`, `OnboardingStore.kt`, `AndroidManifest.xml`

### P0.4 Remove PII from Logs
**Findings:** HIGH-3
**Effort:** S (15 min)

1. Delete id_token claims log at `OpenAIOAuth.kt:201-210`
2. Remove email log in `OpenAiSignIn.kt:70-77`
3. If debugging needed, log only token presence and length, gated on `BuildConfig.DEBUG`
4. Treat verbose `LlmLogger` output as a separate developer-only hardening item under P2.4, not as acceptable default logging

**Acceptance criteria:**
- No token claims, emails, or account identifiers in logcat
- No production/default flow depends on verbose `LlmLogger` output

**Files:** `OpenAIOAuth.kt`, `OpenAiSignIn.kt`

---

## Priority 1: Hardening After Containment

### P1.1 Add Source-Level Accessibility Sanitization
**Findings:** HIGH-1
**Effort:** L (3-5 days)

1. Extend perception model with sensitivity metadata (`isPassword`, `isEditable`, and optional sensitivity enum)
2. Suppress text for password fields at capture time
3. Redact editable-field text from history, checkpoint, and trace serializers unconditionally
4. In live LLM prompts, keep raw text only for the currently focused editable field; for all other editable fields emit structure plus redacted markers such as non-empty/length state instead of raw text
5. Split serializers by audience: action-grounding, LLM prompt, history, trace/debug
6. Do not persist high-fidelity prompt JSON into history/checkpoints unless explicitly justified

**Acceptance criteria:**
- Password fields never appear with raw text in prompt/history/trace
- History, checkpoints, and traces never store raw editable text
- Non-focused editable fields never appear with raw text in live prompt output
- Focused editable text exposure is limited to the live prompt path only
- `PerceptionElement` carries sensitivity context for downstream serializers

**Files:** `Perceptor.kt`, `Models.kt`, `TurnPlanningPhaseRunner.kt`, `ObservationBuilder.kt`, trace and history packages

### P1.2 Remove `shell` From Production Agent Modes
**Findings:** MEDIUM-1
**Effort:** M (1-2 hours)

1. Remove `shell` from production agent definitions and prompts
2. If eval/dev still needs it, register it only in debug/developer mode with explicit enablement
3. For any retained dev shell path: execute argv-only (no `sh -c`), enforce binary allowlist, enforce filesystem path allowlist
4. Govern any retained shell path with a separate data-access policy independent of foreground app

**Acceptance criteria:**
- Production agent modes cannot invoke `shell`
- Debug/eval shell is unavailable unless explicitly enabled
- No `sh -c`, metacharacter interpretation, or unrestricted path access in any retained dev shell path

**Files:** `StandaloneAgentDef.kt`, `SessionToolingBootstrapper.kt`, `ShellTool.kt`, `PolicyEngine.kt`, `ToolName.kt`

### P1.3 Make InsecureSsl Compile-Time Debug-Only
**Findings:** MEDIUM-2
**Effort:** S (30 min)

1. Move `InsecureSslConfig.kt` to `debug/` source set
2. Release source set gets no-op stub
3. Validate base URL overrides require HTTPS outside debug builds

**Acceptance criteria:**
- Release builds cannot compile any insecure TLS helper
- Production base URL rejects non-HTTPS URLs

**Files:** `InsecureSslConfig.kt`, `ChatCompletionClient.kt`, `OpenAIResponseClient.kt`, `CodexResponseClient.kt`

### P1.4 Harden AppClassifier Failure Mode
**Findings:** MEDIUM-3
**Effort:** S (30 min)

1. `fromAssets()` throws or returns an explicit failure instead of returning an empty classifier
2. Session creation aborts and surfaces a user-facing error before agent start
3. Do not add a special escape-action path for classifier load failure; the current back/home carve-out remains only for normal runtime policy when the classifier is available

**Acceptance criteria:**
- Missing/corrupt `app_tiers.json` prevents normal session start
- User sees error explaining the issue
- Production code has no empty-classifier fallback path

**Files:** `AppClassifier.kt`, `SessionServices.kt`

---

## Priority 2: Architecture Follow-Up

### P2.1 Replace OAuth HTTP Callback Listener with Deep-Link Redirect
**Findings:** MEDIUM-4
**Effort:** L (1-2 days)

1. Register `androidagent://oauth/callback` URI scheme
2. Use Custom Tab for auth URL
3. Receive callback via Activity intent filter
4. Remove `OAuthCallbackServer`
5. Interim: bind explicitly to loopback and validate request shape / host before accepting the callback

**Files:** `OpenAIOAuth.kt`, `AndroidManifest.xml`

### P2.2 Add Retention Controls
**Effort:** M (1 day)

1. User-visible settings for session history, checkpoints, memory, and trace retention
2. One-tap secure wipe for traces and stored sessions
3. Default traces to off with clear messaging when enabled

### P2.3 Add Security Regression Tests
**Effort:** M (1-2 days)

Required tests:
1. External intent with sensitive extras is ignored in production
2. Blocked apps produce no trace screenshots/trees
3. Password/editable fields redacted from prompt/history serializers
4. Encrypted-storage failure does not persist refresh tokens in plaintext
5. Shell validator rejects chaining/metacharacters and forbidden paths
6. AppClassifier load failure prevents session start

### P2.4 Minimize Debug Artifact Exposure
**Effort:** S (1 hour)

1. Restrict debug receivers with signature-level permission
2. Require explicit developer switch for external debug artifacts
3. Move trace storage to internal storage only
4. Move verbose `LlmLogger` output behind explicit developer mode or a private local-file sink instead of logcat-by-default behavior

---

## Delivery Sequence

### Milestone A (before next release)
- P0.1 Intent lockdown
- P0.3 Fail-closed secrets + remove /sdcard key path
- P0.4 Remove PII from logs

### Milestone B
- P0.2 Capture-layer privacy gate
- P1.2 Shell hardening/removal
- P1.3 Debug-only InsecureSsl
- P1.4 AppClassifier fail-closed

### Milestone C
- P1.1 Accessibility field sanitization
- P2.1 OAuth deep-link
- P2.2 Retention controls
- P2.3 Regression tests
- P2.4 Debug artifact cleanup

---

## Summary Table

| ID | Finding | Severity | Effort | Priority |
|----|---------|----------|--------|----------|
| P0.1 | Exported intent control plane | CRITICAL | M | P0 |
| P0.2 | Capture-layer privacy gate | CRITICAL | L | P0 |
| P0.3 | Fail-closed secrets + /sdcard removal | HIGH | M | P0 |
| P0.4 | PII in logs | HIGH | S | P0 |
| P1.1 | Accessibility field sanitization | HIGH | L | P1 |
| P1.2 | Shell hardening/removal | MEDIUM | M | P1 |
| P1.3 | Debug-only InsecureSsl | MEDIUM | S | P1 |
| P1.4 | AppClassifier fail-closed | MEDIUM | S | P1 |
| P2.1 | OAuth deep-link callback | MEDIUM | L | P2 |
| P2.2 | Retention controls | MEDIUM | M | P2 |
| P2.3 | Security regression tests | - | M | P2 |
| P2.4 | Debug artifact cleanup | LOW | S | P2 |
