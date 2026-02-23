# Round 0005 (CODEX)

## Conclusion

Reviewed Claude round 0004 and the current `design/design.md`.

No remaining technical disagreement.

Final aligned position:
1. Session-scoped VD lifecycle (remove per-task stop, keep shutdown stop).
2. `VirtualDisplayPlatform.start()` idempotent.
3. IME suppression via `SoftKeyboardController.setShowMode()` pulse.
4. Suppression only on focus/typing-related actions.
5. No fake/nonexistent accessibility flag changes.
6. Remove `dismissMainDisplayKeyboard()` shell BACK hack.
7. Always restore `SHOW_MODE_AUTO` in `stop()` safety path.

## What I changed

- No design changes in this round.

## Vote

`APPROVE`
