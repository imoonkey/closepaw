# Agent Security Policy for Android Agent
## Goal
Android Agent needs one deterministic security policy that answers two separate questions:

1. what the agent is allowed to see
2. what the agent is allowed to do

Today we only gate tool execution by coarse tool/action risk. That is not enough. A `click` in Calculator is not a `click` on "Send money", and privacy is already lost before `ToolRouter` runs because the screen is captured and shown to the LLM during planning.

Success means:
- sensitive apps are not treated like normal apps
- side-effectful actions are detected even when the raw tool is generic
- unknown cases fail safe
- the design stays centered on the existing `PolicyEngine -> ToolRouter -> approval UI` path

## Approach
Use one policy model with three deterministic inputs:

1. `app profile`: what kind of app this package is
2. `action capability`: what the requested tool fundamentally does
3. `action sensitivity tags`: what this specific target means on this screen

The decision rule is simple:
- app profile sets the privacy boundary and the maximum allowed capability
- action capability gives the base risk
- sensitivity tags can only escalate, never de-escalate
- global approval mode can tighten approval, but it cannot bypass app denies

This extends the existing three-axis security design instead of replacing it with another subsystem. App policy becomes part of the capability axis; `ApprovalMode` stays as a coarse global override until the broader `SessionSecurityConfig` migration happens.

## Components
### 1. App classification
Add a deterministic app catalog owned by security, not by prompts:

```kotlin
enum class AppSecurityClass {
    GENERAL,
    COMMUNICATION,
    UNCLASSIFIED,
    SYSTEM_CONTROL,
    FINANCIAL,
    SECRET_STORE,
    SENSITIVE_RECORDS,
    ADMIN_CONSOLE
}
```

Meaning:
- `GENERAL`: normal consumer/productivity apps
- `COMMUNICATION`: email, SMS, chat, social, dating, forums
- `UNCLASSIFIED`: package has no trusted match
- `SYSTEM_CONTROL`: Settings, permission controllers, installers, app stores, device admin surfaces
- `FINANCIAL`: banking, brokerage, crypto, payment, payroll, tax apps
- `SECRET_STORE`: password managers, authenticator/OTP apps, digital identity wallets
- `SENSITIVE_RECORDS`: health, insurance, legal, government, benefits, patient portals
- `ADMIN_CONSOLE`: cloud consoles, MDM, remote admin, enterprise control planes

Classification source priority:

1. user override by exact package name
2. built-in package rule catalog by exact package or package prefix
3. package-label keyword heuristics that only escalate into sensitive classes
4. fallback to `UNCLASSIFIED`

Runtime Play Store category lookup is the wrong dependency here. It is network-bound, coarse, and unstable. If Play Store metadata is useful, use it offline to curate the built-in catalog, not as runtime policy input.

### 2. App policy profile
Each app class maps to one policy profile:

```kotlin
enum class ObservationPolicy {
    FULL,
    MASKED
}

data class AppPolicyProfile(
    val appClass: AppSecurityClass,
    val observationPolicy: ObservationPolicy,
    val maxCapability: CapabilityClass?,
    val approvalFloor: RiskLevel,
    val allowEscapeActions: Boolean = true
)
```

`maxCapability = null` means in-app automation is blocked. `allowEscapeActions` keeps `back`, `home`, and opening another app available so the agent can recover from landing in a blocked app.

Default profiles:

| App class | Observation | Default action policy |
| --- | --- | --- |
| `GENERAL` | `FULL` | allow normal flow; ask on `COMMIT` |
| `COMMUNICATION` | `FULL` | allow normal flow; ask on `COMMIT` |
| `UNCLASSIFIED` | `FULL` | allow `OBSERVE` and `NAVIGATE`; ask on `EDIT` and `COMMIT` |
| `SYSTEM_CONTROL` | `FULL` | allow `OBSERVE`; ask on `NAVIGATE`; deny sensitive state changes |
| `FINANCIAL` | `MASKED` | block in-app automation by default |
| `SECRET_STORE` | `MASKED` | block in-app automation by default |
| `SENSITIVE_RECORDS` | `MASKED` | block in-app automation by default |
| `ADMIN_CONSOLE` | `MASKED` | block in-app automation by default |

This is the important first-principles cut:
- financial, health, auth, and admin apps are not just "high-risk actions"
- they are confidential environments
- "read-only" is still a permission because the agent sends observations to the LLM and stores them in history/trace

So the default is `MASKED + blocked`, not "read is free, write is dangerous".

### 3. Action capability
Keep the capability classes from the three-axis design:

```kotlin
enum class CapabilityClass {
    OBSERVE,
    NAVIGATE,
    EDIT,
    COMMIT
}
```

Base mapping:
- internal tools like `write_todos`, `scratchpad`, `complete_task` are outside app policy
- `wait` is `OBSERVE`
- `open_app`, `scroll`, `swipe`, `back`, `home`, generic `click` are `NAVIGATE`
- `type` is `EDIT`
- no raw tool is born as `COMMIT`; commit comes from screen semantics

This removes `DEFAULT_RISK_LEVELS` and `MobileActionName.defaultRiskLevel` as the source of truth. Risk comes from policy composition, not hardcoded enum trivia.

### 4. Action sensitivity tags
Add deterministic screen-level escalation:

```kotlin
enum class ActionSensitivityTag {
    EXTERNAL_SEND,
    PUBLIC_POST,
    MONEY_MOVEMENT,
    ACCOUNT_CHANGE,
    PERMISSION_CHANGE,
    DESTRUCTIVE,
    SECRET_ENTRY
}
```

These tags come from:
- current package class
- resolved target element text, description, hint text, class name, and resource id
- package-specific rule snippets for known apps

Examples:
- click "Send", "Transfer", "Pay", "Checkout" -> `MONEY_MOVEMENT`
- click "Post", "Publish", "Reply", "Send message" -> `EXTERNAL_SEND` or `PUBLIC_POST`
- click "Delete", "Remove account", "Uninstall" -> `DESTRUCTIVE`
- type into password, OTP, seed phrase, SSN, card, CVV fields -> `SECRET_ENTRY`
- click "Allow", "Grant", "Enable accessibility", "Install" -> `PERMISSION_CHANGE`

Rules:
- tags are deterministic and local
- tags only raise capability/risk
- LLM guesses never decide security

Escalation examples:
- generic `click` + `MONEY_MOVEMENT` -> treat as `COMMIT`
- generic `click` + `DESTRUCTIVE` -> treat as `COMMIT`
- `type` + `SECRET_ENTRY` -> deny in blocked app classes; ask in allowed classes

### 5. User overrides
Keep overrides small and understandable:

```kotlin
data class AppSecurityOverride(
    val observationPolicy: ObservationPolicy? = null,
    val maxCapability: CapabilityClass? = null,
    val approvalFloor: RiskLevel? = null
)
```

Override application order:

1. start from app-class defaults
2. apply per-app override by exact package name
3. apply session-global mode as the final approval floor

Supported override surfaces:
- global: existing `ALWAYS_ASK`, `SMART`, `AUTO_APPROVE`
- per app: raise/lower observation boundary, max capability, or approval floor

Not supported:
- per-button user rules
- prompt text that says "this app is safe"
- arbitrary per-tool matrices

That is how we avoid turning policy into a pile of exceptions.

Recommended default UX:
- blocked app encountered: show "Automation blocked in this app"
- approval prompt in allowed app: show one-time option to "Always ask in this app" or "Allow navigation only in this app"
- explicit per-app relaxations for blocked classes should be framed as a deliberate override, not a hidden side effect of `AUTO_APPROVE`

### 6. Privacy boundary
The privacy boundary must exist before the LLM sees the screen.

Add a perception gate that uses the same app profile:
- `FULL`: current behavior
- `MASKED`: prompt/history/trace receive package name, app class, and a short warning instead of the raw a11y tree and screenshot

Masked example:

> Foreground app: `com.chase.sig.android`
> Class: `FINANCIAL`
> Screen content hidden by security policy. You may leave the app or ask the user for an override.

This gate must apply to:
- `AgentTurnRunner.capturePreTurnSnapshot()` consumers
- `PromptBuilder`
- history recording in `TurnPlanningPhaseRunner.recordScreenObservation`
- trace artifacts and any persisted raw screen payloads

If masking does not cover trace/history, the privacy boundary is fake.

## Interactions
### Classification flow

1. Resolve package under policy:
   - `open_app`: target package from params
   - in-app action: current foreground package
2. Apply user override or built-in rule match
3. If no match, run label/package keyword escalation
4. Else classify as `UNCLASSIFIED`

### Perception state machine

1. Capture screen and current package
2. Resolve `AppPolicyProfile`
3. If `observationPolicy == FULL`, pass full snapshot to prompt/history/trace
4. If `observationPolicy == MASKED`, replace visible observation with a masked stub
5. Agent can still call escape actions or ask the user for an override

### Execution state machine

1. `ToolRouter` validates tool as today
2. `ToolRouter` builds `PolicyCheckRequest`
3. `PolicyEngine` resolves app profile, base capability, and sensitivity tags
4. If the target app is blocked and the action is `open_app`, return `AskUser`; after launch the app remains masked and in-app actions remain denied until override
5. If requested capability exceeds app `maxCapability`, return `Deny`
6. Else derive `RiskLevel`:
   - `OBSERVE` -> `LOW`
   - `NAVIGATE` -> `LOW` or `MEDIUM` depending on app class
   - `EDIT` -> `MEDIUM`
   - `COMMIT` -> `HIGH`
7. Apply global approval mode:
   - `ALWAYS_ASK`: ask for every allowed action
   - `SMART`: ask at or above the app profile floor
   - `AUTO_APPROVE`: auto-approve only if the action is already allowed by policy
8. `ToolRouter` continues with the existing `ALLOW / ASK / DENY` flow

### Policy request shape

```kotlin
data class PolicyCheckRequest(
    val toolName: String,
    val params: JSONObject,
    val currentPackageName: String?,
    val currentAppLabel: String?,
    val currentSnapshot: ScreenSnapshot?,
    val approvalMode: ApprovalMode
)
```

`PolicyEngine.check(...)` should take this request instead of bare `toolName + params`.

### Approval payload
Enrich `ApprovalDetails` with:
- `packageName`
- `appClass`
- `capabilityClass`
- `sensitivityTags`
- `policyReason`

Keep the same approval lifecycle in `ToolRouter`. The payload needs to explain why the prompt exists; the state machine does not need a rewrite.

## Default policy answers
### Which apps are blocked by default?

Blocked by default:
- `FINANCIAL`
- `SECRET_STORE`
- `SENSITIVE_RECORDS`
- `ADMIN_CONSOLE`

Reason: these are both confidentiality-sensitive and high-consequence environments.

### Which apps are always ask by default?

Always ask for state-changing actions:
- `UNCLASSIFIED` for `EDIT` and `COMMIT`
- `SYSTEM_CONTROL` for navigation and any non-blocked change
- `GENERAL` and `COMMUNICATION` when action escalates to `COMMIT`

### Which apps can auto-approve?

Auto-approve in `SMART`:
- `GENERAL` and `COMMUNICATION` for `OBSERVE`, `NAVIGATE`, and non-sensitive `EDIT`
- `UNCLASSIFIED` for `OBSERVE` and `NAVIGATE`

`AUTO_APPROVE` never overrides blocked classes.

## Fail-safe answers
### Unclassified app

Default to `UNCLASSIFIED`, not `GENERAL`.

That means:
- observation allowed
- navigation allowed
- edits and commits ask

This is conservative without making the agent unusable.

### Unknown action semantics

If target resolution fails or the policy code cannot classify the action cleanly:

- never downgrade risk
- treat the action as at least `EDIT`
- if already in a blocked class, deny

## Tasks
### `security-policy-model`

- Scope: `app/src/main/kotlin/com/moonkey/androidagent/tool/**`, `app/src/main/kotlin/com/moonkey/androidagent/protocol/**`
- Acceptance criteria:
  - add app class, observation policy, action sensitivity tags, and package-aware policy request types
  - remove tool/action risk as the primary source of truth from `ToolName` and `PolicyEngine`
  - preserve `Allow / AskUser / Deny` as the execution output
- Dependencies: none

### `package-classification-catalog`

- Scope: `app/src/main/kotlin/com/moonkey/androidagent/security/**`, `app/src/main/assets/**`
- Acceptance criteria:
  - built-in package rule catalog exists
  - exact package and package-prefix matches are supported
  - conservative keyword escalation exists for unknown apps
  - per-app overrides are persisted locally
- Dependencies: `security-policy-model`

### `masked-observation-gate`

- Scope: `app/src/main/kotlin/com/moonkey/androidagent/agent/**`, `app/src/main/kotlin/com/moonkey/androidagent/trace/**`, `app/src/main/kotlin/com/moonkey/androidagent/history/**`
- Acceptance criteria:
  - masked apps do not expose raw a11y tree or screenshot to prompt/history/trace
  - masked observation still tells the agent which app it is in and how to recover
  - blocked-app behavior is visible in UI/debug output without leaking content
- Dependencies: `package-classification-catalog`

### `tool-router-policy-context`

- Scope: `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt`, `app/src/main/kotlin/com/moonkey/androidagent/session/**`, `app/src/main/kotlin/com/moonkey/androidagent/protocol/**`
- Acceptance criteria:
  - `ToolRouter` sends package-aware policy requests
  - `ApprovalDetails` includes package/app-class/capability/tag reason fields
  - existing approval lifecycle and timeout behavior stay intact
- Dependencies: `security-policy-model`, `package-classification-catalog`

### `action-sensitivity-classifier`

- Scope: `app/src/main/kotlin/com/moonkey/androidagent/tool/action/**`, `app/src/main/kotlin/com/moonkey/androidagent/tool/**`
- Acceptance criteria:
  - target text/description/resource-id based tag escalation exists
  - `click` on send/pay/delete style controls becomes `COMMIT`
  - `type` into secret fields is never auto-approved
- Dependencies: `tool-router-policy-context`

### `security-policy-tests`

- Scope: `app/src/test/kotlin/com/moonkey/androidagent/**`
- Acceptance criteria:
  - tests cover blocked financial app, masked observation, unclassified app fallback, commit escalation, and override precedence
  - tests verify `AUTO_APPROVE` cannot bypass blocked classes
- Dependencies: all prior tasks

## Trade-offs
- Reject runtime Play Store classification. It adds fragility without giving trustworthy enforcement.
- Reject prompt-owned app safety. Security must be deterministic and testable.
- Reject a giant settings matrix. The right override surface is per app and per capability band, not per raw tool.
- Reject "read is safe" in sensitive apps. In this system, reading means exposing content to the model, history, and traces.
- Accept some false positives on unknown apps. That is cheaper than silently letting the agent automate a bank or admin console.

## Why this cut wins
It fixes the real problem instead of polishing the wrong seam.

The current repo already has the execution choke point in `PolicyEngine` and `ToolRouter`. The missing piece is package-aware policy and a matching privacy gate before cognition. Once those two seams share the same deterministic app profile, the agent can make clear allow/ask/deny decisions without turning policy into a pile of app-specific hacks.
