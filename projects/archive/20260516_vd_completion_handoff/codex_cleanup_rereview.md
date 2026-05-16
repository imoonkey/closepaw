# VD Completion Handoff — Cleanup Re-review

## Scope
- HEAD reviewed: `70102ab085b432c7e0564e9a7804de9c6f04905a`
- Approach: reread `projects/archive/20260516_vd_completion_handoff/codex_cleanup_review.md`; reviewed `git show 70102ab0 --stat` and the full `git diff 52b91567..70102ab0` for touched files; ran targeted greps for removed symbols (`virtualDisplayAvailable`, `viewerAvailable`, `isViewerAvailable`, `isVirtualDisplayViewerAvailable`, `CompletionHandoffCtaVisibility`, `completionHandoffCtaVisibility`, `View virtual screen`); checked `CompletionHandoff` constructor fallout, `VirtualDisplayPlatform` public surface, handoff tests, and the requested unit-test task.

## Previous findings
- HIGH 1 — Status: ✓ Fixed — `CompletionHandoff` now carries only `appPackage` and `appLabel` (`app/src/main/kotlin/ai/closepaw/protocol/CompletionHandoff.kt:11`), and `buildVdCompletionHandoff` no longer accepts or writes viewer availability (`app/src/main/kotlin/ai/closepaw/protocol/CompletionHandoff.kt:24`). `AgentSession.buildHandoffIfVd` only reads the current package and no longer casts to `VirtualDisplayPlatform` (`app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:492`). `AgentServiceViewerBridge` no longer exposes a liveness API; the remaining viewer methods are open/finish/surface/touch plumbing (`app/src/main/kotlin/ai/closepaw/app/AgentServiceViewerBridge.kt:36`). `AgentServiceViewerBridgeAvailabilityTest.kt` was deleted, and grep found no production/test references to `virtualDisplayAvailable`, `viewerAvailable`, `isViewerAvailable`, or `isVirtualDisplayViewerAvailable`.
- HIGH 2 — Status: ✓ Fixed — `scripts/debug-run.sh` now defines `err()` before the setup preflight (`scripts/debug-run.sh:116`) and uses it on preflight failure (`scripts/debug-run.sh:314`). `scripts/setup.sh` captures caller-provided `LLM_BACKEND` before sourcing `.env` and then resolves backend as caller env > `.env` > default (`scripts/setup.sh:103`), so the API-key gate uses the effective backend (`scripts/setup.sh:115`).
- MEDIUM 1 — Status: ✓ Fixed — `CompletionHandoffCtaVisibility` is gone. The pure helper is now a single boolean `shouldShowOpenAppCta` (`app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:26`), and the composable gates rendering with `canOpen` (`app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:44`). The test now asserts the boolean helper directly (`app/src/test/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaVisibilityTest.kt:17`).
- MEDIUM 2 — Status: ✓ Fixed — `NewSessionNoHandoffTest` no longer contains fake `onOpenApp` / `onOpenViewer` counters or stale two-CTA wording. The test comment now describes clearing chat and dropping handoff rows (`app/src/test/kotlin/ai/closepaw/ui/chat/NewSessionNoHandoffTest.kt:29`), and the assertions cover the surviving reset behavior (`app/src/test/kotlin/ai/closepaw/ui/chat/NewSessionNoHandoffTest.kt:104`).
- LOW 1 — Status: ✓ Fixed — production comments now describe only the `Open <App>` CTA in the protocol model (`app/src/main/kotlin/ai/closepaw/protocol/CompletionHandoff.kt:5`), task event (`app/src/main/kotlin/ai/closepaw/protocol/TaskLifecycleEvents.kt:16`), session handoff capture path (`app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:485`), and chat model (`app/src/main/kotlin/ai/closepaw/ui/chat/model/ChatMessage.kt:49`). The design doc was also updated to say `virtualDisplayAvailable` was dropped because it has no consumer (`projects/archive/20260516_vd_completion_handoff/design_codex.md:8`).
- LOW 2 — Status: ✓ Fixed — `AgentService` imports no longer include `SessionState`; the protocol imports are only `Op`, `PlatformMode`, and `SessionConfig` (`app/src/main/kotlin/ai/closepaw/app/AgentService.kt:12`).

## New findings (introduced by 70102ab0)

No new findings. Specific checks:
- No 3-argument `CompletionHandoff` constructor fallout: current constructions use only `appPackage` and `appLabel` (`app/src/test/kotlin/ai/closepaw/ui/chat/ChatCompletionHandoffTest.kt:41`, `app/src/test/kotlin/ai/closepaw/ui/chat/NewSessionNoHandoffTest.kt:80`).
- The test rewrite did not drop the load-bearing BLOCKED-tier capture-time filter: `AgentSessionCompletionHandoffTest` still covers dropping a BLOCKED package before handoff emission (`app/src/test/kotlin/ai/closepaw/session/AgentSessionCompletionHandoffTest.kt:109`).
- `VirtualDisplayPlatform` no longer exposes viewer availability; the remaining public viewer surface is live-preview switching, image-reader switching, surface mode, and viewer touch handling (`app/src/main/kotlin/ai/closepaw/platform/virtualdisplay/VirtualDisplayPlatform.kt:258`).

### Critical
None.

### High
None.

### Medium
None.

### Low / Nits
None.

## Tests
- `./gradlew :app:testDebugUnitTest --no-configuration-cache` — PASS (`BUILD SUCCESSFUL in 10s`), 183 suites / 1734 tests / 0 failures / 0 errors / 0 skipped. No compiler/test warnings were emitted; Gradle only noted that it started a daemon because one incompatible daemon could not be reused.

## Verdict
ship
