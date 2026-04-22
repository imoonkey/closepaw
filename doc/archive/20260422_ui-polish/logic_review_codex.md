# Logic Review: `frontend-ui-revamp` (`fcb9e0ac..HEAD`)

Scope checked:
- `git log --oneline fcb9e0ac..HEAD | wc -l` => `32`
- `git diff --stat fcb9e0ac..HEAD -- app/src/main/kotlin/ai/closepaw/`
- Baselines: `doc/main/state_machines/ui_capsule.md`, `doc/main/state_machines/ui_chat.md`, plus targeted pre-revamp history reads

Known bugs already documented in `doc/todo/ui-polish/captures/INDEX.md` were intentionally not re-flagged:
- stuck supplement capsule on `Done`
- stale Setup Issue banner after a11y grant

Most of the new logic risk is in chat/history round-trip. I did not find another new state-machine regression in `CapsuleRenderSpec` / `SmartCapsuleSurface` / `CapsuleControlBar` / `CapsuleInputBar` beyond the already-tracked wiring bug above.

## Critical

### 1. Error rows do not survive persistence round-trip
- File: `app/src/main/kotlin/ai/closepaw/history/model/MessageRecord.kt:33-43`, `app/src/main/kotlin/ai/closepaw/history/model/MessageConverter.kt:58-79`, `app/src/main/kotlin/ai/closepaw/ui/chat/ChatEventReducer.kt:155-175`
- What changed / doc says:
  The revamp introduced the 4-state row machine (`Live/Waiting/Complete/Error`). The live reducer now sets `RowState.Error` on `SessionError`, and `ui_chat.md` says Error rows stay locked open. The persisted schema still stores only `isComplete` plus `completedTimestamp`, and `fromRecord()` reconstructs every complete row as `RowState.Complete`.
- Why it is wrong:
  Reloaded sessions lose the terminal row kind. Error rows become ordinary complete rows, so the locked-open invariant is broken, collapse-default changes, and the error-specific footer path is lost. This is round-trip data loss in the new row-state model.
- Concrete reproduction / thought experiment:
  Start a task, emit `SessionError("boom")`, then resume the same session from history. The row that was `Error` live comes back as `Complete`.
- Fix suggestion:
  Persist terminal row state explicitly, or persist an explicit terminal outcome/error flag and derive `RowState.Error` from that on restore. Add a round-trip test that starts live, errors, saves, reloads, and verifies the row is still `Error`.

## Important

### 2. Legacy completed rows get a bogus `completedTimestamp` on the next user turn
- File: `app/src/main/kotlin/ai/closepaw/ui/chat/ChatEventReducer.kt:193-200`, `app/src/main/kotlin/ai/closepaw/history/model/MessageConverter.kt:76-78`
- What changed / doc says:
  `completedTimestamp` was added for elapsed-time footer/collapsed summary. Old records deserialize with `completedTimestamp = null`, which is fine for migration. But `insertUserTurn()` now backfills `msg.completedTimestamp ?: timestamp` every time it "closes" the last agent row, even if that row was already complete before this turn started.
- Why it is wrong:
  Opening a pre-revamp session and sending a new prompt mutates historical rows with the new turn time. That gives nonsense elapsed values and breaks schema-migration safety.
- Concrete reproduction / thought experiment:
  Load an older session JSON with a completed agent row and no `completedTimestamp`. Send a new message. The previous row now gets `completedTimestamp = now`, so its elapsed footer becomes the gap between the old row timestamp and the new prompt.
- Fix suggestion:
  Only stamp `completedTimestamp` when transitioning `Live`/`Waiting` to terminal. If the row is already `Complete` or `Error`, preserve the existing value, including `null`.

### 3. The collapsed headline ladder is missing the promised text fallback
- File: `app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:371-443`
- What changed / doc says:
  The new collapsed-summary comment says the ladder is `user prompt -> first thought -> first action -> first text -> "(no activity)"`. The implementation stops after `first action` and falls straight to `"(no activity)"`.
- Why it is wrong:
  Text-only turns collapse to `(no activity)` even when the assistant produced a real answer/completion. That is a direct mismatch between the coded behavior and the documented ladder.
- Concrete reproduction / thought experiment:
  Run a simple task that emits only assistant text plus a completion summary, with no `ThoughtUpdate` and no action blocks. Collapse the finished row. The headline becomes `(no activity)` instead of the final text.
- Fix suggestion:
  Add the missing `ContentBlock.Text` fallback before `"(no activity)"`. Prefer the merged final text (or first text block), then truncate it for the collapsed row.

### 4. `userPrompt` is attached live but never restored from persisted history
- File: `app/src/main/kotlin/ai/closepaw/ui/chat/ChatEventReducer.kt:217-224`, `app/src/main/kotlin/ai/closepaw/history/model/MessageRecord.kt:33-43`, `app/src/main/kotlin/ai/closepaw/history/model/MessageConverter.kt:58-87`, `app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:429-431`
- What changed / doc says:
  Live agent rows now carry `userPrompt` so the collapsed headline can lead with the opening user prompt. The persistence schema has no field for it, and `fromRecords()` is only a per-record map, so restore never reconstructs the prompt from neighboring user messages.
- Why it is wrong:
  Fresh projection and reloaded-history projection diverge even though the source user message is already persisted. That conflicts with the chat state-machine doc's "same projection on fresh sessions and on reloaded history" requirement.
- Concrete reproduction / thought experiment:
  Send `Open Settings`, let the row complete, and observe the collapsed headline in the live session. Resume the same session from history. The prompt-backed headline is gone because the prompt was dropped during restore.
- Fix suggestion:
  Either persist `userPrompt`, or reconstruct it during `fromRecords()` / session restore by pairing each agent row with the immediately preceding user row that opened it. Add a restore test that checks collapsed-summary inputs before vs. after reload.

## Nit

### 5. Empty `ThoughtUpdate` is ignored live but still persisted
- File: `app/src/main/kotlin/ai/closepaw/ui/chat/ChatEventReducer.kt:87-95`, `app/src/main/kotlin/ai/closepaw/app/AgentServiceEventHandler.kt:50-52`, `app/src/main/kotlin/ai/closepaw/history/SessionRecordingService.kt:164-174`, `app/src/main/kotlin/ai/closepaw/history/AgentMessageBuffer.kt:37-40`
- What changed / doc says:
  The new thought history path records every `ThoughtUpdate`. The live reducer explicitly drops empty thoughts, and there is already a test locking that behavior (`ChatThoughtAndRowStateTest`).
- Why it might be wrong:
  A malformed blank thought can exist only after reload, producing a live/history mismatch and a blank trace row if any caller ever emits invalid input.
- Concrete reproduction / thought experiment:
  Inject `ThoughtUpdate(thought = "")`. Live chat ignores it; the recorder persists `ContentBlockRecord.Thought("")`; after reload the row contains a blank thought item.
- Fix suggestion:
  Mirror the reducer guard in the recording path as well, preferably using `isBlank()` at the event-handler boundary.

## Bug-fix-as-intended

### 6. `startTimestamp` in agent snapshots is fixing a pre-revamp timestamp bug
- File: `app/src/main/kotlin/ai/closepaw/history/AgentMessageBuffer.kt:5-8`, `app/src/main/kotlin/ai/closepaw/history/SessionRecordMessageMerger.kt:14-20`
- What changed:
  Agent snapshots now carry `startTimestamp`, and the merger now writes `MessageRecord.Agent.timestamp = snapshot.startTimestamp` while keeping `lastUpdated` separate.
- Why it looks wrong:
  At first glance it can look like later action/completion updates are no longer "refreshing" the message timestamp.
- Why it is actually right:
  Pre-revamp `SessionRecordMessageMerger` (commit `229f00d`, file lines 9-33 in that revision) overwrote `MessageRecord.Agent.timestamp` with `System.currentTimeMillis()` on every merge. That made row timestamps drift toward action/completion time and broke elapsed math and message ordering. The new split is the correct fix.
- Fix suggestion:
  Keep this behavior. A focused merger test would make the intent explicit and prevent regression.
