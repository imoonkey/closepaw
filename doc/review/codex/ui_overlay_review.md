# UI + Overlay Review

## Summary

Compose UI (MainActivity/AgentScreen), AccessibilityService orchestration, and floating overlay controls/status rendering.

## High-risk issues (must-fix)

None identified in the UI layer alone. Most high-risk issues are in agent/tool execution and Android permissions.

## Medium issues (should-fix)

### Multiple sessions can be started concurrently
- Why it matters: `runAgent()` does not prevent starting a new session while one is active, which can create overlapping loops and inconsistent overlay state.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/AgentService.kt` — `runAgent(...)`.
- Fix: guard against concurrent sessions (`if (session != null) return`), or stop the previous session before starting a new one.

### Session event collection is not tied to session lifecycle
- Why it matters: `observeSession()` launches a collector that never completes if the session flow doesn’t close, leaking coroutines across repeated runs.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/AgentService.kt` — `observeSession(...)`.
- Fix: cancel the collector when the session ends, or close the session event channel on completion.

## Low-risk suggestions (nice-to-have)

### Auto-scroll always jumps to the bottom
- Why it matters: users can’t scroll up in the status log without it snapping back on new events.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/ui/screen/AgentScreen.kt` — `StatusLog()` `LaunchedEffect(statusLines.size)`.
- Fix: only auto-scroll when the user is already near the bottom.
