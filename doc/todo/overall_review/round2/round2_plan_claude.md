# Round 2 Craftsmanship Plan

**Date**: 2026-02-16
**Baseline**: Post-round 1 — 26,365 lines across ~175 Kotlin files
**Method**: Deep code review of every file in agent/, session/, llm/, platform/, app/, tool/, history/, trace/, ui/

---

## Current State After Round 1

### Successfully reduced (no further action needed on size)
| File | Before | After |
|------|--------|-------|
| AgentTurnRunner.kt | 788 | 250 |
| VirtualDisplayPlatform.kt | 645 | 317 |
| ShizukuClient.kt | 544 | 159 |
| SessionServices.kt | 344 | 164 |
| AgentEvent.kt | 361 | 14 |
| SettingsDropdowns.kt | 413 | 340 |
| OpenAIResponseClient.kt | 327 | 292 |
| ChatCompletionClient.kt | 304 | 285 |

### Still over 400-line limit (needs splitting)
| File | Lines | Status |
|------|-------|--------|
| MainActivity.kt | 540 | Untouched |
| AgentTrace.kt | 507 | Untouched |
| AgentService.kt | 477 | Reduced from 572, still over |
| ChatViewModel.kt | 448 | Untouched |
| AgentSession.kt | 443 | Untouched |
| SessionRecordingService.kt | 409 | Reduced from 411, still over |
| HistoryManager.kt | 402 | Untouched |

### Round 1 extraction quality assessment (from code review)
| Extraction | Quality | Issues Found |
|------------|---------|-------------|
| TurnErrorClassifier.kt | ✓ Clean | Missing error classifications (auth, rate-limit); `generateSequence` on cause chain could theoretically infinite-loop |
| TurnPlanningPhaseRunner.kt | ⚠ Needs work | Stream error handling loses exception context (L91-126); `modelResolver` should be local var |
| TurnExecutionPhaseRunner.kt | ⚠ Bug | `actionForNextTurn` overwritten per tool — only last tool recorded for loop detection in multi-tool turns (L63) |
| AgentModelResolver.kt | ⚠ Silent fallback | `runCatching` silently swallows all exceptions during model creation (L29); fallback to vision=false without warning (L42) |
| CloudLlmRetry.kt | ✓ Clean | Minor: retry delay code duplicated between RateLimitException and TransientException branches |
| CloudStreamRetryPolicy.kt | ✓ Clean | Retryability check duplicated with CloudLlmRetry |
| AgentServiceEventHandler.kt | ✓ Clean | Minor: overlayController() could return null |

### Remaining design work from round 1
| Design | Status | What's left |
|--------|--------|-------------|
| 01 LLM Client Consolidation | Partial | Streaming retry loop still ~100 lines duplicated between two clients; extractMessageContent 3x duplicated |
| 05 System Prompt Composition | Not started | Defer — 254 total lines, indirection cost outweighs DRY benefit |
| 06 Tool System DRY-up | Partial | ObservationBuilder exists but MobileActionTool and OpenAppTool still construct observations inline |
| 08 Large File Splits | Partial | 7 files still over 400 lines |
| 10 Settings UI Generics | Not started | 5x dropdown duplication in SettingsDropdowns.kt |

---

## Deep Code Review Findings Summary

### By Severity

**P0 — Correctness / Race Conditions (12 findings)**

| # | File | Line(s) | Issue |
|---|------|---------|-------|
| 1 | SessionAgentRunner.kt | 34-36 | 3 nullable mutable vars (agent, agentJob, cancellationSignal) without synchronization — race between pause/resume/stop/shutdown |
| 2 | UserResponseChannel.kt | 24-25, 51-56 | Two @Volatile fields not atomically coupled — deliver() and cancel() race on pending+pendingCallId |
| 3 | VirtualDisplaySurfaceController.kt | 26-27 | Two @Volatile fields (mode, liveSurfaceView) — non-atomic update creates window where mode=LIVE_PREVIEW but liveSurfaceView=null |
| 4 | VirtualDisplayViewerTouchHandler.kt | 22-25 | viewerDownX/Y/Time/Moved are plain vars without @Volatile or sync — stale reads cause wrong coordinates |
| 5 | ShizukuServiceProxyProvider.kt | 18, 30 | Check-then-set on @Volatile not atomic — two threads both create proxies |
| 6 | AgentService.kt | 261 | instance=null AFTER lifecycle destroy — lookup window between L259-261 |
| 7 | AgentService.kt | 435-437 | Unchecked cast to VirtualDisplayPlatform — crash if platform isn't VD |
| 8 | MainActivity.kt | 116 | `sessionProvider = { currentSession }` — classic TOCTOU race |
| 9 | SessionRecordingService.kt | 38-46 | currentSession mutable var accessed by concurrent recording calls without sync |
| 10 | ChatViewModel.kt | 122-123 | streamingBuffer (StringBuilder) and currentAgentMessageId (var) — unguarded concurrent access |
| 11 | LLMClientFactory.kt | 34 | `getOrPut()` on ConcurrentHashMap is NOT atomic — two threads both compute clients |
| 12 | LFMLLMClient.kt | 207-218 | cleanup() can null modelRunner while getOrLoadModel() is spinning — actual race |

**P1 — Logic Bugs (5 findings)**

| # | File | Line(s) | Issue |
|---|------|---------|-------|
| 1 | TurnExecutionPhaseRunner.kt | 63 | `actionForNextTurn` overwritten per tool — breaks loop detection for multi-tool turns |
| 2 | TurnPlanningPhaseRunner.kt | 91-126 | streamError exception details lost when re-thrown; null turnResult without error triggers generic RuntimeException |
| 3 | TurnErrorClassifier.kt | 19 | `generateSequence(error) { it.cause }` could infinite-loop on circular cause chain |
| 4 | Turn.kt | 190-215 | Fragile text-based tool call recovery — regex patterns undocumented, silent failure |
| 5 | FileTraceRecorder.kt | 38-40 | Channel.UNLIMITED capacity — unbounded OOM risk if writer is slower than producer |

**P2 — Code Duplication (6 findings)**

| # | Files | Lines Duplicated | Issue |
|---|-------|-----------------|-------|
| 1 | OpenAIResponseClient.kt, ChatCompletionClient.kt | ~100 each | Streaming retry loop scaffold is identical |
| 2 | LFMLLMClient.kt, ChatCompletionInterop.kt, LlmLogger.kt | ~10 each | `extractMessageContent()` appears in 3 files |
| 3 | AccessibilityNodeFinder.kt | 37+36 | findClickableNodeAtLocation vs findLongClickableNodeAtLocation — 95% identical |
| 4 | NodeActionPerformer.kt | 15+17 | performNodeClickAt vs performNodeLongClickAt — nearly identical |
| 5 | ShizukuDisplayTransport.kt | 40+ | 3 createVirtualDisplay implementations with duplication |
| 6 | ChatCompletionClient.kt | constants | Redefines INITIAL_BACKOFF_MS/MAX_RETRIES locally instead of using LLMClient constants |

**P3 — Design Smells (8 findings)**

| # | File | Issue |
|---|------|-------|
| 1 | SessionServices.kt | `data class` for DI container — adds unwanted equals/hashCode/copy |
| 2 | SessionServicesBuilder.kt | Pure ceremony — wraps create() + register/unregister with zero value |
| 3 | AgentModelResolver.kt | Silent fallback: runCatching swallows all exceptions, no warning log |
| 4 | Turn.kt | "gpt-5.2" hardcoded as default model in 2 method signatures (L40, L64) |
| 5 | PolicyEngine.kt | Mixes AtomicReference AND synchronized lock — inconsistent concurrency strategy |
| 6 | HistoryManager.kt | items list not properly synchronized — getAll() returns shallow copy but concurrent add() can throw |
| 7 | LeapFunctionInterop.kt | 4+ fallback JSON parsing paths — excessive defensive parsing reduces confidence |
| 8 | AgentService.kt | submitOp() doesn't check if session is being destroyed |

---

## Round 2 Plan

### Plan G: Thread Safety & Race Condition Fixes

**Priority: P0 — correctness bugs that could cause crashes or data corruption**

These are the most critical findings from the deep code review. Each is a targeted fix (not a refactor) that should be done atomically.

#### G1: SessionAgentRunner — Synchronized state machine
- **Problem**: 3 nullable mutable vars (agent, agentJob, cancellationSignal) accessed unsynchronized from pause/resume/stop/shutdown
- **Fix**: Wrap in a `Mutex` or use `synchronized(lock)` around all state access. Consider sealed `RunnerState` instead of 3 separate nullable vars.
- **Files**: `SessionAgentRunner.kt`

#### G2: UserResponseChannel — Atomic compound state
- **Problem**: pending + pendingCallId are two @Volatile fields updated non-atomically
- **Fix**: Bundle into single `AtomicReference<PendingRequest?>` where `PendingRequest(callId, deferred)`
- **Files**: `UserResponseChannel.kt`

#### G3: VirtualDisplaySurfaceController — Atomic mode+view coupling
- **Problem**: mode and liveSurfaceView independently @Volatile — reader could see mode=LIVE_PREVIEW but liveSurfaceView=null
- **Fix**: Use `synchronized(lock)` around switchToLivePreview/switchToImageReader, or combine into single AtomicReference of sealed state
- **Files**: `VirtualDisplaySurfaceController.kt`

#### G4: VirtualDisplayViewerTouchHandler — Add @Volatile
- **Problem**: viewerDownX/Y/Time/Moved are plain vars on UI thread — technically safe for single-thread but fragile
- **Fix**: Add @Volatile annotations as defensive measure; document single-thread expectation
- **Files**: `VirtualDisplayViewerTouchHandler.kt`

#### G5: ShizukuServiceProxyProvider — Double-checked locking
- **Problem**: Check-then-set on @Volatile fields is not atomic
- **Fix**: Use `synchronized` block with double-check, or use `lazy {}` delegation
- **Files**: `ShizukuServiceProxyProvider.kt`

#### G6: ChatViewModel — Synchronized streaming buffer
- **Problem**: streamingBuffer (StringBuilder) and currentAgentMessageId (var) accessed from concurrent event streams
- **Fix**: Use `Mutex` or `synchronized` around buffer access; consider using `StateFlow<String>` for buffer
- **Files**: `ChatViewModel.kt`

#### G7: SessionRecordingService — Guard mutable state
- **Problem**: currentSession var accessed by concurrent recording calls without sync
- **Fix**: Use `Mutex` around all currentSession access
- **Files**: `SessionRecordingService.kt`

#### G8: LLMClientFactory — Atomic cache
- **Problem**: `getOrPut()` on ConcurrentHashMap is not atomic
- **Fix**: Replace with `computeIfAbsent()` (one-line change)
- **Files**: `LLMClientFactory.kt`

#### G9: AgentService — Singleton race + unchecked cast
- **Problem**: (a) instance=null timing window (b) unchecked cast to VirtualDisplayPlatform
- **Fix**: (a) Set instance=null first before cleanup (b) Add `as? VirtualDisplayPlatform ?: return` safe cast
- **Files**: `AgentService.kt`

#### G10: MainActivity — Eliminate TOCTOU
- **Problem**: `sessionProvider = { currentSession }` — race between null check and use
- **Fix**: Use `AtomicReference<SessionHandle?>` or capture session in local val before passing
- **Files**: `MainActivity.kt`

---

### Plan H: Logic Bug Fixes

**Priority: P1 — bugs that cause incorrect behavior under specific conditions**

#### H1: TurnExecutionPhaseRunner — Fix multi-tool loop detection
- **Problem**: `actionForNextTurn` is overwritten for each tool — only last tool's action matters for loop detection
- **Fix**: Track all actions or use first screen-changing action as the signature
- **Files**: `TurnExecutionPhaseRunner.kt`

#### H2: TurnPlanningPhaseRunner — Preserve stream error context
- **Problem**: streamError gets assigned but exception details lost when re-thrown
- **Fix**: Re-throw `streamError` directly instead of wrapping in generic RuntimeException; handle null turnResult explicitly
- **Files**: `TurnPlanningPhaseRunner.kt`

#### H3: TurnErrorClassifier — Guard against cause chain cycles
- **Problem**: `generateSequence(error) { it.cause }` could infinite-loop
- **Fix**: Add `.take(20)` or explicit depth limit
- **Files**: `TurnErrorClassifier.kt`

#### H4: FileTraceRecorder — Bound channel capacity
- **Problem**: `Channel.UNLIMITED` could OOM if writer is slow
- **Fix**: Use bounded capacity (e.g., `Channel(1024)`) with backpressure
- **Files**: `FileTraceRecorder.kt`

#### H5: HistoryManager — Synchronize items list
- **Problem**: items list accessed concurrently without synchronization
- **Fix**: Use `Collections.synchronizedList()` or explicit `Mutex`
- **Files**: `HistoryManager.kt`

---

### Plan I: Large File Splits — Remaining 7 Files

**Priority: P2 — enforces project convention, improves readability**

Note: Several of these files now have known concurrency bugs from Plan G/H that should be fixed BEFORE or DURING the split, not after.

#### I1: MainActivity.kt (540 → ~300 + ~200)
Extract session creation and config building logic.

- **Extract `SessionLauncher.kt`** (~200 lines): `buildSessionConfig()`, `startSession()`, `handleIntent()`, auto-start logic, model catalog initialization
- **Keep `MainActivity.kt`** (~300 lines): lifecycle, permissions, Compose `setContent`, settings sheet, navigation drawer
- **Also fix during split**: G10 (TOCTOU on currentSession)

#### I2: AgentTrace.kt (507 → ~250 + ~220)
Separate event recording from artifact generation.

- **Extract `TraceArtifactBuilder.kt`** (~220 lines): `storeLlmInput()`, `storeLlmOutput()`, `storeCognitionRedacted()`, `storeScreenshot()`, `RunMetrics`, JSON serialization helpers
- **Keep `AgentTrace.kt`** (~250 lines): timeline event emissions, counter tracking

#### I3: AgentService.kt (477 → ~300 + ~150)
Event handler already extracted. Remaining excess is viewer/VD bridging.

- **Extract `AgentServiceViewerBridge.kt`** (~120 lines): `notifyViewerVisible()`, `notifyViewerHidden()`, `onViewerTouch()`, `onViewerClosed()`, `openViewer()`
- **Keep `AgentService.kt`** (~340 lines): lifecycle, session management, overlay setup
- **Also fix during split**: G9 (singleton race + unchecked cast)

#### I4: ChatViewModel.kt (448 → ~250 + ~180)
Extract event reduction logic.

- **Extract `ChatEventReducer.kt`** (~180 lines): `handleAgentEvent()`, the `when (event)` block, message mutation helpers
- **Keep `ChatViewModel.kt`** (~250 lines): ViewModel init, state flows, session observation, op dispatch
- **Also fix during split**: G6 (synchronized streaming buffer)

#### I5: AgentSession.kt (443 → ~250 + ~180)
Extract op dispatching logic.

- **Extract `AgentSessionOpDispatcher.kt`** (~180 lines): `handleOp()` switch, individual op handlers
- **Keep `AgentSession.kt`** (~250 lines): state machine, event flow, lifecycle, public API

#### I6: SessionRecordingService.kt (409 → ~250 + ~150)
Extract message serialization and conversion.

- **Extract `RecordingEventProcessor.kt`** (~150 lines): event-to-record conversion, message finalization helpers
- **Keep `SessionRecordingService.kt`** (~250 lines): recording lifecycle, JSONL I/O
- **Also fix during split**: G7 (guard mutable state)

#### I7: HistoryManager.kt (402 → ~250 + ~150)
Extract history compression logic.

- **Extract `HistoryCompressor.kt`** (~150 lines): token budget calculation, auto-compression
- **Keep `HistoryManager.kt`** (~250 lines): history append, retrieval, conversation assembly
- **Also fix during split**: H5 (synchronize items list)

---

### Plan J: Code Duplication Cleanup

**Priority: P2 — reduces maintenance burden and drift risk**

#### J1: Streaming retry loop extraction
- **Problem**: ~100 lines of identical retry scaffold in OpenAIResponseClient and ChatCompletionClient
- **Action**: Extract `CloudStreamingExecutor` or `streamWithRetry()` helper that takes the stream-event-parsing lambda as parameter
- **Assessment revised**: Initially thought this was over-abstraction, but the code review confirmed the loop scaffold (attempt counter, backoff, emittedEvent flag, CloudStreamRetryPolicy.decide, delay, final error/close) is 100% identical between the two clients. Only the event-parsing inner block differs.
- **Files**: `OpenAIResponseClient.kt`, `ChatCompletionClient.kt`, new `CloudStreamingHelper.kt`

#### J2: extractMessageContent consolidation
- **Problem**: Same ~10-line method in LFMLLMClient, ChatCompletionInterop, LlmLogger
- **Action**: Move to `LlmLogger.kt` (or new `LlmMessageUtils.kt`) as public utility; delete from other two files
- **Files**: `LFMLLMClient.kt`, `ChatCompletionInterop.kt`, `LlmLogger.kt`

#### J3: AccessibilityNodeFinder — Generic finder with predicate
- **Problem**: findClickableNodeAtLocation and findLongClickableNodeAtLocation are 95% identical (differ only in `isClickable` vs `isLongClickable` check)
- **Action**: Extract `findNodeAtLocation(predicate: (AccessibilityNodeInfo) -> Boolean)` and delegate both methods
- **Files**: `AccessibilityNodeFinder.kt`

#### J4: NodeActionPerformer — Shared helper
- **Problem**: performNodeClickAt and performNodeLongClickAt are nearly identical
- **Action**: Extract shared `performNodeActionAt(action, finder)` helper
- **Files**: `NodeActionPerformer.kt`

#### J5: Tool observation consolidation
- **Problem**: ObservationBuilder.kt exists but MobileActionTool and OpenAppTool still construct `ToolObservation.ScreenState` inline
- **Action**: Migrate to use `ObservationBuilder.captureScreenObservation()` consistently
- **Files**: `MobileActionTool.kt`, `OpenAppTool.kt`, `ObservationBuilder.kt`

#### J6: ChatCompletionClient — Use LLMClient constants
- **Problem**: Redefines INITIAL_BACKOFF_MS and MAX_RETRIES locally instead of using LLMClient companion constants
- **Action**: Delete local constants, use `LLMClient.INITIAL_BACKOFF_MS` and `LLMClient.MAX_RETRIES`
- **Files**: `ChatCompletionClient.kt`

---

### Plan K: Settings UI Generics

**Priority: P3 — eliminates 5x duplication, quick win**

**Current**: `SettingsDropdowns.kt` (340 lines) with 5 structurally identical dropdown composables.

**Action**:
1. Create `ui/settings/SettingsDropdown.kt` (~70 lines) — generic `<T>` composable
2. Replace all 5 dropdowns with calls to `SettingsDropdown<T>`
3. Keep `SettingsDropdowns.kt` as thin wrapper (~80 lines)

**Result**: ~340 lines → ~150 lines total (~190 lines eliminated)

---

### Plan L: Design Smell Fixes

**Priority: P3 — improve design quality, low risk**

#### L1: SessionServices — Change from data class to plain class
- **Why**: DI container should not have equals/hashCode/copy semantics
- **Files**: `SessionServices.kt`

#### L2: Delete SessionServicesBuilder.kt
- **Why**: 32 lines of pure ceremony — wraps create() + register/unregister with zero value; logic should live in SessionServices.create()
- **Files**: `SessionServicesBuilder.kt`, update callers

#### L3: AgentModelResolver — Log fallback warnings
- **Why**: Silent `runCatching` swallows all exceptions; model resolution falls back to vision=false without warning
- **Fix**: Add `Log.w()` on fallback; log at WARN level when catalog entry missing
- **Files**: `AgentModelResolver.kt`

#### L4: Turn.kt — Extract default model constant
- **Why**: "gpt-5.2" hardcoded in 2 method signatures
- **Fix**: Move to `LLMClient.DEFAULT_MODEL` or `AgentExecutionConfig`
- **Files**: `Turn.kt`

#### L5: ShizukuDisplayTransport — Deduplicate createVirtualDisplay
- **Why**: 3 API-variant methods share 40+ lines of callback/resolution logic
- **Fix**: Extract shared setup; keep API-specific resolution as minimal branches
- **Files**: `ShizukuDisplayTransport.kt`

---

### Plan M: System Prompt Composition (Deferred)

**Priority: P4 — defer unless prompts grow significantly**

**Assessment**: The 3 AgentDef files total 254 lines with ~60% overlap. However, extracting shared fragments adds indirection that makes prompt editing harder. The cure trades one readability problem for another. Recommend deferring unless:
- Prompts grow beyond 100 lines each
- Team frequently edits prompts and forgets to update all 3 files
- A/B testing of prompt variants is needed

---

## Execution Order

| Phase | Plan | Items | Rationale |
|-------|------|-------|-----------|
| 1 | G: Thread Safety (G1-G10) | 10 targeted fixes | P0 correctness — each is small, isolated, independently testable |
| 2 | H: Logic Bugs (H1-H5) | 5 targeted fixes | P1 correctness — fix before structural changes |
| 3 | I: Large File Splits (I1-I7) | 7 file splits | P2 convention — incorporates thread safety fixes from Phase 1 |
| 4 | J: Duplication (J1-J6) | 6 DRY-up items | P2 maintenance — reduces drift risk |
| 5 | K: Settings UI | 1 generic extraction | P3 quick win |
| 6 | L: Design Smells (L1-L5) | 5 design fixes | P3 quality polish |
| 7 | M: Prompt Composition | Deferred | P4 — reassess after above |

Each phase should be independently committed and validated (`compileDebugKotlin` + `lintDebug` at minimum).

---

## Quick Wins (sprinkle opportunistically during any phase)

1. **AgentService.kt L282**: Add destroyed-session guard to submitOp()
2. **TurnErrorClassifier.kt**: Add missing error classifications (auth, rate-limit, malformed-request)
3. **Turn.kt L159-210**: Standardize synthetic ID format (3 different patterns currently)
4. **AgentEventDispatcher.kt**: Extract emit() helper to reduce method boilerplate
5. **PromptBuilder.kt L79**: Replace fragile `startsWith("- (empty)")` with proper empty check
6. **PolicyEngine.kt**: Pick one concurrency strategy (AtomicReference OR synchronized, not both)
7. **CapsuleOverlayHost.kt L77**: Make lastButtonClickTime atomic
8. **SubAgentRunner.kt L178**: Deduplicate ExecutorStepPolicy instantiation with AgentTurnRunner
