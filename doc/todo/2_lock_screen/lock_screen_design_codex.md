status: draft

# Lock Screen Execution Design (Codex)

Date: 2026-02-16  
Goal: keep agent execution alive while the physical screen is locked, without bypassing keyguard, and fail closed when conditions are unsafe.

## Problem

`VirtualDisplayPlatform` already supports:
- creating/running a headless virtual display via Shizuku
- screenshot capture from `ImageReader`
- input injection targeted by `displayId`
- app launch with `--display`

What it does **not** have is a lockscreen runtime contract:
- no power-state monitor
- no wake-lock lifecycle
- no Doze-aware health policy
- no deterministic transition between `continue` vs `pause`

Result: behavior under screen-off/locked state is device-dependent and not controlled by the agent runtime.

## Scope

In scope:
- session/runtime contract for lockscreen execution
- power + keyguard state tracking
- keep-alive policy (`PARTIAL_WAKE_LOCK`) for VD mode
- failure handling and graceful fallback to paused state
- telemetry and verification plan

Out of scope:
- bypassing lockscreen/PIN/pattern/biometric
- bypassing secure surfaces/DRM protections
- keeping `AccessibilityPlatform` active under lock (this should pause)

## Design Principles

1. Explicit state machine, no hidden heuristics.
2. Fail closed: if preconditions are not met, pause safely.
3. Keep platform code thin; policy lives in a dedicated controller.
4. No global singletons; session-scoped ownership and cleanup.
5. Zero behavior change when lockscreen mode is disabled.

## Proposed Architecture

### 1. Session Config Extension

Add a dedicated lockscreen config to `SessionConfig` (`app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt`):

```kotlin
data class LockScreenConfig(
    val enabled: Boolean = false,
    val policy: LockScreenPolicy = LockScreenPolicy.PAUSE_WHEN_LOCKED,
    val acquireWakeLock: Boolean = true,
    val wakeLockTag: String = "AndroidAgent:LockExecution",
    val wakeLockTimeoutMs: Long = 10 * 60_000L,
    val livenessProbeIntervalMs: Long = 5_000L,
    val maxConsecutiveProbeFailures: Int = 3
)

enum class LockScreenPolicy {
    PAUSE_WHEN_LOCKED,
    CONTINUE_ON_VIRTUAL_DISPLAY
}
```

`SessionConfig` gets `val lockScreenConfig: LockScreenConfig = LockScreenConfig()`.

### 2. LockScreenExecutionController (new)

Add `app/src/main/kotlin/com/moonkey/androidagent/session/lockscreen/LockScreenExecutionController.kt`.

Responsibilities:
- consume device power/keyguard events
- decide state transitions
- acquire/release wake lock
- run VD liveness probes
- request session pause on unrecoverable lockscreen failures

State model:

```kotlin
sealed class LockExecutionState {
    data object Disabled : LockExecutionState()
    data object Unlocked : LockExecutionState()
    data object LockedActive : LockExecutionState()
    data object LockedDegraded : LockExecutionState()
    data object PausedByPolicy : LockExecutionState()
}
```

### 3. Device State Monitor (new)

Add `DeviceStateMonitor` in `session/lockscreen/`.

Inputs:
- `Intent.ACTION_SCREEN_ON`
- `Intent.ACTION_SCREEN_OFF`
- `Intent.ACTION_USER_PRESENT`
- snapshot checks via `PowerManager.isInteractive` + `KeyguardManager.isKeyguardLocked`

Output:
- `StateFlow<DeviceLockSnapshot>`

```kotlin
data class DeviceLockSnapshot(
    val interactive: Boolean,
    val keyguardLocked: Boolean,
    val userPresent: Boolean
)
```

### 4. KeepAliveLease (new)

Add `KeepAliveLease` in `session/lockscreen/`:
- wraps `PowerManager.PARTIAL_WAKE_LOCK`
- idempotent `acquire()` / `release()`
- strictly session-scoped lifecycle

Manifest updates:
- add `android.permission.WAKE_LOCK`
- optional UX prompt path for battery optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) as recommendation, not hard requirement.

### 5. VD Liveness Probe (new)

Add `VirtualDisplayLivenessProbe` in `platform/virtualdisplay/`:
- probe = `captureScreen()` returns screenshot OR a11y root package in bounded time
- failure counter with backoff
- signals degraded mode after threshold

This is runtime health checking, not turn-level logic.

## Runtime Policy

### Policy Matrix

1. `platformMode == ACCESSIBILITY` and screen locks:
- always pause (`PAUSE_WHEN_LOCKED` effectively forced)

2. `platformMode == VIRTUAL_DISPLAY` and `lockScreenConfig.enabled == false`:
- current behavior unchanged

3. `platformMode == VIRTUAL_DISPLAY` and `enabled == true`:
- `PAUSE_WHEN_LOCKED`: pause on lock event
- `CONTINUE_ON_VIRTUAL_DISPLAY`: continue if wake lock + probe healthy, else pause

### Failure Escalation

When locked + continue policy:
1. Probe failure count < threshold: stay `LockedActive`, log warning.
2. Probe failure count >= threshold: move `LockedDegraded`, attempt one recovery (rebind/recreate VD surface path).
3. Recovery fails: emit status + force `Op.Takeover` equivalent pause (`PausedByPolicy`).

No blind execution while perception is dead.

## Integration Points

### `AgentService`

File: `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`

- register/unregister `DeviceStateMonitor` receiver with service lifecycle
- forward device state to active session via a new op:

```kotlin
data class DeviceLockChanged(val snapshot: DeviceLockSnapshot) : Op
```

### `AgentSession`

File: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt`

- own one `LockScreenExecutionController` (session-scoped)
- wire `Op.DeviceLockChanged` handler
- on first task start: controller `start(...)`
- on shutdown: controller `stop()` before `platform.stop()`

### `SessionServices`

File: `app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt`

- optionally host controller dependencies (clock/dispatchers/trace hooks)
- cleanup ordering: controller stop -> platform stop -> other services

### `VirtualDisplayPlatform`

File: `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt`

- expose a small liveness probe API (non-blocking + bounded timeout), e.g.:
  - `suspend fun probeLiveness(): Boolean`
- do not embed lockscreen policy logic here

## Observability

Add trace/status markers:
- `lock_state_changed`
- `device_interactive`
- `keyguard_locked`
- `wake_lock_acquired` / `wake_lock_released`
- `vd_probe_ok` / `vd_probe_failed`
- `lock_policy_pause_reason`

Keep these machine-friendly for scripts and regression tests.

## Security and Safety

Hard constraints:
- never attempt to unlock device credentials
- never claim support for secure surfaces (DRM/FLAG_SECURE capture remains unsupported)
- on permission loss (Shizuku or wake lock failure), transition to paused state

## Rollout Plan

### Phase 1: Contract + Safe Pause
- add config and controller scaffolding
- add device state op flow
- implement `PAUSE_WHEN_LOCKED` fully

### Phase 2: Continue on VD
- implement wake lock lease
- implement probe loop + degraded handling
- enable `CONTINUE_ON_VIRTUAL_DISPLAY`

### Phase 3: Hardening
- add recovery hooks for VD recreation
- add telemetry dashboards / debug-run assertions
- tune thresholds from real-device runs

## Testing Strategy

Unit tests:
- controller state transitions (`Unlocked -> LockedActive -> LockedDegraded -> PausedByPolicy`)
- policy matrix behavior for Accessibility vs VD
- wake lock lease idempotency and timeout paths

Integration tests:
- session receives `DeviceLockChanged` and pauses/resumes correctly
- cleanup always releases wake lock and receivers

Manual verification (device):
1. Run `./scripts/debug-run.sh --virtual-display "open settings and scroll down"`.
2. Lock screen during execution.
3. Verify expected behavior for each policy:
   - pause policy: task pauses cleanly
   - continue policy: task keeps progressing or degrades then pauses with explicit reason
4. Unlock and confirm session can resume without recreating app state unnecessarily.

## Acceptance Criteria

1. No crashes or leaked wake locks across start/stop cycles.
2. Deterministic behavior when screen locks (pause or continue by config).
3. No silent blind execution after perception loss.
4. Default config (`enabled=false`) keeps current behavior unchanged.

