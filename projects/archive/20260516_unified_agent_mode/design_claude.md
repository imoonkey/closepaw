# Unified Agent Mode (remove pro-mode)

## Goal

Eliminate the `BASIC` vs `PRO` `AgentMode` distinction. Ship a single default mode where the main agent has full UI tools **and** `delegate_task` as an optional capability — same shape as Claude Code's main agent + `Agent` tool.

Why:
- Pro mode's planner/executor split is not pulling its weight as a hard architectural boundary. The same effect (context firewall on a specific subtask) is achievable as an opt-in tool call.
- Two modes = two prompts to tune, two LLM-arg surfaces, settings UI noise, extra branching in session bootstrap.
- KISS / minimal redundancy.

Success: one `AgentRoleDef` for the main agent. `AgentMode` enum gone. Settings UI no longer asks the user to pick. Subagent inherits main agent's prompt and tools with system-required exclusions only (`delegate_task`).

## Approach

### 1. One role definition

- Rename `StandaloneRoleDef` → `DefaultRoleDef`.
  - Add `delegate_task` to `allowedTools`.
  - `delegatable = true`. The same def is reused as the subagent role.
  - Prompt gets a `## Delegation` paragraph: when to delegate (independent subtask with isolatable context, or a one-shot exploration whose noisy steps would pollute the main trace).
- Delete `PlannerRoleDef`, `ExecutorRoleDef`, `AgentDefRegistry.mainFor(...)`.
- `AgentDefRegistry` collapses to `val main = DefaultRoleDef`, `fun delegatableRoles() = listOf(DefaultRoleDef)`.

### 2. System-required subagent tool exclusions

`SubAgentRunner.kt:73` already strips `delegate_task` from the child at runtime (no recursion). Keep that as the **only** structural exclusion. Other policy excludes (e.g. drop `remember_experience` from subagents to keep long-term memory writes parent-scoped) are added to the same `excludedNames` set — single seam, no separate WorkerRoleDef file.

Decision: also exclude `remember_experience` from subagent. Subagent's "what I learned" should flow back through the delegation result, not be silently committed.

### 3. AgentMode enum: deleted

- `protocol/SessionConfig.kt`: remove `AgentMode` enum + `agentMode` field.
- `AppSettingsStore` / `AppSettingsState`: drop `DEFAULT_AGENT_MODE`, `agentMode` field, persistence read/write.
- `SettingsHomePage`, `SettingsDropdowns`, `LlmAuthSettingsPage`: remove the mode dropdown and Pro/Basic conditionals (`agentMode == AgentMode.PRO` becomes "delegation is always available").
- `MainActivityIntentPayload`, `MainActivityModelValidation`: drop mode parsing/validation.
- `SessionCheckpointCoordinator.kt:114`: snapshot field removed; reload treats absent field as default.
- `SessionLlmBootstrapper.kt:113`: drop `agentMode == AgentMode.PRO` branch — initialize whatever the new single path needs.
- `OnboardingDemoController`: drop `agentMode = AgentMode.BASIC` override.

### 4. `mainModel` / `executorModel` → `mainModel` / `subagentModel`

- Rename `executorModel` to `subagentModel` everywhere (`SessionConfig`, settings UI labels, snapshot field).
- Resolution logic in `SessionAgentRunner.kt:76-80` collapses: main agent uses `mainModel`; subagent uses `subagentModel ?: mainModel`. The `AgentExecutionRole` switch goes away.
- `AgentExecutionRole`: keep enum but reduce to `MAIN` / `SUBAGENT` (rename `STANDALONE`/`PLANNER`/`EXECUTOR`). This is the runtime identity tag, separate from the role definition.

### 5. ask_user from subagent — confirmed working, no change

Verified via code path (`AskUserTool` is registered once by `SessionAgentRunner.ensureAskUserToolRegistered()` with the **parent** `eventDispatcher`; `SubAgentRunner` shares `toolRegistry` instances via `createFilteredCopy`, so the subagent invokes the same `AskUserTool` instance, which emits `AskUser` events directly to the parent stream → `AgentServiceEventHandler` → overlay/capsule). The `UserResponseChannel`'s single-pending lock prevents collisions.

Decision: leave as-is. Document this invariant in `AskUserTool` so a future "isolated subagent event bus" refactor doesn't accidentally break it.

Optional prompt tweak: in the `## Delegation` section, mention that subagents may invoke `ask_user` and the question is shown to the user as-is; the prompt should encourage subagents to self-prefix their question text (e.g. `"[worker] ..."`) only if disambiguation matters, but don't force it.

## Components

| File | Change |
|---|---|
| `agent/definition/StandaloneAgentDef.kt` | Rename to `DefaultAgentDef.kt`; add `delegate_task`; `delegatable = true`; prompt gains `## Delegation` |
| `agent/definition/PlannerAgentDef.kt` | **Delete** |
| `agent/definition/ExecutorAgentDef.kt` | **Delete** |
| `agent/definition/AgentDefRegistry.kt` | Collapse to single role |
| `agent/AgentExecutionRole.kt` | `STANDALONE`/`PLANNER`/`EXECUTOR` → `MAIN`/`SUBAGENT` |
| `agent/subagent/SubAgentRunner.kt` | `excludedNames = {"delegate_task", "remember_experience"}`; `executorModel` → `subagentModel`; child role = `MAIN` def |
| `protocol/SessionConfig.kt` | Remove `AgentMode`, `agentMode`; rename `executorModel` → `subagentModel` |
| `app/AppSettingsStore.kt`, `AppSettingsState.kt` | Drop agentMode persistence |
| `app/MainActivityIntentPayload.kt`, `MainActivityModelValidation.kt` | Drop mode parsing/validation |
| `app/MainActivityContent.kt` | Drop mode wiring |
| `session/SessionAgentRunner.kt` | Collapse model resolution; always register `delegate_task` (gated by tool allowlist) |
| `session/SessionLlmBootstrapper.kt` | Drop `PRO` branch |
| `session/SessionCheckpointCoordinator.kt` | Drop snapshot field read |
| `ui/settings/SettingsDropdowns.kt` | Delete mode dropdown |
| `ui/settings/SettingsHomePage.kt`, `LlmAuthSettingsPage.kt`, `AgentBehaviorSettingsPage.kt` | Drop Pro/Basic conditionals |
| `onboarding/OnboardingDemoController.kt` | Drop `agentMode` override |
| `trace/TraceModels.kt`, `AgentTrace.kt`, `TraceRecorderFactory.kt` | Drop mode field if present |
| `androidTest/.../Settings*Test.kt`, `DisplayModeSettingsTest.kt` | Remove mode-related test paths |
| `test/.../SessionServicesProviderRoutingTest.kt`, `AgentDefRegistryTest.kt`, `MainActivityModelValidationTest.kt`, etc. | Adjust to single-mode reality |

## Interactions

### Main agent boot
```
SessionAgentRunner.startAgent
  → AgentDefRegistry.main                       (was: mainFor(config.agentMode))
  → roleDef.resolve(termuxSnapshot, excludedTools)
  → if "delegate_task" ∈ allowedTools: register DelegateTaskTool
  → register AskUserTool
  → Agent.run(role = MAIN, model = config.mainModel)
```

### Delegation
```
main agent calls delegate_task("…")
  → DelegateTaskTool resolves the single delegatable role (DefaultRoleDef)
  → IsolatedSubAgentRunner spins up child Agent with:
        - filtered toolRegistry: parent's tools − {delegate_task, remember_experience}
        - shared scratchpad (ScratchpadState)
        - role = SUBAGENT, model = config.subagentModel ?: config.mainModel
  → child runs; events flow through bridgeEvent (see Design A / C below)
  → child completes; success/failure message returned to parent
```

### ask_user (main or subagent)
```
agent.execute(ask_user, ...)
  → AskUserTool (same instance across main/sub)
  → emit AskUser event via parent eventDispatcher (NOT bridgeEvent)
  → AgentServiceEventHandler.is AskUser → overlay.onAskUser(...)
  → user responds via overlay → UserResponseChannel.deliverResponse
  → AskUserInvocation resumes → returns observation to caller
```

## Multi-agent session history model

Two distinct "history" layers, with very different multi-agent semantics. Worth being explicit because Design C below interacts with both.

### Layer 1 — LLM context (`HistoryManager`)

What the LLM sees on each turn. Each Agent instance owns its own `HistoryManager`.

- **Main agent**: persistent `HistoryManager` (lives for the whole session).
- **Sub-agent**: a **fresh, ephemeral** `HistoryManager()` created in `SubAgentRunner.kt:79`. Never persisted, never reused. Sibling delegations don't share history.

The delegation appears in the **main agent's** history as a single Call/Result pair:

```
[..., FunctionCall(delegate_task, "tap the login button"),
      FunctionResult("Sub-agent 'default' completed: <one-line summary>"),
      ...]
```

The sub-agent's internal turns (its a11y trees, its intermediate tool calls) are **invisible to the main agent's LLM**. That's the context-firewall benefit of delegation and the reason a one-shot ad-hoc delegation is worth offering even in a non-split mode.

**Design C does NOT change this layer.** Sub-agent's LLM context stays isolated.

### Layer 2 — Session disk history (`SessionRecordingService` → `SessionRecord` JSON)

What the UI renders and what reload restores. There is exactly **one** `SessionRecordingService` per session, hooked to the parent's event stream. It consumes events through `AgentServiceEventHandler` and folds them into `agentMessageBuffer`:

```
TaskStarted     → startAgentMessage(taskId)        (opens an Agent row)
ActionProposed  → recordAction(actionId, …)        (append Action block to row)
ActionExecuted  → updateActionState(actionId, …)   (mutate by UUID actionId)
ThoughtUpdate   → recordThought(…)                 (append Thought block)
MessageDelta    → appendTextDelta(…)               (streaming text buffer)
TaskCompleted   → recordFinalAnswer + finalize     (seal the Agent row)
```

Each user task produces one `MessageRecord.Agent` row containing chronologically interleaved `ContentBlockRecord`s (Text / Thought / Action / FinalText).

**Today**, sub-agent action events are wrapped by `SubAgentRunner.bridgeEvent` as `SubAgentActivity` and dropped by `AgentServiceEventHandler` — so sub-agent activity is **not persisted** in `SessionRecord`. On reload you see the main agent's actions plus the `🤖 Delegating …` status text, but no detail of what the sub-agent did.

**Under Design C** (forwarding sub-agent `ActionProposed`/`ActionExecuted` to the parent's `eventEmitter` unchanged): the recording service consumes them the same way as main-agent actions. Sub-agent action cards get persisted into the current row's `contentBlocks`, interleaved with main-agent actions. UUID action IDs avoid collisions. **No schema change required.** Reload renders them naturally because they're just `ContentBlockRecord.Action` entries like any other.

What's "lost" under C: there's no `originAgent` field, so reload can't visually distinguish main from sub-agent actions in the persisted row. Deferred to a follow-up — see Design C trade-off above.

### Layer 3 — Runtime checkpoint (`SessionRuntimeSnapshot`)

`SessionCheckpointCoordinator.buildSnapshot` packages:
- Main agent's `HistoryManager.getAll()` items
- `TodoState` and `ScratchpadState`
- Config snapshot, last task outcome

Sub-agent's `HistoryManager` is **never** in the checkpoint. Sub-agent state is process-lifetime only.

### Reload guarantees

`SessionRuntimeSnapshot.kt:92-94`:
```kotlin
fun CheckpointState.isReloadable(): Boolean =
    this == CheckpointState.IDLE_READY || this == CheckpointState.CLOSED
```

Three checkpoint states:
- `RUNNING_DIRTY` — debounced writes during an active turn; may capture mid-tool-call state
- `IDLE_READY` — force-flushed when the agent reaches idle (`AgentSession.kt:456`)
- `CLOSED` — force-flushed at session shutdown

Only `IDLE_READY` and `CLOSED` are reloadable. **A mid-flight delegation is never a valid reload point.** This is the load-bearing invariant for multi-agent reload safety:

| Scenario | Outcome |
|---|---|
| Reload from `IDLE_READY` after a completed delegation | Main agent's `HistoryManager` contains a complete `(Call, Result)` pair for `delegate_task`. Sub-agent is gone, doesn't matter — main agent never needed its internal state. |
| App killed mid-delegation | Last reloadable snapshot is the previous `IDLE_READY`. Reload reverts to that — the in-progress delegation is wiped from both layers (sub-agent's history is ephemeral, main agent's checkpoint predates the `delegate_task` call). |
| New user message after reload | Flows into main agent's `HistoryManager` like any other message. Main agent runs the next turn with full context, including all prior delegation summaries. If a new delegation is needed, a fresh sub-agent is spawned (new `HistoryManager`, new lifecycle). |

So: **agent/LLM history is unaffected by reload, and new user messages are processed normally.** The cost of this guarantee is that the system doesn't try to resume mid-delegation — it rolls back to the last clean idle point instead.

## Design choice: subagent action visibility

Two options. **A is the default; C is feasible-but-additive — pick C if the small UX win is worth the small extra surface.**

### Design A — keep subagent activity quiet (RECOMMENDED, baseline)

Current behavior. `SubAgentRunner.bridgeEvent` wraps subagent action events as `SubAgentActivity`; `AgentServiceEventHandler` ignores those. Only `SubAgentStarted` / `SubAgentCompleted` updates the capsule status line ("🤖 Delegating to default…" / "🤖 default completed").

Pros: zero code change. Zero schema risk to persisted chat history. Acceptable when delegations are short or rare.

Cons: during a long delegation, the user sees a static status line with no per-action heartbeat.

### Design C — stream subagent actions into the parent's action stream

Forward subagent's `ActionProposed` and `ActionExecuted` events to the **parent** `eventEmitter` unchanged. They flow into the parent's chat row and capsule path exactly like main-agent actions. The interleaving (main agent → subagent → main agent) appears as a single chronological action ledger.

**Complexity rating: small.** Concretely:
- `SubAgentRunner.kt:153-172` `bridgeEvent`: split the `when` — `ActionProposed` / `ActionExecuted` go to `parentServices.eventEmitter` (need to plumb the parent emitter in alongside `eventDispatcher`). Everything else stays on the `SubAgentActivity` bridge.
- `ChatEventReducer.kt`: **no change**. ActionProposed/Executed already render via `handleActionProposed` / `handleActionExecuted` and append into the current agent row.
- `ServiceOverlayController.kt:342` `onActionExecuted`: **no change**. Already just refreshes glow.
- `AgentMessageBuffer.kt:70-85` `recordAction` / `updateActionState`: **no change**. Already keyed by `actionId` (UUIDs — no collision risk).
- `ContentBlockRecord`: **no schema change** — actions are recorded the same way. Reload "just works".
- `AgentServiceEventHandler.kt`: **no change** to ActionProposed/Executed handling. `SubAgentStarted` may optionally append an inline framing block to the chat row (`"↳ delegated: <query>"`) — but this is purely cosmetic and can be a follow-up.

**What gets dropped from forwarding:** `ThoughtUpdate`, `MessageDelta`, `TurnPhaseChanged`, `TurnStarted`, `ScreenCaptured`. These would either confuse the parent reducer's streaming buffer or pollute the trace. Subagent thoughts are not surfaced — only its actions.

**Subagent identity in UI:** the action card carries `toolName` (e.g. `mobile_action`, `shell`) and that's the only thing the existing card shows. There's no "this came from a subagent" label in C-minimal. Adding such a label requires a small schema bump on `ContentBlockRecord.Action` (`originAgent: String? = null`). **Defer that to a follow-up if needed** — most users will likely not care to distinguish; the goal of C is "the app doesn't look frozen during delegation," not "label every actor."

Pros: subagent actions visible in real time. Chat history is complete on reload. Capsule glow keeps pulsing through the delegated phase. No persistence schema change. Mirrors Claude Code's behavior (Task tool calls show subagent activity at the same surface as direct tool calls).

Cons: a few extra moving parts in `bridgeEvent` (one more dependency: the parent emitter). Subtle ordering risk: subagent and main agent are not concurrent in the current code path (parent suspends while child runs), so events arrive serially — no race.

Verdict: **adopt C-minimal**. Cost is one file change, the rest is free. Defer the `originAgent` label until UX feedback says it's needed.

## Tasks

### Phase 1 — Simplification (mandatory)

- **uam-1: collapse role defs**
  - Scope: `app/src/main/kotlin/ai/closepaw/agent/definition/**`
  - Acceptance: `DefaultAgentDef.kt` exists with `delegate_task` in tools and `delegatable = true`; `Planner*` and `Executor*` files deleted; `AgentDefRegistry.main` + `delegatableRoles()` return the single def.

- **uam-2: remove AgentMode enum**
  - Scope: `protocol/SessionConfig.kt`, `app/AppSettings*.kt`, `app/MainActivity*.kt`, `onboarding/OnboardingDemoController.kt`, `session/SessionLlmBootstrapper.kt`, `session/SessionCheckpointCoordinator.kt`, `trace/**`
  - Acceptance: `AgentMode` symbol no longer exists in `app/src/main`. Build green.
  - Dependencies: uam-1

- **uam-3: rename executor model → subagent model**
  - Scope: `protocol/SessionConfig.kt`, `session/SessionAgentRunner.kt`, `agent/subagent/SubAgentRunner.kt`, settings UI labels, snapshot fields
  - Acceptance: `executorModel` symbol gone; `subagentModel` reads through everywhere.
  - Dependencies: uam-2

- **uam-4: rename AgentExecutionRole**
  - Scope: `agent/AgentExecutionRole.kt` and references
  - Acceptance: `STANDALONE`/`PLANNER`/`EXECUTOR` → `MAIN`/`SUBAGENT`; all switches collapsed.
  - Dependencies: uam-3

- **uam-5: subagent policy exclusions**
  - Scope: `agent/subagent/SubAgentRunner.kt` line 73 + comment
  - Acceptance: `excludedNames = {"delegate_task", "remember_experience"}`; comment explains both rationales (recursion / scope of long-term memory).
  - Dependencies: uam-1

- **uam-6: settings UI cleanup**
  - Scope: `ui/settings/SettingsDropdowns.kt`, `SettingsHomePage.kt`, `LlmAuthSettingsPage.kt`, `AgentBehaviorSettingsPage.kt`
  - Acceptance: Pro/Basic dropdown gone; "Subagent model" replaces "Executor model" label; conditionals on `agentMode` removed.
  - Dependencies: uam-2

- **uam-7: tests + intent surface**
  - Scope: `androidTest/.../Settings*Test.kt`, `test/.../SessionServices*Test.kt`, `AgentDefRegistryTest.kt`, `MainActivityModelValidation*Test.kt`, `MainActivityIntentApplier*Test.kt`
  - Acceptance: `./gradlew test connectedDebugAndroidTest` (where applicable) green; obsolete mode-pick test paths deleted.
  - Dependencies: uam-1..uam-6

- **uam-8: prompt-tune docs / skill prompts**
  - Scope: `.claude/skills/prompt-tune/references/ownership_model.md`, any prompt-tune SKILL.md references to BASIC/PRO, README, doc/PROGRESS.md entry
  - Acceptance: docs reflect single-mode reality; PROGRESS gets a one-line entry.
  - Dependencies: uam-7

### Phase 2 — Subagent action streaming (Design C-minimal)

- **uam-9: forward subagent action events to parent stream**
  - Scope: `agent/subagent/SubAgentRunner.kt` (`bridgeEvent` + constructor: accept `parentEventEmitter`); `session/SessionAgentRunner.kt:152-166` (`ensureDelegationToolRegistered` pass the parent emitter to `IsolatedSubAgentRunner`)
  - Acceptance: while a subagent runs, `ActionProposed` / `ActionExecuted` events appear in `ChatEventReducer`'s current agent row and on the capsule glow path; manual sanity test (`./scripts/debug-run.sh "delegate something"`) shows action cards streaming during delegation.
  - Dependencies: uam-1..uam-7 (Phase 1 must be done first)
  - Out of scope: forwarding `ThoughtUpdate` / `MessageDelta` / `ScreenCaptured`; adding `originAgent` label on `ContentBlockRecord.Action`. Defer until UX feedback.

## Trade-offs

**Alt 1: keep PRO mode, just simplify settings UI**
Rejected. The split is the cost, not the dropdown. Two prompts, two model knobs, two test matrices — for a structural firewall that the merged model can opt into per-task.

**Alt 2: separate `WorkerRoleDef` file (same prompt, different tool list)**
Rejected. The system-required exclusion is one tool (`delegate_task`). Adding a second policy exclusion (`remember_experience`) is one set entry, not a new file. A second file would just be a near-clone that drifts.

**Alt 3: skip Phase 2 (Design A — keep subagent quiet)**
Defensible if Phase 2 turns out larger than this design claims. The bound is "C-minimal touches one wiring file and adds one constructor parameter." If review of `bridgeEvent` reveals a hidden complication (e.g. the parent emitter is not trivially reachable from `IsolatedSubAgentRunner`'s construction site), fall back to A and revisit later.

**Alt 4: full subagent UI lane (collapsible block, per-actor coloring, originAgent labels)**
Rejected for v1. That's worth doing only after we've shipped C-minimal and have evidence the unlabeled interleaving is actually confusing in practice. YAGNI.

## Risks

- **Snapshot reload compatibility**: `SessionCheckpointCoordinator` reads `agentMode` from old snapshots. The codebase says "no backward compatibility hacks" (CLAUDE.md), so dropping the field cleanly is acceptable; old in-flight checkpoints become invalid. If users have live snapshots they expect to resume across this upgrade, that's a one-time loss — call it out in the PROGRESS entry.
- **Intent payload contract**: `MainActivityIntentPayload` parses an `agent_mode` extra. Anything external (eval harness, scripts under `scripts/`) that passes this extra will start being ignored. Grep `scripts/` and `.claude/skills/autotune` for `agent_mode` usage; remove from those callers as part of uam-2/uam-7.
- **Trace schema drift**: `TraceModels.kt` likely embeds `agentMode`. Old traces become un-readable for fields that disappear, but the trace tooling is dev-internal; clean break is fine.
