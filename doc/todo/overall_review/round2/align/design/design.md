# Round 2 Craftsmanship Plan — Aligned Design (Codex Revision)

**Date**: 2026-02-16  
**Baseline**: Post-round 1 (`26,365` Kotlin LOC, `7` files >400 LOC)

## Dispute Resolution (Evidence-Based)

| Topic | Resolution | Evidence |
|---|---|---|
| ChatViewModel race (`streamingBuffer`) | **Drop** as race finding | `ChatViewModel` mutations run in `viewModelScope.launch` (`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:149`) and `viewModelScope` is main-thread scoped by default in Android guidance. |
| `VirtualDisplayViewerTouchHandler` plain vars | **Drop** as race finding | Touch path starts in Activity `setOnTouchListener` (`app/src/main/kotlin/com/moonkey/androidagent/ui/viewer/VirtualDisplayViewerActivity.kt:135`), i.e. UI thread callbacks. |
| Main-thread session creation + blocking asset read | **Keep, high priority** | `MainActivity` builds session inside `lifecycleScope.launch` (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:386`); `SessionLlmBootstrapper` does blocking asset I/O (`app/src/main/kotlin/com/moonkey/androidagent/session/SessionLlmBootstrapper.kt:68`) and documents it must run off main thread (`app/src/main/kotlin/com/moonkey/androidagent/session/SessionLlmBootstrapper.kt:63`). |
| Completion finalize ownership split | **Keep, high priority** | `AgentServiceEventHandler` only calls `completeAgentMessage()` (`app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceEventHandler.kt:77`), while `MainActivity` separately calls `completeSession()` (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:123`). |
| `LLMClientFactory.getOrPut` | **Keep, medium priority** | `clientCache.getOrPut(...)` (`app/src/main/kotlin/com/moonkey/androidagent/llm/LLMClientFactory.kt:52`); Kotlin concurrent `getOrPut` may evaluate `defaultValue` more than once. |
| TurnPlanning stream error loss | **Drop** original claim | Code already rethrows original `streamError` (`app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt:125`). |
| HistoryManager unsynchronized list | **Drop** original claim; keep minor polish | Core mutators/accessors are `@Synchronized` (`app/src/main/kotlin/com/moonkey/androidagent/history/HistoryManager.kt:40`). |
| `!!` crash risks (ChatCompletionClient/VirtualDisplayPlatform) | **De-prioritize** | Both usages are guarded in immediate local control flow (`app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt:167`, `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt:133`). |
| SessionAgentRunner unguarded vars | **Keep, Phase 1.4** | 3 plain `var` fields (agent, agentJob, cancellationSignal) at lines 34-36 with no @Volatile or sync. Accessed from `start()`, `stop()`, `shutdown()`, `clear()` — concurrent calls can race. |
| SessionRecordingService unguarded state | **Keep, Phase 1.4** | `currentSession`, `currentFileName`, `saveJob` at lines 38-39, 45 modified from `recordUserMessage()`, `startAgentMessage()`, `completeSession()`, `clearSession()` without sync. `scope.launch` means these can run on different dispatchers. |
| TurnErrorClassifier cause chain | **Drop** | Pure stateless function. Circular exception cause chains are not practically observed in JVM. |
| ShizukuServiceProxyProvider double-check | **De-prioritize** | Worst outcome is redundant proxy creation, not corruption. Minor polish. |

## Priority Plan

### Phase 1: Runtime Correctness (P0/P1)

1. Main-thread safety
- `MT1`: Move `AgentSession.create(...)` off main thread from `MainActivity.ensureSessionAndSend`.
- `MT2`: Cache model catalog and enforce off-main load path in session bootstrap.

2. Completion consistency
- `TC1`: Unify `TaskCompleted` finalization to a single owner (prefer session/service side, not Activity callback split).

3. Confirmed logic/capacity bugs
- `H1`: Fix multi-tool `actionForNextTurn` overwrite in `TurnExecutionPhaseRunner`.
- `H4`: Replace `Channel.UNLIMITED` in `FileTraceRecorder` with bounded channel and explicit drop/backpressure strategy.

4. Concurrency hardening (keep, but not all P0)
- `G1`: `SessionAgentRunner` — synchronize or seal the 3 nullable mutable vars (agent, agentJob, cancellationSignal).
- `G2`: `UserResponseChannel` compound state (`pending`, `pendingCallId`) -> single atomic holder.
- `G3`: Couple `mode` and `liveSurfaceView` state transitions in `VirtualDisplaySurfaceController`.
- `G6`: Revisit `AgentService.instance` teardown ordering and lifecycle fence.
- `G8`: `SessionRecordingService` — add Mutex around currentSession/currentFileName/saveJob access.
- `G9`: `LLMClientFactory` switch to `computeIfAbsent`.
- `G10`: Tighten `LFMLLMClient` model lifecycle locking around `getOrLoadModel` and `cleanup`.

### Phase 2: Convergence Refactors (P2)

1. LLM streaming consolidation
- Decision: use a **helper-style extraction** (`streamWithRetry`) instead of introducing a new `CloudLLMClient` inheritance layer now.
- Reason: shared part is retry/lifecycle scaffold; parsing logic is API-specific and already divergent.

2. Tool observation DRY
- Migrate inline `ToolObservation.ScreenState` construction in `UIActionInvocation` and `OpenAppTool` to `ObservationBuilder`.

3. Large-file split compliance
- Split remaining `7` files >400 LOC (`MainActivity`, `AgentTrace`, `AgentService`, `ChatViewModel`, `AgentSession`, `SessionRecordingService`, `HistoryManager`).

4. Additional duplication cleanup
- `extractMessageContent` util unification.
- `AccessibilityNodeFinder` and `NodeActionPerformer` paired-method dedup.
- `ShizukuDisplayTransport` virtual display creation path dedup.

### Phase 3: Maintainability (P3)

1. Settings generic extraction
- Introduce generic `SettingsDropdown<T>` and thin wrappers.

2. Prompt hygiene now, full composition later
- **Now**: remove dead commented prompt fragment in `StandaloneAgentDef`.
- **Later (optional)**: evaluate fragment-based system prompt composition after Phase 1/2.

3. Design smells
- `SessionServices`: `data class` -> plain class.
- Remove unused `SessionServicesBuilder`.
- Add explicit warning logs for fallback path in `AgentModelResolver`.
- Replace hardcoded default model literals in `Turn`/`LLMClient`.

## Scope Decision for Lock-Screen

Lock-screen foundation is **out of Round 2 core scope** and should stay as a separate optional track after Round 2 stability/refactor milestones complete.

## Execution Order

1. Phase 1.1 `MT1 + MT2`
2. Phase 1.2 `TC1`
3. Phase 1.3 `H1 + H4`
4. Phase 1.4 `G1 + G2 + G3 + G6 + G8 + G9 + G10`
5. Phase 2.1 LLM helper extraction
6. Phase 2.2 Tool observation DRY
7. Phase 2.3 Large-file splits
8. Phase 2.4 Remaining duplication
9. Phase 3.1 Settings generic
10. Phase 3.2 Prompt hygiene + design smells
