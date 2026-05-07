# Browser Project — Documented Gaps Audit

**Commit range reviewed**: `6bebf52b..092f01ed` (83 commits)
**Date**: 2026-05-05

## Summary

8 gaps survive the commit range. None are blocking — Phase 6 final-gate is APPROVED and the device chain is PASS — but the entire `browser_setting/design.md` (user-facing Settings tools UI + chrome-flags CTA) is doc-only, the holistic-review's `browser-dialog-state-tracking` follow-up never landed, and several lower-severity follow-ups remain open as TODO comments.

## Gaps

### [Severity: high] Browser-setting Tools section + CDP status row + chrome-flag CTA never implemented
- **Source**: `projects/active/browser_setting/design.md` — header says "PLANNED, not implemented".
- **What was planned**: New `Tools` section with `ToolToggleRow("browser_script")` + `BrowserCdpStatusRow` (probe `@chrome_devtools_remote` in `/proc/net/unix`) + 3-fallback CTA opening `chrome://flags#enable-command-line-on-non-rooted-devices`. Toggle ON should auto-write `/data/local/tmp/chrome-command-line` via Shizuku and remove `browser_script` from the agent allowlist when OFF.
- **What landed**: Doc only (`cb099d7a`). Runtime `browserScriptEnabled` toggle exists in the Experimental section of `PermissionsAdvancedSettingsPage.kt:139-144`, but there is no Tools section, no CDP probe, no chrome-flag CTA, no auto-cmdline write.
- **Evidence it's still open**: No `ChromeCdpProbe.kt` / `ChromeFlagDeepLink.kt` / `ToolsSection.kt` in `app/src/main/kotlin`. `StandaloneAgentDef.kt:22` unconditionally lists `ToolName.BrowserScript.raw`; toggle gates only execution-time capability.
- **Suggested next step**: Dispatch `browser_setting/design.md` as a tracked task — Chrome-stable on stock devices is silently broken without it.

### [Severity: high] Dialog tracking (`Page.javascriptDialogOpening` / `Closed`) not implemented
- **Source**: `design_codex.md:519-526` ("Dialog Tracking"); `holistic_review.md:35` notes it; follow-up `browser-dialog-state-tracking` at `holistic_review.md:106`.
- **What was planned**: Subscribe to `Page.javascriptDialogOpening`/`Closed`; `pageInfo()` should report dialog state before `Runtime.evaluate` because alert/confirm/prompt freeze page JS.
- **What landed**: Nothing. `grep "javascriptDialog\|DialogState\|pendingDialogs"` across `app/src/main` → 0 hits. `page.js` `pageInfo()` returns viewport metrics only.
- **Evidence it's still open**: `ChromeCdpClient.kt` has no dialog state; `browser-use/scripts/page.js` `pageInfo` has no dialog field.
- **Suggested next step**: Add `Page.enable` subscription + dialog state on `ChromeCdpClient`; surface in `pageInfo()`.

### [Severity: medium] Pair-once optimization is broken on locked OEMs every cold session
- **Source**: `diag_20260503_phase4_handoff.md:139-150` (Outstanding #1); `real_device_qa.md:88, 264, 858`.
- **What was planned**: `AdbWirelessManager.isPubkeyAuthorized()` should match the persisted pubkey against `adb_keys` so warm sessions skip the SPAKE2 handshake.
- **What landed**: `f4e662af` shipped a substring-match implementation, but on locked OEMs (nubia P0110) the Shizuku shell uid is denied read of `/data/misc/adb/adb_keys`, so the check returns false and full re-pair runs every cold session.
- **Evidence it's still open**: `WirelessAdbSelfPairTransport.kt:144-150`; 2026-05-05 warm-run logcat: "could not read /data/misc/adb/adb_keys: EACCES" → re-pair fires.
- **Suggested next step**: Cache last-pair fingerprint app-side via `ChromeDevtoolsUserService` to bypass the denied read.

### [Severity: medium] `BrowserSessionManager.close()` does not await runner WebView teardown
- **Source**: `holistic_review.md:28` (Medium finding). Not in any "DONE" / follow-up resolution.
- **What was planned**: Deterministic shutdown that joins the runner's main-thread WebView teardown.
- **What landed**: `BrowserSessionManager.kt:143-149` cancels `runtimeJob` and synchronously closes resources/bridge, but does not await runner's main-Looper WebView destruction.
- **Evidence it's still open**: `close()` returns immediately after `closeBridge()`; no join/await on runner teardown future.
- **Suggested next step**: Make `close()` `suspend` (or expose `awaitClose()`) and join the runner teardown.

### [Severity: medium] Process-global crypto provider mutation never narrowed to provider-qualified calls
- **Source**: `diag_20260504_milestone_review.md:69-72` (Medium #1); milestone task tag `browser-phase6-medium-cleanup`.
- **What was planned**: Replace `WirelessAdbProviders.installBouncyCastle()` global insert with provider-qualified `SSLContext.getInstance(..., bcProvider)` calls.
- **What landed**: `eada8cc4` switched from `insertProviderAt(_, 1)` to **append** at the end of the Security list. Better, but BC remains globally registered — calls anywhere in the app can still resolve BC providers when their preferred one is missing.
- **Evidence it's still open**: `WirelessAdbProviders.kt:14, 41-65` — append-only, not provider-qualified.
- **Suggested next step**: Optional — only if app-wide JSSE clashes ever surface in QA.

### [Severity: low] `requestTimeoutMs` default of 4 s not re-validated post-`readUntilHttpComplete`
- **Source**: `diag_20260503_phase4_handoff.md:217-228`; `e99d4cc3`.
- **What landed**: Restored to `4_000` (`ShizukuChromeDevtoolsBridge.kt:143`); no comment on real-device flake margin.
- **Suggested next step**: Leave as-is unless QA observes flakes.

### [Severity: low] 200 ms `POST_CNXN_SETTLE_MS` defensive sleep still empirical, single-device
- **Source**: `diag_20260504_milestone_review.md:78-82`; `diag_20260503_phase4_handoff.md:225`.
- **What landed**: Sleep retained at `AdbWireProtocolClient.kt:95, 243`; only nubia P0110 in matrix.
- **Suggested next step**: Document once a second device is exercised.

### [Severity: low] Duplicate non-`cn/` `design_codex.md` still present
- **Source**: `holistic_review.md:67, 108` (`browser-doc-source-cleanup`).
- **What landed**: Both files exist (only language differs); `eng_plan_review_claude.md:4` still references the non-`cn` path.
- **Suggested next step**: One-line stub or `git mv`.

## Items checked and confirmed DONE

- Screenshot base64 leak (`5f2920eb`); WebView cancel race (`4634d4f8`); Shizuku unbind/stale-cb (`445362ca`/`ff873f25`); per-LiveConnection pending map (`bf471b67`/`d4296713`); CDP-WS over Shizuku tunnel (`15ed7070`); dead-transport cleanup (`1516206c`); spake2-android → eddsa (`2232b76`-style); BC 1.78→1.84 (`9776a573`); token-gated relays (`2462bf0d`); slowloris deadline (`b60d4ac5`/`6dc2af3a`); storeArtifact session-scope CAS (`f6886f4a`); adb_keys prune (`9226e9e7`/`095228ff`); WS handshake/idle split (`9ed206f6`).

## Codex Re-review (2026-05-05)

| Gap title | Status | Worth-it verdict | T-shirt size | One-line rationale |
| --- | --- | --- | --- | --- |
| Browser-setting Tools/CDP status/flag CTA | STILL OPEN | WORTH IT | M | HEAD still has only the Experimental toggle at `PermissionsAdvancedSettingsPage.kt:139-144`; `StandaloneAgentDef.kt:22` still exposes `browser_script`; no probe/deep-link files exist. `cb099d7a` is docs-only. |
| Dialog tracking | STILL OPEN | NICE-TO-HAVE | M | `BrowserSessionManager.kt:229-233` enables domains, but `ChromeCdpClient.kt:46,161,263` only buffers generic events and `page.js:134-148` reports no dialog state; no `javascriptDialog` hits. |
| Pair-once cold-session optimization | STILL OPEN | WORTH IT | M | `f4e662af` added pubkey substring check, but unreadable `adb_keys` returns null in `ChromeDevtoolsUserService.kt:121-128`; `AdbWirelessManager.kt:62-65` maps that to false and `WirelessAdbSelfPairTransport.kt:143-155` re-pairs. |
| `BrowserSessionManager.close()` await teardown | PARTIALLY DONE | NICE-TO-HAVE | S | Per-run WebView teardown awaits main-thread drain (`BrowserScriptRunner.kt:123-139`, `907b011`), but session `close()` still just cancels/closes synchronously (`BrowserSessionManager.kt:143-149`, `8da5834`). |
| Crypto provider scoping | PARTIALLY DONE | SKIP | S | `eada8cc4` made browser call sites provider-qualified/comments explicit (`WirelessAdbProviders.kt:8-22`), but `Security.removeProvider/addProvider` remains at `WirelessAdbProviders.kt:41-59`; no current QA clash. |
| 4s request timeout revalidation | STILL OPEN | SKIP | XS | HEAD still defaults to `4_000` (`ShizukuChromeDevtoolsBridge.kt:25,143`, `55dea88`); this is only worth changing with observed fetch flakes. |
| 200ms post-CNXN sleep | PARTIALLY DONE | SKIP | XS | The "single-device" claim is stale: `eada8cc4` documents PASS on emulator-5556 and nubia at `AdbWireProtocolClient.kt:238-242`; only minimization remains. |
| Duplicate non-cn design doc | STILL OPEN | NICE-TO-HAVE | XS | Both `projects/active/browser/design_codex.md` and `projects/active/browser/cn/design_codex.md` exist, and `eng_plan_review_claude.md:4` still points at non-cn; cheap cleanup, low runtime impact. |

### Recommended next batch

1. Browser-setting Tools/CDP status/flag CTA.
2. Pair-once app-side cache/fallback for locked OEMs.
3. Dialog tracking and `pageInfo()` surfacing.
4. Duplicate design-doc cleanup as an opportunistic XS docs fix.
