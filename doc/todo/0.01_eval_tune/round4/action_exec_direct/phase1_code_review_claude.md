# Phase 1 Code Review -- Action Execution Direct Debug Harness

**Reviewer**: Claude (Opus 4)
**Date**: 2026-02-19
**Verdict**: CHANGES REQUESTED

---

## CODE REVIEW: scripts/action-test.sh

### [CRITICAL] Stale `.done` sentinel causes race condition -- script reads previous run's results

**Lines**: 278-292
**Problem**: The host script polls for `.done` immediately after sending the broadcast. The app-side `prepareOutputDir()` deletes the old `.done`, but broadcast delivery is asynchronous. If the script's poll loop fires before the receiver's `prepareOutputDir()` executes, it finds the stale `.done` from a previous run and immediately pulls old results, reporting them as current.

Timeline of the bug:
```
Run 1: broadcast -> execute -> write result.json -> write .done (success)
Run 2: broadcast (async, not yet delivered)
Run 2: poll finds Run 1's .done -> pulls Run 1's result.json -> WRONG
Run 2: receiver finally processes broadcast, deletes .done, does work, writes new .done
        (but script already exited with stale data)
```

This is not a theoretical race. `am broadcast` returns before the receiver processes the intent. On a loaded device, the window is easily hundreds of milliseconds.

**Fix**: Delete the `.done` sentinel on the device BEFORE sending the broadcast:
```bash
adb_cmd shell "rm -f '$DEVICE_OUTPUT_DIR/.done'"
```
Add this line at the start of `run_a11y_action()`, before `adb_screencap`.

---

### [HIGH] `eval` on user-constructed string -- fragile and injection-prone

**Lines**: 229, 256, 276
**Problem**: Broadcast extras are accumulated via string concatenation into `$extras`, then executed via `eval "adb_broadcast $extras"`. This is brittle for two reasons:

1. Shell injection: If any parameter value contained shell metacharacters (unlikely with numeric coords, but `$ACTION` comes from argv), `eval` would interpret them. Quoting discipline inside the string is fragile.
2. `adb_broadcast()` uses `$*` (line 66), which merges all positional arguments using IFS. Combined with `eval`, the quoting semantics are hard to reason about and easy to break in future edits.

**Fix**: Use an array to accumulate extras and pass them directly without `eval`:
```bash
local -a extras=(--es action "$ACTION" --ei settle_ms "$SETTLE_MS")
# ...
extras+=(--ei x "$X" --ei y "$Y")
# ...
adb_broadcast "${extras[@]}"
```
And change `adb_broadcast` to use `"$@"` instead of `$*`.

---

### [HIGH] Broadcast response suppressed with `2>/dev/null` -- silent failure on unregistered receiver

**Line**: 66
**Problem**: `adb_broadcast()` redirects stderr to `/dev/null`. If the accessibility service is not running or the receiver is not registered, `am broadcast` prints a helpful error to stdout/stderr. By silencing it, the user gets no feedback -- they wait the full 15 seconds for the poll timeout, then see a generic "Timed out" error with no clue about the root cause.

**Fix**: Do not suppress stderr. Optionally, capture and display the broadcast result:
```bash
adb_broadcast() {
    adb_cmd shell "am broadcast -a $ACTION_INTENT -p $PACKAGE $*"
}
```
At minimum, remove `2>/dev/null`.

---

### [MEDIUM] `bc` dependency not checked

**Line**: 206
**Problem**: `sleep "$(echo "scale=3; $SETTLE_MS / 1000" | bc)"` assumes `bc` is installed. On minimal macOS or Linux environments, this could fail. Since `set -euo pipefail` is active, the script will abort with a cryptic "bc: command not found" error.

**Fix**: Either check for `bc` at script start, or use a simpler approach:
```bash
sleep "$(awk "BEGIN{printf \"%.3f\", $SETTLE_MS/1000}")"
```
`awk` is POSIX-guaranteed.

---

### [MEDIUM] `open` command is macOS-only

**Lines**: 214, 334, 356-357
**Problem**: The `--open` flag uses `open` which is macOS-specific. On Linux, this silently fails (caught by `|| true`), making `--open` a no-op without feedback.

**Fix**: Add platform detection:
```bash
open_file() {
    if command -v open &>/dev/null; then open "$@"
    elif command -v xdg-open &>/dev/null; then xdg-open "$@"
    else warn "No file opener found"
    fi
}
```
Or document macOS-only in the help text.

---

### [MEDIUM] No validation of numeric arguments

**Lines**: 119-133
**Problem**: `--x`, `--y`, `--start-x`, etc. accept any string. Non-numeric values (e.g., `--x foo`) pass through to `adb shell input tap foo 540`, which silently fails, or to intent extras where `--ei x foo` causes `am broadcast` to error. No early feedback to the user.

**Fix**: Validate immediately after parsing:
```bash
is_int() { [[ "$1" =~ ^-?[0-9]+$ ]]; }
# After parse loop:
[[ -n "$X" ]] && ! is_int "$X" && { err "--x must be an integer"; exit 1; }
```

---

### [LOW] Output directory not cleaned between runs

**Lines**: 146-151
**Problem**: When `--tag` is reused, old files (e.g., `pre_tree.json`) from a previous run persist in the output directory. If the new run uses `--no-tree`, the old tree files remain and could mislead analysis.

**Fix**: Add `rm -f "$OUT_DIR"/*` before `mkdir -p`.

---

## CODE REVIEW: app/src/main/kotlin/com/moonkey/androidagent/debug/ActionDebugReceiver.kt

### [MEDIUM] Unstructured coroutine scope -- no cancellation on service teardown

**Lines**: 41
**Problem**: `CoroutineScope(Dispatchers.Main + SupervisorJob())` creates a fire-and-forget scope with no parent. If `AgentService` is destroyed while the debug action is executing, this coroutine continues running with a reference to the destroyed service. The `service.rootInActiveWindow` call would then throw or return invalid data.

The 10-second timeout bounds the blast radius, and this is debug-only code, so this is not critical. But it violates structured concurrency.

**Fix**: Use the service's own scope (already `CoroutineScope(Dispatchers.Main + SupervisorJob())`) so the coroutine is cancelled when the service is destroyed:
```kotlin
val service = AgentService.instance ?: ...
val pendingResult = goAsync()
service.scope.launch {
    try { ... } finally { pendingResult.finish() }
}
```
This requires exposing or passing the scope, so alternatively, store and cancel the scope in the receiver's companion object on unregister. Current approach is acceptable for Phase 1 given the timeout guard.

---

### [LOW] `goAsync()` 30-second system limit undocumented

**Lines**: 40-54
**Problem**: `goAsync()` grants approximately 30 seconds before the system kills the broadcast. The code sets a 10-second internal timeout (good), but this relationship is not documented. A future developer might increase `TIMEOUT_MS` to 60 seconds without realizing the system would kill the operation at 30.

**Fix**: Add a comment:
```kotlin
// goAsync() grants ~30s before system kills the broadcast.
// Our timeout must be well under that.
private const val TIMEOUT_MS = 10_000L
```

---

## CODE REVIEW: app/src/main/kotlin/com/moonkey/androidagent/debug/DebugActionExecutor.kt

### [HIGH] `writeErrorResult` uses broadcast receiver's restricted `Context` -- potential `getExternalFilesDir` failure

**Lines**: 30-31 (ActionDebugReceiver.kt), 282-292 (DebugActionExecutor.kt)
**Problem**: In `ActionDebugReceiver.onReceive()`, when `service == null`, the code calls `DebugActionExecutor.writeErrorResult(context, ...)` using the broadcast receiver's `context`. This is a restricted `ReceiverRestrictedContext` that may have limitations on certain operations.

However, `getExternalFilesDir()` delegates to the application context's file storage, so this is likely safe in practice. The real concern is that this `context` may point to a different external files directory than the one the service would use (if the contexts differ). In practice they resolve to the same path, but the coupling is implicit.

**Fix**: Use `context.applicationContext` to be explicit:
```kotlin
DebugActionExecutor.writeErrorResult(context.applicationContext, "...")
```

**Severity downgrade**: On reflection, this is practically safe. Downgrading to MEDIUM.

---

### [MEDIUM] Result JSON deviates from design schema -- missing hash values, `attempt_trail`

**Lines**: 197-221
**Problem**: The design doc specifies `a11y_changed: boolean`, `a11y_hash_before`, `a11y_hash_after`, and `attempt_trail` in `result.json`. The implementation:
- Replaces hashes with `element_count_before`/`element_count_after` -- less useful for debugging. Two snapshots can have identical element counts but completely different content (e.g., scrolled list).
- Omits `attempt_trail` entirely.
- Omits `a11y_changed` boolean.

The element counts are a weaker signal than hashes for diagnosing false successes. The hash values would directly show whether the fingerprint changed, which is the whole point of this harness.

**Fix**: Expose `UiChangeDetector`'s fingerprint values in the result:
```kotlin
put("a11y_hash_before", pre?.let { UiChangeDetector.fingerprint(it) })
put("a11y_hash_after", post?.let { UiChangeDetector.fingerprint(it) })
```
This requires making `fingerprint()` public (currently private). Alternatively, keep element counts AND add the `a11y_changed` boolean from the verdict.

`attempt_trail` can be deferred to Phase 2 since P1 only does platform-layer dispatch which has no trail.

---

### [MEDIUM] `prepareOutputDir` does not handle subdirectories

**Lines**: 252-257
**Problem**: `dir.listFiles()?.forEach { it.delete() }` only deletes files and empty directories. If a subdirectory with contents ever exists in `latest/`, `File.delete()` silently returns false and the directory persists. This is not a problem today (no subdirectories are created), but it is fragile.

**Fix**: Use a recursive delete:
```kotlin
if (dir.exists()) dir.deleteRecursively()
dir.mkdirs()
```

---

### [MEDIUM] `captureSnapshot` swallows all exceptions silently

**Lines**: 172-183
**Problem**: Any exception from `Perceptor.snapshot()` is caught and returns `null`. The caller then sets `captureTree` results to null and the verdict becomes `Unverifiable`. This is correct behavior, but the `Log.w` is the only signal. If the Perceptor consistently fails (e.g., due to a missing node recycling), the user sees `verdict: unverifiable` in the result JSON with no explanation.

**Fix**: Add the exception message to the result JSON when snapshot capture fails, so the user can see it without checking logcat:
```kotlin
// Store capture errors in a list, include in result JSON
```

---

### [LOW] Design says `long_press` maps to `UIAction.Swipe(x, y, x, y, duration_ms)` but implementation uses `UIAction.LongPressAt`

**Lines**: 144-149
**Problem**: The design doc (line 235) specifies `long_press -> UIAction.Swipe(x, y, x, y, duration_ms) // swipe-to-self`. The implementation correctly uses `UIAction.LongPressAt(x, y, duration_ms)` which is a proper dedicated action type with gesture injection via `injectLongPress()`.

This is actually the RIGHT call -- `LongPressAt` is a cleaner abstraction than a swipe-to-self hack. But the design doc should be updated to match.

**Fix**: Update design doc line 235 to: `long_press -> UIAction.LongPressAt(x, y, duration_ms)`.

---

### [LOW] `LongClickNodeAt` is handled in `performAction` but not exposed via `parseAction`

**Lines**: 97-98, 127-167
**Problem**: `performAction()` handles `UIAction.LongClickNodeAt` (node-based long click via `ACTION_LONG_CLICK`), but `parseAction()` only maps `long_press` to `UIAction.LongPressAt` (gesture-based). There is no way to test node-based long click via the debug harness.

This is acceptable for Phase 1 (no `--use-node` equivalent for long press in the design), but worth noting for Phase 2.

---

### [LOW] `isoTimestamp()` creates a new `SimpleDateFormat` on every call

**Lines**: 303-307
**Problem**: A new `SimpleDateFormat` is allocated per invocation. This is negligible for a debug tool called rarely, but it is wasteful.

**Fix**: Make it a `companion object` val:
```kotlin
private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
```
Note: `SimpleDateFormat` is not thread-safe, but since this runs on `Dispatchers.Main` (single-threaded), it is safe here. Alternatively use `java.time.Instant.now().toString()` which is thread-safe and simpler.

---

## CODE REVIEW: app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceReceiverHelpers.kt

### No issues found.

Clean, correct, follows the existing `registerDebugStopReceiverIfNeeded` pattern exactly. The `BuildConfig.DEBUG` gating, Tiramisu version check, `RECEIVER_EXPORTED` flag, `@Suppress` annotation, and `IllegalArgumentException` catch on unregister are all correct.

The `ACTION_DEBUG_EXEC` constant is defined at the bottom of this file and imported by `ActionDebugReceiver.kt` -- clean separation.

---

## CODE REVIEW: app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt

### No issues found in the changes.

The three changes are minimal and correct:
1. **Import** (line 12): `import com.moonkey.androidagent.debug.ActionDebugReceiver` -- clean.
2. **Field** (line 108): `private val debugExecReceiver = ActionDebugReceiver()` -- instantiated eagerly as a `val`, matching the `stopReceiver` pattern. Not conditionally gated by `BuildConfig.DEBUG` at the field level, but the registration functions are gated, so the receiver is inert in release builds (never registered, never receives intents). The cost is one unused object allocation in release -- negligible.
3. **Register/unregister** (lines 174, 222): Called in `onServiceConnected()` and `onDestroy()` respectively, matching the `stopReceiver` lifecycle exactly.

---

## Summary

| Severity | Count | Files |
|----------|-------|-------|
| CRITICAL | 1 | `scripts/action-test.sh` |
| HIGH | 2 | `scripts/action-test.sh` |
| MEDIUM | 5 | `scripts/action-test.sh`, `DebugActionExecutor.kt`, `ActionDebugReceiver.kt` |
| LOW | 5 | `scripts/action-test.sh`, `DebugActionExecutor.kt` |

### Critical Issues
1. **Stale `.done` race condition** -- script reads previous run's results on second invocation

### High Issues
1. **`eval` on user-constructed string** -- fragile, injection-prone broadcast dispatch
2. **Broadcast stderr suppressed** -- silent 15-second timeout on unregistered receiver

### What's Good
- Clean adherence to KISS -- no over-engineering, no unnecessary abstractions
- `performAction()` exhaustively covers all `UIAction` sealed variants -- no missing branches
- File sizes well under 400-line limit (63 + 322 + 51 = 436 across three new files)
- `BuildConfig.DEBUG` gating is correct and follows existing patterns
- `goAsync()` + timeout + error handling in the receiver is solid
- `UiChangeDetector` reuse is smart -- leverages production change detection logic
- Intent parsing is defensive (returns null on bad input, validates ranges)
- Agent session rejection check prevents debug/agent interference
- Design-to-implementation fidelity is high overall

### Recommendation: **CHANGES REQUESTED**

The stale `.done` race condition is a ship-blocker -- it will cause incorrect results on back-to-back runs, which is the primary usage pattern for this tool. The fix is a one-liner (`rm -f .done` before broadcast). The `eval` issue should also be addressed before this becomes a pattern copied into future scripts.

After fixing the CRITICAL and at least one HIGH: approve.
