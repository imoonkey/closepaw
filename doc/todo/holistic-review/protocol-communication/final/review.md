# Protocol & Communication — Final Review

Date: 2026-04-08
Reviewers: Claude, Codex (double-design, cross-reviewed, aligned)

---

## Executive Assessment

The core command/state surface (`Op`, `SessionState`) is solid — small, closed, and maps to real runtime transitions. The event and config layers carry more surface than the runtime justifies: more domains, more completion reasons, more error categories, and more event types than the system or UI actually uses.

The next iteration should favor deletion and semantic correction over adding abstraction.

---

## What Works

- **`Op`** — 8 operations map to real user intents. Exhaustive `when` in `AgentSession.submit()`. No missing or redundant ops.
- **`SessionState`** — 5 states (`Created`, `Running`, `Idle`, `Paused`, `Shutdown`) match the hot-idle lifecycle. Transitions enforced by guard checks. Correct boundary: approval/user-wait states are handled at the capsule/UI layer, not session state.
- **Small enums** — `TurnPhase`, `AskUserType`, `AppTier`, `ApprovalDecision`, `ApprovalScope`, `ScreenStatePhase` are well-scoped.
- **Sealed usage** — appropriate for the truly closed sets. The problem is not "too much sealed" — it's sealing categories that buy nothing.

---

## High-Priority Findings

### H1. Event Domain Hierarchy Is Not Justified by Consumption

`AgentEventDomains.kt` defines 12 marker interfaces. All consumers switch on concrete event types, not markers. A repo-wide search found no non-protocol consumer importing any marker interface. Naming is inconsistent (`...LifecycleEvent` vs `...DomainEvent`). Net effect: higher taxonomy cost, no simpler dispatch.

### H2. Event Surface Exceeds the Actual Runtime Contract

- **`AgentError.kt`** — 11 error variants, companion factory, abstract properties. Never instantiated, never dispatched on, `isRecoverable` never read. ~170 lines of dead code.
- **`SessionError`** — declared, handled in consumers, but never emitted by any producer.
- **`TodosUpdated` / `ScratchpadUpdated`** — emitted by `AgentEventDispatcher`, fall through to `else -> Unit` in all consumers.
- **`ApprovalResolved`** — emitted but has no event consumer. Approval UI state is already resolved locally before the op is submitted.
- **`TurnStarted.phase`** — always `PERCEPTION`, immediately followed by `TurnPhaseChanged(PERCEPTION)`. One is redundant.
- **`ApprovalRequired.actionId`** — duplicates `ApprovalDetails.callId`. Consumers use `details.callId`.
- **`StatusUpdate.emoji`** — `String? = null`, never populated with non-null. Status strings embed emoji directly.

### H3. CompletionReason Conflates Task Outcome with Session Shutdown Reason

`CompletionReason` serves `TaskCompleted` (outcomes: `GOAL_ACHIEVED`, `MAX_TURNS`, `TASK_IMPOSSIBLE`, `ERROR`) and `SessionCompleted` (reasons: `USER_STOPPED`, `IDLE_TIMEOUT`, `INTERRUPTED`). Consumers branch on impossible states — e.g., `SessionCompleted` with `GOAL_ACHIEVED`. `TASK_IMPOSSIBLE` has no producer. This is a contract design flaw, not just dead code.

### H4. SessionConfig Mixes Concerns; Persistence Is Lossy

`SessionConfig` collapses execution control, model routing, platform/perception, observability, and eval knobs into one flat object. `SessionCheckpointCoordinator` persists only a subset — `actionDelayMs`, `approvalMode`, `debugMode`, `traceEnabled`, `traceRunId`, and `excludedTools` are silently dropped on reload. `SessionLlmConfig` permits contradictory states (`backendType = OPENAI` with non-null `localConfig`).

---

## Medium-Priority Findings

### M1. Approval Identifier Naming Is Inconsistent

`Op.Approve` uses `actionId`. `Op.UserResponse`, `ToolRouter.resolveApproval()`, and `ApprovalDetails` use `callId`. Same underlying concept, different names across the same interaction flow.

### M2. Protocol Package Mixes Domain Contract with UI Projection

`StatusUpdate` is display-ready with optional emoji. `ThoughtUpdate` carries already-truncated display text. `sanitizeThought()` in `TextUtils.kt` is a pure string utility creating a dependency from UI code on the protocol package. The package name implies something more stable and semantic than what it contains.

---

## Low-Priority Findings

### L1. ApprovalDetails.args: JSONObject

Leaks `org.json` dependency into the protocol layer. Works fine in-process but would matter if events cross serialization boundaries.

### L2. File Granularity

Five files contain a single data class each. Marginal complexity, acceptable at current scale.

### L3. Serialization Inconsistency

Only `ScreenStatePhase` has `@Serializable`. Acceptable for in-process events.
