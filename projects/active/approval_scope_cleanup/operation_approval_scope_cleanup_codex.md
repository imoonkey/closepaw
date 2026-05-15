status: draft

# Operation Approval Scope Cleanup Design (Codex)

Date: 2026-05-15

## Goal

Remove one-shot operation approval from the Smart Capsule approval prompt.

The user-facing approval choices become exactly:

1. `Always` - green approval button
2. `Session` - green approval button
3. `Reject` - red rejection button for the current pending approval only

There should be no `Once`, `Allow`, or one-time approval path left in the product behavior.

The prompt must be phrased as app-level automation permission, not as one action
approval. `Unknown app`, `CAUTIOUS`, package names, and policy reasons are internal
concepts; users should see the app display name.

## Current State

The approval prompt is driven by `CapsuleMode.WaitingForApproval` and rendered through:

- `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpec.kt`
- `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleControlBar.kt`
- `app/src/main/kotlin/ai/closepaw/protocol/ApprovalTypes.kt`
- `app/src/main/kotlin/ai/closepaw/session/AgentSession.kt`
- `app/src/main/kotlin/ai/closepaw/tool/ToolRouter.kt`

Today the prompt exposes four choices:

- `Allow` -> `ApprovalScope.ONCE`
- `Session` -> `ApprovalScope.SESSION`
- `Always` -> `ApprovalScope.ALWAYS`
- `Deny` -> `ApprovalDecision.DENIED`

`ONCE` has no policy side effect in `AgentSession.handleApproval`; it only lets the pending tool call continue. This is the source of repeated prompts and an extra branch in the approval model.

The current prompt text is also action-scoped:

- title: `Approve action?`
- body: `${description}\n${appLabel} · ${reason}`

That text is wrong for the new model because `Session` and `Always` are per-app
allow-list entries. Approval no longer means "do this one thing"; it means
"let ClosePaw operate this app while the chosen scope is active."

## Approach

### 1. Make approvals package-scoped only

`ApprovalScope` should only contain:

```kotlin
enum class ApprovalScope { SESSION, ALWAYS }
```

There is no compatibility shim for `ONCE`. This app is pre-release and the cleaner model is better: every positive approval writes an allow-list entry either for the current session or persistently.

`Op.Approve.scope` can keep a conservative default of `ApprovalScope.SESSION` because rejection ignores scope, but production call sites should continue passing scope explicitly.

### 2. Canonicalize the approval subject package

Session and persistent approval require a valid package name. Instead of preserving the current `packageName == null` UI state, make package ownership a prerequisite for an approval prompt.

In `ToolRouter.execute`:

- Continue using the current foreground package and optional `destinationPackage` for `PolicyEngine.check`.
- Introduce an `approvalPackageName = destinationPackage ?: packageName`.
- If policy returns `AskUser` and `approvalPackageName == null`, fail closed with a cancelled result such as `Policy denied: approval package unknown`.
- Send `approvalPackageName` through `ApprovalDetails.packageName`.

This also fixes the `open_app` case: if approval is required because the destination app is cautious, the allow-list entry should belong to the destination package, not whatever app happened to be foreground before launch.

### 3. Render the new three-button prompt

For `CapsuleRenderSpec.from(WaitingForApproval)`, use app-level copy:

```kotlin
thought = ThoughtSpec("Allow ClosePaw to operate ${mode.appLabel}?")
expandedBody = null
```

Do not render `mode.reason` in the user-facing body. Keep it for logs, traces, and
tests. Do not render package names unless app-label resolution fails, and package
resolution failure should normally fail closed before UI.

Do not show the current action description or agent thought in `expandedBody`.
Approval is app-level trust after removing `ONCE`; showing a specific action makes
the prompt read like one-action approval again. Agent thoughts are also not a stable
permission contract and can be too long for the capsule. The app-level title plus the
three duration/rejection buttons is the whole prompt.

Do not delete `CapsuleRenderSpec.expandedBody` globally. It is shared by
`WaitingForInput` and `WaitingForAction` for user questions and user instructions.
Only the `WaitingForApproval` render branch should stop using it.

Simplify the button spec at the same time. `tertiary` only exists to support the old
four-button approval prompt, so delete it:

```kotlin
data class ButtonsSpec(
    val primary: ButtonSpec?,
    val secondary: ButtonSpec? = null,
    val stop: ButtonSpec?,
)
```

Button spec:

```kotlin
buttons = ButtonsSpec(
    primary = ButtonSpec("Always"),
    secondary = ButtonSpec("Session"),
    stop = ButtonSpec("Reject"),
)
```

`ActionButtonCluster` renders buttons in `primary`, `secondary`, `stop` order, so
this produces the requested visual order without an approval-specific layout.

Click mapping:

- `Always` -> `ApprovalDecision.APPROVED`, `ApprovalScope.ALWAYS`
- `Session` -> `ApprovalDecision.APPROVED`, `ApprovalScope.SESSION`
- `Reject` -> `ApprovalDecision.DENIED`, no deny-list mutation

Positive approval buttons should use the existing green role, `MaterialTheme.colorScheme.secondary` / `onSecondary`, for both `Always` and `Session`. Rejection keeps the existing error border/text treatment using `MaterialTheme.colorScheme.error`.

The copy and buttons now answer the same question:

```text
Allow ClosePaw to operate Chrome?

[Always] [Session] [Reject]
```

### 4. Keep state machine semantics unchanged

`WaitingForApproval` remains one state. Resolution is still call-id scoped:

```text
WaitingForApproval -- Always --> Running, persist package allow-list
WaitingForApproval -- Session --> Running, session package allow-list
WaitingForApproval -- Reject  --> Running after cancelling this pending tool call
```

Invalid or stale approvals still do not mutate policy state. Approved responses with a missing or invalid package should resolve as denied or be dropped before resolving the router deferred; do not execute an approved tool call that cannot be tied to a package.

`Reject` is intentionally not session-level or persistent. There is no deny-list in
this change. A session-level or always-level rejection would be a separate policy
feature with its own storage, management UI, and recovery path. Here, rejection means
"do not run the current pending tool call"; future attempts in the same app may ask
again unless another policy rule denies them.

## Components

- `ApprovalTypes.kt`: remove `ONCE`; update enum docs.
- `Op.kt`: update `Approve.scope` docs and default.
- `AgentSession.kt`: remove the `ONCE` branch; validate approved package before resolving the pending approval.
- `ToolRouter.kt`: compute `approvalPackageName`; fail closed before emitting approval UI when no package can own the approval.
- `CapsuleRenderSpec.kt`: change approval title/body and button spec to app-level `Always`, `Session`, `Reject`; set approval `expandedBody = null`; keep `expandedBody` for non-approval modes; remove `ButtonsSpec.tertiary`.
- `CapsuleControlBar.kt`: map primary approval action to `ALWAYS`, secondary to `SESSION`, delete tertiary rendering, use green positive button colors.
- `CapsuleMode.kt`: update comment from `Allow/Session/Always/Deny` to `Always/Session/Reject`.
- App-label resolution path: ensure `appLabel` is a display name such as `Chrome`, not `com.android.chrome`; package names and internal policy terms stay out of the prompt.
- Docs: update `doc/main/state_machines/ui_capsule.md`, `doc/main/infra/session.md`, and any protocol docs that mention `ONCE`, `Allow`, or `Deny` for this flow.
- Tests: update Compose UI and render-spec tests.

## Tasks

### approval-protocol-remove-once

Scope:

- `app/src/main/kotlin/ai/closepaw/protocol/ApprovalTypes.kt`
- `app/src/main/kotlin/ai/closepaw/protocol/Op.kt`
- `app/src/main/kotlin/ai/closepaw/session/AgentSession.kt`
- `app/src/test/kotlin/ai/closepaw/session/**`

Acceptance criteria:

- `ApprovalScope.ONCE` no longer exists.
- Approved session and always scopes still mutate the correct allow-list.
- Rejected approvals cancel only the pending tool call and do not create session or persistent deny-list entries.
- Stale approvals still do not mutate allow-lists.
- Approved responses without a valid package cannot execute as one-shot approvals.

Dependencies: none.

### approval-subject-package

Scope:

- `app/src/main/kotlin/ai/closepaw/tool/ToolRouter.kt`
- `app/src/test/kotlin/ai/closepaw/tool/**`

Acceptance criteria:

- Approval prompts carry `destinationPackage ?: packageName`.
- `AskUser` with no approval subject package fails closed before UI notification.
- `open_app` approvals persist the destination package.
- Existing TOCTOU guard for foreground app changes remains intact for current-app actions.

Dependencies: `approval-protocol-remove-once`.

### approval-capsule-buttons

Scope:

- `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpec.kt`
- `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleControlBar.kt`
- `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleMode.kt`
- `app/src/test/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpecTest.kt`
- `app/src/androidTest/kotlin/ai/closepaw/qa/CapsuleApprovalTest.kt`

Acceptance criteria:

- Waiting approval renders `Always`, `Session`, `Reject` only.
- `ButtonsSpec.tertiary` is removed; `ActionButtonCluster` has no tertiary branch.
- Title is `Allow ClosePaw to operate {AppName}?` using app display name.
- `expandedBody` is null; the prompt does not show action descriptions, agent thoughts, `Unknown app`, `CAUTIOUS`, package names, or raw policy reasons.
- `expandedBody` still exists for `WaitingForInput` and `WaitingForAction`.
- Order is `Always`, then `Session`, then `Reject`.
- `Always` and `Session` are green.
- `Reject` is red.
- Tapping `Always` sends `ApprovalScope.ALWAYS`.
- Tapping `Session` sends `ApprovalScope.SESSION`.
- `Allow`, `Once`, and `Deny` are absent from this prompt.

Dependencies: `approval-protocol-remove-once`.

### approval-docs-verify

Scope:

- `doc/main/state_machines/ui_capsule.md`
- `doc/main/infra/session.md`
- `doc/main/infra/tools.md`
- `doc/main/data_schemas.md`

Acceptance criteria:

- Docs describe only package-scoped approval scopes.
- UI state machine documents app-level prompt copy and `[Always] [Session] [Reject]`.
- Verification runs:
  - `./gradlew test`
  - `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=ai.closepaw.qa` when a device is available

Dependencies: all implementation tasks.

## Trade-offs

### Remove `ONCE` from protocol vs hide it in UI

Remove it. Hiding it leaves dead semantics that future call sites can accidentally reuse. The requested behavior is not "hide the button"; it is "delete the tier".

### Fail closed on missing package vs keep a one-off fallback

Fail closed. A session or persistent allow-list entry needs a package. If the platform cannot identify the package, approving the action would recreate the one-shot path under another name.

### Current-only reject vs session or persistent deny

Keep `Reject` current-only. Deny-list behavior is more dangerous than approval cleanup:
users need a way to inspect, undo, and distinguish "not now" from "never operate this app".
This design removes one-shot positive approval without adding a new negative permission model.

### Keep `tertiary` vs simplify `ButtonsSpec`

Delete `tertiary`. It is now dead structure from the old four-button approval prompt.
The remaining `primary -> secondary -> stop` order directly models `Always -> Session -> Reject`.

## Self-review

- The design fully removes the one-time approval branch from UI, protocol, docs, and tests.
- The `packageName == null` edge case is handled as a canonical policy failure, not a special UI fallback.
- The prompt now matches the actual permission: app-level operation permission with a session or persistent lifetime, with compact capsule-safe body copy.
- Internal policy labels are kept out of user-facing copy.
- The visual request maps cleanly to existing Material roles: green positive buttons, red rejection button.
- The change is intentionally small: no new state machine, no new persistence model, no migration layer, and one obsolete render slot removed.
