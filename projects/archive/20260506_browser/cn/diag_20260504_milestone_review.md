# Browser Milestone Review — codex verdict

**Date**: 2026-05-04
**Reviewer**: codex-browser-milestone-review (gpt-5.5 xhigh)
**Range**: `8a8bbcf4..cb4df0e6` (67 commits, 243 files, +27362/-9127)
**Verdict**: **REQUEST-CHANGES** (1 Critical, 5 High, 3 Medium, 1 Low)

---

## Critical (block production)

### 1. Unauthenticated localhost CDP relays
- `app/src/main/kotlin/ai/closepaw/browser/cdp/wireless/WirelessAdbSelfPairTransport.kt:82`
- `app/src/main/kotlin/ai/closepaw/browser/cdp/shizuku/ChromeDevtoolsUserService.kt:214`

Both bind `127.0.0.1:0`, accept arbitrary TCP clients, then proxy to `chrome_devtools_remote` with full ClosePaw / Shizuku / adbd privilege. **Any local process that finds the port can drive the user's Chrome DevTools during the session** — read cookies, navigate, exfiltrate password manager data.

**User direction (KISS)**: take path B (per-session unguessable token in WS Upgrade header). Do NOT take path A (eliminate TCP relay → in-process CdpConnection rewrite — overengineering).

→ Tracked: `browser-phase6-cdp-relay-auth`

---

## High (fix before next release)

### 1. WS inherits 10s socket read timeout from handshake
- `app/src/main/kotlin/ai/closepaw/browser/cdp/wireless/AdbTlsClient.kt:97`
- `app/src/main/kotlin/ai/closepaw/browser/cdp/wireless/AdbWireProtocolClient.kt:40`

Idle CDP WS dies after 10s even though script-level / command-level budgets are 30-120s. Fix: separate connect/handshake timeout from post-open behavior.

→ `browser-phase6-ws-idle-timeout`

### 2. Direct target switching leaks WS/fd
- `app/src/main/kotlin/ai/closepaw/browser/cdp/ChromeCdpClient.kt:170`

Old WS parked, never closed until session teardown. Tab-heavy script leaks fds.

→ `browser-phase6-target-switch-leak`

### 3. storeArtifact has no size/quota check
- `app/src/main/kotlin/ai/closepaw/browser/script/BrowserScriptJsInterface.kt:30`

Bad script can OOM the app or fill trace storage. Path traversal handled by FileTraceRecorder; DoS is not.

→ `browser-phase6-storeartifact-quota`

### 4. BouncyCastle 1.78.1 has CVEs
- `app/build.gradle.kts:140`

Need ≥1.84 (GHSA-4cx2-fc23-5wg6 + others).

→ `browser-phase6-bc-cve`

### 5. LGPL compliance not satisfied by source comment alone
- `app/build.gradle.kts:53`
- `app/src/main/kotlin/ai/closepaw/browser/cdp/wireless/AdbPairingClient.kt:6`

`com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1` (LGPL-3.0). APK packaging excludes license/notice files. spake2-android is a personal port by MuntashirAkon (App Manager dev), not a major library.

**User direction**: investigate-first. If a major lib (BouncyCastle 1.84+, Tink, libsodium-jni) supports wire-compatible SPAKE2-25519, swap. If not, keep + add proper NOTICE file + source link + 4(d) attestation. **Do NOT reimplement crypto from scratch.**

→ `browser-phase6-spake-replace`

---

## Medium (can ship with note)

### 1. Process-global crypto provider mutation
- `app/src/main/kotlin/ai/closepaw/browser/cdp/wireless/WirelessAdbProviders.kt:38`

Provider order changes for the whole app after browser runtime starts. Prefer provider-qualified calls.

### 2. Pair-once fallback duplicates adb_keys on OEMs
- `app/src/main/kotlin/ai/closepaw/browser/cdp/wireless/WirelessAdbSelfPairTransport.kt:132`

Correctness-safe but unbounded growth on nubia-class OEMs (shell denied adb_keys read). Add hard ceiling.

### 3. 200ms post-CNXN sleep is empirical
- `app/src/main/kotlin/ai/closepaw/browser/cdp/wireless/AdbWireProtocolClient.kt:84`

Acceptable for now; should be replaced by a protocol-ready signal or covered by device matrix evidence.

→ All three: `browser-phase6-medium-cleanup`

---

## Low / nits

### 1. Outdated comment about shell UID
- `app/src/main/kotlin/ai/closepaw/browser/cdp/shizuku/ChromeDevtoolsUserService.kt:27`

Says shell UID is "typically allowed" to connect to Chrome's abstract socket. Milestone evidence: locked OEMs commonly deny it.

→ `browser-phase6-medium-cleanup`

---

## What's well-done (codex positive call-outs)

- End-state cascade clean: USER_SERVICE → WIRELESS_ADB_SELF_PAIR, dead transports gone, cause-chained user errors preserved
- Shizuku provider lifecycle: single-flight binding, refcounted awaiters, stale callback generation guards, close idempotence — race classes covered by focused tests
- WebView script host reasonably hardened: no network/file/content access, no visible setWebContentsDebuggingEnabled, only `send` / `done` / `storeArtifact` exposed to JS
- AndroidPubkey, ADB wire framing, strict HTTP parsing, bridge fallback behavior, real-device evidence ledger all solid
- `./gradlew :app:testDebugUnitTest` passes

---

## Phase 6 plan

8 tasks (1 parent + 7 leaves):

| Task | Priority | Estimate | Worktree |
|---|---|---|---|
| `browser-phase6-cdp-relay-auth` | critical | m | `p6-relay-auth` |
| `browser-phase6-ws-idle-timeout` | high | s | `p6-ws-idle` |
| `browser-phase6-target-switch-leak` | high | s | `p6-target-leak` |
| `browser-phase6-storeartifact-quota` | high | s | `p6-quota` |
| `browser-phase6-bc-cve` | high | xs | `p6-bc-cve` |
| `browser-phase6-spake-replace` | high | m | `p6-spake` (requires human review) |
| `browser-phase6-medium-cleanup` | normal | s | `p6-cleanup` (depends on relay-auth) |
| `browser-phase6-qa` | high | s | `p6-qa` (final gate) |

6 leaves can dispatch in parallel; cleanup waits for relay-auth (provider isolation work touches adjacent files); qa waits for everything.
