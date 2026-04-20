# Frontend UI Revamp Review — Round 2

Round-1 implementation findings are mostly resolved cleanly in this scope: the `SettingsRow` contrast fix is correct, complete rows now default-collapse through a tri-state override, successful rows now render the collapsed summary/footer and `Trace* + optional Final`, the drawer/settings/onboarding surfaces now use `MaterialTheme.shapes` + `closePaw.spacing`, capsule icon strings/emoji are gone, and `ActionCardData.toolIcon` is removed. I did not re-raise the explicitly deferred font-binary follow-up.

Verification:
- `./gradlew :app:testDebugUnitTest --tests 'ai.closepaw.ui.chat.ChatThoughtAndRowStateTest' --tests 'ai.closepaw.ui.chat.ChatSupplementAndActionTransitionTest' --tests 'ai.closepaw.history.model.MessageConverterTest'` ✅
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.closepaw.qa.ChatAgentRowDisclosureTest` ❌

## Critical

None.

## Important

1. **The new disclosure regression test is present but red.** Every `setContent { MessageBubble(...) }` in [ChatAgentRowDisclosureTest.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/androidTest/kotlin/ai/closepaw/qa/ChatAgentRowDisclosureTest.kt:73), [ChatAgentRowDisclosureTest.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/androidTest/kotlin/ai/closepaw/qa/ChatAgentRowDisclosureTest.kt:84), [ChatAgentRowDisclosureTest.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/androidTest/kotlin/ai/closepaw/qa/ChatAgentRowDisclosureTest.kt:101), and [ChatAgentRowDisclosureTest.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/androidTest/kotlin/ai/closepaw/qa/ChatAgentRowDisclosureTest.kt:114) renders `MessageBubble` without `ClosePawTheme`, but `MessageBubble` now reads `MaterialTheme.closePaw`, whose Local throws if the theme provider is missing in [Tokens.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/theme/Tokens.kt:42). On device, all four test methods fail immediately with `IllegalStateException: ClosePawTokens not provided`, so the added regression guard from round 1 is not actually protecting anything.

2. **Restored history rows always compute `0.0s` elapsed.** `MessageRecord.Agent` still persists only the start `timestamp` plus `isComplete` in [MessageRecord.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/history/model/MessageRecord.kt:33), and `MessageConverter.fromRecord()` fabricates `completedTimestamp = record.timestamp` in [MessageConverter.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/history/model/MessageConverter.kt:77). The new footer/summary code subtracts `message.timestamp` from `completedTimestamp` in [MessageBubble.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:415), so any resumed complete row produces zero elapsed time when history is loaded through [ChatSessionHistoryController.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/chat/ChatSessionHistoryController.kt:55). That regresses the new `headline · N actions · elapsed` UI for session history.

3. **Thought traces still do not survive session recording/reload.** The follow-up patch added `ContentBlockRecord.Thought` and read/write support in [MessageRecord.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/history/model/MessageRecord.kt:60) and [MessageConverter.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/history/model/MessageConverter.kt:63), but the runtime recording path never emits a thought block: `ThoughtUpdate` only updates the overlay in [AgentServiceEventHandler.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/AgentServiceEventHandler.kt:50), and [AgentMessageBuffer.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/history/AgentMessageBuffer.kt:33) still knows how to capture only text/action blocks. Live chat now shows `Thought` rows, but resumed sessions silently lose them, which also shifts collapsed headlines away from the intended first-thought ladder.

4. **Error rows never show the new footer.** `outcomeFooter()` has an explicit error branch in [MessageBubble.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:393), but `AgentRow` only renders `OutcomeFooter(message)` when `rowState == RowState.Complete` in [MessageBubble.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:150). `RowState.Error` rows therefore stay expanded with the inline error text only and never surface the new `⚠ <error summary>` footer the row model added.

## Nit

None.

Recommendation: do not hand this off to QA yet.
