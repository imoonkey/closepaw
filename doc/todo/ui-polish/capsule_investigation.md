# Capsule Overlay Investigation (INV-1)

**Date:** 2026-04-20
**Investigator:** Claude (Opus 4.7)
**Scope:** Diagnose why the Smart Capsule overlay never rendered during basic-mode runs, VD-mode runs, or after a direct launch of `VirtualDisplayViewerActivity`. **Investigation only — no behaviour changes applied.**

This report covers the three asks in the polish report's INV-1 section:
- (a) Why `CapsuleOverlayHost` never drew over other apps.
- (b) Where the user-mentioned "eye-icon affordance" lives (or whether it exists).
- (c) Why a direct launch of `VirtualDisplayViewerActivity` produces a black screen.

---

## (a) Capsule overlay never drew — root cause analysis

### How the overlay is supposed to fire

1. `AgentService.onServiceConnected()` constructs `ServiceOverlayController` with a non-null `IslandOverlayHost` (`AgentService.kt:170-179`). `ServiceOverlayController.showPreference` defaults to `ShowPreference.ISLAND` (`ServiceOverlayController.kt:120`).
2. On every state/window change the controller calls `applyVisibility()` (`ServiceOverlayController.kt:149-185`) which delegates to the pure decision function `deriveOverlayVisibility(...)` in `OverlayLocationPolicy.kt:79-142`.
3. `applyVisibility` then calls `capsuleManager.show()` / `.hide()` accordingly. `CapsuleOverlayHost.show()` is a thin wrapper around `OverlayComposeHost.show()` which `windowManager.addView(...)` with `TYPE_ACCESSIBILITY_OVERLAY` (`CapsuleOverlayHost.kt:104-192`, `OverlayComposeHost.kt:30-52`).
4. Window-state events feed into the controller via `AgentService.onAccessibilityEvent` → `handleWindowStateChanged` → `resolveUserLocation(...)` (`AgentService.kt:193-205`, `OverlayLocationPolicy.kt:35-54`).

For an A11y-mode Running task the path that produces `showCapsule=true` is:
`location == OTHER_APP` ∧ `hasActiveTask` ∧ `showPreference == CAPSULE` (`OverlayLocationPolicy.kt:96-105`).

`onTaskStarted` is the **only** place that bumps `showPreference` from ISLAND → CAPSULE (`ServiceOverlayController.kt:265-269`).

### What likely went wrong

The decision tree itself is sound — but it is fragile in three places that line up exactly with the captured "no overlay" symptoms (capture worker reports neither capsule **nor** island visible while the agent operated in system Settings).

1. **`showPreference` defaults to `ISLAND`, and the only writer that flips it to `CAPSULE` is `onTaskStarted`** (`ServiceOverlayController.kt:120`, `:265-269`). Subsequent flows that *should* keep the overlay reachable (`onMinimize`, `onViewerClosed`) actively reset it back to `ISLAND` (`ServiceOverlayController.kt:107-110`, `:223`). If the captures were taken after any of those events, expected behaviour is the **island**, not the capsule. But the captures show no island either — meaning the next failure mode also fired:

2. **`OverlayComposeHost.show()` swallows every exception** with a single `Log.e` and leaves `composeView == null` (`OverlayComposeHost.kt:35-52`). After the fact, `isShowing()` keeps returning `false` and every later `applyVisibility` keeps re-trying the same `addView`. There is no surfacing path to the user, no toast, no event. `BadTokenException` / `WindowManager$BadTokenException` from a stale-or-disconnected service token is the canonical reason `addView` of a `TYPE_ACCESSIBILITY_OVERLAY` silently fails. Per `applyNotTouchableFlag` paths and the `init { ... mode.collect { ... applyVisibility() } }` block (`ServiceOverlayController.kt:122-133`), every state tick reaches `composeHost.show()` again, but the same exception will recur. **Without a runtime logcat, this is the highest-probability root cause for "no overlay of any kind".**

3. **The window-state observer can miss the OTHER_APP transition.** `resolveUserLocation` returns `null` (no-op) when:
   - `className` does not match the heuristic in `isActivityWindowClass` (`OverlayLocationPolicy.kt:26-33`) — checks for `Activity`, `Launcher`, `.app.`, or `Home`. System Settings sub-screens reliably end with `Activity`, so this is unlikely the cause for the basic-mode capture.
   - `displayId != Display.DEFAULT_DISPLAY` (`OverlayLocationPolicy.kt:45`) — for VD mode this is intentional (we ignore the projected display). On TIRAMISU+ the event's `displayId` is read; on older OS it's forced to `DEFAULT_DISPLAY` (`AgentService.kt:195-199`). If a VD-mode device emits `displayId` for the real screen as something other than `0` (some OEMs do), the overlay never updates.

### Most likely diagnosis (ranked)

| Rank | Hypothesis | Evidence | Confirmation |
|---|---|---|---|
| 1 | `OverlayComposeHost.show()` throws on `addView` and the exception is swallowed (`OverlayComposeHost.kt:49-51`). Symptom matches: **neither** capsule nor island visible, but in-app capsule still works. | Both overlay types share the same `OverlayComposeHost` plumbing; both are missing → likely a shared failure surface, not a state-machine bug. | Re-run with `adb logcat -s OverlayComposeHost:* CapsuleOverlayHost:* IslandOverlayHost:*` while agent is mid-task in another app. |
| 2 | `showPreference` was ISLAND at the moment of capture (e.g. due to a stale `onMinimize`/`onViewerClosed` from a prior session, or because `onTaskStarted` fired *after* the window-state event due to ordering). | Decision tree only goes to `showCapsule=true` when `showPreference==CAPSULE` in A11y mode (`OverlayLocationPolicy.kt:99`). | Add `Log.d` in `applyVisibility` echoing `(platformMode, location, mode, hasActiveTask, showPreference)` and the resulting decision. |
| 3 | `handleWindowStateChanged` never observed a non-null `nextLocation` (e.g. `displayId` filter in `OverlayLocationPolicy.kt:45` discarded the event on this device). | Capture worker confirmed a11y was enabled (Setup Issue banner went away). | Same logging pass; confirm `Window changed` log line at `ServiceOverlayController.kt:367`. |

### Effort estimate
- **Diagnose with logcat**: S (≤30 min) — re-run `debug-run.sh --basic` with one filtered logcat and the answer falls out.
- **Fix once root cause is confirmed**: S–M depending on which hypothesis lands. Hypothesis 1 → propagate the exception or surface a UI hint (S). Hypothesis 2 → make `applyVisibility` re-derive `showPreference` from current `(mode, hasActiveTask)` rather than persisting it across sessions, and remove the side-channel resets in `onMinimize`/`onViewerClosed` (M). Hypothesis 3 → broaden the `displayId` filter (S).

### Proposed fix plan
1. Add diagnostic logging at `ServiceOverlayController.applyVisibility()` (`:149`) and at `OverlayComposeHost.show()`'s catch branch (`OverlayComposeHost.kt:49`) — make the catch re-throw in debug builds, or at minimum upgrade `Log.e` → `Log.wtf` so the exception/stack is unmissable.
2. Add `Log.i` in `CapsuleOverlayHost.show()` already exists at `:191` — confirm whether it ever prints during the failing run.
3. Re-run `./scripts/debug-run.sh --basic "Open Settings then go back to home"` and grep logcat for `CapsuleOverlayHost`, `OverlayComposeHost`, `ServiceOverlayController`.
4. Apply the targeted fix per the hypothesis that lands.

---

## (b) The "eye-icon affordance"

### Where it lives
The eye glyph the user mentioned is **`Icons.Rounded.Visibility` with `contentDescription = "Open viewer"`** in `CapsuleControlBar.kt:191-197` (rendered by `NavButtonCluster`). It dispatches `NavAction.OPEN_VIEWER`, which is wired through `SmartCapsuleSurface.onNavigate` (`SmartCapsuleSurface.kt:69`) to either:
- the in-app capsule (`ChatScreen.kt:160-164` → `onOpenViewer()` → `MainActivityUiHelpers.kt:31` → `VirtualDisplayViewerActivity`), or
- the system overlay capsule (`CapsuleOverlayHost.kt:138-142` → `onOpenViewer` callback → `AgentServiceViewerBridge.openViewer`).

### Why the capture worker couldn't find it
Visibility of that nav icon is gated by `NavSpec.from(...)` (`CapsuleRenderSpec.kt:179-205`):

```
showWatch = !controlBarHidden
    && platformMode != PlatformMode.ACCESSIBILITY      // ← gate
    && context != CapsuleContext.SCREEN_VIEWING
```

So the eye icon is **only rendered in VIRTUAL_DISPLAY mode**, and only when not already inside the viewer. In the basic-mode captures (`PlatformMode.ACCESSIBILITY`) the icon does not exist at all — the worker's UI dump correctly returned nothing. In the VD-mode in-app captures, the icon would appear inside the in-app capsule control bar (right cluster). It is **not** a way to "summon the overlay over another app" — its purpose is to open `VirtualDisplayViewerActivity` so the user can watch the projected display.

### Effort estimate
- **None** — the affordance exists and works as designed for VD mode. If the user's mental model is "I want a way to invoke the overlay from the main app while a task is running", that affordance does **not** exist today. Adding one (e.g. a "show overlay" button on the in-app chat header during an active task) is an S task: a single icon button that calls `overlayController.onIslandTapped()` or directly toggles `showPreference = CAPSULE`.

### Proposed (optional) fix plan
If the user wants a discoverable always-on path to the overlay regardless of platform mode, add a small `IconButton(Icons.Rounded.Visibility, contentDescription = "Show overlay")` to the chat top-bar or the in-app capsule control bar that, when tapped, exits `MainActivity` (back to home / previous app) and ensures `showPreference = CAPSULE`. Wiring point: `ChatScreen.kt` top bar, calling a new `viewModel.requestOverlayCapsule()` that bridges to `AgentService.instance?.overlayController?....`.

---

## (c) `VirtualDisplayViewerActivity` launches as black screen

### Root cause
`VirtualDisplayViewerActivity` is, by design, a **pure SurfaceView container** with no built-in content (`VirtualDisplayViewerActivity.kt:21-34`, `:107-120`). The class doc explicitly states "All interaction (stop, takeover, exit, minimize) is via the Smart Capsule overlay." The SurfaceView only displays frames once `AgentService.notifyViewerVisible(sv)` rewires the active `VirtualDisplayPlatform`'s output from its `ImageReader` to this `Surface` (called at `VirtualDisplayViewerActivity.kt:51,77`).

If the activity is launched directly via `am start ai.closepaw/.ui.viewer.VirtualDisplayViewerActivity` (the worker's reproduction), three preconditions are absent:

1. **No active VD session.** `AgentService.instance?.notifyViewerVisible(sv)` is a no-op when there is no `VirtualDisplayPlatform` running (`AgentServiceViewerBridge.kt` and the bridge's `platformProvider` returns `null`). With nothing piping frames into the surface, it stays at the `Color.Black` fill (`VirtualDisplayViewerActivity.kt:111`).
2. **No active task.** `onStart` calls `AgentService.instance?.onViewerOpened()` (`:80`) which sets `userLocation = VD_VIEWER` and `showPreference = CAPSULE`, then `applyVisibility()`. But `deriveOverlayVisibility` for VIRTUAL_DISPLAY with `!isActive` returns "show nothing" (`OverlayLocationPolicy.kt:113-119`) — so even the capsule cannot draw on top to hint that the viewer is empty.
3. **Platform mode mismatch.** If the device is currently in `PlatformMode.ACCESSIBILITY`, the activity has nothing to project anyway; the entire VD pipeline is dormant.

So the "black screen" is the expected output of this activity when launched out of band: it is a surface with no producer + an overlay decision that explicitly hides the capsule.

### Effort estimate
- **S** — add a placeholder/empty-state composable shown when `surfaceView` has no producer attached after a short timeout, plus a guard at `onCreate` that finishes the activity with a Toast when launched without an active session.

### Proposed fix plan
1. In `VirtualDisplayViewerActivity.onStart`, check `AgentService.instance?.getActiveSession() != null && currentPlatformMode == VIRTUAL_DISPLAY`. If false: `Toast.makeText(... "No virtual-display session is running.")` and `finish()`.
2. Otherwise, render a centered "Waiting for first frame…" composable inside the `Box(...)` at `:108-119` that fades out the moment `surfaceChanged` fires (`:150-154`). Avoids the impression that the activity itself is broken.
3. Optionally, document at the top of the file that direct launch is unsupported except mid-session (it's already implied — make it explicit).

---

## Trivial fixes applied

None. This was an investigation-only pass per the task brief; no runtime behaviour was changed. The most-likely-culprit fix for (a) (un-swallowing the `addView` exception) is one line but materially changes overlay error semantics, and is best done together with the verification logcat run in a follow-up task.

---

## Suggested follow-up task split

| Task | Owner | Size |
|---|---|---|
| Re-run basic & VD eval with debug logging on overlay path; identify which of the (a) hypotheses fires. | overlay owner | S |
| Fix per hypothesis (likely: surface `addView` exception + add `Log.wtf` in catch). | overlay owner | S–M |
| Optional: add explicit "open overlay capsule" affordance from main app (per (b)). | UI | S |
| Add empty-state guard + placeholder for direct `VirtualDisplayViewerActivity` launch (per (c)). | viewer owner | S |
