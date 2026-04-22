# Aligned Design: Device Capability Advertising

## Goal

Expose only the commands this session can actually execute at runtime, instead of exposing a mostly static tool list and discovering failures only after the model calls a tool.

This must preserve the current architecture:

- one shared session tool catalog
- per-agent allowlists
- sub-agent filtered tool views
- safe behavior when capability changes after planning but before execution

It should also create a clean base for future `node.describe`-style capability advertising.

## Current Constraints

Today:

- `SessionToolingBootstrapper` eagerly registers built-in tools.
- `SessionAgentRunner` lazily registers `ask_user` and `delegate_task`.
- `Turn` regenerates tool schemas every turn from `ToolRegistry`.
- `ToolRouter` only checks whether a tool exists, not whether it is still available.
- sub-agents build filtered child registries from the parent registry.

That means the system already has one useful property: tool schemas are rebuilt per turn. We should use that instead of building an event-heavy mutation system.

## Core Design

### 1. Session-scoped capability snapshot

Add a session-scoped capability source that produces a point-in-time snapshot of raw runtime facts.

```kotlin
data class CapabilitySnapshot(
    val platformMode: PlatformMode,
    val activeCaps: Set<CapabilityId>,
    val inactiveReasons: Map<CapabilityId, String>,
    val generatedAtEpochMs: Long
)

enum class CapabilityId {
    UI_ACTION,
    APP_LAUNCH,
    LOCAL_SHELL,
    USER_RESPONSE,
    DELEGATION
}
```

Purpose:

- one place to gather runtime truth
- one stable payload for future `node.describe`
- one shared input for all tool availability checks

This is intentionally small. It is not a second policy layer.

### 2. One provider per tool

Each tool owns its own availability decision.

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

Rules:

- one provider maps to one tool name
- shared predicates are fine, but the final availability decision stays tool-local
- this keeps the system extensible for future plugin or skill-owned tools

Examples:

- `wait`, `complete_task`, `write_todos`, `scratchpad`: always available
- `mobile_action`, `system_button`: require `UI_ACTION`
- `open_app`: requires `APP_LAUNCH`
- `shell`: phase 1 may treat as always available because command-level failures are already explicit
- `ask_user`: requires `USER_RESPONSE`
- `delegate_task`: requires `DELEGATION`

Important correction from the initial drafts:

- `wait` must remain always available
- `open_app` must not be bundled with `mobile_action` just because both are common in accessibility mode

### 3. Keep agent allowlists separate from capability filtering

Capability filtering and agent policy filtering are different concerns.

The effective tool set is:

`provider available at runtime` ∩ `agent allowedTools` ∩ `excludedTools`

This must stay true for:

- standalone agent
- planner
- executor
- sub-agents built from filtered parent views

The dynamic capability layer must not bake a single `allowedToolNames` set into the session-wide catalog.

### 4. Evolve `ToolRegistry` into a provider-backed catalog with filtered views

Keep the name `ToolRegistry`, but change what it stores: providers instead of a static final set of live tools.

Required operations:

```kotlin
class ToolRegistry(
    private val capabilitySource: CapabilitySource,
    private val providers: Map<String, ToolProvider>,
    private val allowedNames: Set<String>? = null,
    private val excludedNames: Set<String> = emptySet()
) {
    fun register(provider: ToolProvider)
    fun getAvailable(name: String): AvailableTool?
    fun generateResponsesApiTools(filter: ((ToolSpec) -> Boolean)? = null): List<FunctionTool>
    fun getNames(): Set<String>
    fun describe(filter: ((String) -> Boolean)? = null): DeviceDescription
    fun createFilteredCopy(
        allowedNames: Set<String>,
        excludedNames: Set<String> = emptySet()
    ): ToolRegistry
}
```

Behavior:

- `generateResponsesApiTools(...)` evaluates provider availability against the current snapshot, then applies the existing filter callback
- `getAvailable(name)` resolves one tool against the current snapshot and returns either an executable spec or an unavailable reason
- `createFilteredCopy(...)` creates a filtered view over the same provider catalog and capability source, so sub-agents stay dynamic too

This avoids clear-and-rebuild churn while preserving the existing call sites conceptually.

### 5. Bootstrap once per session

Register providers once per session, but in two construction stages because not all dependencies exist at the same time.

Stage 1 providers, registered during session/bootstrap wiring when platform predicates and session state already exist:

- `complete_task`
- `wait`
- `write_todos`
- `scratchpad`
- `mobile_action`
- `system_button`
- `open_app`
- `shell`

Stage 2 providers, registered once after session event wiring exists:

- `ask_user`
- `delegate_task` — always registered, gated by `DELEGATION` capability. This avoids conditional bootstrap logic and keeps the model consistent: every tool has a provider, availability is always runtime-evaluated.

The key change is that `SessionAgentRunner.start()` should stop mutating the registry with late tool registration.

## Refresh Model

Phase 1 is pull-based.

Refresh the capability snapshot at canonical boundaries:

- session creation
- first `platform.start()`
- hot-idle re-entry
- start of each planning phase
- immediately before execution
- immediately after approval wait, before invoking the tool
- after platform-start or execution-time capability failure

Why this is enough:

- tool schemas are already rebuilt every turn
- most capability changes matter at turn boundaries
- the execution-time refresh closes the race during approval waits or transient disconnects

No event bus is needed in phase 1.

## Runtime Flow

### Planning

1. `TurnPlanningPhaseRunner` refreshes the capability snapshot.
2. `Turn` asks `ToolRegistry.generateResponsesApiTools(...)`.
3. The tool registry includes only currently available tools that also survive the agent allowlist filter.
4. The model never sees tools that are currently unavailable.

### Execution

1. `ToolRouter.execute(...)` resolves the tool through `ToolRegistry.getAvailable(name)`.
2. If unavailable, return a deterministic tool error with the provider reason.
3. Validate params and build the invocation.
4. If approval is needed, wait as today.
5. After approval, refresh capabilities again and re-resolve the tool before executing.
6. If the tool became unavailable during the wait, fail cleanly instead of executing stale intent.

This execution-time recheck is mandatory, not optional.

### Sub-agents

1. `SubAgentRunner` creates a filtered registry view from the parent registry.
2. The child view shares the same provider catalog and capability source.
3. The child still applies its own allowlist, but availability remains live.

This preserves the current multi-agent design instead of replacing it.

## Device Description

Expose a stable internal description now:

```kotlin
data class DeviceDescription(
    val commands: List<String>,
    val caps: List<String>,
    val platform: String,
    val version: String
)
```

Meaning:

- `commands`: currently available tool names after runtime capability filtering
- `caps`: currently active capability ids
- `platform`: current platform mode
- `version`: app version

Phase 1 uses this for:

- debug summaries
- traces and inspection artifacts
- future alignment with external control surfaces

## Error Handling

Capability refresh must be robust:

- if refresh fails transiently, keep the last complete snapshot and log the failure
- do not publish a half-built snapshot
- if no snapshot exists yet, fall back to a conservative snapshot that hides capability-gated tools

This keeps behavior deterministic during reconnects.

## Out of Scope for Phase 1

- push-based permission or service listeners
- networked gateway integration
- UI for live capability inspection
- changing prompts to enumerate unavailable tools

Simply omitting unavailable tools from the schema is enough for phase 1.

## Why This Design

This is the smallest design that solves the real problem:

- it respects the current agent/sub-agent wiring
- it keeps availability owned by each tool
- it prevents stale execution after capability loss
- it creates a direct path to `node.describe`

The main anti-goals are:

- no single session-wide allowlist baked into the registry
- no grouped provider that hides tool-specific capability rules
- no register/unregister churn loop
- no event-driven machinery before it is needed
