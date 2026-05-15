# QA: Operation Approval Scope Cleanup

## Result

PASS.

## Flows Verified

- User sees an app-level approval prompt:
  `Allow ClosePaw to operate Chrome?` with `[Always] [Session] [Reject]`:
  PASS via `CapsuleApprovalTest` on P0110.
- User taps `Always`; UI dispatches `ApprovalDecision.APPROVED` with
  `ApprovalScope.ALWAYS` and the app package: PASS via `CapsuleApprovalTest`.
- Router asks only when a valid app package can own the approval; missing package
  fails closed before UI: PASS via `ToolRouterTest` / `ToolCallStateTest`.
- `open_app` approval subject and allow-list lookup use destination package:
  PASS via `ToolRouterTest` and `PolicyEngineTest`.
- Reject remains current-call only and does not mutate allow-lists:
  PASS via `AgentSessionTest` stale/mutation guards and reviewed
  `AgentSession.handleApproval`.

## Commands

- `./gradlew test`
- `./gradlew lint`
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.closepaw.qa.CapsuleApprovalTest`
- `git diff --check`

## Notes

A prior full QA-package instrumentation run was stopped because the device ROM
showed a permission-controller interstitial for the test APK. The targeted
approval instrumentation run completed successfully, and no `ai.closepaw.test`
package remains installed for user 0.
