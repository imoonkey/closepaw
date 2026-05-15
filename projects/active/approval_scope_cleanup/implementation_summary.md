# Operation Approval Scope Cleanup Implementation Summary

Date: 2026-05-15

## Implemented

- Removed `ApprovalScope.ONCE`; positive approval scopes are now only `SESSION`
  and `ALWAYS`.
- Made approval responses package-scoped. `ApprovalDetails.packageName`,
  `CapsuleMode.WaitingForApproval.packageName`, and approval callbacks now carry
  a non-null approval subject package.
- Updated `ToolRouter` so approval UI is emitted only when a valid package can
  own the approval. `open_app` uses `destinationPackage ?: packageName`, so the
  allow-list entry belongs to the destination app.
- Updated `AgentSession.handleApproval` so approved responses with invalid
  packages resolve as denied and never execute as one-shot approvals.
- Replaced the capsule approval prompt with:
  `Allow ClosePaw to operate {AppName}?`
  and buttons `[Always] [Session] [Reject]`.
- Removed `ButtonsSpec.tertiary`; approval uses `primary`, `secondary`, and
  `stop` in visual order.
- Kept `Reject` current-call only. It cancels the pending tool call and does not
  mutate any deny-list.

## Verification

- Targeted JVM tests passed:
  `./gradlew testDebugUnitTest --tests ai.closepaw.ui.overlay.model.CapsuleRenderSpecTest --tests ai.closepaw.tool.ToolRouterTest --tests ai.closepaw.tool.ToolCallStateTest --tests ai.closepaw.ui.overlay.CapsuleApprovalTransitionTest --tests ai.closepaw.session.AgentSessionTest`
- Full JVM test suite passed: `./gradlew test`.
- Android lint passed: `./gradlew lint`.
- Targeted device QA passed on P0110:
  `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.closepaw.qa.CapsuleApprovalTest`
- Diff hygiene passed: `git diff --check`.

Note: a prior full QA-package device run was intentionally stopped because the
device ROM showed a permission-controller interstitial for the test APK. The
targeted approval instrumentation run completed successfully, and the test APK
was not left installed for user 0.
