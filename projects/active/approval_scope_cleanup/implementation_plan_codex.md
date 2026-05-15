# Operation Approval Scope Cleanup Implementation Plan

Date: 2026-05-15

## Phase 1: Remove one-shot approval end to end

This is a single independently committable phase. The change is intentionally
small: remove the obsolete `ONCE` scope, keep approval package-scoped, and update
the capsule prompt to match the real permission.

### Affected files

- `app/src/main/kotlin/ai/closepaw/protocol/ApprovalTypes.kt`
- `app/src/main/kotlin/ai/closepaw/protocol/Op.kt`
- `app/src/main/kotlin/ai/closepaw/session/AgentSession.kt`
- `app/src/main/kotlin/ai/closepaw/tool/ToolRouter.kt`
- `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleMode.kt`
- `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpec.kt`
- `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleControlBar.kt`
- `app/src/main/kotlin/ai/closepaw/app/ServiceOverlayController.kt`
- `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/IslandOverlayHost.kt`
- Unit and instrumentation tests covering render spec, approval transitions,
  router approval subject handling, and session allow-list mutation.
- `doc/main/**` approval/capsule/session/tool docs and `doc/PROGRESS.md`.

### Key decisions

- `ApprovalScope` has only `SESSION` and `ALWAYS`.
- Rejection stays current-call only. It does not create a deny-list.
- Positive approval must have an owning package before UI is shown.
- `open_app` approval uses the destination package when it is known.
- The approval prompt is app-level: `Allow ClosePaw to operate {AppName}?`
- Approval prompt body is empty; `expandedBody` remains for input/action modes.
- `ButtonsSpec.tertiary` is deleted; approval buttons use
  `primary -> secondary -> stop` as `Always -> Session -> Reject`.

### Verification

- Add/update focused unit tests first for render spec and router approval subject
  behavior.
- Run targeted tests while iterating.
- Run `./gradlew test`.
- Run instrumentation approval tests when a device is available.
- Do a local code review pass and write findings to the project directory.
