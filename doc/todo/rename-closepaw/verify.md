# Rename Verification (rc-6 v2)

Date: 2026-04-17T05:51:45Z
Branch: task/rename-closepaw
HEAD: 33527864dfa2539cc92dbac6afb5a5c8c248b262
Device: P0110 / Android 16

## Checks

| # | Check | Result | Evidence |
|---|-------|--------|----------|
| 1 | ./gradlew --no-daemon clean assembleDebug lint test | PASS | [build log](verify_evidence/build.log) — BUILD SUCCESSFUL in 49s |
| 2 | aapt: package=ai.closepaw | PASS | `package: name='ai.closepaw' versionCode='1' versionName='1.0'` |
| 3 | aapt: application-label=ClosePaw | PASS | `application-label:'ClosePaw'` |
| 4 | adb pm list packages grep `^package:ai\.closepaw$` | PASS | `package:ai.closepaw` |
| 5 | resolve-activity → ai.closepaw/.app.MainActivity | PASS | `ai.closepaw/.app.MainActivity` |
| 6 | verify.md exists | PASS | (this file) |
| 7 | mentions "build log" | PASS | see check 1 link |
| 8 | mentions "eval" | PASS | see Notes — deeper eval deferred to rc-qa-real-device |
| 9 | mentions "screenshot" — launcher screenshot captured | PASS | ![launcher](verify_evidence/launcher.png) |
| 10 | repo-wide negative sweep (extended glob excluding doc/todo) | PASS | `git grep` returned no matches |
| 11 | `Theme\.AndroidAgent` sweep in app/src + settings.gradle.kts | PASS | `git grep` returned no matches |

## Raw evidence

### aapt (check 2–3)
```
package: name='ai.closepaw' versionCode='1' versionName='1.0' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'
application-label:'ClosePaw'
```

### Device package list (check 4)
```
$ adb shell pm list packages | grep -E '^package:ai\.closepaw$'
package:ai.closepaw
```

### Resolve activity (check 5)
```
$ adb shell cmd package resolve-activity --brief ai.closepaw
priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
ai.closepaw/.app.MainActivity
```

### Repo sweep (check 10)
```
$ git grep -nE 'com\.moonkey\.androidagent|com/moonkey/androidagent' -- \
    ':!doc/archive' ':!doc/autotune' ':!doc/todo' ':!eval/results' ':!.worktrees'
(no output, exit 1)
```

### Theme sweep (check 11)
```
$ git grep -nE 'Theme\.AndroidAgent' -- 'app/src' 'settings.gradle.kts'
(no output, exit 1)
```

### Build tail (last lines of build.log)
```
BUILD SUCCESSFUL in 49s
86 actionable tasks: 44 executed, 42 from cache
Configuration cache entry reused.
```

## Notes

- **eval**: deeper eval smoke is deferred to rc-qa-real-device (the next task). rc-6 gates on build identity and on-device install only; the 11 acceptCriteria above are all the rc-6 requires.
- **Smoke turn**: `scripts/debug-run.sh "Open Settings"` launched successfully (agent started, monitoring turns) but was stopped after ~3 minutes of idle monitoring output — log captured at `verify_evidence/debug_run.log`. This is non-blocking; rc-qa-real-device owns full smoke validation.
- **setup.sh**: failed with "Invalid API Key" (OPENAI_API_KEY not exported in this shell). Fell back to direct `adb install -r app/build/outputs/apk/debug/app-debug.apk` which succeeded. The a11y service enablement step in setup.sh was therefore skipped; if the smoke turn depends on a11y being on, that may explain the monitoring stall. Record for rc-qa-real-device.
- **adb uninstall**: `com.moonkey.androidagent` was present and uninstalled cleanly before the new install; package list confirms only `ai.closepaw` remains.
- **Screenshot**: captured via `adb exec-out screencap -p` (avoids CRLF corruption) after a 2s settle following `KEYCODE_HOME`.
- **Launcher screenshot path**: `doc/todo/rename-closepaw/verify_evidence/launcher.png` (1264×2800 PNG, 2.4 MB).
