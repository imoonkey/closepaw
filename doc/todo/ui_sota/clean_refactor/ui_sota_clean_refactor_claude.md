# UI SOTA Clean Refactor — Design

**Author**: Claude (Opus 4)
**Date**: 2026-02-22
**Status**: Draft
**Scope**: Code review + refactoring proposal across capsule, overlay touch, session reload, supplement history, and action executors.

---

## 1. Executive Summary

After reading all four `align/design` specs and the full source of every file they touch, I found **six** concrete problems ranked by severity. The codebase is structurally sound — the state machines are correct, the overlay visibility system works, the session lifecycle is right. What's wrong is **accidental duplication** from iterative feature work and **one real bug** in history compression.

| # | Problem | Type | Impact | Lines Saved |
|---|---------|------|--------|-------------|
| R1 | ClickExecutor / LongPressExecutor are 90% identical | Duplication | HIGH | ~140 |
| R2 | `compress()` can drop supplement user messages | Bug (P0) | HIGH | +5 |
| R3 | `handleUserInput()` uses sequential ifs instead of when | Clarity | MEDIUM | ~10 |
| R4 | `PersistedHistoryItem` mirrors `ResponseItem` field-by-field | Duplication | MEDIUM | ~100 |
| R5 | `MainActivity.ensureSessionAndSend()` is 150 lines, 4+ nesting | God method | MEDIUM | ~200 (extract) |
| R6 | Dead code: `channelCloseScheduled`, duplicated `isSemantic()`, etc. | Cruft | LOW | ~15 |

Total: ~465 lines removed or extracted. Zero behavior change (except R2 which is a bug fix).

---

## 2. What's Already Clean (Don't Touch)

Before listing problems, credit where due — these should **not** be refactored:

- **`CapsuleStateHolder`** (285 lines): Clean state machine. Guards are correct. `onTaskCompleted` vs `onSessionEnded` have intentionally different behavior (Done+autoHide vs Hidden). Leave it.
- **`ServiceOverlayController`** (361 lines): The `applyVisibility()` single-authority pattern is good design. The explicit calls after each event are clearer than making it reactive. Leave it.
- **`ChatEventReducer`** (193 lines): The `insertUserTurn()` shared method is the right fix for supplement ordering. Clean. Leave it.
- **`OverlayLocationPolicy`** (157 lines): Pure functions, well-tested. Leave it.
- **`SessionCheckpointCoordinator`** (124 lines): `flushIdleReady`/`flushClosed` differ by only the enum value. Merging them saves 8 lines but hurts readability. Leave it.
- **`ActionPriorityOrder`** (30 lines): Minimal, correct. Leave it.

---

## 3. R1 — Eliminate Action Executor Duplication

### Problem

`ClickExecutor.kt` (174 lines) and `LongPressExecutor.kt` (181 lines) are **90% identical code**. Diff:

| Section | Click | LongPress | Identical? |
|---------|-------|-----------|------------|
| Target resolution | Lines 31-46 | Lines 30-45 | YES |
| Bounds check | Lines 48-55 | Lines 47-54 | YES |
| Channel loop structure | Lines 57-108 | Lines 57-112 | YES (structure) |
| UIAction constructed | TapAt / ClickNodeAt | LongPressAt / LongClickNodeAt | NO |
| `buildSuccessOutcome` | Lines 116-140 | Lines 126-151 | YES (99%) |
| `isWithinDisplayBounds` | Lines 142-146 | Lines 120-124 | VERBATIM |
| `formatSuccess/Failure` | Lines 148-170 | Lines 153-177 | YES (structure) |
| `Target.isSemantic()` | Line 172 | Line 179 | VERBATIM |

`ScrollExecutor.kt` (138 lines) shares the channel-loop + post-capture pattern but differs in target resolution (area vs point). It's similar enough to benefit from shared infrastructure but different enough to stay its own class.

### Root Cause

Copy-paste from ClickExecutor when LongPressExecutor was created. No common abstraction extracted afterward.

### Fix: Extract `executeFallbackChain` + shared utilities

**Step 1**: Move `isSemantic()` to `Target` itself.

```kotlin
// Target.kt
sealed class Target {
    data class Coordinate(val x: Int, val y: Int) : Target()
    data class ElementIndex(val index: Int) : Target()
    data class Text(val text: String) : Target()

    val isSemantic: Boolean get() = this is ElementIndex || this is Text
}
```

**Step 2**: Extract shared point-action infrastructure.

```kotlin
// PointActionExecutor.kt — ~80 lines, replaces ~350 lines across Click + LongPress

/**
 * Shared executor for point-targeted actions with fallback chains.
 * Used by click and long_press. Scroll stays separate (area-targeted).
 */
internal object PointActionExecutor {

    data class Channel(
        val name: String,
        val requiresSemantic: Boolean,
        val toAction: (Point) -> UIAction,
    )

    suspend fun execute(
        channels: List<Channel>,
        target: Target,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        isCancelled: () -> Boolean,
        settleMs: Long,
        formatSuccess: (Point, String) -> String,
        formatFailure: (Point, String, String) -> String,
        targetResolver: TargetResolver = TargetResolver,
    ): ActionOutcome {
        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled")

        // 1. Resolve
        val (point, warnings) = when (val r = targetResolver.resolve(target, snapshot)) {
            is TargetResolver.ResolveResult.Resolved -> r.point to r.warnings
            is TargetResolver.ResolveResult.NotFound ->
                return ActionOutcome.Failed(r.reason, emptyList())
        }

        // 2. Bounds check
        val display = platform.getDisplayInfo()
        if (display.widthPixels > 0 && display.heightPixels > 0 &&
            (point.x !in 0 until display.widthPixels || point.y !in 0 until display.heightPixels)
        ) {
            return ActionOutcome.Failed(
                "Target (${point.x},${point.y}) outside ${display.widthPixels}x${display.heightPixels}",
                emptyList()
            )
        }

        // 3. Fallback chain
        val trail = mutableListOf<String>()
        var lastFail = "" to ""

        for (ch in channels) {
            if (isCancelled()) return ActionOutcome.Cancelled("Cancelled")
            if (ch.requiresSemantic && !target.isSemantic) continue

            when (val result = platform.performAction(ch.toAction(point))) {
                is ActionResult.Success -> {
                    trail += "${ch.name}: success"
                    return buildSuccess(point, ch.name, warnings, trail, settleMs, platform,
                        formatSuccess)
                }
                is ActionResult.Cancelled -> return ActionOutcome.Cancelled(result.reason)
                is ActionResult.Failure -> {
                    lastFail = ch.name to result.reason
                    trail += "${ch.name}: ${result.reason}"
                }
            }
        }

        return ActionOutcome.Failed(
            formatFailure(point, lastFail.first, lastFail.second) + warnings.format(),
            trail
        )
    }

    private suspend fun buildSuccess(
        point: Point, channel: String, warnings: List<String>,
        trail: List<String>, settleMs: Long, platform: AndroidPlatform,
        format: (Point, String) -> String,
    ): ActionOutcome.Success {
        delay(settleMs)
        val post = runCatching { platform.captureScreen() }.getOrNull()
        val captureWarning = runCatching { platform.captureScreen() }
            .exceptionOrNull()?.message?.let { "Post-capture failed: $it" }
        val allWarnings = warnings + listOfNotNull(captureWarning)
        return ActionOutcome.Success(
            message = format(point, channel) + allWarnings.format(),
            observation = post?.let { buildObservation(it, platform) },
            attemptTrail = trail,
            verified = true
        )
    }

    private fun List<String>.format(): String =
        if (isEmpty()) "" else joinToString("") { "\nWarning: $it" }
}
```

**Step 3**: Slim down ClickExecutor and LongPressExecutor to thin wrappers (~25 lines each).

```kotlin
// ClickExecutor.kt — was 174 lines, now ~25
class ClickExecutor(private val targetResolver: TargetResolver = TargetResolver) {
    suspend fun execute(
        target: Target, snapshot: ScreenSnapshot?,
        platform: AndroidPlatform, isCancelled: () -> Boolean
    ): ActionOutcome = PointActionExecutor.execute(
        channels = ActionPriorityOrder.click.map { it.toChannel() },
        target = target, snapshot = snapshot,
        platform = platform, isCancelled = isCancelled,
        settleMs = 300L, targetResolver = targetResolver,
        formatSuccess = { p, ch -> "${if (ch == "gesture_tap") "Tapped" else "Clicked"} (${p.x},${p.y}) via $ch" },
        formatFailure = { p, ch, reason -> "Click at (${p.x},${p.y}) via $ch failed: $reason" },
    )

    private fun ActionPriorityOrder.ClickChannel.toChannel() = when (this) {
        ActionPriorityOrder.ClickChannel.NODE_CLICK ->
            PointActionExecutor.Channel("node_action_click", requiresSemantic = true) {
                UIAction.ClickNodeAt(it.x, it.y)
            }
        ActionPriorityOrder.ClickChannel.GESTURE_TAP ->
            PointActionExecutor.Channel("gesture_tap", requiresSemantic = false) {
                UIAction.TapAt(it.x, it.y)
            }
    }
}

// LongPressExecutor.kt — was 181 lines, now ~30
class LongPressExecutor(private val targetResolver: TargetResolver = TargetResolver) {
    suspend fun execute(
        target: Target, durationMs: Long, snapshot: ScreenSnapshot?,
        platform: AndroidPlatform, isCancelled: () -> Boolean
    ): ActionOutcome = PointActionExecutor.execute(
        channels = ActionPriorityOrder.longPress.map { it.toChannel(durationMs) },
        target = target, snapshot = snapshot,
        platform = platform, isCancelled = isCancelled,
        settleMs = 300L, targetResolver = targetResolver,
        formatSuccess = { p, ch -> "Long pressed (${p.x},${p.y}) for ${durationMs}ms via $ch" },
        formatFailure = { p, ch, reason -> "Long press at (${p.x},${p.y}) for ${durationMs}ms via $ch failed: $reason" },
    )

    private fun ActionPriorityOrder.LongPressChannel.toChannel(durationMs: Long) = when (this) {
        ActionPriorityOrder.LongPressChannel.NODE_LONG_CLICK ->
            PointActionExecutor.Channel("node_action_long_click", requiresSemantic = true) {
                UIAction.LongClickNodeAt(it.x, it.y)
            }
        ActionPriorityOrder.LongPressChannel.GESTURE_LONG_PRESS ->
            PointActionExecutor.Channel("gesture_long_press", requiresSemantic = false) {
                UIAction.LongPressAt(it.x, it.y, durationMs)
            }
    }
}
```

**ScrollExecutor stays unchanged** — it uses area-based resolution, not point-based.

### Files Changed

| File | Change |
|------|--------|
| `tool/action/PointActionExecutor.kt` | **New** — shared fallback executor (~80 lines) |
| `tool/action/ClickExecutor.kt` | Rewrite: 174 → ~25 lines |
| `tool/action/LongPressExecutor.kt` | Rewrite: 181 → ~30 lines |
| `tool/action/Target.kt` | Add `isSemantic` property |

**Net: +80 new, -300 removed = -220 lines.**

### Risk: LOW

- Behavior is identical — same channel order, same UIActions, same formatting.
- Existing tests (`ClickExecutorTest`, `LongPressExecutorTest`) verify correctness through the same public API.

---

## 4. R2 — Protect User Messages from History Compression (P0 Bug)

### Problem

`HistoryManager.compress()` drops supplement messages because they are `ResponseItem.Message(role="user")` — identical to any other history item. The compression strategy `removeFirstItem()` removes from the front of the list, and supplements (being early messages) are among the first removed.

This caused the agent to forget "改成听陈奕迅" and revert to the original task "play 容祖儿" (documented in `260222_supplement_history/issue_summary_claude.md`, Issue 2).

### Root Cause

`compress()` treats all items equally. User messages are typically a few words but contain **all task intent**. Dropping them is catastrophic; keeping them costs almost nothing in token budget.

### Fix: Never drop user messages during compression

```kotlin
// HistoryManager.kt — compress(), change Strategy 2

// BEFORE:
while (estimateTokenCount() > targetTokens && items.size > 2) {
    removeFirstItem()
}

// AFTER:
while (estimateTokenCount() > targetTokens && items.size > 2) {
    val idx = items.indexOfFirst {
        !(it is ResponseItem.Message && it.role == "user")
    }
    if (idx < 0) break // only user messages left
    removeItemAt(idx)
}
```

And add `removeItemAt(index)` that handles orphan cleanup:

```kotlin
@Synchronized
private fun removeItemAt(index: Int) {
    if (index !in items.indices) return
    val removed = items.removeAt(index)
    if (removed is ResponseItem.FunctionCall) {
        items.removeAll { it is ResponseItem.FunctionCallOutput && it.callId == removed.id }
    }
    lastTokenEstimate = null
}
```

### Why this is the right fix

The three options from the issue doc were:
- A. Pin flag on items — adds a field to every item for a rare case
- B. Merge supplement into system prompt — changes prompt construction
- C. Separate amendments section — new data structure

**None of these are needed.** The simplest correct fix: user messages are intent, tool outputs are data. Compress data, never compress intent. This handles both supplements AND the original task messages, with zero new fields or data structures.

### Files Changed

| File | Change |
|------|--------|
| `history/HistoryManager.kt` | Modify `compress()` + add `removeItemAt()` (~10 lines) |

### Risk: LOW

User messages are short (typically <50 tokens). Even protecting all of them, compression still has the entire function call + output budget to work with. No change to the happy path.

---

## 5. R3 — Flatten AgentSession.handleUserInput

### Problem

`handleUserInput()` (lines 248-317) uses sequential `if` statements for state dispatch:

```kotlin
if (_state.value == SessionState.Running || _state.value == SessionState.Paused) { ... return }
if (_state.value == SessionState.Shutdown) { ... return }
if (_state.value == SessionState.Created) { ... }
if (_state.value == SessionState.Idle) { ... }
// fall through to common task-start logic
```

This obscures the state machine. A reader has to trace through 4 independent `if` blocks to understand which states reach the task-start code at the bottom.

### Fix: Use `when` with explicit branches

```kotlin
private suspend fun handleUserInput(op: Op.UserInput) {
    when (_state.value) {
        SessionState.Running, SessionState.Paused -> {
            emitStatus("⚠️ Agent is busy. Please wait.")
            return
        }
        SessionState.Shutdown -> return

        SessionState.Created -> {
            initializeRecordingIfNeeded()
            if (!startPlatform()) return
            emit(SessionStarted(sessionId = sessionId, timestamp = now(), goal = op.text))
        }
        SessionState.Idle -> {
            cancelIdleTimeout()
            if (!startPlatformForFollowUp()) return
        }
    }

    startTask(op.text)
}

/** @return true if started, false if failed (error already emitted) */
private suspend fun startPlatform(): Boolean {
    return try {
        services.platform.start()
        true
    } catch (e: Exception) {
        Log.e(TAG, "Platform start failed", e)
        emitStatus("⚠️ Platform initialization failed: ${e.message}")
        false
    }
}
```

### Also: Remove dead `channelCloseScheduled`

`channelCloseScheduled` (AtomicBoolean, line 218) guards `closeChannelWithDelay()`, which is only called from `handleShutdown()`, which already has an idempotency guard (`if (_state.value == SessionState.Shutdown) return`). The AtomicBoolean is redundant. Remove it.

```kotlin
// BEFORE:
private val channelCloseScheduled = AtomicBoolean(false)

private fun closeChannelWithDelay() {
    if (!channelCloseScheduled.compareAndSet(false, true)) return
    scope.launch { delay(EVENT_DELIVERY_GRACE_PERIOD_MS) }
}

// AFTER:
private fun closeChannelWithDelay() {
    scope.launch { delay(EVENT_DELIVERY_GRACE_PERIOD_MS) }
}
```

### Files Changed

| File | Change |
|------|--------|
| `session/AgentSession.kt` | Refactor `handleUserInput()`, extract `startPlatform()`, remove `channelCloseScheduled` |

### Risk: NONE

Pure structural refactor. Same state transitions, same behavior.

---

## 6. R4 — Eliminate PersistedHistoryItem Parallel Hierarchy

### Problem

`ResponseItem` (runtime) and `PersistedHistoryItem` (persistence) are field-by-field mirrors:

```
ResponseItem.Message        ↔ PersistedHistoryItem.Message
ResponseItem.FunctionCall   ↔ PersistedHistoryItem.FunctionCall
ResponseItem.FunctionCallOutput ↔ PersistedHistoryItem.FunctionCallOutput
```

`HistoryItemConverter` (54 lines) exists solely to copy fields between them. Every field added to either type requires updating both + the converter. The only substantive difference: `FunctionCall.arguments` is `JSONObject` in runtime vs `String` in persistence.

### Fix: Make ResponseItem directly serializable

**Step 1**: Add `argumentsRawJson: String` to `ResponseItem.FunctionCall`. Keep `arguments: JSONObject` as a lazy-parsed accessor.

```kotlin
@Serializable
data class FunctionCall(
    val id: String,
    val name: String,
    val argumentsRawJson: String,
) : ResponseItem() {
    /** Parsed arguments. Lazy to avoid parsing when only serializing. */
    @Transient
    val arguments: JSONObject by lazy { JSONObject(argumentsRawJson) }
}
```

**Step 2**: Add `@Serializable` to all `ResponseItem` variants. Use `kotlinx.serialization`.

**Step 3**: Delete `PersistedHistoryItem`, `HistoryItemConverter`, and update `SessionRuntimeSnapshot` to use `ResponseItem` directly:

```kotlin
@Serializable
data class SessionRuntimeSnapshot(
    val historyItems: List<ResponseItem>,  // was List<PersistedHistoryItem>
    // ... rest unchanged
)
```

**Step 4**: Update call sites. The LLM client that creates `FunctionCall` from parsed JSON already has the raw string — pass it through instead of creating a `JSONObject` first.

### Files Changed

| File | Change |
|------|--------|
| `history/ResponseItem.kt` | Add `@Serializable`, change FunctionCall to use `argumentsRawJson` |
| `history/model/PersistedHistoryItem.kt` | **Delete** |
| `history/model/HistoryItemConverter.kt` | **Delete** |
| `history/model/SessionRuntimeSnapshot.kt` | Change `historyItems` type |
| `session/SessionCheckpointCoordinator.kt` | Remove converter calls |
| `session/AgentSession.kt` | Remove converter calls in `reload()` |
| `agent/PromptBuilder.kt` | Use `argumentsRawJson` instead of `arguments.toString()` |
| `llm/ResponseParser.kt` (or similar) | Pass raw JSON string through |

### Risk: MEDIUM

This touches the LLM ↔ agent data pipeline. Needs careful testing that:
1. Prompt construction produces identical output (byte-compare LLM input before/after)
2. Checkpoint round-trip is lossless (serialize → deserialize → equal)
3. Action argument parsing still works

Mitigated by: existing `HistoryItemConverter` round-trip tests can be adapted.

---

## 7. R5 — Extract SessionCoordinator from MainActivity

### Problem

`MainActivity` (603 lines) is a god object. `ensureSessionAndSend()` alone is 150 lines with 4+ nesting levels, combining:

1. Permission validation (overlay + accessibility)
2. Service availability check + retry scheduling
3. Session creation guard (synchronized lock + volatile flag)
4. Reload vs fresh session decision
5. AgentSession creation
6. Event collection wiring
7. Pending input queue management

The session_reload_refactor aligned design (Section 6) already proposed extracting a `SessionThreadCoordinator`. This was deferred as "Stage 2" for the lifecycle fix. The code is stable now — time to extract.

### Fix: Extract `SessionCoordinator`

```kotlin
/**
 * Owns the session lifecycle: create, reload, submit, shutdown.
 *
 * MainActivity delegates all session decisions here.
 * This class has NO Android UI references — testable without instrumentation.
 */
class SessionCoordinator(
    private val scope: CoroutineScope,
    private val settingsProvider: () -> AppSettingsState,
    private val serviceProvider: () -> AgentService?,
    private val historyManager: SessionHistoryManager,
    private val onSessionReady: (AgentSession) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var currentSession: AgentSession? = null
    private var selectedSession: SessionInfo? = null
    @Volatile private var creating = false

    val session: AgentSession? get() = currentSession

    suspend fun send(text: String, forceFresh: Boolean = false) { ... }
    fun selectHistory(session: SessionInfo) { ... }
    suspend fun clearSession() { ... }
    fun rebindIfNeeded(serviceSession: AgentSession?) { ... }
}
```

`MainActivity` becomes a thin UI host (~350 lines):
- `onCreate`: set up Compose content
- `handleIntent`: parse extras, call coordinator
- Lifecycle hooks: forward to coordinator
- Permission checks: stays in Activity (needs Context)

### Files Changed

| File | Change |
|------|--------|
| `app/SessionCoordinator.kt` | **New** — extracted from MainActivity (~150 lines) |
| `app/MainActivity.kt` | Remove session orchestration, delegate to coordinator (603 → ~350 lines) |

### Risk: MEDIUM

Large structural move. No behavior change, but many references to update.
Mitigated by: each method moves intact. No logic changes during extraction.

### Note: Phase 2

This is the largest refactor. Implement R1-R4 first, then R5 in a separate commit.

---

## 8. R6 — Minor Cleanups

### R6a: Remove stale doc references in `HistoryManager`

Line 8: "from Codex's context_manager/history.rs" — this codebase has its own identity now. Remove the origin comment.

### R6b: Simplify `CapsuleStateHolder.onStopRequested` guard

Current:
```kotlin
if (mode !is CapsuleMode.Running &&
    mode !is CapsuleMode.TakeoverPending &&
    mode !is CapsuleMode.Takeover &&
    mode !is CapsuleMode.WaitingForInput &&
    mode !is CapsuleMode.WaitingForAction
) { return false }
```

Simpler (uses the existing `hasActiveTask` property):
```kotlin
if (!hasActiveTask) return false
```

### R6c: Deduplicate Runnable scheduling in MainActivity

`scheduleGoalDispatch` (lines 548-557) and `scheduleDrainPendingInputs` (lines 559-568) are structurally identical. If R5 is implemented, this goes away naturally. If not, extract:

```kotlin
private fun scheduleDelayed(
    currentRef: KMutableProperty0<Runnable?>,
    delayMs: Long,
    action: () -> Unit
) {
    currentRef.get()?.let { window.decorView.removeCallbacks(it) }
    val runnable = Runnable { currentRef.set(null); action() }
    currentRef.set(runnable)
    window.decorView.postDelayed(runnable, delayMs)
}
```

### R6d: `onTaskCompleted` callback in ChatViewModel is a no-op log

Lines 114-116 in `MainActivity`:
```kotlin
onTaskCompleted = {
    Log.d(TAG, "Task completed; session remains in Idle for follow-up")
}
```

Either remove the callback parameter from `ChatViewModel` entirely, or leave it as a hook with a `TODO` comment. Don't ship a lambda that just logs.

---

## 9. Implementation Order

```
Phase 1 (independent, can be parallel commits):
  R2: Fix compress() user message protection   [1 file, P0 bug]
  R6: Minor cleanups                           [3 files, trivial]

Phase 2 (depends on nothing):
  R1: PointActionExecutor extraction           [4 files, mechanical]
  R3: Flatten handleUserInput + remove dead code [1 file, mechanical]

Phase 3 (depends on R3 for clean AgentSession):
  R4: Eliminate PersistedHistoryItem hierarchy  [8 files, careful testing needed]

Phase 4 (depends on all above for a clean base):
  R5: Extract SessionCoordinator from MainActivity [2 files, structural]
```

R2 should ship first — it's a bug fix, not a refactor.

---

## 10. What I Considered and Rejected

### Making `applyVisibility()` reactive instead of explicit
Could use `combine(stateHolder.mode, showPreferenceFlow, ...)` to auto-derive visibility. Rejected: explicit calls after each event are clearer, easier to debug, and avoid reactive scheduling edge cases. The current pattern works.

### Extracting a base class for all three executors (Click + LongPress + Scroll)
Scroll uses area-based targeting, not point-based. Forcing it into the same abstraction as Click/LongPress would require parameterizing target resolution, which adds complexity without removing much duplication. ScrollExecutor shares the channel-loop pattern but differs enough to stay separate.

### Adding a `pinned` field to `ResponseItem.Message`
Proposed in the supplement issue doc as Option A. Rejected: the simpler fix (never drop user messages during compression) handles both supplements AND original task messages, with zero new fields.

### Merging `onTaskCompleted` and `onSessionEnded` in CapsuleStateHolder
They share some `CompletionReason` mapping logic, but the behavior is intentionally different (task completion shows "Done" briefly; session end for user-stops hides immediately). A shared helper would obscure the distinct semantics.

---

## 11. Verification

After each phase:
1. `./gradlew assembleDebug` — builds clean
2. `./gradlew test` — all existing tests pass
3. `./gradlew lint` — no new warnings

For R2 specifically: add a unit test that `compress()` preserves all `role="user"` messages even under aggressive token budget.

For R4 specifically: byte-compare LLM `input_items` before and after for a multi-turn session to verify prompt construction equivalence.
