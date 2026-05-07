# Phase 6 Codex Review — Round 2 (post storeArtifact quota fix)

Re-review on `task/p6-qa-r2` (worktree `.worktrees/p6-qa-r2`),
HEAD `f6886f4a fix(browser): lift storeArtifact session quota to session scope + atomic CAS`.
Round-1 review at `phase6_codex_review.md` returned REJECT for item 4 only.

## Item 4 — storeArtifact session quota fix

**A. Counter session-scoped (YES).** Counter owned by
`app/src/main/kotlin/ai/closepaw/browser/script/BrowserSessionManager.kt:106`,
threaded through `runnerFactory` at `BrowserSessionManager.kt:86` and
`BrowserSessionManager.kt:179`, into runner at
`app/src/main/kotlin/ai/closepaw/browser/script/BrowserScriptRunner.kt:42`,
then JsInterface at `BrowserScriptRunner.kt:86`, `BrowserScriptRunner.kt:89`.
JsInterface takes an injected counter at
`app/src/main/kotlin/ai/closepaw/browser/script/BrowserScriptJsInterface.kt:24`.

**B. Survives markBroken reconnects (YES).** `markBroken` clears resources/bridge
only at `BrowserSessionManager.kt:252`, and reconnect rebuilds the runner with the
same field at `BrowserSessionManager.kt:177`, `BrowserSessionManager.kt:179`.

**C. Atomic CAS reservation (YES).** Atomic reservation uses `getAndAccumulate` at
`BrowserScriptJsInterface.kt:61`, rejects without consuming at
`BrowserScriptJsInterface.kt:64`, releases on recorder exception at
`BrowserScriptJsInterface.kt:80`, and releases on recorder rejection at
`BrowserScriptJsInterface.kt:83`.

**D. Tests (YES).** Shared JsInterface counter test:
`app/src/test/kotlin/ai/closepaw/browser/script/BrowserScriptJsInterfaceTest.kt:230`,
`BrowserScriptJsInterfaceTest.kt:247`, `BrowserScriptJsInterfaceTest.kt:272`.
Reconnect same-AtomicLong test:
`app/src/test/kotlin/ai/closepaw/browser/script/BrowserSessionManagerTest.kt:183`,
`BrowserSessionManagerTest.kt:197`, `BrowserSessionManagerTest.kt:217`.

## Round-1 items still hold

| Round-1 item | Round-2 |
|---|---|
| 1. Token-gate relays | YES |
| 2. WS handshake vs idle | YES |
| 3. CDP target switch races | YES |
| 5. BouncyCastle ≥ 1.84 | YES |
| 6. SPAKE2 / LGPL purge | YES |

## Test execution

Codex ran focused suites:
`./gradlew testDebugUnitTest --tests ai.closepaw.browser.script.BrowserScriptJsInterfaceTest --tests ai.closepaw.browser.script.BrowserSessionManagerTest`. Both passed.

## New Critical/High introduced by fix commit

None.

## Verdict

**VERDICT: APPROVE**
