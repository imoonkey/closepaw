# 5 Three-Axis Security - Claude Design

## Thesis

OpenClaw's useful lesson is not Docker. The useful lesson is that safety should answer three different questions with three different mechanisms:

1. Is this request coming from a trusted operating context?
2. Is this capability allowed at all?
3. If the request wants extra power, can it be elevated?

Android Agent already has one partial answer to question 2 in `PolicyEngine`, but questions 1 and 3 are missing, and question 2 is still too coarse.

## Current State

The repo today has:

- `RiskLevel` with `LOW / MEDIUM / HIGH`
- canonical tool/action names in `ToolName` and `MobileActionName`
- `PolicyEngine.check()` returning `Allow`, `Deny`, or `AskUser`
- approval events flowing through `ToolRouter`, `TurnExecutionPhaseRunner`, and `AgentSession`

That means the architecture seam already exists. The design should extend it, not replace it with a second policy subsystem.

## Design Direction

### Axis 1: Execution context

Introduce a small context enum:

```kotlin
enum class ExecutionContext {
    LOCAL_FOREGROUND,
    LOCAL_BACKGROUND,
    REMOTE
}
```

This is the Android equivalent of OpenClaw's sandbox axis. The real question on mobile is not "container or not", it is "is the user present and able to supervise the action right now".

Rules:

- `LOCAL_FOREGROUND`: normal mobile default
- `LOCAL_BACKGROUND`: stricter than foreground, because prompts are easier to miss
- `REMOTE`: strongest floor, especially for side-effecting actions

### Axis 2: Capability class

Replace raw risk defaults with stable capability classes:

```kotlin
enum class CapabilityClass {
    OBSERVE,
    NAVIGATE,
    EDIT,
    COMMIT
}
```

Mapping:

- `OBSERVE`: `wait`, read-only shell, future screenshot/tree reads
- `NAVIGATE`: `open_app`, `system_button`, `mobile_action.click`, `scroll`, `swipe`
- `EDIT`: `mobile_action.type`
- `COMMIT`: anything that can send, confirm purchase, delete, or submit irreversible content

This is better than a plain risk number because it encodes user intent. The approval policy can still derive `RiskLevel` for UI and events, but the catalog itself should speak in domain terms.

### Axis 3: Elevation gate

Introduce a one-shot elevation token:

```kotlin
data class ElevationGrant(
    val scope: ElevatedScope,
    val expiresAtMs: Long
)
```

Used only for truly exceptional capabilities:

- future unrestricted shell
- future destructive filesystem operations
- future "remote session may bypass local prompt" experiments

Do not use this for ordinary high-risk actions. High-risk actions should stay in the normal approval path. Elevation exists so emergency escape hatches stay explicit and auditable.

## Decision Model

Keep one policy engine, but give it richer input:

```kotlin
data class PolicyContext(
    val executionContext: ExecutionContext,
    val capabilityClass: CapabilityClass,
    val elevationGrant: ElevationGrant?,
    val taskSensitivity: TaskSensitivity = TaskSensitivity.NORMAL
)
```

Decision algorithm:

1. Resolve tool/action to `CapabilityClass`
2. Raise severity when `taskSensitivity` is `MESSAGE_SEND`, `PAYMENT`, or `DESTRUCTIVE`
3. Apply the execution-context floor
4. If elevation is required and missing, deny
5. Return `Allow`, `AskUser`, or `Deny`

## How task sensitivity works

This is the practical part missing from the current repo.

A generic click is not always generic:

- click "Send" in a chat app
- click "Pay now"
- click "Delete"

Those should not rely on static tool risk alone.

So Phase 2 adds app-skill owned sensitivity hints:

```kotlin
data class TaskSensitivityRule(
    val packageName: String,
    val triggerTexts: Set<String>,
    val sensitivity: TaskSensitivity
)
```

The policy layer stays generic. Package-specific knowledge lives with package-specific skills or metadata.

## Data Model Changes

### Session config

Add:

```kotlin
data class SessionSecurityProfile(
    val executionContext: ExecutionContext = ExecutionContext.LOCAL_FOREGROUND,
    val requireApprovalFrom: CapabilityClass = CapabilityClass.COMMIT,
    val elevationEnabled: Boolean = false
)
```

This can live inside `SessionConfig` as `securityProfile`.

### Approval payloads

Extend `ApprovalDetails` with:

- capability class
- execution context
- sensitivity tag
- explicit policy reason

The current UI already has the event plumbing. It mainly needs better labels.

## Rollout Plan

### Phase 1: make the current system honest

- keep the existing `PolicyEngine` and `ToolRouter`
- replace tool-local risk defaults with a single capability catalog
- add `executionContext` to session config
- keep local default conservative:
  - `OBSERVE`: auto
  - `NAVIGATE`: auto
  - `EDIT`: configurable
  - `COMMIT`: ask

### Phase 2: sensitivity hints

- add package/app-skill owned sensitivity rules
- escalate send/pay/delete flows into `COMMIT`

### Phase 3: remote entry

- remote sessions always require approval for `COMMIT`
- first remote device pairing requires on-device acceptance

## Main Trade-off

This design prefers semantic categories over raw risk bands.

That makes the catalog slightly richer, but it prevents a common failure mode: everything becomes `MEDIUM`, then policy becomes vague, then special cases pile up.

`OBSERVE / NAVIGATE / EDIT / COMMIT` is still small enough to stay readable, but it carries more meaning than `LOW / MEDIUM / HIGH`.
