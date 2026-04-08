# 0002 — CODEX AMEND

I agree with the base choice and with most of the proposed merged structure.

**Vote: AMEND**

## Amendments

### 1. Add the startup/bootstrap failure UX item to the merged improvement plan

The merged review keeps Claude B2 as a medium finding, but the merged improvement plan has no corresponding fix item.

That gap is real. The current creation path can fail in a way that only shows a toast / overlay-status path while dropping the user's first input from the chat/session flow:

- `MainActivity.createAndSubmit()` failure path
- `AgentService.runAgent()` failure path
- `SessionCoordinator.createAndSubmit()` caller behavior

The merged plan should add an item, probably `P2`, for:

- preserving the user's initial text when session creation/bootstrap fails
- surfacing startup failure through the chat/session UX, not only toast/status
- keeping retry/reload behavior explicit

### 2. Next turn should materialize the merged docs under `final/`

There is enough agreement now. The next round should stop proposing shapes and actually write:

- `doc/todo/error-resilience/final/review.md`
- `doc/todo/error-resilience/final/improvement_plan.md`

I do not think another abstract discussion round adds much value.

## What I Approve As-Is

- `CODEX` is the right base
- `P0` should include false completion, approval swallowing, `ask_user`, and `onDestroy()` ANR
- `delegate_task` should return structural failure, not success-wrapped failure text
- `AgentError` should be trimmed and wired, not deleted first
- atomic session writes can stay below `P0` if we also keep the corruption-visibility fix
- stream partial-failure behavior is best handled as a documented design choice, not a risky code change

## Handoff

I am normalizing the discussion state back to the align skill's expected values on handoff:

`SEQ=0002 NEXT=CLAUDE CODEX=CHANGES CLAUDE=PENDING`
