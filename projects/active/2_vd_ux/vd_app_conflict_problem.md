# VD App Conflict: Same App on Both Displays Causes Playback Interference

Date: 2026-02-23
Status: Open
Severity: Low (UX limitation for concurrent same-app usage)

## Problem

When the agent completes a task on the Virtual Display (VD) — e.g., playing
a YouTube video — the playback persists correctly in Hot Idle mode. However,
if the user then opens the same app (YouTube) on the main display (display 0),
one of two things happens:

1. The VD instance loses its activity (Android moves the single-instance
   activity to the main display), stopping playback.
2. The main-display instance and VD instance interfere with each other
   (e.g., only one can hold the audio focus / media session at a time).

The result is that VD playback is not as robust as a foreground app on the
main display. The user cannot freely use the same app on both displays
simultaneously.

## Reproduction

1. Start an agent task in VD mode: "open YouTube and play a song."
2. Agent completes successfully — video is playing on VD.
3. On the main screen, tap the YouTube app icon.
4. Observe: VD playback stops or the YouTube activity migrates to display 0.

## Root Cause

Android's activity launch model is fundamentally single-instance for most
apps. Key factors:

### 1. `singleTask` / `singleInstance` launch modes

Most apps (including YouTube) declare their main activity with
`android:launchMode="singleTask"` or equivalent. When the user launches
YouTube on display 0, Android's `ActivityTaskManagerService` finds the
existing task (running on the VD) and moves it to the requesting display
rather than creating a new instance.

### 2. Audio focus is system-wide

Android's `AudioManager` audio focus is not per-display. When YouTube on
display 0 gains audio focus, the VD instance loses it and pauses playback.
Even if both instances could coexist as separate tasks, only one can hold
`AUDIOFOCUS_GAIN` at a time.

### 3. Media session singleton

YouTube (and most media apps) maintains a single `MediaSession`. When the
app's activity moves or a new instance starts, the media session is
re-bound, interrupting the previous playback.

## Why the VD lifecycle fix doesn't fully solve this

The lifecycle fix (commit `659747c`) ensures:
- VD is **not destroyed** between tasks (Hot Idle mode).
- Apps launched on VD **continue running** after agent completion.

This works correctly — YouTube keeps playing after the agent finishes.
The problem only surfaces when the user independently launches the same
app on display 0, triggering Android's single-instance activity resolution.

## Confirmed behavior from trace analysis

Debug run `run_20260223_180318` confirmed:
- Agent does NOT press HOME or BACK after completing the YouTube task.
- Video was playing at 0:02 of 5:38 when agent called `complete_task`.
- The debug-run script sends a `STOP_AGENT` broadcast which destroys
  the VD entirely — this is specific to debug-run, not normal app flow.
- In normal app flow (Hot Idle), playback persists correctly.

The issue is exclusively about same-app cross-display conflict, not about
agent behavior or VD lifecycle.

## Impact

- **User experience**: If the user opens the same app on the main screen
  that the agent was using on the VD, playback or state may be disrupted.
  This is unintuitive — the user expects VD to be independent.
- **Agent behavior**: No impact. Agent tasks complete correctly.
- **Frequency**: Only occurs when the user opens the exact same app on
  the main display. Different apps are unaffected.

## Potential Mitigations

### Option A: Launch VD apps with multi-instance flags

Use `FLAG_ACTIVITY_MULTIPLE_TASK | FLAG_ACTIVITY_NEW_DOCUMENT` when
launching apps on the VD, forcing Android to create a separate task
even for `singleTask` activities.

**Trade-offs**:
- (+) True isolation — two independent YouTube instances.
- (-) Not all apps support multi-instance. Some crash or show errors.
- (-) YouTube specifically may detect and block multi-instance.
- (-) Resource-intensive (two full app processes).

### Option B: Warn user about same-app conflict

When the agent is using an app on the VD, show a notification or warning
if the user launches the same app on display 0.

**Trade-offs**:
- (+) No behavioral change, just user awareness.
- (-) Annoying UX — users don't want warnings.
- (-) Detection is unreliable (race between app launch and warning).

### Option C: Accept as known limitation

Document the behavior. Most users won't open the same app on both
displays simultaneously. When they do, the workaround is straightforward:
restart the agent task or re-launch the app on the VD.

**Trade-offs**:
- (+) No code change, no regression risk.
- (-) Imperfect UX for same-app concurrent usage.

## Current Decision

Option C — accepted as known limitation. The VD is designed for
background agent execution, not as a full secondary display for user
interaction. Same-app conflicts are an inherent Android platform
constraint that cannot be cleanly solved at the app level.

Revisit if multi-instance app support (`android:resizeableActivity`,
`FLAG_ACTIVITY_MULTIPLE_TASK`) becomes more reliable across target apps.
