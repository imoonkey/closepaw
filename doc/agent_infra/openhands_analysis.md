# OpenHands Architecture Analysis & Recommendations

**Purpose**: Analysis of OpenHands architecture patterns and targeted recommendations for Android Agent infrastructure improvement.

**Related Documents**:
- [Reference Analysis (Codex/Gemini)](./reference_analysis.md)
- [Infrastructure Design](./infra_design.md)

---

## 1. OpenHands Architecture Overview

OpenHands represents a more mature, production-grade agent system that has undergone significant architectural evolution. The codebase is currently migrating from V0 to V1, with clear separation between legacy code and the new architecture.

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         OPENHANDS ARCHITECTURE                                   │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌──────────────┐                                                               │
│  │   Frontend   │──────────────────────────┐                                    │
│  └──────────────┘                          │                                    │
│                                            ▼                                    │
│                               ┌─────────────────────────┐                       │
│                               │      EventStream        │ ◄─── Central Message  │
│                               │    (Pub/Sub Pattern)    │       Bus             │
│                               └───────────┬─────────────┘                       │
│                    ┌──────────────────────┼──────────────────────┐              │
│                    │                      │                      │              │
│                    ▼                      ▼                      ▼              │
│  ┌─────────────────────────┐  ┌─────────────────┐  ┌─────────────────────────┐  │
│  │   AgentController       │  │     Memory      │  │       Runtime           │  │
│  │   (Lifecycle Manager)   │  │  (Microagents)  │  │   (Tool Execution)      │  │
│  └───────────┬─────────────┘  └─────────────────┘  └─────────────────────────┘  │
│              │                                                                   │
│              ▼                                                                   │
│  ┌─────────────────────────┐                                                    │
│  │    Agent (agenthub)     │                                                    │
│  │  CodeActAgent, etc.     │                                                    │
│  │  • step(State) → Action │                                                    │
│  │  • Condenser            │                                                    │
│  │  • ConversationMemory   │                                                    │
│  └─────────────────────────┘                                                    │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Key Architectural Patterns in OpenHands

### 2.1 EventStream: Central Pub/Sub Message Bus

**Unlike Codex/Gemini**, OpenHands uses a central `EventStream` with subscriber pattern:

```python
class EventStreamSubscriber(str, Enum):
    AGENT_CONTROLLER = 'agent_controller'
    RUNTIME = 'runtime'
    MEMORY = 'memory'
    SERVER = 'server'
    # ... more subscribers
```

**How it works:**
1. Any component can `add_event(event, source)` to the stream
2. Subscribers register callbacks via `subscribe(subscriber_id, callback, callback_id)`
3. Events are processed in order, dispatched to all subscribers
4. Events have typed `source`: `AGENT`, `USER`, `ENVIRONMENT`

**Key benefit**: Complete decoupling between producers and consumers. Runtime doesn't know about Agent; Agent doesn't know about Memory.

### 2.2 Agent Definition vs Agent Execution (Key Insight!)

OpenHands clearly separates:

| Component | Responsibility | Stability |
|-----------|---------------|-----------|
| `Agent` (base class in `controller/agent.py`) | Interface definition + registry | **Stable** |
| `CodeActAgent` (in `agenthub/`) | Actual agent logic, prompts, tools | **Evolving** |
| `AgentController` | Lifecycle, state machine, delegation | **Stable** |
| `ConversationMemory` | Per-agent message construction | **Evolving** |
| `Condenser` | History compression strategies | **Evolving** |

```python
# Stable interface in controller/agent.py
class Agent(ABC):
    @abstractmethod
    def step(self, state: State) -> Action:
        """Single step - all agent logic goes here"""
        pass
    
    @classmethod
    def register(cls, name: str, agent_cls: type['Agent']) -> None:
        """Registry pattern for extensibility"""
        pass
```

**This is THE separation pattern the OpenHands contributor mentioned:**
- `AgentController` = Engineering (stable infrastructure)
- `Agent.step()` implementations = Research (evolving agent logic)

### 2.3 Condenser System: Pluggable History Compression

OpenHands has a sophisticated, **pluggable** history compression system:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        CONDENSER STRATEGIES                                      │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  NoOpCondenser          ─── Pass through, no condensation                        │
│  RecentEventsCondenser  ─── Keep last N events                                   │
│  ConversationWindow     ─── Sliding window approach                              │
│  LLMSummarizing         ─── Use LLM to summarize history                         │
│  LLMAttention           ─── Use LLM to select important events                   │
│  AmortizedForgetting    ─── Gradually forget old events                          │
│  ObservationMasking     ─── Mask verbose tool outputs                            │
│  BrowserOutput          ─── Specialized for browser observations                 │
│  Pipeline               ─── Chain multiple condensers                            │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

**Key abstraction:**
```python
class Condenser(ABC):
    @abstractmethod
    def condense(self, view: View) -> View | Condensation:
        """Transform history - return condensed view OR condensation action"""
        pass
```

This allows researchers to experiment with different memory strategies without touching infrastructure.

### 2.4 Runtime as Event Subscriber

The Runtime (tool execution environment) subscribes to EventStream:

```python
class Runtime:
    def __init__(self, event_stream: EventStream, ...):
        event_stream.subscribe(
            EventStreamSubscriber.RUNTIME, 
            self.on_event, 
            self.sid
        )
    
    def on_event(self, event: Event) -> None:
        if isinstance(event, Action):
            observation = self.run_action(event)
            self.event_stream.add_event(observation, source)
```

**Actions → Runtime → Observations** without direct coupling to Agent.

### 2.5 V0 to V1 Migration: Explicit Marking

OpenHands explicitly marks legacy code for deprecation:

```python
# IMPORTANT: LEGACY V0 CODE
# This file is part of the legacy (V0) implementation...
# V1 replacement for this module lives in the Software Agent SDK.
```

This allows:
1. Clear identification of code that will change
2. Gradual migration without breaking existing functionality
3. Parallel development of new architecture

---

## 3. Comparison Matrix: OpenHands vs Codex/Gemini vs Your Current Design

| Concern | Codex | Gemini | **OpenHands** | **Your Current** |
|---------|-------|--------|---------------|------------------|
| **Event Flow** | Channel-based SQ/EQ | Callbacks + Streaming | EventStream (pub/sub) | Flow<AgentEvent> |
| **Service Location** | SessionServices | Config mega-object | Injected dependencies | SessionServices |
| **Tool Execution** | Direct call | State machine | Runtime subscriber | ToolRouter + state machine |
| **History Management** | ContextManager | ChatCompression | Pluggable Condenser | HistoryManager |
| **Agent Definition** | N/A (single agent) | AgentRegistry | agenthub + Agent base | AgentRegistry |
| **Multi-Agent** | Limited | Sub-agents | AgentDelegateAction | MobileV3Orchestration |
| **Research/Eng Split** | Implicit | Some separation | **Explicit (V0/V1)** | Explicit (layers) |

---

## 4. Targeted Recommendations

Based on OpenHands patterns and your stated goal of "engineering stable, agent flexible":

### 4.1 Consider EventStream Pattern for Greater Decoupling

**Current**: `Flow<AgentEvent>` is good but creates direct dependency chains.

**Recommendation**: Consider an EventStream-like central hub:

```kotlin
// Proposed: Central event dispatch
class EventStream {
    private val subscribers = mutableMapOf<SubscriberId, (Event) -> Unit>()
    
    fun subscribe(id: SubscriberId, callback: (Event) -> Unit)
    fun addEvent(event: Event, source: EventSource)
    
    // Expose as Flow for UI consumption
    val events: Flow<Event>
}

enum class EventSource { AGENT, USER, PLATFORM, SYSTEM }
```

**Why**: This would allow:
- Platform (tool execution) to be completely decoupled from orchestration
- Memory/logging systems to subscribe independently
- Future extensions without modifying core flow

### 4.2 Adopt Pluggable Condenser Pattern

**Current**: `HistoryManager` has fixed truncation logic.

**Recommendation**: Make history compression a strategy:

```kotlin
// Proposed: Pluggable condenser
interface Condenser {
    fun condense(history: List<HistoryEntry>): CondenserResult
}

sealed class CondenserResult {
    data class View(val events: List<HistoryEntry>) : CondenserResult()
    data class NeedsCompression(val action: CondensationRequest) : CondenserResult()
}

// Research can swap strategies without touching infrastructure
class RecentEventsCondenser(private val windowSize: Int) : Condenser
class LLMSummarizingCondenser(private val llm: LLMClient) : Condenser
class MobileActionCondenser : Condenser  // Specialized for UI actions
```

### 4.3 Strengthen Agent Interface Contract

**Current**: Your `AgentRegistry` stores definitions; orchestration creates instances.

**Recommendation**: Make the `Agent` interface the clear research boundary:

```kotlin
// Proposed: Explicit research boundary
interface Agent {
    /**
     * Single step execution - ALL agent logic goes here.
     * This is the ONLY method researchers need to implement.
     */
    suspend fun step(state: AgentState): AgentAction
    
    /** Optional: Custom prompt construction */
    fun buildPrompt(state: AgentState): LLMPrompt = defaultPromptBuilder(state)
    
    /** Optional: Custom condenser */
    val condenser: Condenser get() = defaultCondenser
}

// Stable infrastructure handles everything else:
// - Lifecycle management (AgentSession)
// - Event dispatch
// - Tool execution
// - Error recovery
```

### 4.4 Decouple Platform from Orchestration

**Current**: `MobileV3Orchestration` directly calls `platform.performAction()`.

**Recommendation**: Platform as event subscriber (like OpenHands Runtime):

```kotlin
// Proposed: Platform as subscriber
class AccessibilityPlatform(private val eventStream: EventStream) {
    init {
        eventStream.subscribe(SubscriberId.PLATFORM) { event ->
            if (event is UIAction) {
                val result = executeAction(event)
                eventStream.addEvent(ActionResult(event.id, result), EventSource.PLATFORM)
            }
        }
    }
}
```

**Why**: Orchestration just emits UIAction events; doesn't need to know how they execute.

### 4.5 Add Migration Markers (Like OpenHands V0/V1)

**Current**: Mixed legacy and new code without explicit markers.

**Recommendation**: Add explicit stability annotations:

```kotlin
/**
 * @Stable - This interface is part of stable infrastructure.
 *           Changes require careful consideration.
 */
@Stable
interface AgentSession { ... }

/**
 * @Evolving - This implementation is under active research.
 *             Expected to change frequently.
 */
@Evolving
class MobileV3Orchestration : AgentOrchestration { ... }

/**
 * @Deprecated - Legacy code, will be removed in future version.
 *               Use [NewImplementation] instead.
 */
@Deprecated("Use AgentSession instead", ReplaceWith("AgentSession"))
class LegacyAgentRunner { ... }
```

---

## 5. Proposed Architecture Evolution

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    PROPOSED ANDROID AGENT ARCHITECTURE                           │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌──────────────┐                                                               │
│  │     UI       │                                                               │
│  │  (Overlay)   │──────────────────────────────┐                                │
│  └──────────────┘                              │                                │
│                                                ▼                                │
│                                    ┌─────────────────────────┐                  │
│    STABLE                          │      EventStream        │                  │
│    INFRASTRUCTURE ─────────────────│    (Central Hub)        │                  │
│                                    └───────────┬─────────────┘                  │
│                      ┌─────────────────────────┼─────────────────────────┐      │
│                      │                         │                         │      │
│                      ▼                         ▼                         ▼      │
│       ┌────────────────────┐     ┌──────────────────┐     ┌──────────────────┐ │
│       │   AgentSession     │     │     Platform     │     │   HistoryStore   │ │
│       │   (Lifecycle)      │     │   (Subscriber)   │     │   (Subscriber)   │ │
│       └─────────┬──────────┘     └──────────────────┘     └──────────────────┘ │
│                 │                                                               │
│                 ▼                                                               │
│       ┌────────────────────┐                                                    │
│       │  Orchestration     │ ◄── Calls Agent.step()                             │
│       └─────────┬──────────┘                                                    │
│                 │                                                               │
│    EVOLVING ────┼───────────────────────────────────────────────────────────   │
│    RESEARCH     │                                                               │
│                 ▼                                                               │
│       ┌────────────────────────────────────────────────────────────────┐        │
│       │                     Agent Implementations                       │        │
│       │  ┌─────────┐  ┌──────────┐  ┌───────────┐  ┌───────────────┐  │        │
│       │  │ Manager │  │ Executor │  │ Reflector │  │ Future Agents │  │        │
│       │  └─────────┘  └──────────┘  └───────────┘  └───────────────┘  │        │
│       │                                                                │        │
│       │  ┌─────────────────────────────────────────────────────────┐  │        │
│       │  │  Condensers: RecentEvents, LLMSummarizing, Mobile, ...  │  │        │
│       │  └─────────────────────────────────────────────────────────┘  │        │
│       │                                                                │        │
│       │  ┌─────────────────────────────────────────────────────────┐  │        │
│       │  │  Prompts: System prompts, context templates, etc.       │  │        │
│       │  └─────────────────────────────────────────────────────────┘  │        │
│       └────────────────────────────────────────────────────────────────┘        │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Priority Recommendations

| Priority | Recommendation | Effort | Impact |
|----------|----------------|--------|--------|
| **P0** | Add stability annotations to existing code | Low | High - Clarity |
| **P1** | Extract Condenser interface from HistoryManager | Medium | High - Research flexibility |
| **P2** | Consider EventStream for future decoupling | Medium | Medium - Extensibility |
| **P3** | Refactor Platform as event subscriber | High | Medium - Cleaner architecture |

---

## 7. Key Takeaways from OpenHands

1. **Pub/Sub > Direct Flow**: EventStream pattern provides maximum decoupling

2. **Pluggable Condensers**: History compression is a research concern; make it swappable

3. **Agent.step() as Contract**: One method, one responsibility - all research goes there

4. **Explicit Stability Markers**: V0/V1 marking helps manage technical debt

5. **Runtime as Subscriber**: Tool execution doesn't need to know about agent logic

6. **Multi-Agent via Events**: AgentDelegateAction allows clean sub-agent patterns

---

## 8. References

- [OpenHands Repository](https://github.com/OpenHands/OpenHands)
- [Software Agent SDK](https://github.com/OpenHands/software-agent-sdk) (V1 core)
- [OpenHands Memory System](https://github.com/OpenHands/OpenHands/tree/main/openhands/memory)
- [Our Reference Analysis (Codex/Gemini)](./reference_analysis.md)
- [Our Infrastructure Design](./infra_design.md)

---

## 9. Feasibility Review vs Our Current `androidagent` Codebase

This section evaluates which recommendations in this doc are **immediately feasible** given the current Kotlin/Android architecture and which ones require **wiring changes** first.

### 9.1 Current Reality Check: We Have *Two* Execution Pipelines (Not One)

Right now there are two parallel stacks:

- **Stack A (MobileV3 loop)**: `AgentSession` → `MobileV3Orchestration` → `AndroidPlatform` (`AccessibilityPlatform`)
  - Manager/Executor/Reflector live in `domain/agents/` and call `LLMClient.chat()` directly.
  - Actions are executed via `services.platform.performAction(...)` (direct platform call).

- **Stack B (Infra Tool System)**: `SessionServices.toolRegistry/toolRouter/policyEngine/historyManager`
  - `ToolRouter` is a state machine + approval gate.
  - `HistoryManager` is a “conversation history” container.
  - But **neither is currently used by the MobileV3 orchestration loop nor the domain agents**.

This matters because several OpenHands-inspired recommendations (EventStream-like decoupling, Condenser strategy, “runtime as subscriber”) only become valuable once Stack A and Stack B are intentionally unified.

### 9.2 Recommendation Feasibility (Concrete)

#### (A) “EventStream / PubSub bus”

**Feasible, but currently redundant** at the *UI/session* boundary:

- You already have Codex-style semantics: `AgentSession.submit(Op)` and `AgentSession.events: Flow<AgentEvent>` backed by a buffered `Channel`.
- This is already a good Android fit (lifecycle + coroutines).

Where an OpenHands-style EventStream *does* become useful:

- **Inside** the session as an internal bus to decouple:
  - Orchestration (planner) ↔ action executor ↔ perception ↔ logging/telemetry ↔ policy/approval.
- On Android, this should likely be implemented as `SharedFlow`/`Channel` with **structured concurrency**, not thread-per-subscriber (OpenHands uses dedicated threads).

Practical suggestion:
- Keep the existing Op/Event protocol for UI.
- Add a **session-internal** event bus only if you need multiple independent subscribers (analytics, persistence, debugging recorder, safety monitors) without coupling.

#### (B) “Platform as subscriber (Runtime-like)”

**Conceptually feasible**, but in Android you must respect:

- Accessibility APIs often need main-thread access (`rootInActiveWindow`, `performGlobalAction`, `dispatchGesture` callbacks).
- UI state is timing-sensitive: “capture → decide → act → settle → capture” loops depend on deterministic ordering.

So instead of making platform a “free-running subscriber”, a better Android adaptation is:

- an **ActionExecutor** component that provides **request/response semantics** (suspend until completion), but can still be driven by events if desired.

In other words: keep **awaitable** tool/action execution as the primary interface, optionally mirror it onto an event stream for observers.

#### (C) “Pluggable Condenser”

**Feasible**, but only after you decide what “history” means for MobileV3 in this app.

Today:

- `domain/agents/Manager` and `Executor` prompt with a *fresh* synthesized prompt each step.
- There is no shared, explicit “conversation transcript” that grows turn-by-turn.

If you want OpenHands-style memory/condensation:

- First unify on a single “turn record” model (e.g., `Perception` + `Thought` + `ActionProposed` + `ActionExecuted` + `Outcome`).
- Then:
  - Have agents read from a `HistoryStore` abstraction (current `HistoryManager` is a good start).
  - Add `Condenser` as a strategy that transforms `HistoryStore.view()` into `PromptView`.

Key risk on Android: token budgets and history size aren’t just LLM concerns; they affect **battery/network** too. A condenser that triggers too often will increase latency + cost.

#### (D) “Strengthen Agent interface contract”

**Feasible and recommended**, but it needs to match your “research boundary”.

Currently your “research boundary” is effectively:

- `domain/agents/*` (prompt+parsing+heuristics)
- and `orchestration/v3/MobileV3Orchestration` (loop semantics)

To improve separation:

- Define a stable `Agent` interface in the stable layers (e.g., under `orchestration/` or a new `core/agent/`) that is closer to:
  - `suspend fun step(state: TurnContext): ProposedAction`
  - where `TurnContext` is stable, versioned, and testable.

Then keep `domain/agents/*` as **implementations** only.

#### (E) “Stability markers (@Stable/@Evolving)”

**Highly feasible and low-risk**.

Android-specific twist:

- You may want stability markers not just for code review, but for **API compatibility** between app modules (and future dynamic-loading/plugin experiments).

Practical suggestion:

- Add annotation types and start by tagging:
  - `protocol/*`, `session/*`, `infra/*`, `platform/*` as `@Stable`
  - `orchestration/*`, `domain/agents/*`, prompts, heuristic logic as `@Evolving`

### 9.3 A Minimal “Unification Plan” (If You Want OpenHands-style Benefits)

If your goal is: “engine stable, agent flexible, and tool lifecycle/debugging gets easier”, a pragmatic path is:

1. **Decide the canonical execution path**:
   - Either “MobileV3 drives platform directly” (current) OR “MobileV3 emits tool calls to ToolRouter”.
2. If you choose ToolRouter:
   - Treat `UIAction` as tool invocations (Click/Type/Scroll/etc) so approvals/policy become real.
   - Make Executor output tool calls (or tool-like commands), not ad-hoc JSON actions.
3. Make `HistoryManager` the canonical “turn log”, then add `Condenser` later.

This keeps the migration incremental and avoids introducing an EventStream purely for aesthetics.

