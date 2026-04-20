# UI Revamp — Logic Review (Claude, ultra-think pass)

Scope: `git diff fcb9e0ac..HEAD -- app/src/main/kotlin/ai/closepaw/`
(31 commits, 41 files, +1119/−998).
Reviewed against `doc/main/state_machines/ui_chat.md`, `ui_capsule.md`, and the
pre-revamp git tree at `fcb9e0ac`.
**Logic only** — colors, spacing, typography intentionally ignored.

Categories: **Critical** (breaks documented transition / loses data on
round-trip) · **Important** (bug, miswiring, race, edge-case miss) · **Nit**
(dead code, inefficiency) · **Bug-fix-as-intended** (looks suspicious; actually
fixes prior bug or is a documented design choice).

> Re-evaluated severity in the ultra-think pass: original C1/C2 calls were
> revised after I traced the *user-visible* fall-out (the OutcomeFooter still
> sniffs the "⚠" prefix on reload, and the User bubble preceding still carries
> the prompt). They are now **Important** — real degradations, not data loss.
> A new **Critical** rendering bug (I8 → C3 below) surfaced on the second pass.

---

## Critical

### C3. `Final` paragraph silently fuses streamed text + completion summary + error text with no separator
File: `ui/chat/components/MessageBubble.kt:225-247`

```kotlin
val finalText = message.contentBlocks
    .filterIsInstance<ContentBlock.Text>()
    .joinToString(separator = "") { it.text }
```

The Track A model "Final" block is rendered by joining **every** `ContentBlock.Text`
in the bubble with an **empty separator**. The reducer feeds three structurally
distinct kinds of Text into that list:

1. Streamed `MessageDelta` chunks (potentially fragmented across thoughts/actions
   per the spec — the join-merge here is correct and *intended*).
2. The completion summary appended by `appendCompletionToMessages`
   (`ChatViewModel.kt:45`) — `completionSummary(result)` defaults to the literal
   string `"Task completed"` when `result` is null/blank.
3. The error string `"⚠️ <message>"` appended by `handleSessionError`
   (`ChatEventReducer.kt:160`).

Because all three land as `ContentBlock.Text`, the renderer concatenates them
into one paragraph with **no whitespace, newline, or visual break**:

| In flight | Final renders as |
|---|---|
| stream `"Opening Settings now."` then TaskCompleted with `result = null` | `Opening Settings now.Task completed` |
| stream `"Done."` then SessionError("Network error") | `Done.⚠️ Network error` |
| no stream + TaskCompleted with `result = "Settings opened."` | `Settings opened.` (fine) |

This is a **regression** from the pre-revamp `AgentBubble` (deleted block
formerly at MessageBubble.kt:103-203 in `fcb9e0ac`), where each `ContentBlock.Text`
was rendered as its own `Text` composable inside the same column with explicit
12dp spacing — paragraphs were visually separated.

**Why Critical, not Important:** every successful task with a null `result` field
hits this — i.e. it fires on the *common* path, not an edge. The
`captures/INDEX.md` example "Open Settings · 2 actions · 8.3s ✓" hides the bug
because that capture shows the *collapsed* row; expanding it would surface the
fused paragraph.

**Fix sketch:** either (a) introduce a distinct content-block type for completion
summary + error so the renderer can space them out, or (b) walk
`message.contentBlocks` once and emit a Text composable per Text block (the
pre-revamp shape) instead of joining. Note: the spec §4.4 merge requirement
(stream fragments split by Thought/Action) can still be satisfied with a single
contiguous-Text reducer pass *before* the renderer splits paragraphs — fragments
between trace items merge, but distinct-arrival texts (completion, error) stay
separated.

---

## Important

### I1. `ChatScreen.onUserResponse` does not wrap `viewModel::sendUserResponse` with `capsuleBinding.onUserResponseSent(callId)` (already documented)
File: `ui/chat/ChatScreen.kt:153`

Verified during ultrathink:
- `CapsuleStateHolder.onUserResponseSent(callId: String): Boolean` exists at
  `ui/overlay/CapsuleStateHolder.kt:198`.
- The overlay path *does* call it: `app/ServiceOverlayController.kt:95`
  (`if (stateHolder.onUserResponseSent(callId)) { ... }`).
- The `CapsuleBinding` data class (`ui/capsule/CapsuleBinding.kt:18`) **does not
  expose the method** — only `onStopRequested` and `onApprovalResolved` are
  surfaced. The fix therefore requires *two* edits: add
  `onUserResponseSent: (String) -> Boolean` to `CapsuleBinding` (and to
  `InertCapsuleBinding`), wire it in `MainActivityContent.rememberCapsuleBinding`,
  and consume it in `ChatScreen.kt:153`.

The capsule's `WaitingForInput → Running` edge is *only* triggered locally on
the surface that submits the response; the agent does not echo back a transition
event. So the symptom is exactly what `INDEX.md` reports — the input bar stays
locked in "type your response" while the agent has already received and is
processing the answer.

### I2. `MainActivity.deriveRepairModel()` is one-shot inside `setContent`; not recomputed in `onResume` (already documented)
File: `app/MainActivity.kt:241,300,854`

`repairModel = deriveRepairModel()` evaluates once when `setContent` runs.
`onResume` (line 300) does not invalidate. After the user grants accessibility
in system Settings → returns to the activity, `onResume` fires but the cached
`repairModel` value (and the banner derived from it) is not rebuilt.

Confirmed coverage. Fix is the standard `mutableStateOf` + recompute-in-onResume
pattern.

### I3. Late `ThoughtUpdate` after `TaskCompleted` mutates a `Complete` row
File: `ui/chat/ChatEventReducer.kt:87-95,148-153,228-234`

`handleThoughtUpdate` calls `updateLastAgentMessage`, which is unconditional:
```kotlin
val index = messages.indexOfLast { it is ChatMessage.Agent }
```
The reducer *does* clear `currentAgentMessageId` on `TaskCompleted` (line 152)
but never consults that id from `updateLastAgentMessage`. So a `ThoughtUpdate`
arriving after `TaskCompleted` (network reorder, late-flush from the agent
runtime) appends a `Thought` block into a sealed bubble. Re-render then has a
Thought visible but no closing summary update — and the row's `completedTimestamp`
no longer agrees with "last activity."

`ThoughtUpdate` is the *new* event in this revamp, so the lack of a Complete-state
guard is a fresh bug, not inherited code. (Other events — `MessageDelta`,
`ActionProposed`, `ActionExecuted` — share the same pattern but are out of scope
for this review since they did not change here. They should be guarded too, but
that's a separate hardening pass.)

**Fix:** in `updateLastAgentMessage`, skip when
`current.state == AgentMessageState.Complete`, *or* gate `handleThoughtUpdate` on
`currentAgentMessageId != null`.

### I4. `TaskCompleted(result = ERROR)` does not produce `RowState.Error` unless `SessionError` ran first
Files: `ui/chat/ChatViewModel.kt:42-49`, `ui/chat/ChatEventReducer.kt:148`

`appendCompletionToMessages` always lands in `RowState.Complete` unless the row
was *already* `Error`:
```kotlin
rowState = if (current.rowState == RowState.Error) RowState.Error else RowState.Complete
```
The capsule state machine *does* branch on outcome
(`onTaskCompleted(outcome, message)` →
`Done`/`Error` based on `TaskOutcome`, see `CapsuleStateHolder.kt:236`). The
chat reducer does not. If the runtime emits `TaskCompleted(result = ERROR_TEXT)`
without a preceding `SessionError`, the chat row is collapsible-Complete with
the OutcomeFooter rendering "✓" — the symbol disagreement with the capsule is
visible to the user.

**Fix:** branch on the outcome in `handleTaskCompleted` (the `TaskCompleted`
event already carries the result), and route to a SessionError-equivalent when
ERROR.

### I5. `RowState` is not persisted; reloaded errored rows degrade to `Complete`
Files: `history/model/MessageRecord.kt:34-44`, `history/model/MessageConverter.kt:73-79`

`MessageRecord.Agent` carries only `isComplete: Boolean`. The reverse map:
```kotlin
rowState = if (record.isComplete) RowState.Complete else RowState.Live
```
A row that ended in `RowState.Error` is rehydrated as `Complete`.

Re-evaluated severity (was Critical): the user-visible degradation is small —
the error TEXT block still lives in `contentBlocks`, and `OutcomeFooter` recovers
the error glyph by sniffing for the `"⚠"` prefix on the last Text block. So the
*footer* still shows "⚠ <error>". What's lost is:

1. The "locked open" invariant — but per the spec note, lock-open is meant for
   the **live** session, not after-the-fact history. So this is arguably fine.
2. `CollapsedHeader` shows a neutral `✓` glyph (line 159) instead of an error
   indicator, because it doesn't branch on `RowState.Error`.

So the real bug is symptom #2: a reloaded errored task collapses with a
**checkmark** sitting next to a row whose footer says "⚠ <error>". Same row,
contradicting glyphs. **Fix is C2-style:** persist `rowState` (default null for
back-compat, fall back to `isComplete → Complete`).

### I6. `userPrompt` is not persisted; collapsed-row headline silently degrades on reload
Files: `ui/chat/model/ChatMessage.kt:39-43` (source comment), `history/model/MessageConverter.kt`

The source comment on `ChatMessage.Agent.userPrompt` acknowledges this:
> "Null when restored from historical records or for synthetic agent rows
> (startup errors)."

Re-evaluated severity (was Critical): the user prompt **is** still visible to
the user — the preceding `MessageRecord.User` survives the round-trip and
renders as a User bubble above the collapsed Agent row. The headline ladder
just falls off tier 1 (user prompt) to tier 2 (first thought) for reloaded
sessions.

Worth the small fix because the data is already in the persisted log: in
`MessageConverter.fromRecords`, walk with a one-back lookup; when the previous
record is `User`, hydrate `userPrompt = previous.text`.

### I7. `OutcomeFooter` extracts the error message via `startsWith("⚠")` string sniffing
File: `ui/chat/components/MessageBubble.kt:269-279`

```kotlin
.lastOrNull { it.text.startsWith("⚠") }
?.removePrefix("⚠️")
```
Coupled to the literal emoji in two emitter paths (`ChatEventReducer.handleSessionError`
prefixes `"⚠️ "`; `ChatViewModel.appendStartupFailureMessages` prefixes
`"⚠️ Failed to start: "`). Two sites today; nothing prevents a third site from
shipping with a different prefix and silently producing the fallback "⚠ Error"
footer with no message.

If C3 is fixed (separating completion/error from streamed Text), this
extraction can move to a typed source (e.g. an `ContentBlock.Error(message)`
variant) and the prefix sniffing can go.

### I8. `appendCompletionToMessages` no-open-agent branch creates a row with `userPrompt = null`
File: `ui/chat/ChatViewModel.kt:53-62`

The fallback path (TaskCompleted with no preceding Agent in the timeline)
creates a synthetic Agent with no userPrompt and no content other than the
completion summary. The `CollapsedHeader` ladder then resolves to "(no
activity)" — the user sees a row labeled "(no activity)" alongside their
completion summary. This is a defensive fallback for an event that shouldn't
happen in steady state, so low frequency, but the resulting UX is strictly
worse than collapsing to the completion text itself.

**Fix:** in the fallback branch, set the headline from the completion text (or
omit `CollapsedHeader` entirely for synthetic rows by initially keeping them
expanded).

---

## Bug-fix-as-intended

### B1. Agent-record `timestamp` now reflects start, not completion (was a long-standing bug)
Files: `history/AgentMessageBuffer.kt:6-21,66-77`,
`history/SessionRecordingService.kt:144,413-421`,
`history/SessionRecordMessageMerger.kt:11-35`

Pre-revamp `mergeAgentSnapshot` set
`MessageRecord.Agent.timestamp = System.currentTimeMillis()` at merge time
(i.e. close time). The new code captures `startTimestamp` at
`AgentMessageBuffer.start(id, timestamp)` and threads it through, while adding
a separate `completedTimestamp`.

Effect: `formatElapsed()` now returns a meaningful elapsed on reload.
Previously `(end − timestamp)` was ~0 because `timestamp` was already the close
time. **Looks suspicious in diff** — the old `timestamp` parameter is renamed
to `lastUpdated`, which on a casual read appears to be a lost parameter; flagged
to prevent accidental revert.

**Caveat:** old session JSON written before this revamp has
`messages[*].timestamp` = close time and `completedTimestamp` defaults to null.
On reload of *those* records, `formatElapsed` returns null → no elapsed shown.
Acceptable soft regression.

### B2. `ContentBlockRecord.Thought` polymorph is forward-extensible
File: `history/model/MessageRecord.kt:58-67`

Adding a new `@SerialName("thought")` subclass to the sealed
`ContentBlockRecord` is forward-compatible: old JSON has no `"thought"` blocks
→ deserializes cleanly. Newer JSON containing `Thought` read by an older binary
would crash, but this is a one-way concern (no rollback contract documented).

### B3. `ButtonSpec` lost its `icon: String` glyph field
File: `ui/overlay/model/CapsuleRenderSpec.kt:27`

Pre-revamp `ButtonSpec("✋", "Takeover")` carried a leading emoji; post-revamp
`ButtonSpec("Takeover")` and the comment "The Compose layer chooses the icon."
This is the documented Track B "d2-2" model cleanup — icons now resolve in
`CapsuleControlBar` via Material `Icons.Rounded.*`. Verify by spot-checking that
every primary/secondary/tertiary/stop combination has a matching icon mapping
in `CapsuleControlBar`; any unmapped variant renders text-only.

### B4. `ActionCardData.expandedContent` was always declared-but-unpopulated, even pre-revamp
Files: `ui/chat/model/ChatMessage.kt:115`, `ui/chat/components/MessageBubble.kt:346`

I initially flagged this as a possible regression. Verified at `fcb9e0ac` — the
deleted `ActionCard.kt` *also* read `data.expandedContent` (line 78, 129, 131
of the pre-revamp file) without anything ever populating it. Inherited dead
code, not a revamp regression.

### B5. `RowState.Waiting` is reserved-but-unreached
Files: `ui/chat/model/ChatMessage.kt:55-66` (enum), reducer never sets it

The `Waiting` value is declared in `RowState` and rendered in
`MessageBubble.rowDescription` semantics (line 137) but no reducer path emits
it. The spec explicitly notes "Waiting is reserved for future AskUser/Approval
routing in the chat reducer (no current event triggers it; the capsule still
owns the live affordance)." Intended.

---

## Nits

### N1. `ActionState.Executing` rendered but never produced
File: `ui/chat/components/MessageBubble.kt:307-330`

Branches in `statusGlyph`, `statusDescription`, `statusColor` for `Executing`
are dead code per spec ("declared but the current reducer never emits it"). Same
status as `RowState.Waiting` — keep or delete consistently.

### N2. `CapsuleRenderSpec.DotSpec.pulsing` is set but never read
File: `ui/capsule/surface/SmartCapsuleSurface.kt:245`

The status-line dot uses `animateColorAsState` only; `spec.dot.pulsing` is not
consumed (verified at `fcb9e0ac` — pre-revamp also did not read it, so this is
inherited dead code). The flag is `true` for `Running` and `false` everywhere
else — if it should pulse, wire an `infiniteTransition` alpha on the dot Box.

### N3. `recordThought` log alignment
File: `app/AgentServiceEventHandler.kt:50-52`

Three layers handle a stray ThoughtUpdate differently — handler delegates,
recording warns and drops, chat silently drops. Worth aligning: either all
three log a warn, or none does.

### N4. `truncateWords` allocates a regex per call
File: `ui/chat/components/MessageBubble.kt:447`

`text.trim().split(Regex("\\s+"))` per recomposition of every collapsed header.
Hoist `private val WHITESPACE = Regex("\\s+")` to top-level.

### N5. `outcomeFooter` and `collapsedHeadline` re-walk `contentBlocks` multiple times
File: `ui/chat/components/MessageBubble.kt:265-280,394-411`

Three `filterIsInstance<…>().firstOrNull()` passes plus two `count` walks.
Trivial for typical bubble sizes (~10 blocks); single-pass scan would be
cleaner if traces ever grow large.

### N6. `formatToolCall` produces `toolName(description)` with no escaping
File: `ui/chat/components/MessageBubble.kt:336-339`

Action `description` is a free-form string emitted by the tool layer. If it
contains parentheses, they nest visually with the wrapping `()`. Cosmetic.

---

## Cross-cuts checked and OK

- **Reducer `streamingBuffer.clear()` on `ThoughtUpdate`** matches spec §
  "Streaming buffer". Correct.
- **`SessionRecordingService.recordThought`** finalises the pending text block
  before appending the Thought — preserves chronological order in the record
  (matches the reducer, matches MessageConverter's reverse map). Good
  three-way alignment.
- **`completedTimestamp` round-trip** — `MessageRecord.Agent.completedTimestamp:
  Long? = null` defaults safely; `MessageConverter` round-trips both directions.
- **`updateActionBlockForExecution`** — uses `indexOfLast` (most-recent match)
  per spec; mutates in place; preserves order. Correct.
- **`appendStartupFailureMessages`** — correctly stamps `rowState = Error` and
  sets a `completedTimestamp` for synthetic startup-failure rows.
- **Reducer concurrency** — every event is dispatched under `stateLock`. No
  reentry hazard observed.
- **`StreamingText` inline cursor** — `remember(text)` correctly invalidates the
  annotated string per text update; cursor stays trailing. Visual change only.
- **Capsule pre-Stop/pre-Approval guards via `CapsuleBinding`** — `onStopRequested`
  and `onApprovalResolved` correctly short-circuit forwarding. Idempotent.

## Cross-cuts I considered then dismissed

- **`handleMessageDelta` REPLACE-last-Text could overwrite turn-1 text on
  TurnStarted in turn-2.** The case requires turn 1 to end with a streaming Text
  block (no terminal action) AND a new turn to start anyway. In the agent loop,
  text-only turns terminate via `TaskCompleted`, not `TurnStarted`. Safe in
  practice — flagging here only as a brittleness note for any future
  multi-paragraph turns.
- **`onTakeoverRequested` / `onResumed` not exposed on `CapsuleBinding`.** Chat
  routes the user's tap through `viewModel::requestTakeover/requestResume`
  which goes service-side; the capsule transition arrives back via protocol
  events. Latency ~one round-trip but no broken-state risk. Symmetric with the
  pre-revamp wiring.

---

## Suggested follow-up order (revised)

1. **C3** (Final fusion) — single-file change in `MessageBubble.kt`, biggest
   user-visible impact, fires on the common path. Either (a) split per-Text
   composables or (b) introduce a typed completion/error block.
2. **I1 + I2** (known bugs from `INDEX.md`) — small, surgical, capture-blocking.
3. **I4** (TaskCompleted ERROR rowState) — three lines in
   `appendCompletionToMessages`.
4. **I5 + I6** (rowState + userPrompt persistence) — together touch only
   `MessageRecord.kt` + `MessageConverter.kt`. Low cost, removes the
   sniffing-based recovery (I7) over time.
5. **I3** (Complete-state guard in `updateLastAgentMessage`) — defensive.
6. **I7** (drop "⚠" prefix sniffing) — falls out for free once C3 lands.
7. Nits as cleanup pass.
