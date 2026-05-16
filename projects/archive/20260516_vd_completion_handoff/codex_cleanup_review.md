# VD Completion Handoff — Post-CTA-Removal Cleanup Review

## Scope
- Branch / HEAD reviewed: `task/vd-completion-handoff` at `8a721fb7`.
- Commits looked at: full `main..HEAD`, with focus on `2dfc19bd` (CTA removal) and `8a721fb7` (doc follow-up).
- Approach: reviewed the full PR stat/diff, the `2dfc19bd` cleanup diff, targeted current files, deleted-symbol searches, viewer/open-viewer call chains, handoff capture/render paths, CTA tests, and `scripts/debug-run.sh` / `scripts/setup.sh`.

## Findings

### Critical (regression risk)
No findings.

### High (dead/inconsistent code that should be cleaned)

1. Viewer availability survived as a dead data path after the `View virtual screen` CTA was removed.
   - Evidence: `CompletionHandoff.virtualDisplayAvailable` is still part of the protocol model at `app/src/main/kotlin/ai/closepaw/protocol/CompletionHandoff.kt:15`, captured at `app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:495`, and written into every VD handoff at `app/src/main/kotlin/ai/closepaw/protocol/CompletionHandoff.kt:49`, but the current renderer only checks launcher availability via `handoff.appPackage` at `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:64`.
   - Evidence: the bridge liveness API `AgentServiceViewerBridge.isViewerAvailable()` at `app/src/main/kotlin/ai/closepaw/app/AgentServiceViewerBridge.kt:44` has no production callers; `rg` finds only its test. The test comment still claims it is used by overlay paths and viewer keep-open logic at `app/src/test/kotlin/ai/closepaw/app/AgentServiceViewerBridgeAvailabilityTest.kt:10`, which is not true in the current tree.
   - Why it matters: this is exactly the CTA-specific plumbing the removal was supposed to unwind. It preserves state, tests, and comments for an affordance that no longer exists, making future readers think viewer liveness is still meaningful to chat completion handoff.
   - Concrete fix: remove `virtualDisplayAvailable` from `CompletionHandoff`; remove the `viewerAvailable` parameter from `buildVdCompletionHandoff`; remove the `VirtualDisplayPlatform.isViewerAvailable()` read from `AgentSession.buildHandoffIfVd`; delete `AgentServiceViewerBridge.isViewerAvailable()` and `AgentServiceViewerBridgeAvailabilityTest`; then update handoff tests to assert only package/label filtering.

2. The new `debug-run.sh` setup preflight has a broken failure path and does not reliably preserve `--local`.
   - Evidence: `scripts/debug-run.sh:313` calls `err` when preflight fails, but this script only defines `log`, `ok`, and `warn` at `scripts/debug-run.sh:113`; there is no `err()` helper, so setup failure becomes `err: command not found` instead of the intended message.
   - Evidence: `scripts/debug-run.sh:313` passes `LLM_BACKEND="$LLM_BACKEND"` into `setup.sh`, but `setup.sh` immediately sources `.env` at `scripts/setup.sh:103` before resolving `LLM_BACKEND` at `scripts/setup.sh:108`. If `.env` contains `LLM_BACKEND=openai`, a `debug-run.sh --local ...` preflight can still hit the OpenAI API-key gate.
   - Why it matters: this PR added the preflight specifically so `debug-run.sh --local` would not trip the OpenAI gate, but the current shape only works when `.env` does not override the backend.
   - Concrete fix: define `err()` in `debug-run.sh` or replace the preflight fallback with an inline `{ ...; exit 1; }` block. In `setup.sh`, capture caller-provided backend before sourcing `.env`, then prefer it afterward, e.g. `REQUESTED_LLM_BACKEND="${LLM_BACKEND:-}"` before `source`, then `LLM_BACKEND="${REQUESTED_LLM_BACKEND:-${LLM_BACKEND:-openai}}"`.

### Medium (over-engineering / smell)

1. The CTA visibility helper is still shaped for multiple CTAs even though only `Open <App>` remains.
   - Evidence: `CompletionHandoffCtaVisibility` wraps one boolean at `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:31`, and `any` is now just `showOpenApp` at `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:34`. The composable then checks `visibility.any` at `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:71`.
   - Why it matters: the object/`any` shape made sense for two buttons; now it adds indirection around one boolean and keeps the old mental model alive.
   - Concrete fix: replace `CompletionHandoffCtaVisibility` with a boolean helper such as `shouldShowOpenAppCta(handoff, canResolveLauncher)`, or inline the `handoff.appPackage?.takeIf { canResolveLauncher(it) } ?: return` check in `CompletionHandoffCtaRow`; update `CompletionHandoffCtaVisibilityTest` accordingly.

2. `NewSessionNoHandoffTest` still carries two-CTA scaffolding and includes assertions that cannot fail in the current code path.
   - Evidence: the test comment still says New Session must not invoke `Open <App>` / `View virtual screen` actions at `app/src/test/kotlin/ai/closepaw/ui/chat/NewSessionNoHandoffTest.kt:31`, and the test creates `onOpenApp` / `onOpenViewer` counters at `app/src/test/kotlin/ai/closepaw/ui/chat/NewSessionNoHandoffTest.kt:80` even though those lambdas are never passed to `ChatViewModel.startNewSession()`.
   - Evidence: the zero-invocation assertions at `app/src/test/kotlin/ai/closepaw/ui/chat/NewSessionNoHandoffTest.kt:132` are therefore structural tautologies, not meaningful coverage; the unused lambdas are kept alive only by suppressions at `app/src/test/kotlin/ai/closepaw/ui/chat/NewSessionNoHandoffTest.kt:136`.
   - Why it matters: this looks like regression coverage for viewer/open-app launch leakage, but it is really just reset coverage with disconnected counters. After CTA removal, the viewer half is especially stale.
   - Concrete fix: remove the fake callback counters and stale viewer text; keep the useful assertion that `startNewSession()` clears messages and no handoff-bearing row survives.

### Low / Nits

1. Several production comments still describe the removed two-CTA design.
   - Evidence: `CompletionHandoff.kt` says chat can render `"Open <App>", "View virtual screen"` at `app/src/main/kotlin/ai/closepaw/protocol/CompletionHandoff.kt:6`; `TaskLifecycleEvents.kt` repeats that at `app/src/main/kotlin/ai/closepaw/protocol/TaskLifecycleEvents.kt:16`; `AgentSession.kt` repeats it at `app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:488`; and `ChatMessage.kt` says handoff drives both CTAs at `app/src/main/kotlin/ai/closepaw/ui/chat/model/ChatMessage.kt:49`.
   - Concrete fix: if the viewer-availability data is removed, these comments disappear or become singular `Open <App>` docs. If the field is retained, at least rewrite them to say the viewer CTA is not currently rendered.

2. `AgentService` has an unused import left from the CTA cleanup churn.
   - Evidence: `ai.closepaw.protocol.SessionState` is imported at `app/src/main/kotlin/ai/closepaw/app/AgentService.kt:16`, and there are no other `SessionState` references in that file.
   - Concrete fix: delete the import.

## Coverage Summary
- Reviewed targeted current code: `CompletionHandoffCtaRow`, `AgentRow`, `MessageBubble`, `ChatScreen`, `MainActivity`, `MainActivityContent`, `MainActivityUiHelpers`, `AgentService`, `AgentServiceViewerBridge`, `VirtualDisplayViewerActivity`, `CompletionHandoff`, and the `AgentSession` handoff path.
- Reviewed tests around handoff, CTA visibility, New Session reset, and viewer bridge availability.
- Reviewed scripts touched by the PR: `scripts/debug-run.sh` and `scripts/setup.sh`.
- Searched for deleted/removal-era symbols: `CompletionViewerAvailability`, `canOpenCompletionViewer`, `EXTRA_KEEP_VIEWER_OPEN_WHEN_IDLE`, `keepOpenWhenIdle`, `openCompletionViewerFromCta`, `virtualDisplayViewerAvailable`, `isVirtualDisplayViewerAvailable`, `_virtualDisplayViewerAvailable`, `publishViewerAvailability`, and `showOpenViewer`.
- Intentionally skipped unrelated PR changes outside the cleanup surface, such as settings persistence and platform internals not involved in handoff/viewer routing.

## Verdict
ship-with-cleanup

The shipped `Open <App>` path is understandable, and I did not find a critical CTA-removal regression. The cleanup should still remove the dead viewer-availability path and fix the script preflight bugs before this branch is treated as tidy.
