# VD Completion Handoff — QA Report

> **Historical**: this report covers the build that included the
> `View virtual screen` CTA (Flow C). That CTA was removed before merge
> (commit `2dfc19bd`) because the post-completion viewer had no working
> takeover / edge glow / exit — only the `Open <App>` CTA ships. Flow C
> findings below are retained for traceability only. Flows A, B, D still
> apply to the shipped behavior.

## Environment
- Device: Tecno P0110 (Android 16) connected via Tailscale ADB at `100.64.43.95:5555`.
- Shizuku server running (pid 23506); `moe.shizuku.manager.permission.API_V23: granted=true` for `ai.closepaw`.
- Accessibility service enabled; overlay permission granted.
- LLM: OpenAI (gpt-5.4) via `.env`-loaded API key.
- Branch: task/vd-completion-handoff
- HEAD before evidence: `5ae5012c` (`docs(vd-handoff): QA report covering completion handoff flows A–D`)
- Run command: `PERCEPTION_MODE=accessibility_only ./scripts/debug-run.sh --vd "Open the Calculator app and stop."`
- Active platform observed in logcat: `PlatformFactory: Using VirtualDisplayPlatform: 1264x2800@560dpi`, `applyVisibility: platformMode=VIRTUAL_DISPLAY`.

## Note on prior run
An earlier QA pass labelled this "code-trace only" because Shizuku permission was not yet granted to ClosePaw, so the agent silently fell back to AccessibilityPlatform (`PlatformFactory: Shizuku permission not granted`). After granting Shizuku via the Shizuku app once, all subsequent runs engage VD mode. Real on-device evidence for each flow is captured below in `qa_evidence/`.

## Flow A — Completion chat default (no auto-launch on TaskCompleted)
**Result**: PASS

**Evidence**

1. `AgentSession` emits `TaskCompleted` carrying optional handoff metadata; nothing
   in this branch starts an activity or hits a launcher intent.
   - `app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:445-454` — the only
     side effect adjacent to emit is the trace flush above it; the next steps
     (lines 457-471) checkpoint, transition to `Idle`, clear the agent runner,
     and schedule the idle timeout. No `startActivity` / launcher path.
   - `buildHandoffIfVd` (`AgentSession.kt:492-505`) is pure metadata capture; it
     only reads `platform.getCurrentPackageName()`, viewer liveness, and runs
     it through `buildVdCompletionHandoff`.
2. `ChatEventReducer.handleTaskCompleted` only appends a row and clears the
   streaming buffer.
   - `app/src/main/kotlin/ai/closepaw/ui/chat/ChatEventReducer.kt:148-160` —
     calls `appendCompletionToMessages(..., handoff = event.handoff)`, then
     `streamingBuffer.clear()`, then `setCurrentAgentMessageId(null)`. No
     callbacks invoked here.
3. `AgentRow` rendering is purely declarative — the CTA composable is only
   placed in the tree, never auto-invoked.
   - `app/src/main/kotlin/ai/closepaw/ui/chat/components/AgentRow.kt:101-107` —
     `if (rowState == Complete && handoff != null) { CompletionHandoffCtaRow(...) }`.
     `CompletionHandoffCtaRow` (`CompletionHandoffCtaRow.kt:60-114`) renders
     `FilledTonalButton` / `OutlinedButton` whose `onClick` lambdas are the only
     paths back to `onOpenApp` / `onOpenViewer`. Nothing runs at render time
     besides the package-manager visibility lookup.
4. The protocol level documents and enforces this: `TaskCompleted.handoff` is a
   nullable data field, not a command.
   - `app/src/main/kotlin/ai/closepaw/protocol/TaskLifecycleEvents.kt:19-26`.

Conclusion: VD completion on this branch deposits a row into the chat list and
nothing else. The user stays in chat by default.

## Flow B — Explicit Open <App> CTA
**Result**: PASS

**Evidence**

1. Blocked-package filter is applied **at capture time**, not just at render.
   - `app/src/main/kotlin/ai/closepaw/protocol/CompletionHandoff.kt:26-51` —
     `buildVdCompletionHandoff` zeros out `appPackage` when the package equals
     ClosePaw, equals `com.android.systemui`, **or** classifies as
     `AppTier.BLOCKED` (line 36). The label resolution and the returned
     `appPackage` then collapse to `null` for blocked packages.
   - The classifier is threaded in from `AgentSession.buildHandoffIfVd`:
     `app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:503`
     (`classifyTier = { services.appClassifier.classify(it) }`).
   - `AppClassifier.classify` (`app/src/main/kotlin/ai/closepaw/tool/AppClassifier.kt:17-21`)
     returns the JSON-loaded tier (default `CAUTIOUS`), so the BLOCKED list in
     `app/src/main/assets/security/app_tiers.json` (finance/auth packages
     including `com.chase.sig.android`, `com.coinbase.android`, `io.metamask`,
     `com.onepassword.android`, `com.duosecurity.duomobile`, …) is suppressed.
   - Unit coverage:
     `app/src/test/kotlin/ai/closepaw/session/AgentSessionCompletionHandoffTest.kt`
     asserts that BLOCKED packages collapse `appPackage` and `appLabel` to
     `null` while `virtualDisplayAvailable` is preserved.
2. Render-time filter further drops the CTA when the package has no launcher
   intent.
   - `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:67-74` —
     production composable now delegates to `completionHandoffCtaVisibility(...)`
     (the pure helper covered by
     `app/src/test/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaVisibilityTest.kt`),
     guaranteeing the unit test and runtime computation cannot drift apart.
   - `completionHandoffCtaVisibility` (`CompletionHandoffCtaRow.kt:40-50`) sets
     `showOpenApp = appPackage?.let(canResolveLauncher) ?: false`, so a `null`
     package or unresolvable intent suppresses the button.
3. Tap is the only path to a launcher intent.
   - `CompletionHandoffCtaRow.kt:84-98` — `FilledTonalButton(onClick = { onOpenApp(resolvablePkg) })`,
     where `resolvablePkg` is non-null only when `showOpenApp` was true.
   - `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:262-266` —
     `onOpenApp = { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.apply { addFlags(FLAG_ACTIVITY_NEW_TASK) }?.let { startActivity(it) } }`.
     The launcher intent runs on the real (default) display because no display
     ID is set on the intent.

Conclusion: the "Open <App>" CTA only appears for non-blocked, non-self,
non-SystemUI packages that resolve to a launcher intent, and tapping it routes
through `MainActivity.onOpenApp` which is the only launcher call site.

## Flow C — Explicit View virtual screen CTA
**Result**: PASS

**Evidence**

1. Viewer visibility is first guarded by capture-time liveness and re-derived at
   render time via the same helper as Flow B.
   - `app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:495-500` —
     `viewerAvailable = (platform as? VirtualDisplayPlatform)?.isViewerAvailable() ?: false`.
   - `app/src/main/kotlin/ai/closepaw/platform/virtualdisplay/VirtualDisplayPlatform.kt:286` —
     `fun isViewerAvailable(): Boolean = arbiter.state is VdState.Running`.
   - `CompletionHandoffCtaRow.kt:40-50` — `showOpenViewer = handoff.virtualDisplayAvailable`.
2. Tap-time **revalidates** the live platform before launching, addressing the
   stale-row High finding from `codex_code_review.md` #2.
   - `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:255-261` —
     `onOpenViewer = { if (AgentService.instance?.isVirtualDisplayViewerAvailable() == true) { openViewer(this@MainActivity) } else { Log.d(TAG, "Open viewer suppressed: VD platform not running") } }`.
   - `AgentService.isVirtualDisplayViewerAvailable()`
     (`app/src/main/kotlin/ai/closepaw/app/AgentService.kt:405`) forwards to
     `AgentServiceViewerBridge.isViewerAvailable()`
     (`app/src/main/kotlin/ai/closepaw/app/AgentServiceViewerBridge.kt:44`),
     which polls the *current* `VirtualDisplayPlatform.isViewerAvailable()`.
     This means even if the chat row's `virtualDisplayAvailable = true` is now
     stale (idle timeout cleaned up the VD; see `AgentSession.kt:705` and
     `SessionServices.kt:386`), the tap is a no-op and only logs.
3. The bridge-side unit test for the revalidation contract:
   `app/src/test/kotlin/ai/closepaw/app/AgentServiceViewerBridgeAvailabilityTest.kt`
   confirms `isViewerAvailable` reflects the live platform state.

Conclusion: the viewer CTA is only rendered when the row's snapshot said
viewer was live, and an additional live check at tap time prevents stale rows
from launching a dead viewer.

## Flow D — New Session no handoff
**Result**: PASS

**Evidence**

1. Header / drawer wire `onNewSession` straight to `MainActivity`'s reset
   lambda. No handoff data is on this path.
   - `app/src/main/kotlin/ai/closepaw/ui/chat/ChatScreen.kt:124-127` (drawer
     "New conversation") and `:148` (`ChatHeader.onNewChatClick = onNewSession`)
     both invoke the same callback.
   - `app/src/main/kotlin/ai/closepaw/ui/chat/components/ChatHeader.kt:69-72` —
     IconButton `onClick = onNewChatClick`; no other side effects.
   - `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:250-254`:
     ```
     onNewSession = {
         coordinator.selectedSessionForReload = null
         lifecycleScope.launch { coordinator.clearSession() }
         viewModel.startNewSession(settingsState.selectedModel, BuildConfig.VERSION_NAME)
     }
     ```
     No reads from `CompletionHandoff`, no calls to `onOpenApp`, no calls to
     `onOpenViewer`, no `startActivity`.
2. `ChatViewModel.startNewSession` is a pure wrapper around the controller's
   reset.
   - `app/src/main/kotlin/ai/closepaw/ui/chat/ChatViewModel.kt:422-426` — cancels
     event collection and calls `sessionHistoryController.startNewSession(...)`.
3. `ChatSessionHistoryController.startNewSession` clears state only.
   - `app/src/main/kotlin/ai/closepaw/ui/chat/ChatSessionHistoryController.kt:75-81` —
     `clearConversation()` (line 33-40) clears messages, streaming buffer,
     current agent id, and flips `showEmptyState = true`. No launcher.
4. Regression coverage:
   `app/src/test/kotlin/ai/closepaw/ui/chat/NewSessionNoHandoffTest.kt:72-138`
   drives a real `ChatViewModel` through a VD `TaskCompleted` carrying a valid
   `CompletionHandoff`, then calls `startNewSession()` and asserts the row is
   gone, `showEmptyState` is true, and `openAppCount`/`openViewerCount` remain
   zero. A second test (`:141-187`) exercises
   `ChatSessionHistoryController.startNewSession` directly with a
   handoff-bearing row and confirms no row with `handoff != null` survives.

Conclusion: New Session is a pure reset; it never reads `CompletionHandoff`,
never invokes the CTA callbacks, and never launches an app or the viewer.

## Unit Tests
`./gradlew :app:testDebugUnitTest` — **PASS** (exit 0; build `SUCCESSFUL in 7s`,
configuration cache reused; aggregating
`app/build/test-results/testDebugUnitTest/*.xml`: **1739 tests, 0 skipped,
0 failures, 0 errors** across 184 suite files).

Key handoff-related suites verified to exist and pass under that run:
- `ai.closepaw.session.AgentSessionCompletionHandoffTest`
- `ai.closepaw.ui.chat.ChatCompletionHandoffTest`
- `ai.closepaw.ui.chat.NewSessionNoHandoffTest`
- `ai.closepaw.ui.chat.components.CompletionHandoffCtaVisibilityTest`
- `ai.closepaw.app.AgentServiceViewerBridgeAvailabilityTest`

## Open Concerns

1. **On-device e2e evidence captured.** Real-device run logged
   `PlatformFactory: Using VirtualDisplayPlatform: 1264x2800@560dpi` and all
   four flows were exercised by tap, with screenshots stored under
   `projects/active/vd_completion_handoff/qa_evidence/`:
   - `01_completion_chat_with_ctas.png` — completed VD row showing both
     `Open <App>` and `View virtual screen` CTAs (Flow A + B + C visibility).
   - `03_open_app_launcher.png` — after tapping `Open 系统桌面`,
     `topResumedActivity=com.android.launcher3/com.obric.feature.ObricLauncher`
     and AgentService logs `Window changed: pkg=com.android.launcher3 ... to=OTHER_APP`
     (Flow B click validated).
   - `04_back_to_chat.png` — return to ClosePaw shows the same row, CTAs intact.
   - `05_after_new_session.png` — after tapping the `+` (New conversation),
     the chat is reset to the empty "What can I help you with?" surface and
     `topResumedActivity` stays at `ai.closepaw/.app.MainActivity`. Logcat shows
     no `onOpenApp` / `onOpenViewer` / `getLaunchIntentForPackage` /
     `VirtualDisplayViewer` activity (Flow D click validated).
   - Flow C click result (no separate screenshot — chat is unchanged): tapping
     `View virtual screen` after the script's `STOP_AGENT` released the VD
     produced `MainActivity: Open viewer suppressed: VD platform not running`
     in logcat (codex review HIGH#2 fix). The button intentionally remains
     visible (its visibility is captured at completion time), but the tap
     handler revalidates `AgentService.isVirtualDisplayViewerAvailable()` and
     no-ops cleanly when the platform is gone — no dead viewer is launched.
2. **Codex review Medium #1 partially mitigated.** The composable now calls
   `completionHandoffCtaVisibility(...)` (drift between helper and composable
   resolved). However, a Compose/Robolectric test that renders
   `MessageBubble`/`ChatScreen`, clicks each CTA, and verifies the
   `onOpenApp` / `onOpenViewer` callbacks fire is still absent. Existing tests
   prove plumbing/reducer correctness but not button-click wiring. Low risk
   given the simple structure, but worth a small Robolectric pass before this
   feature graduates to "trusted without code review on every change."
3. **`MainActivity.onOpenApp` does not re-check blocked tier at tap.** The
   capture-time filter in `buildVdCompletionHandoff` is the only blocked-package
   guard; if anyone ever bypasses that builder (e.g., reconstructs a
   `CompletionHandoff` directly with a BLOCKED package), the CTA would still
   tap-launch. Today no such code path exists in `main`, so this is a defensive
   note, not a finding. A belt-and-braces fix would be to also call
   `services.appClassifier.classify(pkg) != BLOCKED` inside the tap lambda or
   inside `completionHandoffCtaVisibility`.

## Verdict
**ship** — design goals are met by the code on `task/vd-completion-handoff` at
`e0cb3b2b`: completion stays in chat, the two CTAs only render when their guards
pass, tap-time revalidation prevents stale-viewer launches, blocked packages are
suppressed at capture, and New Session has a clean no-handoff invariant backed
by regression tests. Open Concerns #2 and #3 are nice-to-have hardening, not
gating.
