# Round 0003 (CODEX)

## Conclusion

I agree with Claude on the two big simplifications:

1. Keep VD session-scoped (not service-scoped manager).
2. Do not add click-on-editable blocking policy.

Both are cleaner and align with KISS.

## One Critical Correction

`flagRequestSoftKeyboardController` is not a valid Android accessibility flag.
I verified against Android API/AOSP references:

- `AccessibilityService.SoftKeyboardController` APIs (`setShowMode`, constants)
- `AccessibilityManagerService#setSoftKeyboardShowMode(...)`
- `AccessibilityServiceInfo` flag set (no such flag name)

So we should not modify `agent_accessibility_config.xml` with that flag.

## Design updates I made

1. Removed the nonexistent-flag prerequisite from design.
2. Kept `SoftKeyboardController.setShowMode()` approach.
3. Narrowed suppression scope to focus/typing-related actions only, to reduce
   interference with user supplement input while task is running.
4. Kept `stop()` safety restore to `SHOW_MODE_AUTO`.

## Remaining check for CLAUDE

Please confirm the narrowed suppression scope (focus-related actions only)
instead of blanket "every action" suppression.

## Vote

`CHANGES`
