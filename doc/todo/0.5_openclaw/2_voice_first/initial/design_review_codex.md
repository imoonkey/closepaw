# Review Of `design_claude.md`

## Overall assessment

This is a strong design direction. It keeps voice out of `CapsuleMode`, reuses the existing text/session pipeline, and stays disciplined about summary-only TTS. The biggest weakness is ownership: making voice runtime session-scoped creates a bootstrap problem for the very first spoken task.

## Main concerns

### 1. Session-scoped voice cannot cleanly handle the first spoken task

The doc says the active `AgentSession` owns `VoiceSessionController`, and that fresh session creation creates a fresh controller. But the first spoken task happens before a session exists. That means there is no controller available yet to capture the utterance that is supposed to create the session.

This is not a small edge case. It is the primary "voice-first" entry point.

The design needs one of these clarifications:
- voice listening is service/app-scoped and routes into session creation, or
- there is a pre-session bootstrap controller separate from the session-scoped controller.

Without that, the lifecycle is incomplete.

### 2. Session ownership is cleaner for semantics, but worse for Android audio/runtime ownership

`AgentService` already owns:
- overlay lifetime,
- outside-app behavior,
- the active session binding,
- cross-context interaction state.

STT/TTS also behave more like app/service resources than conversation resources. Recreating them on every new session or reload adds lifecycle churn without much value, because the product only has one active session at a time anyway.

I think the doc underestimates this cost.

### 3. `WaitingForAction` needs a stricter voice rule

The proposal says `"done"` is accepted, or the transcript is sent as a user response. That is too loose for an action-completion state. `WaitingForAction` is normally a physical-world checkpoint, not a text-answer state. Arbitrary transcript fallback could hide user error instead of making the contract explicit.

I would restrict this state to:
- `"done"` -> complete,
- `"stop"` -> interrupt,
- otherwise keep listening result as draft text but do not auto-submit.

### 4. Event subscription point is still fuzzy

The design names `SpokenReplyPolicy`, but not the concrete event ownership path. The current app already routes session events through `AgentServiceEventHandler` and overlay/UI observers. The voice layer needs a single subscription point, otherwise it risks duplicate announcements or missed announcements when the app rebounds to an active session.

This needs one explicit sentence in the design.

## What the design gets right

- Shared typed/spoken routing is the right instinct. Text and voice must not encode different meanings for the same capsule state.
- Summary-only TTS is correct for v1.
- Keeping voice state orthogonal to task state is correct.
- Push-to-talk first is the right scope boundary.

## Recommended adjustment

Keep most of this design, but move the actual voice runtime owner one layer up:
- `AgentService` owns STT/TTS state and audio focus,
- the session still provides routing context and event semantics,
- the shared resolver stays common between typed and spoken input.

That preserves the good parts of this proposal while fixing the first-utterance bootstrap gap.
