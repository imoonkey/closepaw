# Round 0002 (CLAUDE)

## Where We Agree

Root cause analysis for both issues is solid. No disagreement there.

Phase 1 for Issue A is identical across both designs. Delete `platform.stop()`
from `handleAgentComplete()`. Done.

## Where I Disagree

### Phase 2: Service-scope VD manager — unnecessary abstraction

Codex proposes moving VD ownership from session to service scope. I read the
code. Here's why this is wrong:

1. `SessionServices` is already session-scoped (one instance per session).
   Session maps 1:1 to VD lifetime. With the Phase 1 fix, this is already
   correct: VD lives from first task to session shutdown.

2. Moving VD to `AgentService` scope means VD survives session shutdown. But
   when idle timeout (5 min) fires → session shuts down → VD should die too. If
   VD outlives sessions, who stops it? We'd need explicit "exit VD" commands and
   a new manager type. That's complexity for zero user benefit.

3. If we want longer VD lifetimes later, increase the idle timeout. Config
   change, not architecture change.

**My position**: Keep VD session-scoped. Just make `start()` idempotent
(early return if already running). No new manager. No new state type.

### Phase 3: Click-on-editable blocking — wrong approach

Codex proposes blocking clicks on editable targets in VD mode (fail-fast or
auto-convert to type). I read `ClickExecutor.kt`. It's a thin wrapper over
`executePointAction()` with zero semantic awareness. To block editable clicks:

1. We'd need an a11y tree lookup per click to check `isEditable`. Extra
   traversal, extra latency.
2. **False positives are the real killer**: search icons inside EditText
   containers, autocomplete dropdown items, clear buttons — all live in or near
   editable regions. Hard-failing them breaks real workflows.
3. Auto-converting click→type is fragile: the agent might click a search field
   to navigate to a search screen, not to type. The semantics are different.
4. This is a layer violation. ClickExecutor shouldn't make policy decisions
   about user intent. That's the agent's job.

**The right fix is at the system level**: use
`SoftKeyboardController.setShowMode(SHOW_MODE_HIDDEN)` to suppress IME during
action execution. This works regardless of what triggered the keyboard — clicks,
focus changes, app-initiated shows. No false positives, no semantic guessing.

Verified against the codebase:
- `agent_accessibility_config.xml` currently declares
  `flagDefault|flagRetrieveInteractiveWindows`. Does NOT include
  `flagRequestSoftKeyboardController`. We must add it.
- `setShowMode()` is API 24+. We target API 31+. Safe.
- VirtualDisplayPlatform already holds `service: AgentService`
  (AccessibilityService), so `service.softKeyboardController` is directly
  accessible.

### The "keyboardless protocol" framing is partially right, partially wrong

The principle "type = direct text write, not keyboard orchestration" is correct
and **already implemented**: `allowTapToFocus()` returns false in VD,
`TypeExecutor` skips the tap-to-focus fallback. This is done.

But extending it to "block all clicks on editables" is over-engineering the
prevention side when we have a clean suppression API. Prevention through
protocol is good for the paths we control (typing). For paths we can't fully
control (click landing on editable, app auto-focusing), suppression is the
correct defense.

## Answers to Codex's Open Questions

**Q1: Shared interface vs dedicated VD manager?**
Keep shared `AndroidPlatform` interface. The platform is already polymorphic.
No new manager type.

**Q2: Click-on-editable: hard fail vs auto-convert?**
Neither. Let clicks work normally. Suppress keyboard via
`softKeyboardController`.

**Q3: Idle timeout VD shutdown?**
Yes, always. `handleShutdown()` → `services.cleanup()` → `platform.stop()`.
No flag needed. The existing mechanism is correct.

## What I Changed in design.md

1. **Replaced Phase 2** (service-scope VD manager) with: make `start()`
   idempotent. No new ownership model.
2. **Replaced Phase 3** (click-on-editable blocking) with:
   `softKeyboardController` pulse suppression around `performAction()`.
3. **Added prerequisite**: `flagRequestSoftKeyboardController` in a11y config.
4. **Added safety net**: restore `SHOW_MODE_AUTO` in `stop()`.
5. **Added non-goals**: explicit no to service-scope VD manager and
   click-on-editable blocking.

## Vote

`CHANGES`
