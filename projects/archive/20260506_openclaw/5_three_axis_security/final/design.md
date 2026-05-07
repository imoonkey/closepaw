# Three-Axis Security for Android Agent

## Context

The current repo already has a real safety seam:

- `SessionConfig` carries one `approvalMode`
- `SessionToolingBootstrapper` creates one `PolicyEngine`
- `PolicyEngine` maps canonical tools and actions to `RiskLevel`
- `ToolRouter` converts that into allow / ask / deny
- approval state reaches the UI through `ApprovalRequired` and `ApprovalResolved`

That is a solid starting point, but it overloads one control with three different jobs:

1. trust the operating context
2. classify what the tool is allowed to do
3. decide whether a stronger override is allowed

OpenClaw's useful lesson is to separate those jobs. On Android, the exact axes should be adapted to mobile reality.

## Design goals

- Make the three axes explicit and orthogonal
- Keep the implementation centered on the existing policy path
- Keep Phase 1 small and useful
- Avoid backward-compat translation layers and ad hoc special cases

## Non-goals

- Docker-style sandboxing on Android
- Prompt-only safety heuristics
- A giant settings matrix with per-tool switches everywhere

## The aligned model

### Axis 1: supervision context

This is the Android equivalent of OpenClaw's sandbox axis. The important question is not "container or not". The important question is "can the user supervise this action right now".

```kotlin
enum class SupervisionContext {
    LOCAL_FOREGROUND,
    LOCAL_BACKGROUND,
    REMOTE
}
```

- `LOCAL_FOREGROUND`: the user is on-device and can see prompts immediately
- `LOCAL_BACKGROUND`: started locally, but prompts are easier to miss
- `REMOTE`: off-device control path; strongest safety floor

This axis affects the minimum approval requirement, but it does not decide capability by itself.

### Axis 2: capability policy

This is the main practical Phase 1 axis.

Use a small semantic catalog for canonical tools/actions:

```kotlin
enum class CapabilityClass {
    OBSERVE,
    NAVIGATE,
    EDIT,
    COMMIT
}
```

Canonical mapping:

- `OBSERVE`: `wait`, `scratchpad`, `write_todos`, current read-only `shell`
- `NAVIGATE`: `open_app`, `system_button`, `mobile_action.click`, `scroll`, `swipe`
- `EDIT`: `mobile_action.type`
- `COMMIT`: actions that send, delete, confirm purchase, or otherwise create hard-to-undo side effects

The catalog should be owned by `PolicyEngine`, not scattered across enum constructors and random `when` branches.

For approval output and UI, derive a simple decision severity:

```kotlin
enum class RiskClass {
    SAFE,
    MODERATE,
    HIGH
}
```

Recommended default derivation:

- `OBSERVE` -> `SAFE`
- `NAVIGATE` -> `MODERATE`
- `EDIT` -> `MODERATE`
- `COMMIT` -> `HIGH`

The semantic class is the source of truth. The risk band is the rendered decision summary.

### Axis 3: elevation policy

Elevation is separate from normal high-risk approval.

High risk means "this needs confirmation".
Elevation means "this requests power outside the normal capability profile".

```kotlin
data class ElevationPolicy(
    val enabled: Boolean = false,
    val allowedScopes: Set<ElevatedScope> = emptySet()
)
```

Use elevation only for genuine escape hatches:

- future unrestricted shell
- future destructive filesystem operations
- future remote bypass experiments

Do not use elevation for ordinary `COMMIT` actions. Those stay inside normal approval flow.

## Session model

Replace `approvalMode` in `SessionConfig` with:

```kotlin
data class SessionSecurityConfig(
    val supervisionContext: SupervisionContext = SupervisionContext.LOCAL_FOREGROUND,
    val capabilityPolicy: CapabilityPolicy = CapabilityPolicy.default(),
    val elevationPolicy: ElevationPolicy = ElevationPolicy()
)
```

Then embed it directly:

```kotlin
data class SessionConfig(
    // existing fields...
    val security: SessionSecurityConfig = SessionSecurityConfig()
)
```

This becomes the single source of truth for session safety. No compatibility wrapper around the old enum.

## Policy engine shape

Keep one policy engine, but give it richer input:

```kotlin
data class PolicyCheckRequest(
    val toolName: String,
    val params: JSONObject,
    val security: SessionSecurityConfig,
    val packageName: String?,
    val sensitivityTags: Set<String> = emptySet()
)
```

Decision flow:

1. Resolve canonical subject from tool/action name
2. Map subject to `CapabilityClass`
3. Derive base `RiskClass`
4. Raise the floor using `supervisionContext`
5. If the subject requires elevation and no grant exists, deny
6. Return allow / ask / deny with an explicit reason

This preserves the current architecture:

- `SessionToolingBootstrapper` passes security config into `PolicyEngine`
- `ToolRouter` still owns lifecycle and approval waiting
- approval events stay unchanged structurally, but their payload gets richer

## Sensitive-operation escalation

The source brief is right that generic clicks are not always generic:

- clicking "Send"
- clicking "Pay now"
- clicking "Delete"

Those cases should become stricter than ordinary navigation. But this must be deterministic.

Phase 2 adds sensitivity tags owned by app/package metadata:

```kotlin
enum class SensitivityTag {
    MESSAGE_SEND,
    PAYMENT,
    DESTRUCTIVE
}
```

Ownership:

- app-skill metadata
- package-specific safety rules
- deterministic labels from resolved targets

Non-ownership:

- free-form LLM guesses
- prompt-only policy branches

If a package rule marks the target as `PAYMENT`, the policy engine upgrades the subject to `COMMIT` even if the raw tool is just `click`.

## UI and event changes

Keep the current approval event flow and enrich the payload:

`ApprovalDetails` should add:

- `capabilityClass`
- `riskClass`
- `supervisionContext`
- `policyReason`
- `sensitivityTags`

The approval UI can then explain why the prompt exists without inventing a new event system.

Settings should stay minimal:

- safety preset for local sessions
- optional "ask before edit" toggle
- remote sessions always force `HIGH` approval for `COMMIT`

Do not expose a giant per-tool UI in the normal product surface.

## Code changes by layer

### Session layer

- `SessionConfig`: replace `approvalMode` with `security`
- `ConversationConfigSnapshot`: persist security fields
- `MainActivity` and `AgentService`: construct `SessionSecurityConfig` from entry mode and settings

### Tool bootstrap layer

- `SessionToolingBootstrapper.create(security: SessionSecurityConfig)`
- `SessionServices.updateApprovalMode()` becomes `updateSecurityConfig()`

### Policy layer

- move default policy catalog ownership into `PolicyEngine`
- keep canonical naming in `ToolName` / `MobileActionName`
- remove risk defaults from enum constructors
- add deterministic sensitivity escalation hook

### Protocol/UI layer

- extend `ApprovalDetails`
- keep `ApprovalRequired` / `ApprovalResolved`
- update approval UI labels only; no new flow needed

## Rollout

### Phase 1: capability-first rollout

- replace `approvalMode` with `SessionSecurityConfig`
- implement `SupervisionContext`
- implement semantic capability catalog and derived `RiskClass`
- local default:
  - `OBSERVE`: auto
  - `NAVIGATE`: auto
  - `EDIT`: auto by default, configurable to ask
  - `COMMIT`: ask

This is enough to satisfy the source brief's main point: orthogonal safety axes with tool risk as the first useful step.

### Phase 2: sensitivity escalation

- add app/package-owned sensitivity tags
- upgrade send/pay/delete flows to `COMMIT`
- ensure prompt text includes the detected sensitivity reason

### Phase 3: remote entry

- add real remote entry points
- force `COMMIT` approval for remote sessions
- require first-time device approval on the phone

## Key decisions

- No fake Android sandbox axis. Use supervision context instead.
- No second policy subsystem. Extend the existing `PolicyEngine`.
- No backward-compat wrapper around `ApprovalMode`.
- No prompt-owned safety logic for send/pay/delete detection.
- Phase 1 ships mostly in `SessionConfig`, `PolicyEngine`, `ToolRouter`, and approval payloads.

## Why this is the right cut

It keeps the design simple and honest.

The repo already has the correct execution choke point. The missing piece is not more plumbing; it is separating three different policy questions so the system can evolve without turning `PolicyEngine` into a pile of exceptions.
