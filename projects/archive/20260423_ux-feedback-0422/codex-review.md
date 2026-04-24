# Review: ux-feedback-0422 cumulative diff `30fe9ad7..HEAD`

## Findings

### High
1. Reloaded history still violates the new `FinalText` contract. The live path now classifies final vs trace text in `applyCompletionToBlocks()`, but the persistence path still serializes completion text as plain `Text` and reconstructs `FinalText` by guessing on restore. [`AgentServiceEventHandler`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/AgentServiceEventHandler.kt:84) always calls `appendTerminalText()` with `event.result` or a fallback like `"Stopped"` / `"Done"`; [`AgentMessageBuffer`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/history/AgentMessageBuffer.kt:43) stores that as `ContentBlockRecord.Text`; then [`MessageConverter.migrateLegacyFinalText()`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/history/model/MessageConverter.kt:146) blindly promotes the last non-blank `Text` on every complete row. That breaks parity with the live reducer in two ways: tool-less text completions reload as `Text(answer) + FinalText(answer)` instead of a single final block, and `USER_STOPPED` / other no-answer completions reload with a fabricated final region (`"Stopped"`) even though the spec says complete rows with no answer keep no final region ([README](</Users/moonkey/workspace/android-agent-workspace/androidagent/projects/active/ux-feedback-0422/README.md:240>)). This needs one shared structural source of truth for persisted final-vs-trace blocks; restore-time promotion is no longer sufficient.

## Boundary Notes

- The remaining interaction risk is the split ownership between the live reducer path ([`ChatViewModel.applyCompletionToBlocks()`](</Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/chat/ChatViewModel.kt:83>)) and the recorder/reload path (`AgentServiceEventHandler` -> `AgentMessageBuffer` -> `MessageConverter`). The 4 PRs compose correctly in-memory, but not after session persistence/reload.

## Verification

- Passed: `:app:testDebugUnitTest` for `ChatCompletionMessageTest`, `ChatEventReducerTest`, `ChatThoughtAndRowStateTest`, `MessageConverterTest`, `ThoughtGroupTest`, `TurnPlanningPhaseRunnerTest`, `CapsuleStateHolderTest`, `CapsuleModeTest`
- Not run: `connectedDebugAndroidTest`

## Recommendation

CHANGES_REQUESTED
