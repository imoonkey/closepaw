# VD Completion Handoff — Code Review

> **Historical**: HIGH #2 (stale `View virtual screen` metadata) was first
> patched with tap-time revalidation in `e0cb3b2b`, then the CTA was removed
> entirely in `2dfc19bd` (see `design_codex.md` update). HIGH #1 (blocked
> apps in Open CTA) was fixed in `e0cb3b2b` and remains in the shipped build.

## Scope
- branch: task/vd-completion-handoff
- commits reviewed: 9be6c778, d0f6ac99, 7b2a109d, 44f3323f
- files reviewed:
  - app/src/main/kotlin/ai/closepaw/app/MainActivity.kt
  - app/src/main/kotlin/ai/closepaw/app/MainActivityContent.kt
  - app/src/main/kotlin/ai/closepaw/app/MainActivityUiHelpers.kt
  - app/src/main/kotlin/ai/closepaw/app/OverlayLocationPolicy.kt
  - app/src/main/kotlin/ai/closepaw/app/ServiceOverlayController.kt
  - app/src/main/kotlin/ai/closepaw/platform/virtualdisplay/VirtualDisplayPlatform.kt
  - app/src/main/kotlin/ai/closepaw/protocol/CompletionHandoff.kt
  - app/src/main/kotlin/ai/closepaw/protocol/TaskLifecycleEvents.kt
  - app/src/main/kotlin/ai/closepaw/session/AgentSession.kt
  - app/src/main/kotlin/ai/closepaw/session/SessionServices.kt
  - app/src/main/kotlin/ai/closepaw/tool/AppClassifier.kt
  - app/src/main/kotlin/ai/closepaw/tool/PolicyEngine.kt
  - app/src/main/kotlin/ai/closepaw/tool/impl/OpenAppTool.kt
  - app/src/main/kotlin/ai/closepaw/ui/chat/ChatEventReducer.kt
  - app/src/main/kotlin/ai/closepaw/ui/chat/ChatScreen.kt
  - app/src/main/kotlin/ai/closepaw/ui/chat/ChatSessionHistoryController.kt
  - app/src/main/kotlin/ai/closepaw/ui/chat/ChatViewModel.kt
  - app/src/main/kotlin/ai/closepaw/ui/chat/components/AgentRow.kt
  - app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt
  - app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt
  - app/src/main/kotlin/ai/closepaw/ui/chat/model/ChatMessage.kt
  - app/src/main/kotlin/ai/closepaw/ui/viewer/VirtualDisplayViewerActivity.kt
  - app/src/main/assets/security/app_tiers.json
  - app/src/test/kotlin/ai/closepaw/session/AgentSessionCompletionHandoffTest.kt
  - app/src/test/kotlin/ai/closepaw/ui/chat/ChatCompletionHandoffTest.kt
  - app/src/test/kotlin/ai/closepaw/ui/chat/NewSessionNoHandoffTest.kt
  - app/src/test/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaVisibilityTest.kt

## Findings (ordered by severity)

### Critical
No findings.

### High
1. Blocked apps can still get an `Open <App>` handoff CTA and launch on the real display.
   - File: app/src/main/kotlin/ai/closepaw/protocol/CompletionHandoff.kt:31
   - File: app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:69
   - File: app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:256
   - Issue: `buildVdCompletionHandoff` filters only ClosePaw and `com.android.systemui`, while `CompletionHandoffCtaRow` checks only `PackageManager.getLaunchIntentForPackage`, and `MainActivity` launches the returned intent directly. No step checks `AppClassifier.classify(pkg) != AppTier.BLOCKED`.
   - Why it matters: the project treats BLOCKED packages as an absolute safety floor for screen-changing actions in `PolicyEngine` and `OpenAppTool`; app tiers explicitly mark finance/auth packages as BLOCKED. This handoff path bypasses that policy and can surface an explicit real-display launcher for a VD task that ended on a blocked app. See app/src/main/kotlin/ai/closepaw/tool/PolicyEngine.kt:67, app/src/main/kotlin/ai/closepaw/tool/impl/OpenAppTool.kt:190, and app/src/main/assets/security/app_tiers.json:3.
   - Suggested fix: route handoff eligibility through `AppClassifier` at capture time or render/click time, suppress `appPackage` or the Open CTA when `classify(pkg) == AppTier.BLOCKED`, and add regression coverage for a blocked package such as `com.chase.sig.android`.

2. `View virtual screen` can launch from stale completion metadata after the VD is no longer viewable.
   - File: app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:492
   - File: app/src/main/kotlin/ai/closepaw/platform/virtualdisplay/VirtualDisplayPlatform.kt:286
   - File: app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:73
   - File: app/src/main/kotlin/ai/closepaw/app/MainActivityUiHelpers.kt:31
   - Issue: `virtualDisplayAvailable` is captured once at `TaskCompleted`, stored on the chat row, and later trusted by the CTA renderer. The click handler opens `VirtualDisplayViewerActivity` without revalidating that the active VD platform still exists and is Running.
   - Why it matters: the same session schedules idle auto-shutdown after completion and cleanup stops the platform, but the completed chat row is not updated when that happens. A user can come back to a row whose handoff still says `virtualDisplayAvailable = true` and launch a dead or immediately finishing viewer. See app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:705, app/src/main/kotlin/ai/closepaw/session/SessionServices.kt:386, and app/src/main/kotlin/ai/closepaw/ui/viewer/VirtualDisplayViewerActivity.kt:103.
   - Suggested fix: make viewer eligibility live instead of row-static, or at least revalidate in `onOpenViewer` before starting the activity. For example, expose `AgentService.instance?.isViewerAvailable()` backed by the current `VirtualDisplayPlatform.isViewerAvailable()`, hide/disable the CTA when false, and guard the click path with the same check.

### Medium
1. CTA regression tests mostly exercise detached helper logic, not the production rendering and callback path.
   - File: app/src/test/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaVisibilityTest.kt:8
   - File: app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:67
   - File: app/src/test/kotlin/ai/closepaw/ui/chat/NewSessionNoHandoffTest.kt:80
   - Issue: `CompletionHandoffCtaVisibilityTest` says production rendering reuses the helper, but `CompletionHandoffCtaRow` recomputes launcher visibility inline. `NewSessionNoHandoffTest` also defines open-app/viewer counters but never wires them to the rendered `ChatScreen`; the assertions prove the counters stayed unused, not that a UI click path could not call them.
   - Why it matters: the highest-risk behavior here is accidental launching. The current tests cover reducer/model plumbing and controller reset well, but they do not catch drift between the helper and composable, blocked-package CTA visibility, or actual button/header callback wiring.
   - Suggested fix: either make `CompletionHandoffCtaRow` call `completionHandoffCtaVisibility` or add a Compose/Robolectric test around `MessageBubble`/`ChatScreen` with injectable package resolution. Add one UI-level test that renders a handoff row, clicks each CTA, and clicks New Session with the same `onOpenApp`/`onOpenViewer` counters wired into the composable.

### Low / Nits
No findings.

## Coverage Summary
- New Session no-handoff behavior: reviewed `MainActivity` New Session wiring, `ChatViewModel.startNewSession`, `ChatSessionHistoryController.startNewSession`, and `NewSessionNoHandoffTest`; production code does not read or execute `CompletionHandoff` on reset. See app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:250, app/src/main/kotlin/ai/closepaw/ui/chat/ChatViewModel.kt:422, app/src/main/kotlin/ai/closepaw/ui/chat/ChatSessionHistoryController.kt:75, and app/src/test/kotlin/ai/closepaw/ui/chat/NewSessionNoHandoffTest.kt:72.
- Explicit CTA-only app launch behavior: reviewed `TaskCompleted` plumbing, `AgentRow` rendering, `CompletionHandoffCtaRow`, and `MainActivity.onOpenApp`; no app launch occurs from `TaskCompleted` itself, but blocked-package filtering is missing. See app/src/main/kotlin/ai/closepaw/ui/chat/ChatEventReducer.kt:148, app/src/main/kotlin/ai/closepaw/ui/chat/components/AgentRow.kt:101, app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:83, and app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:256.
- Explicit CTA-only viewer launch behavior: reviewed viewer callback propagation through `ChatScreen`, `MessageBubble`, `AgentRow`, and `MainActivityUiHelpers`; no viewer launch occurs from `TaskCompleted` itself, but stale liveness is not rechecked at click time. See app/src/main/kotlin/ai/closepaw/ui/chat/ChatScreen.kt:221, app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:44, app/src/main/kotlin/ai/closepaw/ui/chat/components/AgentRow.kt:101, and app/src/main/kotlin/ai/closepaw/app/MainActivityUiHelpers.kt:31.
- VD vs accessibility handoff emission: reviewed `AgentSession.buildHandoffIfVd` and unit coverage; accessibility mode returns null, VD mode emits metadata. See app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:492, app/src/test/kotlin/ai/closepaw/session/AgentSessionCompletionHandoffTest.kt:41, and app/src/test/kotlin/ai/closepaw/session/AgentSessionCompletionHandoffTest.kt:112.
- Stale handoff after session/viewer dies: reviewed idle timeout, cleanup, viewer availability capture, and viewer launch; stale row metadata is a high-risk issue above. See app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:705, app/src/main/kotlin/ai/closepaw/session/SessionServices.kt:386, and app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:73.
- Lifecycle/threading races: reviewed serialized `AgentSession` lifecycle completion path and async New Session clearing; no mutex regression found in handoff emission, but viewer liveness races remain because the UI stores a stale boolean. See app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:259, app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:445, and app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:250.
- Non-VD regression risk: reviewed nullable `handoff` defaults through protocol/model/reducer paths and accessibility tests; non-VD rows keep `handoff = null`. See app/src/main/kotlin/ai/closepaw/protocol/TaskLifecycleEvents.kt:25, app/src/main/kotlin/ai/closepaw/ui/chat/model/ChatMessage.kt:54, and app/src/test/kotlin/ai/closepaw/ui/chat/ChatCompletionHandoffTest.kt:62.
- Test coverage adequacy: reducer/model/session reset coverage is solid, but production CTA rendering, blocked-package filtering, live viewer availability, and actual UI callback wiring need additional tests before QA. See app/src/test/kotlin/ai/closepaw/ui/chat/ChatCompletionHandoffTest.kt:35, app/src/test/kotlin/ai/closepaw/session/AgentSessionCompletionHandoffTest.kt:60, app/src/test/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaVisibilityTest.kt:8, and app/src/test/kotlin/ai/closepaw/ui/chat/NewSessionNoHandoffTest.kt:80.

## Recommendation
merge-with-fixes
