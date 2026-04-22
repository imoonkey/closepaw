# Review of Codex Voice-First Design

Reviewer: Claude
Reviewed: `design_codex.md`

---

## What works well

1. **Voice state orthogonal to CapsuleMode.** Correct call. `CapsuleMode` already has 9+ variants modeling task state; mixing transport state in would create a combinatorial explosion. Both designs agree here.

2. **Shared input resolver.** The routing table (capsule mode → Op type) matches how `SmartCapsuleSurface` already dispatches typed input. Reusing the same mapping is the right move.

3. **Summary-only TTS policy.** The "speak only high-value events" list is well-scoped. Excluding `MessageDelta` and tool cards avoids the most common annoyance path.

4. **Phase rollout with AUTO_TALK as Phase 2.** Cleanly separates push-to-talk (safe MVP) from auto-rearm (needs tuning). Good that wake word is Phase 3.

5. **Barge-in rule is simple and correct:** starting STT always stops TTS first. No overlapping states.

---

## Issues

### 1. Service-scoped voice ownership is the wrong lifecycle (Medium)

The design places `VoiceInteractionManager` in `AgentService`. The stated reason is that the service "survives outside the main app" and "owns the overlay."

Problem: `AgentService` is an `AccessibilityService`. Its lifecycle is controlled by the system — it can be killed and recreated unpredictably. It has no `onBind`/`onUnbind` contract with the app. Today it holds a volatile `instance` singleton and the overlay controller, but it does **not** own session lifecycle — `SessionCoordinator` does, and it lives in the app process.

Voice state is conversational: it tracks which session is active, what the last `ask_user` prompt was, and whether to re-arm listening. That maps to session lifetime, not service lifetime. If the accessibility service is toggled off/on by the system, a service-scoped voice controller loses all conversational context. A session-scoped controller (`SessionServices`) gets this for free — it dies with the session and is recreated cleanly.

The overlay callbacks can still delegate to the session's voice controller via `AgentService.instance?.activeSession?.voiceController` without the service owning the controller.

**Recommendation:** Move `VoiceInteractionManager` ownership to session scope (inside `SessionServices`). Let `AgentService` hold a thin binding to the active session's voice controller for overlay UI integration.

### 2. `VoiceCommandRouter` control-word matching is fragile (Medium)

The design introduces special voice commands:
- `"stop"` → `Op.Interrupt`
- `"take over"` → `Op.Takeover`
- `"resume"` → `Op.Resume`

This is risky:
- STT may transcribe "stop" when the user says "stop the alarm" (intended as a task, not an interrupt).
- "Take over" could appear in a sentence like "I want to take over from here" where the intent is a task description.
- Exact string matching on speech transcripts is inherently unreliable.

The existing app handles these commands through explicit UI buttons (Takeover/Resume/Stop in CapsuleRow2), not text input. Moving them to voice keywords creates a new, unvalidated input path that bypasses the button-based intent signals.

**Recommendation:** Drop control-word routing in v1. Keep Takeover/Resume/Stop as button-only. Voice input should only produce `Op.UserInput`, `Op.Supplement`, or `Op.UserResponse` — the same ops that typed text produces. If voice control commands are desired later, they should go through the agent (let the LLM interpret "stop" in context) rather than client-side keyword matching.

### 3. Missing `callId` resolution for `WaitingForAction` (Low)

The routing table says `WaitingForAction` maps transcript to `Op.UserResponse(callId, transcript.ifBlank { "done" })`. But `callId` is not mentioned as flowing into the voice layer. Today `callId` lives in the approval/ask-user event and is tracked in `CapsuleStateHolder` or `ChatViewModel`. The design should specify how `VoiceCommandRouter` obtains the active `callId` — likely by observing it from the same state holder that the capsule UI uses.

### 4. Default `spokenFeedbackEnabled = true` may surprise users (Low)

The design defaults spoken feedback to ON while voice input defaults to OFF. This means the app will start speaking TTS summaries to users who never opted into voice features. Since the app runs as an accessibility service (often in public/quiet contexts), unexpected audio output could be disruptive.

**Recommendation:** Default both to OFF. Enable spoken feedback automatically when the user first activates voice input, or prompt them in settings.

### 5. No audio focus failure strategy beyond "fall back to text" (Low)

The design says "if focus is denied, surface a visible voice error and fall back to text-only interaction." On Android, audio focus denial is common (phone calls, navigation, other assistants). The design should specify whether this is a transient fallback (retry on next mic tap) or a sticky disable. Transient is correct — just worth stating.

### 6. File count is slightly high for the scope (Nit)

Six new files (`VoiceInteractionManager`, `SpeechRecognizerController`, `SpeechOutputController`, `VoiceCommandRouter`, `VoiceSummaryFormatter`, `VoiceStateHolder`) for what amounts to ~300-400 lines of logic. `VoiceStateHolder` is a single sealed class that could live in `VoiceInteractionManager`. `VoiceCommandRouter` should be dropped per issue #2, or folded into the shared input resolver.

---

## Comparison with my design

| Dimension | Codex | Claude | Assessment |
|-----------|-------|--------|------------|
| Voice lifecycle owner | `AgentService` (service-scoped) | `SessionServices` (session-scoped) | Session-scoped is safer — see issue #1 |
| Control-word routing | Client-side keyword matching | Not included | Omitting is safer for v1 — see issue #2 |
| Input resolver | `VoiceCommandRouter` (new, voice-specific) | `CapsuleInputActionResolver` (shared typed+spoken) | Shared resolver is simpler and prevents drift |
| Auto-talk in Phase 2 | Explicit `AUTO_TALK` mode in settings | Not in scope | Good addition by Codex — worth adopting |
| Default TTS | ON | OFF | OFF is safer — see issue #4 |
| Overlay permission handling | Deep-link back to MainActivity | Disable mic button | Codex's deep-link is more helpful |

---

## Summary

The Codex design is solid and closely aligned on the core decisions (orthogonal voice state, shared routing, summary-only TTS, push-to-talk first). The two substantive issues are:

1. **Service-scoped ownership** should be session-scoped to match conversation lifecycle.
2. **Control-word routing** should be dropped in v1 to avoid false-positive command triggers from speech transcripts.

The Phase 2 auto-talk concept and the overlay deep-link for missing permissions are good ideas worth adopting in the merged design.
