# 5 Three-Axis Security - Codex Design

## Problem

Android Agent already has the beginnings of a safety layer:

- `SessionConfig` carries a single `approvalMode`
- `SessionToolingBootstrapper` builds one `PolicyEngine`
- `PolicyEngine` maps tools/actions to `RiskLevel`
- `ToolRouter` turns that into allow / ask / deny

That is better than "everything is allowed", but it is still one-dimensional. OpenClaw's useful idea is not the exact Docker model; it is the separation of concerns:

1. Where the request comes from
2. Which capabilities are available
3. Whether a stronger override is allowed

Android does not need a fake Docker axis. It needs the same orthogonality applied to mobile automation.

## Goals

- Replace the single approval knob with three explicit axes
- Keep the first rollout small and aligned with current code seams
- Make risk decisions deterministic and inspectable
- Support stricter rules for remote entry later without redesign

## Non-goals

- Full remote-control implementation
- LLM-driven heuristic safety guesses as the primary mechanism
- Backward-compat shims that preserve the old `ApprovalMode` model forever

## Design

### 1. Replace `approvalMode` with `SessionSecurityConfig`

Replace the field in `SessionConfig` with a real security object:

```kotlin
data class SessionSecurityConfig(
    val surface: SecuritySurface = SecuritySurface.LOCAL_INTERACTIVE,
    val capabilityPolicy: CapabilityPolicy = CapabilityPolicy.default(),
    val elevationPolicy: ElevationPolicy = ElevationPolicy.disabled()
)
```

```kotlin
enum class SecuritySurface {
    LOCAL_INTERACTIVE,
    LOCAL_AUTOSTART,
    REMOTE
}
```

`LOCAL_INTERACTIVE` means the user is physically driving the phone and can see prompts.

`LOCAL_AUTOSTART` means the request started locally but without an immediate foreground confirmation path.

`REMOTE` means an off-device entry point. The repo does not ship that yet, but the axis exists now so we do not have to redesign later.

### 2. Capability policy is the main Phase 1 axis

Keep the current idea of canonical tool/action names, but move from scattered risk defaults to one catalog:

```kotlin
enum class RiskClass {
    SAFE,
    MODERATE,
    HIGH
}

data class CapabilityRule(
    val subject: CapabilitySubject,
    val risk: RiskClass,
    val defaultDecision: DecisionFloor
)
```

`CapabilitySubject` is canonical and flat:

- tool-level: `open_app`, `shell`, `scratchpad`
- action-level: `mobile_action.click`, `mobile_action.type`
- future package-specific override: `package:com.tencent.mm/send`

Phase 1 should classify only what the code can already name reliably:

- `wait`, `scratchpad`, `write_todos`: `SAFE`
- `open_app`, `system_button`, `mobile_action.click`, `mobile_action.scroll`: `MODERATE`
- `mobile_action.type`, any future send/pay/delete actions, any future state-changing shell: `HIGH`

Do not bury this in ad hoc `when` trees. Put it in one catalog owned by the policy layer.

### 3. Elevation is explicit and separate

Elevation is not "high risk". Elevation means "this request wants access beyond the normal profile".

```kotlin
data class ElevationPolicy(
    val enabled: Boolean = false,
    val allowedScopes: Set<ElevatedCapability> = emptySet()
)
```

Examples:

- future unrestricted shell
- future file deletion
- future remote control override that bypasses local prompt requirements

Current `shell` is read-only inspection and should stay inside normal capability policy. Do not abuse elevation for normal tools. Reserve it for genuine escape hatches.

### 4. Policy evaluation becomes a composition, not a mode switch

Replace `PolicyEngine.check(toolName, params)` with:

```kotlin
data class PolicyCheckRequest(
    val toolName: String,
    val params: JSONObject,
    val security: SessionSecurityConfig,
    val packageName: String?,
    val targetHints: Set<String> = emptySet()
)
```

Decision flow:

1. Resolve canonical subject from tool/action name
2. Load base capability rule
3. Raise the effective floor using `surface`
4. If the subject requires elevation and elevation is missing, deny
5. Return allow / ask / deny with explicit reason

This preserves KISS. One request in, one deterministic decision out.

### 5. Sensitive-flow escalation is a hook, not the foundation

The source brief is right that "send message" and "pay" should become stricter than generic click/type. But the current policy layer does not see enough semantic context to do that well.

So Phase 2 adds a narrow hook:

```kotlin
interface SensitiveOperationHintProvider {
    fun hintsFor(
        packageName: String?,
        toolName: String,
        params: JSONObject
    ): Set<String>
}
```

Primary owners:

- app skill metadata
- package-specific safety hints
- simple deterministic target labels from the resolved action path

The important part is ownership. Sensitive hints belong in app/package knowledge, not in a giant generic prompt.

## Integration Points

### Session layer

- `SessionConfig`: replace `approvalMode` with `security`
- `ConversationConfigSnapshot`: persist `surface` and selected safety profile
- `MainActivity` / `AgentService`: build `SessionSecurityConfig` from settings and entry point

### Tool bootstrap

- `SessionToolingBootstrapper.create(security)`
- `SessionServices.updateApprovalMode()` is removed and replaced with `updateSecurityConfig()`

### Policy layer

- `PolicyEngine` becomes the owner of capability catalog + surface rules + elevation checks
- `ToolName` / `MobileActionName` can keep canonical naming, but the risk defaults should move out of enum constructors into the policy catalog

### UI / events

- `ApprovalDetails` should expose `riskClass`, `surface`, and `reason`
- approval UI stays event-driven through `ApprovalRequired` / `ApprovalResolved`
- settings UI should expose only a minimal "interaction safety" control, not dozens of per-tool toggles

## Rollout

### Phase 1

- Add `SessionSecurityConfig`
- Add capability catalog with `SAFE / MODERATE / HIGH`
- Replace `approvalMode`
- Local default: `SAFE` auto, `MODERATE` auto, `HIGH` ask

### Phase 2

- Add sensitive-operation hints for package/task context
- Escalate send/pay/delete style actions from `MODERATE` to `HIGH`

### Phase 3

- Add `REMOTE` surface
- Force `HIGH` approval for remote sessions
- Add device-side approval for first remote pairing

## Why this design

- It maps directly onto the code that already exists
- It keeps the first implementation mostly in `SessionConfig`, `PolicyEngine`, and `ToolRouter`
- It avoids pretending Android needs a host sandbox abstraction when the real near-term problem is action safety
- It preserves the OpenClaw insight: orthogonal axes, not one overloaded permission switch
