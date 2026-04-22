# Review of Codex Design: Device Capability Advertising

Reviewer: Claude
Reviewed document: `design_codex.md`

## Overall Assessment

Strong design. It arrives at the same core architecture (ToolProvider + per-turn polling + execution-time gate) but adds two meaningful extras: (1) a `CapabilitySnapshot` layer that separates raw device facts from tool availability decisions, and (2) a `DeviceDescription` projection for debug/future use. The trade-off analysis is sound and the phasing is well-reasoned.

There are a few areas worth scrutinizing.

---

## Correctness Issues

### 1. CapabilitySnapshot indirection — necessary or premature?

The design introduces `CapabilityId` enum + `CapabilitySnapshot` as an intermediary between platform state and tool availability:

```
AndroidPlatform → CapabilitySource → CapabilitySnapshot → ToolProvider.availability(snapshot) → tool visible?
```

Compare with the simpler path:

```
AndroidPlatform → ToolProvider.isAvailable() → tool visible?
```

The snapshot adds value if multiple providers need the same capability fact (avoiding redundant platform queries) or if we want a serializable point-in-time record. But for Phase 1 the capability checks are trivially cheap — `isAccessibilityServiceConnected()` is a boolean read. The snapshot layer creates:

- A new enum (`CapabilityId`) that must be kept in sync with platform capabilities
- A new service (`CapabilitySource`) that must be threaded through session wiring
- A dependency inversion where providers must interpret an enum they don't own, instead of directly querying the thing they depend on

**Verdict:** The snapshot is a good Phase 2/3 artifact (especially for `node.describe` serialization and trace recording). For Phase 1, it's added complexity without added capability. Consider deferring `CapabilitySnapshot` and having providers query platform state directly, then introducing the snapshot when external broadcasting or trace recording demands it.

### 2. `WaitTool` grouped under `UI_ACTION` capability

The design maps `wait` to `UI_ACTION`:

> `mobile_action`, `system_button`, `wait`: require `UI_ACTION`

But `WaitTool` is a deterministic delay (`Thread.sleep` / `delay`). It doesn't touch the accessibility service at all. It works fine without UI capabilities. Grouping it with `UI_ACTION` would make `wait` disappear when accessibility disconnects, which is wrong — the agent should still be able to wait even in degraded mode.

**Fix:** Move `wait` to always-available (alongside `complete_task`, `write_todos`, `scratchpad`).

### 3. ToolRegistry modification vs. wrapping

The design proposes modifying `ToolRegistry` itself to accept a `CapabilitySource` and become provider-aware:

```kotlin
class ToolRegistry(
    private val capabilitySource: CapabilitySource
) {
    fun register(provider: ToolProvider)
    fun availableSpecs(filter: ...): List<ToolSpec>
    fun resolveForExecution(name: String): AvailableTool?
}
```

This changes `ToolRegistry` from a simple map to a stateful service with runtime dependencies. Currently `ToolRegistry` is a plain data structure — it's easy to test, easy to create filtered copies, and has no lifecycle. Adding `CapabilitySource` couples it to session infrastructure.

Alternative: wrap `ToolRegistry` (as my design does with `DynamicToolRegistry`) so the inner registry stays a dumb map. The wrapper owns the provider-awareness. This preserves `ToolRegistry`'s simplicity and testability.

**Verdict:** Both approaches work, but wrapping preserves a cleaner separation. The Codex design's approach is viable if the team prefers fewer classes over stricter SRP.

---

## Gaps

### 4. `ask_user` and `delegate_task` provider lifecycle

The design correctly identifies that `SessionAgentRunner` currently registers `delegate_task` and `ask_user` late:

> `SessionAgentRunner` should stop mutating the registry during `start()`. Instead [...] register the providers at bootstrap.

But it doesn't specify the availability condition for these tools. `delegate_task` depends on multi-agent mode (planner-executor). `ask_user` depends on an active response channel. What `CapabilityId` values gate these? `USER_RESPONSE` and `DELEGATION` are defined in the enum but the provider implementations aren't sketched.

This matters because these are the tools most likely to have genuinely variable availability across session configurations, unlike the accessibility tools which are either all-on or all-off.

### 5. Thread safety of `refresh()` + `resolveForExecution()`

The design calls for refresh at multiple points including "immediately before tool execution." If the planning phase calls `availableSpecs()` and then execution calls `resolveForExecution()` which internally refreshes, the tool set could change between planning and execution within the same turn. The design acknowledges this race:

> execution-time recheck closes the race where capability changes between planning and acting

But a tool that was planned could now return `Unavailable`. The design doesn't specify what happens to the remaining batched tool calls in that turn. Does the turn abort? Do other tool calls proceed? This edge case should be specified.

### 6. No verification of `CapabilitySnapshot` consistency

`CapabilitySource` merges platform capabilities with session capabilities. What happens if `AndroidPlatform.capabilities()` throws or returns partial results (e.g., service is in a transitional state during reconnection)? The design doesn't specify error handling for snapshot generation itself. A stale-but-complete snapshot is better than a fresh-but-partial one.

---

## Trade-off Analysis

### 7. Dynamic resolution vs. imperative add/remove — good call

The Codex design explicitly argues against register/unregister churn:

> Do not model capability changes as repeated `register()/unregister()` churn. That creates unnecessary mutable state and race edges.

This is a strong argument. The "compute availability from providers each time" approach is inherently idempotent. No ordering bugs, no forgotten unregister calls, no stale entries. The Codex design is cleaner than my own on this point — my `DynamicToolRegistry.refresh()` does clear-and-rebuild which is functionally equivalent but expressed as mutations.

### 8. One provider per tool vs. grouped providers

The Codex design proposes one provider per tool ("each existing tool gets a thin provider next to its current implementation"). My design groups tools by shared capability dependency. Trade-offs:

| | Per-tool provider | Grouped provider |
|---|---|---|
| File count | 9+ provider files | 3-4 provider files |
| Availability duplication | Multiple providers with `requires(UI_ACTION)` | One provider per capability |
| Granularity | Can gate individual tools | Gates move in lockstep |
| Plugin extensibility | Natural: one provider = one plugin | Plugins need their own groups |

For the current tool set, grouped is simpler. For a future plugin system, per-tool is more natural. The Codex design's choice is defensible for long-term extensibility, though it adds more files for Phase 1.

### 9. `DeviceDescription` — lightweight and useful

The `DeviceDescription` data class is a small addition with clear value for debugging and traces. Even if Phase 3 (external broadcast) never ships, having a structured capability summary in traces is useful for eval analysis and cog-tune. Good inclusion.

---

## Summary

| Finding | Severity | Recommendation |
|---------|----------|----------------|
| `WaitTool` wrongly gated by `UI_ACTION` | Bug | Move to always-available |
| `CapabilitySnapshot` may be premature for Phase 1 | Design | Consider deferring; let providers query platform directly |
| `ToolRegistry` modification vs. wrapping | Design | Either works; wrapping is cleaner SRP |
| `ask_user`/`delegate_task` provider conditions unspecified | Gap | Specify availability logic |
| Turn behavior when execution-time recheck fails mid-batch | Gap | Specify: abort batch or continue remaining? |
| Snapshot error handling during platform transitions | Gap | Specify: stale snapshot fallback |
| Per-tool providers vs. grouped | Trade-off | Grouped is simpler for Phase 1; per-tool better for plugins |
| `DeviceDescription` inclusion | Positive | Good for debug/trace value |

The designs converge on the same core: ToolProvider interface, per-turn refresh, execution-time safety gate. The Codex design adds more forward-looking structure (CapabilitySnapshot, DeviceDescription, per-tool providers). Whether that structure earns its keep in Phase 1 depends on how soon Phase 2/3 follow. If they're imminent, the extra structure pays off. If Phase 1 stands alone for a while, the simpler grouped-provider approach with deferred snapshot is lower risk.
