# Action Execution Direct Debug: Implementation Checklist

**Companion to**: `tech_design_claude.md`
**Status**: Ready for review

---

## Phase 1 Checklist (Core — Minimum Viable Debug Path)

### App-Side

- [ ] Create `app/src/debug/AndroidManifest.xml` with `ActionDebugReceiver` registration
- [ ] Create `app/src/main/kotlin/com/moonkey/androidagent/debug/ActionDebugReceiver.kt`
  - [ ] Parse intent extras into `ActionDebugRequest` data class
  - [ ] `goAsync()` + coroutine launcher pattern
  - [ ] Get `AgentAccessibilityService.instance` (verify static access works)
  - [ ] Pre-action: screenshot + a11y tree capture
  - [ ] Action dispatch via UIAction → platform.performAction()
  - [ ] Post-action: settle delay + screenshot + a11y tree capture
  - [ ] UIChangeDetector comparison
  - [ ] Write `result.json` + image files to device storage
  - [ ] Write `.done` sentinel file last
  - [ ] Error handling: write error result if service unavailable
- [ ] Create `app/src/main/kotlin/com/moonkey/androidagent/debug/DebugActionExecutor.kt`
  - [ ] Compose `NodeActionPerformer` + `AccessibilityGestureInjector` from service
  - [ ] `performAction(UIAction): ActionResult`
  - [ ] `captureSnapshot(): ScreenSnapshot` (a11y tree + screenshot)
  - [ ] Minimal dependencies — no PerceptionConfig, no TraceWriter
- [ ] Verify `AgentAccessibilityService` has accessible static instance reference

### Host-Side

- [ ] Create `scripts/action-test.sh`
  - [ ] Arg parsing for all action types (click, tap, long_press, scroll, swipe)
  - [ ] Device selection (reuse logic from debug-run.sh)
  - [ ] Output directory creation with tag support
  - [ ] Default mode: pre-screenshot → broadcast → poll `.done` → pull results → summary
  - [ ] `--adb` mode: pre-screenshot → adb input → post-screenshot
  - [ ] Summary output with action result, ui_changed, elapsed_ms
  - [ ] `--open` flag to auto-open screenshots

### Validation

- [ ] Test click at known coordinates (Settings → Wi-Fi)
- [ ] Test scroll down on a scrollable list
- [ ] Test swipe with explicit coordinates
- [ ] Test with `--adb` flag — confirm adb baseline works
- [ ] Verify result.json schema matches design
- [ ] Verify screenshots are captured correctly

---

## Phase 2 Checklist (Comparison & Executor Layer)

- [ ] `--compare` mode in action-test.sh
  - [ ] Run adb action first, capture before/after
  - [ ] Prompt user to reset screen
  - [ ] Run a11y action, capture before/after
  - [ ] Save both sets side by side
- [ ] `dump-tree` command
  - [ ] Broadcast with `action=dump_tree`
  - [ ] Format options: json (structured) and prompt (LLM format)
  - [ ] Script pulls and displays tree
- [ ] Executor layer support (`--layer executor`)
  - [ ] Broadcast extras for element_index, text, text_index
  - [ ] Receiver builds snapshot, runs TargetResolver + Executor
  - [ ] Result includes resolved_point, element_info, warnings
- [ ] `--use-node` flag for click (true=ClickNodeAt, false=TapAt)

---

## Phase 3 Checklist (Enhancements, On-Demand)

- [ ] VirtualDisplay platform support (`--platform vd`)
- [ ] Batch runner script for regression suites
- [ ] `--device2` flag for parallel device comparison
- [ ] `--auto-reset` with configurable reset command

---

## File Inventory

### New Files

| File | Phase | Lines (est) |
|------|-------|-------------|
| `app/src/debug/AndroidManifest.xml` | 1 | ~10 |
| `app/src/main/kotlin/.../debug/ActionDebugReceiver.kt` | 1 | ~150 |
| `app/src/main/kotlin/.../debug/DebugActionExecutor.kt` | 1 | ~80 |
| `scripts/action-test.sh` | 1 | ~250 |

### Modified Files

| File | Phase | Change |
|------|-------|--------|
| None in Phase 1 | — | Zero production code changes |

---

## Quick Smoke Test (Post Phase 1)

```bash
# 1. Build and install debug APK
./scripts/setup.sh

# 2. Open Settings on device
adb shell am start -a android.settings.SETTINGS
sleep 2

# 3. Run action test
./scripts/action-test.sh click --x 540 --y 400 --tag smoke

# 4. Verify output
ls debug-output/action-test/smoke/
# Expected: before.png  after.png  pre_tree.json  post_tree.json  result.json

cat debug-output/action-test/smoke/result.json | jq .
# Expected: status=Success, ui_changed=true/false, attempt_trail populated

# 5. Compare with adb
./scripts/action-test.sh tap --x 540 --y 400 --adb --tag smoke_adb

# 6. Visual comparison
open debug-output/action-test/smoke/after.png
open debug-output/action-test/smoke_adb/after_adb.png
```
