# Review: Security & Privacy Hardening (`c1f644d..HEAD`, `app/`)

## Summary
I reviewed the 9 security/privacy changes against the runtime code paths, not just the touched helpers. I also ran the added unit suites:

```bash
./gradlew testDebugUnitTest --tests '*MainActivityIntentApplierSecurityTest' --tests '*CapturePrivacyGateTest' --tests '*AppSettingsStoreFailClosedTest' --tests '*OAuthCredentialStoreFailClosedTest' --tests '*AppClassifierSecurityTest' --tests '*ShellToolBlocklistTest' --tests '*CognitionTraceRedactorSecurityTest' --tests '*SessionServicesProviderRoutingTest'
```

Those tests passed, but several of the hardening goals are still bypassable or incomplete.

## Critical
1. ShellTool hardening is trivially bypassed, so P1.2 does not actually hold.

   Location: `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ShellTool.kt:38-49`, `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ShellTool.kt:77-81`

   `validate()` only checks the first token against `am/pm/reboot/su`, but execution always happens through `ProcessBuilder("sh", "-c", command)`. That makes the blocklist bypassable with commands like `sh -c 'am start ...'`, `PATH=/system/bin am start ...`, or any other shell indirection. The tool description still says "file-oriented inspection only", yet the implementation now allows arbitrary state-changing shell once the first token is not one of those four words. This defeats the stated hardening and can bypass normal tool-routing/policy expectations from a NORMAL app context.

   The added tests only cover direct first-token and full-path forms (`app/src/test/kotlin/com/moonkey/androidagent/tool/impl/ShellToolBlocklistTest.kt:18-48`). They do not cover nested-shell, env-prefix, or alternate state-changing commands.

   Fix: replace the blocklist with a strict allowlist of inspection commands plus argument validation, or stop using `sh -c` and execute a parsed argv directly.

2. The blocked-app capture gate can fail open when package detection is unavailable.

   Location: `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt:62-69`, `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt:330-335`, `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt:227-235`, `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt:358-364`

   Both capture implementations decide whether to mask by calling `isPackageBlocked(getCurrentPackageName())` before capture. But both `getCurrentPackageName()` implementations can return `null` when the root is temporarily unavailable. In that case the gate treats the screen as non-blocked and proceeds to capture the raw tree/screenshot. That is a direct bypass of P0.2 for exactly the transient states this codebase already treats as common elsewhere.

   The new privacy tests only use fake platforms with a non-null package name (`app/src/test/kotlin/com/moonkey/androidagent/tool/action/CapturePrivacyGateTest.kt:38-43`, `app/src/test/kotlin/com/moonkey/androidagent/tool/action/CapturePrivacyGateTest.kt:47-167`), so this fail-open path is not exercised.

   Fix: derive the package from the same captured roots/windows used for the snapshot, or fail closed when the foreground package is unknown and a capture would otherwise occur.

## High
1. P0.4 is incomplete: OAuth secrets/PII are still written to logs.

   Location: `app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAIOAuth.kt:103-115`, `app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAIOAuth.kt:253-255`, `app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAIOAuth.kt:314-315`, `app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAIOAuth.kt:437-443`, `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:723-731`

   `OAuthCallbackServer.waitForCallback()` still logs the full HTTP request line, which contains the authorization `code` and CSRF `state`. `MainActivity` still logs `email=${tokens.email}` after OAuth success. There are also several auth failure paths that log raw response bodies from OpenAI/ChatGPT. So the hardening removed some obvious leaks but did not actually close the logging surface.

   The added security tests do not check any logging path. `CognitionTraceRedactorSecurityTest` is about trace redaction, not runtime logs.

   Fix: remove these values from logs entirely, or log only coarse status such as presence/HTTP code.

2. The "fail-closed" credential-storage change still leaves legacy plaintext secrets on disk.

   Location: `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:131-146`, `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:246-255`

   When encrypted storage is unavailable, both migration paths return immediately. On upgraded installs that still have old plaintext keys in `agent_prefs`, those secrets remain on disk indefinitely. The runtime no longer reads them, but the at-rest exposure remains, which undercuts the stated hardening goal.

   The new test suite explicitly normalizes this behavior instead of flagging it (`app/src/test/kotlin/com/moonkey/androidagent/app/AppSettingsStoreFailClosedTest.kt:103-111`).

   Fix: either wipe legacy plaintext secrets on degrade, or surface a blocking migration/error that forces the user to re-enter credentials without leaving plaintext copies behind.

## Medium
1. AppClassifier only fails closed on missing/invalid JSON syntax, not on semantic corruption.

   Location: `app/src/main/kotlin/com/moonkey/androidagent/tool/AppClassifier.kt:56-75`

   `fromAssets()` now throws if the file is missing or the JSON is not parseable, but unknown tier strings are silently ignored via `AppTier.fromString(...)?...`. A typo in `app_tiers.json` therefore downgrades that package to the default `CAUTIOUS` tier instead of aborting startup. For blocked financial/auth apps, that is weaker than the intended "fail closed" posture.

   The tests cover missing file and invalid JSON only (`app/src/test/kotlin/com/moonkey/androidagent/tool/AppClassifierSecurityTest.kt:25-59`). They do not cover missing `"apps"` or invalid tier values.

   Fix: treat missing required keys and unknown tier strings as fatal parse errors.

2. The new trace-retention control is not applied to every session creation path.

   Location: `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/PermissionsAdvancedSettingsPage.kt:153-229`, `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:316-321`

   Settings now expose a user-facing `Session Traces` toggle, but `AgentService.runAgent()` still hardcodes `traceEnabled = true`. If that overlay/service path is still used, users can disable traces in settings and still get trace artifacts written to disk. That is a completeness gap in P2.2.

   There are no tests for the new settings page behavior or for this alternate session-entry path.

   Fix: plumb the persisted trace setting through all session constructors, or explicitly restrict `runAgent()` to debug-only flows.

## Test Quality Gaps
- `MainActivityIntentApplierSecurityTest` only exercises `applyIntentPayloadToSettings()`. It does not verify the real production behavior in `MainActivity.handleIntent()` such as goal confirmation, ignoring `fresh_session`, or preventing auto-dispatch.
- `CapturePrivacyGateTest` only covers `buildObservation`, `open_app`, and `UIActionInvocation`. It does not cover null-package transitions or the `mobile_action` post-action path that most interactions use.
- There is no regression test for password suppression in `Perceptor`; the new redaction tests target a different subsystem.
- There is no retention-path test for clearing traces/session history while an active session is running.

## Recommendation
CHANGES_REQUESTED
