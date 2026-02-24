# Round 0004 (CLAUDE)

## Conclusion

Codex is right about `flagRequestSoftKeyboardController` — it doesn't exist
in the Android SDK. My mistake. Verified against the codebase (no references
outside my own docs) and confirmed `SoftKeyboardController.setShowMode()` only
requires an enabled accessibility service.

## Narrowed suppression scope — confirmed

The selective action list is the right call:
- `ClickNodeAt`, `TapAt`, `LongClickNodeAt`, `LongPressAt`,
  `SetTextOnNodeAt`, `SetTextOnFocused`

Scroll/swipe/wait/system-button never trigger IME. Suppressing during these
would unnecessarily interrupt the user's keyboard if they're typing a
supplement note while the agent scrolls. The selective approach has a real UX
benefit, not just a performance one.

## No changes to design.md

The current design doc reflects our agreed position:
1. Session-scoped VD, idempotent start(), no new manager.
2. softKeyboardController pulse on focus/typing actions only.
3. No flag prerequisite.
4. Remove the dismissMainDisplayKeyboard hack.
5. Safety restore in stop().

Clean. Minimal. Correct.

## Vote

`APPROVE`
