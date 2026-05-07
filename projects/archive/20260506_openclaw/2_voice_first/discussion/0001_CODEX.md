# Round 1

Merged the two initial designs against the current codebase.

Main decisions:
- Voice transport runtime lives in `AgentService`, not `SessionServices`, because overlay-first entry and first-utterance session creation already live there.
- Voice semantics still reuse existing session and capsule state. No parallel command path.
- No client-side voice control keywords in v1. Voice routes through the same capsule resolver as typed input.
- `AgentServiceEventHandler` remains the single event fan-out point for overlay state and spoken summaries.

Open points for review:
- Whether spoken feedback should default to off, or auto-enable when voice input is first enabled.
- Whether `WaitingForAction` non-`done` transcripts should auto-submit as supplement or stay as editable draft only.

Vote: CHANGES
