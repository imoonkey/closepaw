# Tool System Improvement Plan (Codex)

## Goal

Keep the current high-level shape:

- `ToolSpec`
- `ToolRegistry`
- `ToolRouter`
- `PolicyEngine`
- `mobile_action` as the main UI tool

Do **not** rewrite the whole stack.

Instead:

1. fix the security boundary
2. make tool metadata first-class
3. simplify the action runtime around one observation/cancellation model
4. remove or sharply constrain the shell escape hatch

## Guiding Principles

- KISS over framework growth
- capabilities come from tool definitions, not parallel enums
- every screen capture goes through one gate
- explicit targets fail explicitly
- cancellation must mean the same thing across all tools

## Phase 0: Secure The Observation Boundary

### Objective

Make “blocked apps are masked and denied” true at every tool-layer capture point, not just at turn start.

### Changes

1. Add one shared capture service inside `tool/`, for example `ObservationCapture` or `ToolObservationGate`.
2. Move all direct `platform.captureScreen()` calls in the tool module behind that service.
3. The capture service must:
   - read the current foreground package at capture time
   - apply `AppClassifier.maskIfBlocked(...)`
   - build the observation
   - optionally compute change detection
4. Replace raw capture usage in:
   - `OpenAppTool`
   - `UIActionInvocation`
   - `PostActionAnalysis`
   - any future screen-changing tool

### Policy Follow-up

The tool layer also needs destination-aware policy for app launches.

Smallest viable change:

1. let invocations expose optional policy metadata before execution
2. for `open_app`, resolve the target package before the router calls `PolicyEngine`
3. teach `PolicyEngine` to evaluate both:
   - current foreground app
   - destination app tier when the tool may launch/switch apps

If destination resolution cannot happen before execution, then `open_app` should do an internal policy re-check immediately before `launchApp(...)`.

### Acceptance Tests

- `open_app` from a normal app to a blocked app is denied before launch
- any allowed action that lands on a blocked app returns a masked observation
- no raw `captureScreen()` remains in `tool/` outside the shared observation service

## Phase 1: Replace Name Heuristics With Tool Metadata

### Objective

Remove the parallel taxonomy problem where `ToolName` can drift away from the actual registered tools.

### Changes

Add metadata directly to `ToolSpec`, for example:

```kotlin
data class ToolMetadata(
    val category: ToolCategory,
    val screenChanging: Boolean,
    val capturesScreen: Boolean,
    val mayLaunchApp: Boolean = false,
    val mayAskUser: Boolean = false,
    val loopSignatureKind: LoopSignatureKind = LoopSignatureKind.Default
)
```

Then:

1. each tool declares its own metadata
2. `PolicyEngine` uses metadata, not `ToolName.isScreenChanging`
3. turn arbitration uses metadata
4. loop signature selection uses metadata
5. UI display uses metadata plus optional display config

### Migration Strategy

- keep `ToolName` temporarily as a display helper if needed
- remove `Unknown => screenChanging`
- add a test that every registered tool has explicit metadata

### Why This Matters

This fixes:

- `ask_user` misclassification
- `shell` misclassification
- future drift whenever a new tool is added but the enum is not updated

## Phase 2: Simplify Action Execution Around One Runtime Contract

### Objective

Preserve the good consolidation already present in click/long-press, but stop action types from inventing their own semantics for cancellation, fallback, and observation.

### Changes

Create one shared action runtime layer with responsibilities:

1. target resolution
2. channel attempt loop
3. cancellation propagation
4. post-action observation capture
5. verification result normalization
6. attempt-trail assembly

Action-specific code should only define:

- target requirements
- dispatch channels
- success/failure wording
- whether retargeting is allowed

### Concrete Fixes

1. `scroll` with explicit target:
   - unresolved target must fail
   - only targetless scroll may use full-screen bounds
2. `swipe`:
   - map platform cancellation to `Cancelled`, not `Failed`
3. `type`:
   - preserve `Cancelled` through direct-set, tap-to-focus, and focused-set paths
4. retargeting:
   - make container/child promotion explicit and opt-in
   - surface in the result when retargeting happened

### Non-Goal

Do not introduce a giant inheritance tree for executors. Keep the runtime functional and narrow.

## Phase 3: Remove Or Replace `shell`

### Objective

Stop one tool from bypassing the whole declarative tool model.

### Preferred Option

Remove `shell` from the default built-in tool list until a constrained replacement exists.

### Replacement Options

Option A:

- `read_file`
- `list_dir`
- `stat_path`

Option B:

- keep one shell-like tool
- parse argv directly
- use no shell
- use a strict allowlist of binaries and flags

### Hard Rule

Do not keep `ProcessBuilder("sh", "-c", command)` in the final design.

## Phase 4: Tighten The Router Contract

### Objective

Make the clean router state machine real, not just representational.

### Changes

1. If the router exposes `cancel(callId)` / `cancelAll()`, it should own per-call cancellation tokens and drive them.
2. If that is too much scope, rename the API to reflect the truth:
   - `abortPendingApproval(callId)`
   - `abortAllPendingApprovals()`
3. Add tests that cover:
   - cancelling an already executing tool
   - cancellation propagation through type/swipe
   - approval abort vs execution abort

## Phase 5: Cleanup

### Objective

Reduce noise once the important fixes land.

### Cleanup Items

- remove unused framework pieces if they stay unused:
  - `DataQueryInvocation`
  - `ToolSpec.toFunctionSchema()`
  - `ToolRegistry.generateResponsesApiTools()`
- centralize settle-delay constants
- remove references from code comments to `doc/todo/...`
- make output contracts more regular for cognitive tools

## Suggested Order

1. Phase 0
2. Phase 1
3. Phase 2
4. Phase 3
5. Phase 4
6. Phase 5

## Smallest Safe First Patch Set

If the team wants the minimum safe sequence instead of the full cleanup:

1. centralize masked capture
2. make `open_app` destination-aware in policy
3. fail explicit-target scroll when resolution fails
4. normalize `Cancelled` in swipe/type
5. remove `shell` from default registration

That gets the biggest correctness and security wins without rewriting the framework.
