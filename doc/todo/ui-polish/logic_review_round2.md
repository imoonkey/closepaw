# Logic Review Round 2

Scope: `951c82f5`, `89977c14`

## PR-A — `951c82f5`

1. `#1` — VERIFIED: `MessageRecord.Agent` now persists nullable `rowState`, `toRecord()` writes it, and `parseRowState()` falls back from legacy `null`/unknown values to `isComplete ? Complete : Live`, so old sessions do not get spuriously promoted to `Error` (`app/src/main/kotlin/ai/closepaw/history/model/MessageRecord.kt:33-48`, `app/src/main/kotlin/ai/closepaw/history/model/MessageConverter.kt:26-45`, `app/src/main/kotlin/ai/closepaw/history/model/MessageConverter.kt:122-130`).
2. `#2` — VERIFIED: `fromRecords()` walks one record back and hydrates `userPrompt` only from a preceding `User`, with both the positive and orphan-agent cases covered in tests (`app/src/main/kotlin/ai/closepaw/history/model/MessageConverter.kt:94-99`, `app/src/test/kotlin/ai/closepaw/history/model/MessageConverterTest.kt:181-213`).
3. `#5` — VERIFIED: `updateLastAgentMessage()` now returns before mutation when the trailing agent row is already `Complete`, which drops the late `ThoughtUpdate` case that previously mutated sealed rows (`app/src/main/kotlin/ai/closepaw/ui/chat/ChatEventReducer.kt:234-243`, `app/src/test/kotlin/ai/closepaw/ui/chat/ChatEventReducerTest.kt:151-172`).
4. `#8` — VERIFIED: `insertUserTurn()` only stamps `completedTimestamp` while sealing a non-`Complete` row and preserves existing `null`/historical values on already-complete rows, which matches the intended back-compat behavior (`app/src/main/kotlin/ai/closepaw/ui/chat/ChatEventReducer.kt:195-206`, `app/src/test/kotlin/ai/closepaw/ui/chat/ChatEventReducerTest.kt:174-211`).

## PR-B — `89977c14`

5. `C3` — VERIFIED: `ExpandedTrace()` no longer `joinToString("")`-fuses all text blocks and instead renders each `ContentBlock.Text` as its own composable, so streamed prose, completion text, and error text stay visually separate (`app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:213-257`).
6. `#6` — VERIFIED: `handleTaskCompleted()` now branches on `TaskOutcome.ERROR`, prefixes the warning text, and passes `isError` into `appendCompletionToMessages()`, which pins the terminal row to `RowState.Error` in both the existing-agent and synthetic-agent paths (`app/src/main/kotlin/ai/closepaw/ui/chat/ChatEventReducer.kt:148-155`, `app/src/main/kotlin/ai/closepaw/ui/chat/ChatViewModel.kt:34-64`, `app/src/test/kotlin/ai/closepaw/ui/chat/ChatEventReducerTest.kt:213-251`).
7. `#7` — VERIFIED: `OutcomeFooter()` no longer sniffs `startsWith("⚠")`, and the error display path still works because `AgentRow()` suppresses the footer for `RowState.Error` while error rows remain expanded and `ExpandedTrace()` renders the inline `⚠️ ...` text block directly (`app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:119-160`, `app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:213-257`, `app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:403-410`).
8. `#3` — VERIFIED: `collapsedHeadline()` now includes a `ContentBlock.Text` rung before `"(no activity)"`, and the new unit test locks that fallback in place (`app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:425-449`, `app/src/test/kotlin/ai/closepaw/ui/chat/components/CollapsedHeadlineTest.kt:51-60`).

## New regressions found

- None from these two fixes on the reviewed path.
- Coverage note: the added tests do exercise the intended legacy-null/back-compat branches for persisted `rowState` and `completedTimestamp`; the only remaining unexercised fallback in this area is the malformed-string branch of `parseRowState()` (`app/src/test/kotlin/ai/closepaw/history/model/MessageConverterTest.kt:154-178`, `app/src/test/kotlin/ai/closepaw/ui/chat/ChatEventReducerTest.kt:174-211`, `app/src/main/kotlin/ai/closepaw/history/model/MessageConverter.kt:122-130`).
