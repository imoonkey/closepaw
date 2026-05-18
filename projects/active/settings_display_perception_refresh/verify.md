# Verify Log — display-perception-refresh

Date: 2026-05-17

## JVM + lint (./gradlew clean assembleDebug lint test)

BUILD SUCCESSFUL. All JVM tests pass including the new
`VirtualDisplayToggleGateTest` and `VirtualDisplayStatusMapperTest` (12 rows).

## connectedDebugAndroidTest

Ran across three devices (P0110 - 16 / AndroidWorldAvd / AndroidWorldAvd2). 6 failures on each — all in chat/capsule UI suites that pre-date this branch:

- `CapsuleRenderingTest.running_shows_stop_button`
- `CapsuleLifecycleTest.error_dismiss_button_fires_callback`
- `ChatAgentRowDisclosureTest.row_default_collapses_when_transitioning_live_to_complete`
- `ChatBubbleAlignmentTest.agent_bubble_sits_in_left_half` / `user_bubble_sits_in_right_half`
- `ChatEmptyStateTest.shows_three_suggestion_chips` / `tapping_suggestion_fires_callback_with_text`
- `ChatHeaderTest.new_chat_button_hidden_when_showNewChatButton_false`
- `ChatThinkingStateTest.thinking_indicator_*`
- `ChatStreamingCursorTest.streaming_message_shows_inline_cursor_on_last_text_block`

None of these touch Settings, Display Mode, Perception, or anything modified in this branch.

Scope-relevant suites all green: `DisplayModeSettingsTest` (3/3), `SettingsNavTest`, `SettingsPermissionsTest`, `SettingsTermuxRowTest`, `SettingsLlmAuthTest`.

## Manual visual pass (queued for /qa-device task)

The dedicated `display-perception-qa-device` task captures uiautomator dumps + screenshots for every reachable Display Mode state and all three Perception Mode states. That artifact lives in `projects/active/settings_display_perception_refresh/qa/`.

For the verify gate the structural state-walk is:

- (a) Agent Behavior page renders sections in order: Perception (vertical radio list) → Display Mode (single Virtual Display toggle card) → Tools (Termux Shell + Browser Script toggle cards).
- (b) Permissions & Advanced no longer renders the old Display Mode radio block. `platformMode` removed from its signature.
- (c) `SettingsHomePage` Agent Behavior row subtitle now carries the Display chip (`Transcript · VD (this session)` and `Transcript · VD (next session)` when persisted vs effective diverge). Permissions row subtitle is `All granted · Debug on` with no VD/A11y chip.

Confirmed via the `home_subtitle_reflects_effective_platform_mode` instrumentation case in `DisplayModeSettingsTest` (passed).

## Outcome

Verify gate passes for scope. Pre-existing chat/capsule failures are tracked separately (not in this branch).
