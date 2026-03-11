# 0001_CLAUDE — Initial Consolidated Draft

## What I did

Created `final/design.md` — a consolidated design synthesizing both individual designs and incorporating findings from both reviews.

## Key decisions in the draft

1. **One provider per tool** (Codex approach). Each tool gets its own `ToolProvider` with `isAvailable()`. Shared predicates (e.g., `a11yAvailable: () -> Boolean`) are injected via constructor, giving DRY without lockstep coupling. This follows the brief's "each tool owns its availability" principle more faithfully than grouping.

2. **Modify ToolRegistry, don't wrap it** (Codex approach). `ToolRegistry` stores providers instead of specs. Avoids a wrapper class that duplicates every method. The registry remains simple — it's a map of providers with derived availability.

3. **Layered filtering preserved** (Codex review finding). Capability filtering (provider says available) is separate from agent policy filtering (`AgentDef.allowedTools`). The registry handles capability; `Turn` + `AgentExecutionConfig` handle policy. This addresses Codex's valid critique that Claude's design baked `allowedToolNames` into `DynamicToolRegistry`, conflicting with multi-agent filtering.

4. **Execution-time gate via `resolveForExecution`** (consensus). `ToolRouter` re-checks provider availability before execution, not just registry membership. Closes the planning→execution race. Individual tool failures don't abort the batch — matching current validation-failure behavior.

5. **`WaitTool` is always-available** (Claude review finding). Its implementation is `delay()`, not a11y-dependent. Codex design grouped it under `UI_ACTION` — fixed.

6. **`ask_user` and `delegate_task` specified** (both reviews flagged this gap). `AskUserToolProvider` is always-available (response channel exists in all main sessions). `DelegateTaskToolProvider` is gated by `delegationAvailable: () -> Boolean` based on session mode.

7. **No `CapabilitySnapshot` in Phase 1** (Claude position). Deferred to Open Questions. `DeviceDescription` serves the debug/trace need without the enum sync burden. Listed as open question for Codex to contest.

8. **`DeviceDescription` included** (Codex contribution). Lightweight data class for debug/traces. Good value with minimal cost.

9. **Sub-agent support explicit** (Codex review finding). `createFilteredCopy` copies providers, not specs, so child registries maintain dynamic availability.

## Still open / for Codex to review

- **CapabilitySnapshot deferral** — I argued to defer; Codex may push to include. The design works without it, but Codex may have arguments about trace consistency or Phase 3 readiness.
- **`createSpec()` called on every check** — Should providers cache the spec, or is fresh creation each time acceptable? Current tool constructors are cheap, but worth confirming.
- Any other gaps I may have missed from the Codex review.

## Vote

**CHANGES** — I created a new `final/design.md`, so Codex needs to review.
