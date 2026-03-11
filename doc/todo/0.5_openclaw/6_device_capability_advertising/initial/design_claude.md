# Design: Device Capability Advertising

## Goal

Replace the static, compile-time tool list with a dynamic, runtime-aware system where each tool reports its own availability based on actual device state (permissions, accessibility service, hardware). This prevents the agent from wasting turns calling tools that will inevitably fail, and provides the foundation for future plugin/capability systems.

## Current State Analysis

**How tools work today:**

1. `SessionToolingBootstrapper.registerBuiltInTools()` unconditionally registers all 8+ tools into `ToolRegistry`
2. `AgentDef.allowedTools` is a static `Set<String>` per agent role (e.g., StandaloneAgentDef hardcodes 9 tool names)
3. `ToolRegistry.generateResponsesApiTools()` sends all registered (and allowed) tools to the LLM
4. `ToolRouter` dispatches to tools — failures only discovered at execution time

**What breaks:**
- `MobileActionTool` requires accessibility service — if service disconnects mid-session, calls fail silently or crash
- `ShellTool` assumes certain filesystem access — varies by device/ROM
- Screenshot-dependent tools fail if media projection permission is revoked
- No mechanism to add/remove tools at runtime based on changing conditions

**Key insight:** `ToolSpec` has no concept of availability. `ToolRegistry` has `register`/`unregister` but nothing calls them dynamically. The registry is a dumb map — the intelligence needs to live at a different level.

## Approach

**Core principle:** Each tool owns its availability logic. A thin coordination layer queries tools and rebuilds the active set when device state changes. No central "capability manager" that knows about every tool's requirements.

### Why not modify ToolSpec directly?

Adding `isAvailable()` to `ToolSpec` would mix concerns — tool specification (schema, validation, execution) vs. capability probing (permission checks, service state). These change at different rates and for different reasons. Instead, introduce a separate `ToolProvider` concept that wraps tool creation with availability gating.

## Components

### 1. ToolProvider Interface

```kotlin
// tool/ToolProvider.kt
interface ToolProvider {
    /** Tools this provider can supply when conditions are met. */
    val toolNames: Set<String>

    /** Check if this provider's tools are currently available. */
    fun isAvailable(): Boolean

    /** Create the tool specs. Called only when isAvailable() == true. */
    fun createTools(): List<ToolSpec>

    /**
     * Human-readable reason when unavailable.
     * Shown in logs and optionally to the LLM as context.
     */
    fun unavailableReason(): String
}
```

### 2. Built-in ToolProvider Implementations

Group tools by their capability dependency, not by individual tool:

```kotlin
// tool/provider/CoreToolProvider.kt
/** Tools with no device dependencies — always available. */
class CoreToolProvider(private val sessionState: AgentSessionState) : ToolProvider {
    override val toolNames = setOf("wait", "write_todos", "scratchpad", "complete_task")
    override fun isAvailable() = true
    override fun createTools() = listOf(
        WaitTool(), WriteTodosTool(sessionState.todos),
        ScratchpadTool(sessionState.scratchpad), CompleteTaskTool()
    )
    override fun unavailableReason() = "" // never unavailable
}

// tool/provider/AccessibilityToolProvider.kt
/** Tools that require the accessibility service to be connected. */
class AccessibilityToolProvider(
    private val a11yStateProvider: () -> Boolean  // lambda to check service state
) : ToolProvider {
    override val toolNames = setOf("mobile_action", "system_button", "open_app")
    override fun isAvailable() = a11yStateProvider()
    override fun createTools() = listOf(MobileActionTool(), SystemButtonTool(), OpenAppTool())
    override fun unavailableReason() = "Accessibility service is not connected"
}

// tool/provider/ShellToolProvider.kt
/** Shell tool — requires basic filesystem access. */
class ShellToolProvider : ToolProvider {
    override val toolNames = setOf("shell")
    override fun isAvailable() = true  // always available; fails gracefully per-command
    override fun createTools() = listOf(ShellTool())
    override fun unavailableReason() = ""
}
```

**Design choice:** Group by _shared capability dependency_, not one provider per tool. MobileAction, SystemButton, and OpenApp all need accessibility service — they share one provider. This avoids N providers with identical `isAvailable()` logic.

### 3. DynamicToolRegistry

Wraps `ToolRegistry` with provider-aware lifecycle:

```kotlin
// tool/DynamicToolRegistry.kt
class DynamicToolRegistry(
    private val providers: List<ToolProvider>,
    private val allowedToolNames: Set<String>  // from AgentDef
) {
    private val registry = ToolRegistry()
    private val _unavailableTools = mutableMapOf<String, String>() // name → reason

    val unavailableTools: Map<String, String> get() = _unavailableTools.toMap()

    /** Rebuild the registry based on current provider states. */
    fun refresh(): ToolAvailabilityDelta {
        val previousNames = registry.getNames()
        registry.clear()
        _unavailableTools.clear()

        for (provider in providers) {
            if (provider.isAvailable()) {
                provider.createTools()
                    .filter { it.name in allowedToolNames }
                    .forEach { registry.register(it) }
            } else {
                val reason = provider.unavailableReason()
                provider.toolNames
                    .filter { it in allowedToolNames }
                    .forEach { _unavailableTools[it] = reason }
            }
        }

        val currentNames = registry.getNames()
        return ToolAvailabilityDelta(
            added = currentNames - previousNames,
            removed = previousNames - currentNames
        )
    }

    /** Delegate to inner registry for LLM schema generation. */
    fun generateResponsesApiTools() = registry.generateResponsesApiTools()

    /** Delegate for tool lookup during execution. */
    fun get(name: String) = registry.get(name)
    fun getAll() = registry.getAll()
    fun contains(name: String) = registry.contains(name)
    fun getNames() = registry.getNames()
    fun size() = registry.size()
}

data class ToolAvailabilityDelta(
    val added: Set<String>,
    val removed: Set<String>
) {
    val hasChanges: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()
}
```

### 4. Integration: SessionToolingBootstrapper Changes

```kotlin
// session/SessionToolingBootstrapper.kt (modified)
internal object SessionToolingBootstrapper {
    fun create(
        approvalMode: ApprovalMode,
        a11yStateProvider: () -> Boolean
    ): SessionToolingBootstrap {
        val policyEngine = PolicyEngine(approvalMode)
        val sessionState = AgentSessionState()

        val providers = listOf(
            CoreToolProvider(sessionState),
            AccessibilityToolProvider(a11yStateProvider),
            ShellToolProvider()
        )

        val dynamicRegistry = DynamicToolRegistry(
            providers = providers,
            allowedToolNames = /* from AgentDef, passed in or resolved here */
        )
        dynamicRegistry.refresh()  // initial population

        val toolRouter = ToolRouter(dynamicRegistry.innerRegistry, policyEngine)
        // ...
    }
}
```

### 5. Agent Loop Integration: Per-Turn Refresh

The lightest integration point is refreshing at the start of each turn's planning phase:

```kotlin
// agent/TurnPlanningPhaseRunner.kt (modified)
suspend fun runPlanningPhase(...) {
    // Refresh tool availability before building the LLM request
    val delta = dynamicToolRegistry.refresh()
    if (delta.hasChanges) {
        Log.i(TAG, "Tool availability changed: +${delta.added} -${delta.removed}")
    }

    // Existing flow: build input items, create Turn, call LLM
    // Tools sent to LLM now reflect current device state
    val turn = Turn(dynamicToolRegistry, llmClient, allowedToolNames)
    // ...
}
```

**Why per-turn, not event-driven?** Polling at turn boundaries is simpler and sufficient. Accessibility service disconnects are rare, and the agent can't act between turns anyway. Event-driven refresh adds complexity (threading, race conditions with in-flight requests) for marginal benefit.

### 6. LLM Context: Unavailable Tool Hints

When tools become unavailable, optionally inform the LLM so it doesn't attempt workarounds:

```kotlin
// In prompt building, append to system message if any tools are unavailable:
if (dynamicRegistry.unavailableTools.isNotEmpty()) {
    val hint = dynamicRegistry.unavailableTools.entries.joinToString("\n") { (tool, reason) ->
        "- $tool: $reason"
    }
    systemPrompt += "\n\n## Unavailable Capabilities\n$hint"
}
```

This is optional and low-priority. Simply not including the tool in the schema is usually enough — the LLM won't call tools it doesn't see.

## Interactions

### Data Flow (per turn)

```
Turn Start
  │
  ├─ DynamicToolRegistry.refresh()
  │   ├─ CoreToolProvider.isAvailable() → true (always)
  │   ├─ AccessibilityToolProvider.isAvailable() → check service
  │   └─ ShellToolProvider.isAvailable() → true
  │
  ├─ registry now contains only available tools
  │
  ├─ TurnPlanningPhaseRunner
  │   ├─ registry.generateResponsesApiTools() → only available tools sent to LLM
  │   └─ LLM sees only tools it can actually use
  │
  └─ TurnExecutionPhaseRunner
      └─ ToolRouter.execute() → tool guaranteed to be in registry
```

### State Transitions (capability lifecycle)

```
[All Available] ──(a11y disconnects)──→ [Degraded: no mobile_action/system_button/open_app]
       ↑                                            │
       └────────(a11y reconnects)───────────────────┘
```

No explicit state machine needed — `DynamicToolRegistry.refresh()` derives state from providers each time.

## Trade-offs

### Considered: `isAvailable()` on ToolSpec directly

**Rejected.** Mixes specification with runtime state. ToolSpec is a clean data contract (name, schema, validate, execute). Adding availability probes breaks single responsibility and makes testing harder — every test would need to stub availability too.

### Considered: Event-driven capability updates (BroadcastReceiver / Flow)

**Deferred to Phase 2.** Real-time events (accessibility service connect/disconnect, permission changes) could trigger immediate registry rebuilds. But:
- Adds threading complexity (concurrent registry modification during turn execution)
- The agent loop is sequential — it can only react at turn boundaries anyway
- Per-turn polling is ~0ms overhead (checking a boolean service state)

Phase 2 adds this when we need sub-turn reactivity (e.g., mid-execution recovery).

### Considered: Capability negotiation protocol (OpenClaw-style `node.describe`)

**Deferred to Phase 3.** Broadcasting capabilities to an external gateway is a networking feature. The design here (ToolProvider abstraction + DynamicToolRegistry) provides the internal foundation. Phase 3 serializes `DynamicToolRegistry` state as a capability manifest for the gateway protocol.

### Considered: Per-invocation availability check (check right before execute)

**Rejected for primary mechanism, useful as safety net.** The main check should happen before LLM planning (so unavailable tools aren't in the schema). A secondary check at execution time is a cheap guard:

```kotlin
// ToolRouter.execute() — add one line
if (!dynamicRegistry.contains(toolName)) {
    return ToolCallResult.Error("Tool '$toolName' is no longer available")
}
```

## Phase Summary

| Phase | Scope | Effort |
|-------|-------|--------|
| **1: Dynamic Tool Registration** | ToolProvider interface, DynamicToolRegistry, per-turn refresh, bootstrapper changes | Core work |
| **2: Runtime Capability Events** | Accessibility service listener, permission change receiver, mid-session tool set updates | Follow-up |
| **3: External Capability Broadcast** | Serialize capability manifest, gateway protocol integration | Future (OpenClaw) |

This design focuses on Phase 1 — it's the foundation that makes Phase 2 and 3 trivial extensions.

## Files Changed

| File | Change |
|------|--------|
| `tool/ToolProvider.kt` | **New** — interface |
| `tool/DynamicToolRegistry.kt` | **New** — provider-aware registry wrapper |
| `tool/provider/CoreToolProvider.kt` | **New** — always-available tools |
| `tool/provider/AccessibilityToolProvider.kt` | **New** — a11y-dependent tools |
| `tool/provider/ShellToolProvider.kt` | **New** — shell tool |
| `session/SessionToolingBootstrapper.kt` | **Modified** — use providers + DynamicToolRegistry |
| `agent/TurnPlanningPhaseRunner.kt` | **Modified** — call `refresh()` per turn |
| `tool/ToolRouter.kt` | **Modified** — add safety check for stale tool calls |
| `tool/ToolRegistry.kt` | **No change** — stays as-is, wrapped by DynamicToolRegistry |
