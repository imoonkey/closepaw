# Tool System Design Review (Codex)

## Scope

Reviewed the full `app/src/main/kotlin/com/moonkey/androidagent/tool/` module:

- Framework: `ToolSpec`, `ToolRegistry`, `ToolRouter`, `PolicyEngine`, `ToolCallState`, `ToolCallResult`, `ToolName`, `ToolSchemaConverters`
- Implementations: all files under `tool/impl`
- Action layer: all files under `tool/action`
- Handlers: all files under `tool/handlers`
- `AppClassifier`

I also checked a few direct consumers outside the module only where that was necessary to verify impact:

- tool registration
- turn arbitration
- action signature generation

I did not read the parallel Claude review docs or any other `doc/todo` design files.

## Executive Summary

The top-level shape is good:

- declarative tool specs
- a registry
- a router-owned approval lifecycle
- a mostly centralized `mobile_action` entrypoint

The design stops being minimal in three places:

1. the blocked-app security boundary is not enforced end to end
2. tool metadata is duplicated and heuristic instead of coming from the tool specs
3. post-action capture, fallback, and cancellation semantics are spread across multiple layers

My judgement:

- Pipeline minimal: partially
- State machine clean: mostly in `ToolRouter`, not end to end
- Policy engine correct: no
- Tools consistent: inputs mostly yes, outputs/capabilities no
- Action fallback chains: click/long-press are reasonably centralized; scroll/type/swipe still drift

## What Is Working

- `ToolRouter` has a clear approval lifecycle and mostly clean state progression.
- `MobileActionTool` is the right consolidation move; it avoids one-tool-per-gesture sprawl.
- `TargetResolver` is pure and simple.
- `ClickExecutor` and `LongPressExecutor` share a common point-action core instead of duplicating fallback logic.
- `PolicyEngine` itself is small and understandable; the correctness issues come from missing metadata and missing end-to-end enforcement, not from overcomplicated branching inside the file.

## Critical

### 1. The blocked-app boundary is not enforced end to end

This is the most important design flaw in the module.

- `PolicyEngine.check()` decides based on the current foreground package only, before the tool executes (`app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt:43-79`).
- `open_app` resolves the destination package only inside invocation execution, after policy has already allowed the call, then launches and captures the destination screen (`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt:143-221`).
- Raw post-action screen capture happens in multiple tool-layer paths:
  - `UIActionInvocation` (`app/src/main/kotlin/com/moonkey/androidagent/tool/handlers/UIActionInvocation.kt:74-80`)
  - `capturePostActionAnalysis()` (`app/src/main/kotlin/com/moonkey/androidagent/tool/action/PostActionAnalysis.kt:17-90`)
  - `OpenAppInvocation` (`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt:203-216`)
- The router applies `maskIfBlocked()` only in one narrow path: the snapshot refresh after approval wait (`app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:251-257`).

Consequences:

- From a `NORMAL` app, the agent can intentionally or accidentally navigate into a `BLOCKED` app.
- Once that transition happens, the tool layer can capture and return blocked-app content in a tool observation.
- That violates the stated invariant that blocked apps are masked and denied.

This is not a small policy bug. It means the security boundary exists only at turn-start and one approval-refresh path, not at every observation boundary.

## High

### 2. `ToolName` is not actually canonical, and omissions silently change runtime behavior

`ToolName` says it contains canonical tool identifiers used across UI and policy layers, but it omits real first-class tools:

- `shell` is registered as a built-in tool (`app/src/main/kotlin/com/moonkey/androidagent/session/SessionToolingBootstrapper.kt:55-63`)
- `ask_user` is registered dynamically (`app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt:149-160`)
- neither appears in `ToolName.from()` (`app/src/main/kotlin/com/moonkey/androidagent/tool/ToolName.kt:19-83`)

The fallback behavior is also wrong:

- `Unknown` defaults to `isScreenChanging = true` (`app/src/main/kotlin/com/moonkey/androidagent/tool/ToolName.kt:11-17`)

This metadata is consumed outside the module for real behavior:

- policy gating (`app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt:48-49`)
- turn arbitration (`app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicy.kt:57-65`)
- loop signatures (`app/src/main/kotlin/com/moonkey/androidagent/agent/ActionSignature.kt:21-35`, `app/src/main/kotlin/com/moonkey/androidagent/agent/ActionSignature.kt:69-77`)

Consequences:

- `ask_user` can be treated as a screen-changing action and denied on blocked apps, which is exactly where user intervention is most likely to be needed.
- `shell` and `ask_user` can distort turn arbitration and loop-detection behavior simply because a parallel enum drifted out of sync.

This is a design smell: capabilities should come from the tool definition, not a separate name map that can go stale.

### 3. `shell` is an opaque escape hatch, not a well-formed tool

The tool description says shell is for read-only file inspection, but the implementation does not enforce that contract:

- validation checks only the first token against a blocklist (`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ShellTool.kt:38-49`, `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ShellTool.kt:61-65`)
- execution runs `ProcessBuilder("sh", "-c", command)` (`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ShellTool.kt:81-85`)

That means the framework cannot reason about the actual operation:

- shell metacharacters bypass the “first token” check
- the command is not structurally inspectable by policy
- the tool breaks the otherwise declarative input-model of the tool system

Even if the Android app sandbox limits damage, this is still a tool-system design failure. One tool bypasses the entire effort to keep actions explicit, typed, and policy-checkable.

### 4. Explicit scroll targeting silently degrades to whole-screen scrolling

`ScrollExecutor.resolveScrollArea()` falls back to full-display bounds whenever target resolution fails or lacks bounds (`app/src/main/kotlin/com/moonkey/androidagent/tool/action/ScrollExecutor.kt:111-127`).

That means:

- `scroll` with an explicit `element_index` or `text` target does not reliably mean “scroll this container”
- a stale or missing target silently changes semantics to “scroll the whole screen”

For a UI agent, that is too much hidden behavior. If the caller specified a target, unresolved target should be a failure, not a silent fallback.

## Medium

### 5. Cancellation semantics are inconsistent below the router

The router presents a clean `Success/Error/Cancelled` lifecycle, but the action layer does not preserve that consistently:

- `executePointAction()` propagates `ActionResult.Cancelled` as `ActionOutcome.Cancelled` (`app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt:86-123`)
- `ScrollExecutor` does the same (`app/src/main/kotlin/com/moonkey/androidagent/tool/action/ScrollExecutor.kt:35-97`)
- `SwipeExecutor` converts platform cancellation into `Failed` (`app/src/main/kotlin/com/moonkey/androidagent/tool/action/SwipeExecutor.kt:49-54`)
- `TypeExecutor` collapses cancelled platform results into generic failure trails (`app/src/main/kotlin/com/moonkey/androidagent/tool/action/TypeExecutor.kt:56-73`, `app/src/main/kotlin/com/moonkey/androidagent/tool/action/TypeExecutor.kt:87-112`, `app/src/main/kotlin/com/moonkey/androidagent/tool/action/TypeExecutor.kt:127-140`)

The router’s own cancellation ownership is also incomplete:

- `cancel()` and `cancelAll()` abort pending approvals and drop active state (`app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:333-347`)
- but the router does not own per-call cancellation tokens for already-executing invocations
- `SimpleToolRouterContext` has a cancellation flag, but the router does not retain or drive it (`app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:388-397`)

The result is a state machine that is cleaner on paper than in the actual execution stack.

### 6. The framework standardizes input schemas, but not tool capabilities or outputs

`ToolSpec` standardizes name, description, input schema, validation, and invocation creation, but it does not standardize:

- tool capabilities
- output schema
- post-action observation policy

Evidence:

- capability decisions are delegated to `ToolName.isScreenChanging` instead of the tool spec (`app/src/main/kotlin/com/moonkey/androidagent/tool/ToolName.kt:11-17`)
- tool success payloads are `data: Any?` (`app/src/main/kotlin/com/moonkey/androidagent/tool/ToolSpec.kt:128-145`)
- post-action capture timing lives in multiple places with different constants and rules:
  - `UIActionInvocation` (`app/src/main/kotlin/com/moonkey/androidagent/tool/handlers/UIActionInvocation.kt:27-30`, `app/src/main/kotlin/com/moonkey/androidagent/tool/handlers/UIActionInvocation.kt:74-84`)
  - `OpenAppTool` (`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt:133-136`, `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt:203-216`)
  - `PostActionAnalysis` (`app/src/main/kotlin/com/moonkey/androidagent/tool/action/PostActionAnalysis.kt:14-15`, `app/src/main/kotlin/com/moonkey/androidagent/tool/action/PostActionAnalysis.kt:31-41`)

This is why the tool system feels consistent at the top and inconsistent at the edges.

### 7. Point-action execution is centralized, but still too magical

The click/long-press shared core is a good consolidation, but it also hides a lot of behavior:

- target resolution
- channel fallback order
- change verification
- retargeting from a non-clickable element to a container or nearby child

The retargeting heuristic is especially important:

- `refinePointActionTarget()` promotes to a clickable container or nearest actionable child (`app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt:185-216`)
- child selection uses area filters and distance heuristics (`app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt:263-291`)

This may improve practical hit-rate, but it means the runtime can act on a different UI element than the one the agent explicitly selected. For a tool system, that behavior should be explicit policy, not hidden executor magic.

## Low

### 8. There is still some dead or stale abstraction around the tool framework

- `DataQueryInvocation` appears unused in production code.
- `ToolSpec.toFunctionSchema()` and `ToolRegistry.generateResponsesApiTools()` are not referenced in production code.
- `ActionPriorityOrder` embeds a reference to a `doc/todo/...` path in code comments (`app/src/main/kotlin/com/moonkey/androidagent/tool/action/ActionPriorityOrder.kt:3-10`), which is the wrong dependency direction.

None of these are urgent, but they add to the feeling that the framework boundary is broader than what the current runtime actually needs.

## Recommendation

**CHANGES_REQUESTED**

The module has a good skeleton, but I would not call the current tool system correct or minimal yet. The first priority is to make the blocked-app boundary end to end. After that, the biggest design win is to move capabilities and policy metadata onto the tool specs themselves and centralize post-action observation so the runtime stops drifting by tool/action type.
