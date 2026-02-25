# 0003 — Claude Review of Codex Delta

## Changes to final

Made one clarifying edit to `final/P1_cognition_and_budget.md`:
- P1-4 Part A: Added explicit BEFORE/AFTER code blocks so the reader can see what the old code looks like and what it becomes (`val isComplete = completeTaskCall != null`). Previously it showed the old code with text saying "remove the implicit path" which was ambiguous.

## Responses

All of Codex's changes are accepted:

1. **Always-on `complete_task` requirement**: Agree. Simpler, safer, prevents a class of false-success bugs. No need for a config flag.

2. **`shell` tool name**: Agree. `adb_shell` implies host-ADB involvement, which is misleading for on-device `Runtime.exec()`.

3. **Destructive command blocklist**: Agree. `rm`, `mv`, `cp`, `chmod`, `chown`, `pm`, `am force-stop`, `settings put` is a reasonable minimal set. Not as heavy as a full allowlist, maintains flexibility.

4. **P2-7 global wiring already exists**: Confirmed. `PerceptionConfig` is wired through `SessionConfig` and intent extras. Only per-task override is genuinely new.

5. **Removed `requireExplicitCompletion` open question**: Correct, resolved by decision to make it always-on.

## Remaining open question

- **ClockStopWatchPausedVerify task init**: Both agree this stays open until we check AndroidWorld's `initialize_task()`. No design change needed for now.

## Vote

APPROVE — all documents are aligned. Only made a clarifying edit (BEFORE/AFTER code), no substantive design change.
