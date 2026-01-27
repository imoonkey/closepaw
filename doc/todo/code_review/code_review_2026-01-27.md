# Review: Android Agent codebase (2026-01-27)

## Scope / Notes
- Reviewed `app/src/main/kotlin/...` plus `doc/main` for context.
- `.reference/` directories appear vendor/reference; not reviewed for runtime behavior.

## Critical
- None found.

## [Done] High
1. Approval race can drop fast user responses: `ToolRouter.execute()` invokes `onApprovalRequired` before `pendingApprovals[callId]` is registered, so an immediate `resolveApproval()` can fail and the tool waits until timeout. Fix: register the `CompletableDeferred` before emitting the approval event (or buffer early approvals).
2. Stop while paused can still execute a new turn: `Agent.run()` only checks `shouldContinue()` at loop start. If `stop()` is called while paused, the loop resumes and runs another `executeTurn()` before re-checking. Fix: re-check stop/cancel after pause wait (and before max-turn/turn execution).
3. Multiple tool calls executed per turn despite one-action rule: `Agent.executeTurn()` iterates all `result.toolCalls`, but the system prompt explicitly forbids multiple actions per turn. This violates the observe-act loop and can chain actions without fresh screen state. Fix: enforce one tool call (first one only) or treat multi-tool responses as an error and retry.

## [Done] Medium
1. Perception truncation can hide critical controls: `Perceptor.snapshot()` DFS + `MAX_ELEMENTS` can cap out before reaching interactive elements deeper in the tree; TODO already calls this out. Fix: two-pass traversal (interactive first, then text), or prioritize nodes with `clickable/editable/scrollable`.
2. Session listing scales poorly: `SessionHistoryManager.listSessions()` reads full session files to extract titles. For large histories, this is heavy. Fix: store lightweight index metadata or a separate summary file.
3. PolicyEngine config is not thread-safe: `allowList/denyList/riskOverrides` are mutable sets/maps without synchronization. If updated from UI while tools are executing, races are possible. Fix: guard with `Mutex` or use concurrent collections.
4. UI tool icon mapping is stale: `ChatViewModel.getToolIcon()` maps old tool names (`click/type/scroll`) while the actual tool is `mobile_action`. Result: generic icon and reduced clarity. Fix: map `mobile_action` + `action` subtype to the appropriate icon.

## Low
1. Overlay emoji icons are device-dependent: `OverlayManager` uses emoji glyphs for control icons. TODO already notes vector assets for consistency.

## [Done] Dead Code / Unused
- Legacy UI screen and theme: `ui/screen/AgentScreen.kt` and `AgentTheme` are not referenced.
- Legacy session bottom sheet: `ui/session/SessionListSheet.kt` and `SessionListItem.kt` have no call sites.
- `OverlayManager` is not referenced (SmartCapsule/EdgeGlow appear to be the active path).
- `AgentEvent.AgentThinking` is defined but never emitted or handled.
- `TurnPhase.REFLECTION` is defined but never used.
- `ActionSkipped` event is handled in UI but never emitted; choose to implement or remove.

## Refactor Opportunities
- Large files exceed the 400-line rule: `Agent.kt`, `AgentService.kt`, `MainActivity.kt`, `ChatViewModel.kt`, `Turn.kt`.
  - Split agent logic: prompt building, tool execution, event emission.
    - [Done] prompt building and action descriptions moved to helpers.
    - [Done] event emission moved to `AgentEventDispatcher`.
  - Split service logic: overlay management, session observation, foreground tracking.
    - [Done] overlay management + foreground tracking moved to `ServiceOverlayController`.
  - Split activity logic: settings persistence, intent handling, session wiring.
    - [Done] settings persistence moved into `AppSettingsState`.
    - [Done] intent parsing moved into `MainActivityIntentPayload`.
  - Split view model logic: event ingestion vs UI state reducers.
    - [Done] event routing grouped into `ChatViewModel.EventReducer`.
  - [Done] Turn input assembly moved to `TurnInputBuilder`.
- [Done] `SmartCapsuleManager` UI construction moved to `SmartCapsuleLayoutBuilder`.
- [Done] `SessionRecordingService` message buffering moved to `AgentMessageBuffer`.
- [Done] `AgentSession` agent lifecycle moved to `SessionAgentRunner`.
- [Done] Consolidate local LLM config types: `LocalLLMSessionConfig` vs `LocalLLMConfig` duplication leads to drift; unify or enforce a single mapping layer.
- [Done] Replace stringly-typed tool names with sealed types/enums for safer UI mapping and policy checks.

## Tests (Targeted)
- `ToolRouter` approval flow: immediate approve, timeout, cancel path.
- Agent stop/pause semantics: stop while paused should not execute new turn.
- Perception traversal: ensure interactive elements survive truncation.

## Recommendation
CHANGES_REQUESTED (approval race + stop/pause behavior).
