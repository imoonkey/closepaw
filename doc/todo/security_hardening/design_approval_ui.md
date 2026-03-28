# Design: Tool Approval UI

**Goal**: Wire `ApprovalRequired` events to the SmartCapsule overlay so users can approve/deny agent actions on CAUTIOUS-tier apps. Three allow granularities (once, session, always) + deny. Runtime allow-list with two tiers: session-scoped (memory) and persistent (SharedPreferences).

**UX Spec**: `doc/todo/security_hardening/ux_spec_approval_ui.md`

---

## Approach

Structurally identical to the existing `AskUser(ACTION)` → `WaitingForAction` flow — same event pipeline, same capsule mode pattern, same callback plumbing. Differences:

1. **4 flat buttons** (Allow / Session / Always / Deny) instead of 1 (Done)
2. **Response type**: `ApprovalDecision` enum instead of `String`
3. **Side effects**: "Session" writes to in-memory allow-list, "Always" writes to SharedPreferences

Principle: **treat WaitingForApproval as another WaitingFor\* mode** — no new abstractions.

---

## Components

### 1. CapsuleMode.WaitingForApproval

```kotlin
// CapsuleMode.kt
data class WaitingForApproval(
    val callId: String,
    val description: String,    // "Click 'Settings' button"
    val appLabel: String,       // "Chrome" (resolved from packageName at event time)
    val packageName: String?,   // for allow-list persistence
    val reason: String          // "Unknown app — action requires approval"
) : CapsuleMode
```

### 2. ButtonsSpec: add `actions` list

Current `ButtonsSpec(primary, stop)` can't handle 4 buttons cleanly. Replace slot model with a flexible list for the left group:

```kotlin
data class ButtonsSpec(
    val actions: List<ActionButtonSpec> = emptyList(),
    val stop: ButtonSpec?
) {
    // Backward compat convenience
    constructor(primary: ButtonSpec?, stop: ButtonSpec?) : this(
        actions = listOfNotNull(primary?.let {
            ActionButtonSpec(it.icon, it.text, it.enabled, ActionButtonStyle.PRIMARY)
        }),
        stop = stop
    )
}

data class ActionButtonSpec(
    val icon: String,
    val text: String,
    val enabled: Boolean = true,
    val style: ActionButtonStyle = ActionButtonStyle.PRIMARY,
    val tag: String = "",  // machine-readable ID for click dispatch
)

enum class ActionButtonStyle { PRIMARY, SECONDARY, DANGER }
```

Wait — this is over-engineered. The existing modes only ever have 0-1 primary button. Only WaitingForApproval needs 4. Simpler: **keep `primary` + `stop`, add `extras: List<ButtonSpec>`** that only WaitingForApproval uses.

Actually, even simpler — just use the existing `primary` + `stop` + add `secondary` and `tertiary`:

```kotlin
data class ButtonsSpec(
    val primary: ButtonSpec?,
    val secondary: ButtonSpec? = null,
    val tertiary: ButtonSpec? = null,
    val stop: ButtonSpec?
)
```

All existing callers: `ButtonsSpec(primary = X, stop = Y)` → gets `secondary = null, tertiary = null` by default. Zero breakage.

For WaitingForApproval:
```
primary   = [✓ Allow]       — FilledTonal (default primary style)
secondary = [✓ Session]     — FilledTonal (same style)
tertiary  = [✓ Always]      — FilledTonal (same style)
stop      = [✕ Deny]        — Outlined red (existing stop style)
```

### 3. CapsuleRenderSpec for WaitingForApproval

```kotlin
is CapsuleMode.WaitingForApproval -> CapsuleRenderSpec(
    dot = DotSpec(CapsuleColors.AMBER, pulsing = false),
    thought = ThoughtSpec("🛡 Approve action?"),
    expandedBody = "${mode.description}\n${mode.appLabel} · ${mode.reason}",
    buttons = ButtonsSpec(
        primary = ButtonSpec("✓", "Allow"),
        secondary = ButtonSpec("✓", "Session"),
        tertiary = if (mode.packageName != null) ButtonSpec("✓", "Always") else null,
        stop = ButtonSpec("✕", "Deny"),
    ),
    row3 = null,
)
```

Layout in Row2:
```
┌──────────────────────────────────────────┐
│ [Allow] [Session] [Always]        [Deny] │
│  ← left group (spacedBy 8.dp) →   right  │
└──────────────────────────────────────────┘
```

Nav icons hidden during WaitingForApproval (same as other WaitingFor\* modes).

### 4. CapsuleRow2 Extension

```kotlin
// SmartCapsuleSurfaceParts.kt — CapsuleRow2, left group
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    spec.buttons.primary?.let { btn -> /* existing FilledTonalButton */ }
    spec.buttons.secondary?.let { btn ->
        FilledTonalButton(
            onClick = { onSecondaryClick(mode) },
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(secondaryIconForMode(mode), null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(btn.text, fontSize = 14.sp)
        }
    }
    spec.buttons.tertiary?.let { btn -> /* same pattern */ }
    spec.buttons.stop?.let { btn -> /* existing OutlinedButton */ }
}
```

Click dispatch by mode — add `WaitingForApproval` cases:

```kotlin
// primary click
is CapsuleMode.WaitingForApproval -> onApprovalResponse(mode.callId, mode.packageName, ALLOW_ONCE)
// secondary click
is CapsuleMode.WaitingForApproval -> onApprovalResponse(mode.callId, mode.packageName, ALLOW_SESSION)
// tertiary click
is CapsuleMode.WaitingForApproval -> onApprovalResponse(mode.callId, mode.packageName, ALLOW_ALWAYS)
// stop click
is CapsuleMode.WaitingForApproval -> onApprovalResponse(mode.callId, mode.packageName, DENY)
```

### 5. ApprovalScope enum (protocol layer)

```kotlin
// ApprovalTypes.kt
enum class ApprovalScope { ONCE, SESSION, ALWAYS }
```

`Op.Approve` extended:
```kotlin
data class Approve(
    val actionId: String,
    val decision: ApprovalDecision,
    val scope: ApprovalScope = ApprovalScope.ONCE,
    val packageName: String? = null
) : Op
```

The controller submits the full Op. The session handler owns all side effects:
```kotlin
// AgentSession.handleApproval()
private suspend fun handleApproval(op: Op.Approve) {
    // Persist allow-list (only if APPROVED + package known)
    if (op.decision == ApprovalDecision.APPROVED && op.packageName != null) {
        when (op.scope) {
            ApprovalScope.SESSION -> services.policyEngine.allowPackageForSession(op.packageName)
            ApprovalScope.ALWAYS -> services.policyEngine.allowPackagePersistent(op.packageName)
            ApprovalScope.ONCE -> { /* no side effect */ }
        }
    }
    services.toolRouter.resolveApproval(op.actionId, op.decision)
    emit(ApprovalResolved(...))
}
```

### 6. SmartCapsuleSurface Callback

```kotlin
onApprovalResponse: (callId: String, packageName: String?, ApprovalScope, ApprovalDecision) -> Unit
```

Controller maps to Op:
```kotlin
onApprovalResponse = { callId, pkg, scope, decision ->
    if (stateHolder.onApprovalResolved(callId)) {  // optimistic transition
        session.submit(Op.Approve(callId, decision, scope, pkg))
    }
}
```

### 7. Two-Tier Allow-List in PolicyEngine

```kotlin
// PolicyEngine.kt
private val sessionAllowedPackages = ConcurrentHashMap.newKeySet<String>()
private val persistentAllowedPackages: MutableSet<String>  // loaded from SharedPrefs

fun allowPackageForSession(packageName: String) {
    sessionAllowedPackages.add(packageName)
    Log.d(TAG, "Session allow-list: +$packageName")
}

fun allowPackagePersistent(packageName: String) {
    persistentAllowedPackages.add(packageName)
    savePersistentAllowList()  // write to SharedPreferences
    Log.d(TAG, "Persistent allow-list: +$packageName")
}

private fun isUserAllowed(packageName: String?): Boolean =
    packageName != null && (
        packageName in sessionAllowedPackages ||
        packageName in persistentAllowedPackages
    )

fun check(toolName: String, params: JSONObject, packageName: String?): PolicyDecision {
    val tier = appClassifier.classify(packageName)

    if (!ToolName.from(toolName).isScreenChanging) return PolicyDecision.Allow
    if (isEscape(toolName, params)) return PolicyDecision.Allow

    // BLOCKED is absolute floor — nothing overrides
    if (tier == AppTier.BLOCKED) {
        return PolicyDecision.Deny("Blocked: financial/auth app ($packageName)")
    }

    // User-granted allow-list — but NOT in ALWAYS_ASK mode
    if (approvalMode.get() != ApprovalMode.ALWAYS_ASK && isUserAllowed(packageName)) {
        return PolicyDecision.Allow
    }

    // Apply approval mode
    return when (approvalMode.get()) {
        ApprovalMode.ALWAYS_ASK -> PolicyDecision.AskUser(...)
        ApprovalMode.AUTO_APPROVE -> PolicyDecision.Allow
        ApprovalMode.SMART -> when (tier) {
            AppTier.CAUTIOUS -> PolicyDecision.AskUser(...)
            AppTier.NORMAL -> PolicyDecision.Allow
            AppTier.BLOCKED -> PolicyDecision.Deny("unreachable")
        }
    }
}

fun reset() {
    approvalMode.set(ApprovalMode.SMART)
    sessionAllowedPackages.clear()
    // persistent list NOT cleared on reset — survives across sessions
}
```

**Storage**: `AppSettingsStore` already handles SharedPreferences. Add:
```kotlin
fun loadPersistentAllowList(): Set<String>
fun savePersistentAllowList(packages: Set<String>)
```

Store as `StringSet` under key `"user_allowed_packages"`. Not encrypted — package names are not sensitive.

### 8. Event Routing (mirrors AskUser exactly)

| Layer | File | Change |
|---|---|---|
| EventHandler | `AgentServiceEventHandler.kt` | Add `is ApprovalRequired` branch → `overlay?.onApprovalRequired(event.details)` |
| Controller | `ServiceOverlayController.kt` | Add `onApprovalRequired(details)` — resolve appLabel, set state, force capsule |
| StateHolder | `CapsuleStateHolder.kt` | Add `onApprovalRequired(...)` and `onApprovalResolved(callId)` |
| OverlayHost | `CapsuleOverlayHost.kt` | Add `var onApprovalResponse` callback |
| Surface | `SmartCapsuleSurface.kt` | Add `onApprovalResponse` param, wire to Row2 buttons |

### 9. Overlay Visibility & Guards

Add `WaitingForApproval` everywhere `WaitingForInput/WaitingForAction` appear:

- `OverlayLocationPolicy.kt`: force `ShowPreference.CAPSULE`
- `CapsuleStateHolder.hasActiveTask`: return `true`
- `CapsuleStateHolder.onStopRequested()`: allow stop
- `NavSpec.from()`: hide nav buttons
- `shouldLockUserInteraction()`: no change needed (non-terminal, non-takeover = locked)

---

## Data Flow

### Inbound (ApprovalRequired → UI)
```
ToolRouter → PolicyDecision.AskUser
  → TurnExecutionPhaseRunner.emitApprovalRequired(details)
  → AgentSession emits ApprovalRequired event
  → AgentServiceEventHandler: is ApprovalRequired
  → ServiceOverlayController.onApprovalRequired(details)
      → resolve appLabel via PackageManager
      → CapsuleStateHolder.onApprovalRequired(...)
      → mode = WaitingForApproval → Compose re-renders
```

### Outbound (User taps button → Agent resumes)
```
User taps [Session]
  → SmartCapsuleSurface: onApprovalResponse(callId, pkg, SESSION, APPROVED)
  → CapsuleOverlayHost callback
  → ServiceOverlayController:
      → stateHolder.onApprovalResolved(callId) → Running (optimistic)
      → session.submit(Op.Approve(callId, APPROVED, SESSION, pkg))
  → AgentSession.handleApproval():
      → policyEngine.allowPackageForSession(pkg)   [side effect in session layer]
      → toolRouter.resolveApproval(callId, APPROVED)
  → CompletableDeferred completes
  → ToolRouter: re-check foreground package
      → if changed: cancel ("App changed during approval")
      → if same: execute action
```

### Post-Approval Foreground Recheck (ToolRouter)

After `deferred.await()` returns APPROVED, before executing:
```kotlin
val currentPkg = getCurrentForegroundPackage()
if (currentPkg != approvalDetails.packageName) {
    return ToolCallResult.Cancelled(callId, "App changed during approval wait")
}
```
This prevents a TOCTOU race where the user approves for Chrome but the agent executes in a banking app.

---

## Tasks

### T1: `capsule-mode-approval`
**Scope**: `CapsuleMode.kt`, `CapsuleRenderSpec.kt`
**Work**: Add `WaitingForApproval` data class. Add `secondary`/`tertiary` to `ButtonsSpec` (default null). Add render spec case for the new mode.
**AC**: Existing modes compile unchanged. New mode produces correct 4-button spec.

### T2: `capsule-row2-buttons`
**Scope**: `SmartCapsuleSurfaceParts.kt`, `SmartCapsuleSurface.kt`
**Work**: Render `secondary`/`tertiary` buttons in CapsuleRow2. Add `onApprovalResponse` callback. Wire button clicks with mode-based dispatch.
**AC**: 4 buttons render for WaitingForApproval. Click → correct ApprovalResponse.
**Depends**: T1

### T3: `event-routing`
**Scope**: `AgentServiceEventHandler.kt`, `ServiceOverlayController.kt`, `CapsuleStateHolder.kt`, `CapsuleOverlayHost.kt`
**Work**: Route `ApprovalRequired` event through full pipeline. Wire `onApprovalResponse` callback back to `session.submit(Op.Approve)`. Optimistic state transition on tap (same as `onUserResponseSent` pattern).
**AC**: ApprovalRequired event → WaitingForApproval mode. Button tap → optimistic Running transition + Op.Approve submitted.
**Depends**: T1

### T4: `two-tier-allow-list`
**Scope**: `PolicyEngine.kt`, `AppSettingsStore.kt`, `Op.kt`, `AgentSession.kt`
**Work**: Session allow-list (in-memory Set, cleared on reset). Persistent allow-list (SharedPreferences, survives sessions). Check after BLOCKED, before SMART logic. Skip allow-list in ALWAYS_ASK mode. Extend `Op.Approve` with `ApprovalScope` + `packageName`. Session handler owns allow-list mutations.
**AC**: `allowPackageForSession("x")` → check returns Allow. `allowPackagePersistent("x")` → survives reset(). BLOCKED always denied. ALWAYS_ASK ignores allow-list.

### T5: `overlay-visibility-guards`
**Scope**: `OverlayLocationPolicy.kt`, `CapsuleStateHolder.kt`, `CapsuleRenderSpec.kt` (NavSpec)
**Work**: Add `WaitingForApproval` to all visibility/guard branches alongside WaitingForInput/WaitingForAction. In ALWAYS_ASK mode or null packageName, hide Session/Always buttons (collapse to Allow + Deny).
**AC**: Capsule forced visible during approval. Nav hidden. Stop works. hasActiveTask = true.
**Depends**: T1

### T6: `post-approval-recheck`
**Scope**: `ToolRouter.kt`
**Work**: After approval deferred completes with APPROVED, re-check current foreground package. If different from the package shown in approval prompt, cancel the action.
**AC**: Approve Chrome action → switch to banking app during wait → action cancelled, not executed.

---

## Trade-offs

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| Button layout | 4 flat buttons | Dropdown on Allow | Faster (no extra tap), critical with 60s timeout |
| ButtonsSpec shape | `primary`/`secondary`/`tertiary`/`stop` named slots | `List<ButtonSpec>` | Named slots are simpler, we know max count. List adds iteration complexity |
| Allow-list storage | Two-tier (session memory + persistent SharedPrefs) | Single persistent | Session-only is useful for "trust this once but don't permanently whitelist" |
| Persistent allow-list location | `AppSettingsStore` (plain SharedPrefs) | EncryptedSharedPreferences | Package names are not sensitive. Plain prefs are simpler |

---

## Codex Review Response

Review: `doc/todo/security_hardening/design_review_codex.md`

### High 1: Foreground app change during approval wait — ACCEPTED

60s is enough for the user to navigate away. After `CompletableDeferred` completes with APPROVED, ToolRouter must re-check the current foreground package. If it differs from the `packageName` shown in the approval prompt, cancel the action (return `ToolCallResult.Cancelled("App changed during approval")`). This is a ToolRouter-level guard, not a PolicyEngine change.

**Implementation**: In `ToolRouter.execute()`, after `deferred.await()` returns APPROVED, capture current foreground package. If `currentPkg != approvalDetails.packageName`, cancel.

### High 2: Allow-list weakens ALWAYS_ASK — ACCEPTED

`ALWAYS_ASK` is an explicit user choice to be prompted every time. The allow-list must not bypass it.

**Fix in PolicyEngine.check()**:
```kotlin
// BLOCKED is absolute floor
if (tier == AppTier.BLOCKED) return Deny(...)

// Allow-list — but NOT in ALWAYS_ASK mode
if (approvalMode != ALWAYS_ASK && isUserAllowed(packageName)) return Allow

// Apply approval mode
return when (approvalMode) { ... }
```

**Fix in UI**: In ALWAYS_ASK mode, hide Session and Always buttons — show only Allow (once) + Deny.

### High 3: Policy mutations in controller — ACCEPTED

The controller should not call PolicyEngine directly. All side effects go through Op → session.

**Fix**: Extend `Op.Approve` with scope:
```kotlin
data class Approve(
    val actionId: String,
    val decision: ApprovalDecision,
    val scope: ApprovalScope = ApprovalScope.ONCE
) : Op

enum class ApprovalScope { ONCE, SESSION, ALWAYS }
```

The session handler (`AgentSession.handleApproval`) maps scope:
```kotlin
when (op.scope) {
    ONCE -> { /* no side effect */ }
    SESSION -> services.policyEngine.allowPackageForSession(packageName)
    ALWAYS -> services.policyEngine.allowPackagePersistent(packageName)
}
```

The controller just submits: `session.submit(Op.Approve(callId, APPROVED, SESSION))`. Pure input surface.

**Note**: The session handler needs the `packageName` to know what to persist. Options:
- (a) Include `packageName` in `Op.Approve`
- (b) Look it up from `ToolRouter.pendingApprovals` before completing

Option (a) is simpler:
```kotlin
data class Approve(
    val actionId: String,
    val decision: ApprovalDecision,
    val scope: ApprovalScope = ApprovalScope.ONCE,
    val packageName: String? = null  // for SESSION/ALWAYS scope
) : Op
```

### Medium 1: 4 buttons density — NOTED, KEEPING

We keep 4 flat buttons. Nav icons are hidden during WaitingForApproval, so full row width is available. Labels are short (Allow/Session/Always/Deny). If tight on narrow devices, we can drop icons and use text-only buttons. The 60s timeout makes speed critical.

When `packageName == null` OR `approvalMode == ALWAYS_ASK`, collapse to 2 buttons (Allow + Deny), which is comfortable.

### Medium 2: null-package and state transition — ACCEPTED

**null-package**: If `packageName == null`, hide both Session and Always. Show only Allow + Deny.

**State transition**: Use optimistic local transition (same as `onUserResponseSent` pattern). The controller calls `stateHolder.onApprovalResolved(callId)` immediately on button tap, then submits `Op.Approve`. No need to route `ApprovalResolved` event back through the handler — the state holder already transitions out of `WaitingForApproval` before the Op reaches the session.

