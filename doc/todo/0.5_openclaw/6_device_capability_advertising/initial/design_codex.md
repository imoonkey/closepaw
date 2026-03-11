# Design: Device Capability Advertising

## Goal

Make the agent advertise and use the tool set that is actually available on the current device at runtime.

Today the session mostly registers tools once in `SessionToolingBootstrapper`, then each turn exposes that static registry to the LLM. That means the model can see tools that are conceptually built in but not truly usable in the current runtime. The design goal is:

- expose only tools that are currently usable
- keep agent-mode allowlists (`AgentDef.allowedTools`) as policy, not capability truth
- let each tool own its own availability logic
- make capability loss safe even if it happens after planning but before execution
- produce a stable `node.describe`-style snapshot for future UI or remote control surfaces

## Current State

Relevant current behavior:

- `SessionToolingBootstrapper` registers built-in tools eagerly and statically.
- `SessionAgentRunner` lazily adds `delegate_task` and `ask_user`.
- `Turn` regenerates tool schemas every turn from `ToolRegistry`, but `ToolRegistry` itself is mostly static.
- `ToolRouter` validates tool existence and params, but does not re-check runtime availability before execution.
- `PlatformFactory` already performs one runtime capability decision: `VIRTUAL_DISPLAY` falls back to `ACCESSIBILITY` when Shizuku is unavailable.

This means the architecture already has one useful property: tool schemas are rebuilt every turn. We should exploit that instead of adding a lot of event-driven registry mutation.

## Approach

### 1. Separate raw runtime facts from tool availability

Add a session-scoped runtime snapshot that answers: what can this session do right now?

```kotlin
data class CapabilitySnapshot(
    val platformMode: PlatformMode,
    val activeCaps: Set<CapabilityId>,
    val inactiveCaps: Map<CapabilityId, String>,
    val generatedAtEpochMs: Long
)

enum class CapabilityId {
    PLATFORM_READY,
    UI_ACTION,
    APP_LAUNCH,
    LOCAL_SHELL,
    PLANNING_MEMORY,
    USER_RESPONSE,
    DELEGATION
}
```

`CapabilitySnapshot` is raw truth, not a tool list. It says what operations exist and why missing ones are unavailable.

Ownership:

- `AndroidPlatform` reports platform-derived capabilities.
- session wiring reports session-derived capabilities (`USER_RESPONSE`, `DELEGATION`, memory tools)
- the capability service merges those into one snapshot

This keeps the central layer responsible only for collecting facts, not for deciding which tools should exist.

### 2. Replace static tool registration with capability-aware providers

Instead of registering only `ToolSpec`, register providers that can answer both:

- how to build the tool
- whether it is currently available

```kotlin
interface ToolProvider {
    val name: String
    fun createSpec(): ToolSpec
    fun availability(snapshot: CapabilitySnapshot): ToolAvailability
}

sealed interface ToolAvailability {
    data object Available : ToolAvailability
    data class Unavailable(val reason: String) : ToolAvailability
}
```

Examples for the current tool set:

- `complete_task`, `write_todos`, `scratchpad`: always available
- `shell`: requires `LOCAL_SHELL`
- `ask_user`: requires `USER_RESPONSE`
- `delegate_task`: requires `DELEGATION`
- `mobile_action`, `system_button`, `wait`: require `UI_ACTION`
- `open_app`: requires `APP_LAUNCH`

This satisfies the brief’s main rule: each tool decides its own availability. The center only supplies the snapshot.

### 3. Keep `ToolRegistry`, but make it a provider catalog

Do not model capability changes as repeated `register()/unregister()` churn. That creates unnecessary mutable state and race edges.

Instead, keep one stable registry of providers for the session:

```kotlin
class ToolRegistry(
    private val capabilitySource: CapabilitySource
) {
    fun register(provider: ToolProvider)
    fun availableSpecs(filter: (String) -> Boolean = { true }): List<ToolSpec>
    fun resolveForExecution(name: String): AvailableTool?
    fun describe(filter: (String) -> Boolean = { true }): DeviceDescription
}
```

From the rest of the system's point of view, this is still dynamic runtime registration: the command set exposed to the model is recomputed from live capability state. The only difference is that the implementation uses dynamic resolution instead of imperative add/remove mutations.

`availableSpecs(...)` computes:

`registered providers` ∩ `tool provider says available` ∩ `agent allowlist` ∩ `excludedTools`

`resolveForExecution(name)` is the execution-time gate. It returns either:

- an available `ToolSpec`
- or an unavailable reason such as `"open_app unavailable: app launch capability missing"`

That removes the current mismatch where planning and execution trust different notions of availability.

### 4. Refresh capabilities by polling at canonical boundaries

Phase 1 should be pull-based, not listener-heavy.

Refresh the snapshot at:

- session creation
- first `platform.start()`
- hot-idle re-entry (`reacquirePlatform()`)
- start of each planning phase
- immediately before tool execution
- immediately after platform-start failure or execution-time capability failure

Why this is enough for phase 1:

- `Turn` already rebuilds tools every turn
- most capability changes only matter at turn boundaries
- execution-time recheck closes the race where capability changes between planning and acting

This turns dynamic capability handling into the canonical case without building a complex event bus first.

Phase 2 can add push refresh for instant UI updates when permissions or platform connectivity change mid-turn.

### 5. Add a stable `node.describe` projection now

Expose the session’s capability state as a small immutable description:

```kotlin
data class DeviceDescription(
    val commands: List<String>,
    val caps: List<String>,
    val platform: String,
    val version: String
)
```

For now this is an internal projection used by:

- debugging (`SessionServicesSummaryFormatter`)
- traces / inspection artifacts
- future UI surfaces

`commands` comes from currently available tools after policy filtering.
`caps` comes from `CapabilitySnapshot.activeCaps`.

This gives phase 3 a stable payload shape without forcing remote-control implementation now.

## Components

### `CapabilitySource`

New session-scoped service that owns the latest `CapabilitySnapshot`.

Responsibilities:

- query `AndroidPlatform` for platform capabilities
- add session capabilities (`PLANNING_MEMORY`, `USER_RESPONSE`, `DELEGATION`)
- cache the latest snapshot
- expose `refresh()` and `current()`

It belongs in `SessionServices`.

### `AndroidPlatform.capabilities()`

Extend `AndroidPlatform` with a structured capability report.

`AccessibilityPlatform` should report:

- `PLATFORM_READY`
- `UI_ACTION`
- `APP_LAUNCH`

when the accessibility service is operational.

`VirtualDisplayPlatform` should report the same operational capabilities only when Shizuku is alive and permission is still granted.

This is better than reusing `hasRequiredPermissions(): Boolean` because the new flow needs partial capability truth, not one coarse yes/no.

### `ToolProvider` implementations

Each existing tool gets a thin provider next to its current implementation in `tool/impl/`.

The tool code itself should stay mostly unchanged. Availability belongs in the provider, not in every invocation.

This is also the extension point for future plugin or skill-owned tools: adding a new tool means adding one provider, not editing a central capability switchboard.

### `ToolRegistry`

`ToolRegistry` becomes a catalog of providers plus lookup helpers for:

- planning-time exposure
- execution-time resolution
- description/debug output

### Session bootstrap

`SessionToolingBootstrapper` should build the provider catalog once.

`SessionAgentRunner` should stop mutating the registry during `start()`. Instead:

- if the session config supports delegation, register the `delegate_task` provider at bootstrap
- always register `ask_user` provider at bootstrap because the response channel already exists in `SessionServices`

This simplifies lifecycle and avoids mode-specific late registry mutation.

## Interaction Flow

### Planning flow

1. `TurnPlanningPhaseRunner` calls `capabilitySource.refresh()`.
2. `Turn` asks `ToolRegistry.availableSpecs(...)`.
3. `Turn` sends only that filtered tool set to the model.
4. The model cannot plan around tools that are not currently usable.

### Execution flow

1. `ToolRouter.execute(...)` asks `ToolRegistry.resolveForExecution(toolName)`.
2. If unavailable, return a deterministic tool error with the provider’s reason.
3. Refresh capabilities after that failure so the next turn sees the updated truth.
4. If available, execute normally.

### Description flow

1. `SessionServicesSummaryFormatter` asks `ToolRegistry.describe(...)`.
2. It prints active commands and active caps instead of only static registered names.

## State Machine

Per tool, availability is a simple state machine:

- `UNKNOWN`
- `AVAILABLE`
- `UNAVAILABLE(reason)`

Transitions:

- `session_bootstrap` -> evaluate initial state
- `platform_started` -> re-evaluate
- `pre_turn_refresh` -> re-evaluate
- `pre_execution_refresh` -> re-evaluate
- `runtime_loss detected` -> move to `UNAVAILABLE`
- `runtime_recovered` -> move back to `AVAILABLE`

Side effects:

- `AVAILABLE`: tool schema may be exposed to the LLM
- `UNAVAILABLE`: tool schema is hidden and direct execution is rejected with reason

No special-case branch is needed for “tool was once available but changed later”; that is just a normal state transition.

## Scope

### In scope for this project

- dynamic runtime filtering of tool exposure
- execution-time availability recheck
- structured capability snapshot and description payload
- bootstrap simplification so providers are registered once per session
- debug/trace visibility for current commands and caps

### Not in scope

- remote gateway or web console
- full permission observer graph
- hardware-specific tools not present in this repo yet
- changing prompt text beyond what naturally follows from the new tool set

## Trade-offs

### Why not keep mutating the registry on every capability change?

Because the registry is already read every turn. Recomputing availability from a stable provider catalog is simpler, easier to reason about, and removes races around register/unregister timing.

### Why not put all availability logic in one central mapping?

That would be easy initially, but it violates the brief’s key principle and becomes brittle as new tools appear. Tool-local availability scales better.

### Why not solve phase 2 now with listeners and flows everywhere?

Because phase 1 does not need it. Polling at turn and execution boundaries solves the user-visible failure mode with much less complexity. Push updates can be added later without changing the core provider model.

## Verification Plan

- unit tests for provider availability decisions from snapshots
- unit tests for `ToolRegistry.availableSpecs()` intersection logic
- unit tests for `ToolRouter` rejecting execution when a tool becomes unavailable after planning
- integration test for `PlatformFactory` fallback to accessibility mode producing the correct command set
- debug summary test covering `commands` and `caps` output

## Result

The simplest durable design is:

- one stable registry of tool providers
- one live capability snapshot
- one filtered projection of available commands per turn
- one execution-time availability gate

That gets the agent out of the “tool exists in code, therefore it must be usable” trap and creates a clean base for future plugin and external-advertising work.
