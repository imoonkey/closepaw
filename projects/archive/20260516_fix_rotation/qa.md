# QA: fix-active-turn-survives-rotation

## Result

PASS — agent task continues uninterrupted across a full landscape↔portrait rotation cycle. No `JobCancellationException`, no `MainActivity` recreation, task reaches `GOAL_ACHIEVED`.

## Repro Steps

Device: P0110 (`100.64.43.95:5555`), Android 15-class ROM, portrait by default with auto-rotate off.

1. Build + install the fixed debug APK:
   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. Allow `adb wm` to actually drive rotation on this device (auto-rotate is off and the launcher pins portrait):
   ```bash
   adb shell wm fixed-to-user-rotation enabled
   adb shell wm set-ignore-orientation-request false
   adb shell wm user-rotation lock 0   # baseline portrait
   ```

3. Force-stop the app, clear logcat, dispatch a multi-step task that takes ~30s end-to-end (so an LLM stream is in flight when we rotate):
   ```bash
   adb shell am force-stop ai.closepaw
   adb logcat -c
   ANDROID_SERIAL=100.64.43.95:5555 ./scripts/debug-run.sh \
     "Open Settings, then go back home, then open Settings again, then back home, then open Settings once more"
   ```

4. Once `AgentService: 🧠 Thinking...` appears in logcat (LLM stream active), rotate:
   ```bash
   adb shell wm user-rotation lock 1   # → ROTATION_90 (landscape)
   sleep 3
   adb shell wm user-rotation lock 0   # → ROTATION_0 (portrait)
   ```

5. Wait for `Task completed!` from `debug-run.sh`. Confirm logcat shows `TaskCompleted, outcome: GOAL_ACHIEVED` with no `JobCancellationException` and no `MainActivity` `onDestroy`/`onCreate` lines between the two rotation events.

6. Restore the device:
   ```bash
   adb shell wm user-rotation free
   adb shell wm set-ignore-orientation-request reset
   adb shell wm fixed-to-user-rotation default
   ```

## Evidence

Run: `debug-output/run_20260515_231541/` (copied to `evidence/`).

Rotation cycle in `logcat_full.log`:

```
05-16 02:17:19.793 ActivityTaskManager: Config changes ... mRotation=ROTATION_90 (land)
05-16 02:17:22.859 ActivityTaskManager: Config changes ... mRotation=ROTATION_0 (port)
```

Agent activity bracketing the rotation:

```
05-16 02:17:13.113 AgentService: 🧠 Thinking...           ← LLM streaming
05-16 02:17:17.832 AgentService: 🧠 Thinking...           ← still streaming
05-16 02:17:19.793 [rotation → landscape]
05-16 02:17:22.859 [rotation → portrait]
05-16 02:17:23.526 AgentService: 🧠 Thinking...           ← survived
05-16 02:17:36.746 AgentService: ✓ complete_task executed
05-16 02:17:36.746 AgentService: ✅ Goal achieved!
05-16 02:17:36.775 AgentService: Task completed: ... outcome: GOAL_ACHIEVED
```

Verification counts:

```
$ grep -c JobCancellation evidence/logcat_after_fix.log    → 0
$ grep -c TaskCompleted    evidence/logcat_after_fix.log   → 2  (turn + session)
$ grep 'D MainActivity:' evidence/logcat_after_fix.log | grep -iE 'onCreate|onDestroy'  → empty
```

Build / lint / unit test:

```
$ ./gradlew --no-configuration-cache assembleDebug test lint
BUILD SUCCESSFUL in 2m 26s
```

## What Was Fixed

| Fix | File | Effect |
|---|---|---|
| A — `configChanges` on MainActivity | `app/src/main/AndroidManifest.xml` | Rotation, screen size, keyboard, and UI mode changes no longer destroy/recreate the activity. |
| B — `AgentSession` scope ownership | `AgentService.kt`, `MainActivity.kt`, `OnboardingDemoController.kt` | All three `AgentSession.create` / `reload` call sites now bind to `AgentService.serviceScope` (renamed from `scope`, exposed `internal`). Session lifetime is decoupled from any UI lifecycle. |

Defense-in-depth: even if some future config-change variant slips past the manifest list, the session itself now lives in the long-lived service scope and won't be cancelled by activity teardown.
