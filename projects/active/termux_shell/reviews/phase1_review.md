# Phase 1 Review — Termux Substrate

## Verdict
REQUEST_CHANGES

## Findings

1. severity: medium, file: app/src/debug/res/xml/network_security_config.xml:5, description: The debug resource overlay still permits cleartext to `10.0.2.2` and `localhost` in addition to `127.0.0.1`. Because this overlay replaces `app/src/main/res/xml/network_security_config.xml` for debug builds, Phase 1 does not consistently enforce the design's "127.0.0.1 cleartext exception" boundary across variants. Release is narrow, but debug/test builds are broader without a documented design exception. suggested fix: Either remove the debug overlay or reduce it to the same `127.0.0.1`-only policy; if emulator-host cleartext is intentionally still needed for debug, document that variant-specific exception in the design/review and add exact-host tests for it.

2. severity: medium, file: app/src/androidTest/kotlin/ai/closepaw/termux/TermuxLocalhostCleartextTest.kt:24, description: The instrumentation test validates the default debug APK policy, not the release config called out by `design.md` ("OkHttp can reach `http://127.0.0.1` under release config"). It also only checks `example.com` as the blocked host, so it passes even while debug permits other non-127 cleartext hosts such as `10.0.2.2`. suggested fix: Align debug with release, or add a release-config test path; in either case, assert that `10.0.2.2` and `localhost` are blocked unless they are explicitly accepted debug-only exceptions.

3. severity: low, file: app/src/test/kotlin/ai/closepaw/termux/ClosePawBridgeResourceTest.kt:19, description: The JVM resource test reads the packaged resource APK, which is good, but it only asserts that some line starts with `BRIDGE_VERSION =`. The design says the CI test should verify the packaged raw resource is byte-identical to the canonical `tools/termux-bridge/closepaw_bridge.py` source and contains the expected version line. This test would not catch a stale generated resource with a mismatched body or a wrong version value. suggested fix: Compare the APK entry bytes against `tools/termux-bridge/closepaw_bridge.py` and assert the exact expected `BRIDGE_VERSION = "1"` line.

## Notes

- `app/src/main/AndroidManifest.xml` declares `com.termux.permission.RUN_COMMAND`, `com.termux` package visibility, and the `com.termux.RUN_COMMAND` intent query as required. I did not see new over-broad Termux visibility; the existing all-launcher-apps query predates this phase.
- `app/src/main/res/xml/network_security_config.xml` is correct for release/main: cleartext is allowed only for `127.0.0.1`, with `base-config cleartextTrafficPermitted="false"`.
- `copyClosePawBridge` is wired into `preBuild`, has `outputs.upToDateWhen { false }`, and Gradle dry-runs for `:app:processDebugResources`, `:app:processReleaseResources`, and `:app:testDebugUnitTest` all include `:app:copyClosePawBridge` before resource processing.
- The canonical bridge source under `tools/termux-bridge/closepaw_bridge.py` and `BRIDGE_VERSION = "1"` match the Phase 1 design. The stub body is acceptable because the HTTP daemon is Phase 2 scope.
