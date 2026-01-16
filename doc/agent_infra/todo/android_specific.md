# Android-Specific Infrastructure Notes (App-Use Agents)

**Purpose**: Translate desktop/server-side agent infra patterns (Codex/Gemini/OpenHands) into Android reality, so we can keep **engineering code stable** while iterating on **agent/orchestration** safely.

**Scope**: This doc focuses on the “app-use agent on real Android device” case (Accessibility-driven, multi-app navigation), not IDE coding agents.

Related:
- `infra_design.md` (our current stable infra boundaries)
- `openhands_analysis.md` (OpenHands patterns + feasibility notes)

---

## 1. “Event Stream” on Android vs Desktop/Server: What Actually Changes?

### 1.1 Lifecycle and process death are first-class

On desktop/server:
- Processes tend to be long-lived; restarts are operator-controlled; a crash is “rare but recoverable”.

On Android:
- Your process can be killed for memory, background restrictions, OEM policies.
- Activity/UI can be recreated at any time.
- AccessibilityService can be restarted or temporarily lose `rootInActiveWindow`.

**Implication for an EventStream design**:
- Treat the event stream as **rebuildable** from persisted state.
- Design “replay” and “resume” semantics from day 1, even if minimal.

Practical patterns:
- Keep a small persisted “session checkpoint” (turn number, last action, last known package/activity, last screen hash).
- Optionally persist a bounded event log for debugging/replay (ring-buffer file / Room table).

### 1.2 Main-thread constraints are real

On Android, many UI/Accessibility operations must be coordinated with the main thread:
- Reading `rootInActiveWindow` safely
- Executing global actions
- Dispatching gestures and receiving callbacks

**Implication**:
- An “event stream” cannot be a fully free-running background bus that arbitrarily executes UI operations.
- You usually want **request/response** semantics for actions (a suspend function that returns an `ActionResult`), and *optionally* mirror those transitions into events for observers.

### 1.3 Ordering, backpressure, and UI “settling” matter more

Desktop/server tool calls often behave like: “run tool, get output”.

On Android, the tool you are executing is often “touch the UI”, and:
- The UI may not update immediately.
- Accessibility tree updates can lag.
- Gestures can be cancelled by the OS.
- A wrong timing window can cause the agent to act on stale state.

**Implication**:
- Your infra needs explicit “settling” and “screen-stability” concepts (even if heuristic).
- Backpressure is not just a data-structure problem: it can translate into mis-clicks.

### 1.4 Android-friendly EventStream implementation shape

If you introduce an internal EventStream (OpenHands-style), prefer:
- `SharedFlow`/`Channel` (coroutines) instead of “thread-per-subscriber”.
- One “session scope” that owns everything (structured concurrency) so cancellation works reliably.
- A single serialization point for UI actions (one action at a time) unless you have strong evidence parallelism helps.

---

## 2. Thread / Coroutine Management: What’s Different on Android

### 2.1 “Long running loop” needs OS-friendly execution

On desktop, an agent can run indefinitely.

On Android, long-running work must respect:
- Foreground/background execution limits
- Battery optimizations / Doze
- OEM task killers

**Practical patterns**:
- Run the agent loop inside a component with explicit lifecycle (your `AccessibilityService` is already the right anchor for UI automation).
- Keep the loop cooperative-cancellable (coroutines + cancellation signals). You already do this in `AgentSession` and `MobileV3Orchestration`.

### 2.2 Main vs IO dispatching is not optional

Rule of thumb:
- **Perception/UI**: main thread (or main-adjacent) where required by framework APIs.
- **LLM / network / JSON / logging**: `Dispatchers.IO` or `Default`.

Pitfall specific to app-use agents:
- “capture screen” often touches framework objects (`AccessibilityNodeInfo`) that are time-sensitive; capture on main, but serialize/transform on background if heavy.

### 2.3 Timeouts are required for correctness, not just robustness

Desktop tools often have deterministic completion.

On Android:
- Gestures can hang (callback never arrives).
- The UI can freeze.
- Accessibility service can temporarily fail to produce a root node.

**Infra-level suggestion**:
- Wrap perception and action execution in timeouts with clear error surfaces:
  - `captureScreen(timeoutMs)`
  - `performAction(timeoutMs)`
- Treat timeout as a first-class outcome feeding into the planner (e.g., “retry”, “back”, “relaunch app”, “ask user”).

### 2.4 Concurrency discipline: single “UI action lane”

Even if your LLM calls can be parallelized, UI actions should generally be serialized:
- Two gestures in flight leads to non-determinism.
- “Click A then Type B” must be ordered with a settle window.

**Recommended**:
- A single “ActionExecutor” queue (channel/actor) that owns UI actions.
- Keep the orchestration logic pure, send requests to the executor, await results.

---

## 3. “Sandbox” and Tool Execution: Desktop Agents vs Android App-Use Agents

### 3.1 Desktop “sandbox” != Android “sandbox”

Desktop/server coding agents assume they can:
- run arbitrary shell commands
- read/write arbitrary repo files
- spawn processes
- use Docker/VM sandboxing

Android app-use agents assume:
- tools are OS APIs (Accessibility gestures, Intents, app navigation, notifications)
- the app already runs in a sandbox (app sandbox + permissions)
- executing arbitrary code is either impossible or a major security risk

**Implication**:
- On Android, the “sandbox” story is mostly about **permission boundaries + user consent**, not containers.

### 3.2 What “tool execution” looks like on Android

Most tools are:
- `captureScreen()` (Accessibility tree; optionally screenshot/OCR if needed)
- `click(elementId)` / `tap(x,y)`
- `type(text)` (setText, IME, clipboard, etc.)
- `scroll/swipe`
- `back/home/recents`
- “open app” / “deep link” (Intent-based navigation)

So “ToolRouter” is still useful, but it should be framed as:
- a **UI action lifecycle manager** (validate → policy → execute → observe)
- not a “run command in sandbox” scheduler

### 3.3 When you *do* need a real sandbox

If your product roadmap includes:
- executing code (Python/JS)
- running external tools
- interacting with remote resources in privileged ways

Then for Android you typically use:
- **remote sandbox** (server-side container) and keep device as a thin client
- or a “companion runtime” on-device with hard limits (rare, complex, risky)

**Key design point**:
- Keep the “tool interface” stable, and let the execution backend differ (device vs remote). This is where OpenHands’ Runtime abstraction is a useful reference.

---

## 4. Android-Specific Gotchas Desktop Agents Usually Don’t Face

### 4.1 Perception is messy: Accessibility tree is not “truth”

The accessibility tree can be:
- incomplete (custom views)
- noisy (many nodes)
- stale (timing)
- missing semantics (no labels)

Infra implication:
- Your perception layer likely needs fallback signals:
  - UI stability heuristics (tree hash, element count deltas)
  - “is this the same screen?” checks
  - optional screenshot/OCR for certain apps (privacy-sensitive)

### 4.2 System gestures and safe regions

Modern Android navigation gestures can conflict with your swipes.

Infra implication:
- Scroll/swipe primitives should encode “safe regions” and be centralized (you already started this in `AccessibilityPlatform.performScroll`).
- Consider per-device calibration and “gesture failed” recovery paths.

### 4.3 Multi-app boundaries and permissions

Desktop agents often assume one workspace.

Android agents cross:
- apps
- system UI surfaces
- permission dialogs
- OEM overlays

Infra implication:
- Treat “packageName changed” as an event.
- Create explicit policies for permission dialogs (ask user, deny, stop).

---

## 5. What This Means for *Our* Current Architecture (`androidagent`)

### 5.1 Keep the Op/Event protocol (it’s already the right Android fit)

Your current session boundary:
- `submit(Op)` and `events: Flow<AgentEvent>`
…already gives you a stable contract and UI-decoupling similar to Codex.

Recommendation:
- Keep this as the *only* API the UI layer relies on.

### 5.2 Make “Action execution” a first-class stable service

Right now `MobileV3Orchestration` calls `services.platform.performAction(...)` directly.

If you want OpenHands-style observability and policy gates:
- Introduce a stable `ActionExecutor` (or adapt `ToolRouter` to be it).
- Have orchestration request actions through it and receive structured results.

This lets you add:
- approval flows
- risk classification
- retries/timeouts
- better logging
…without rewriting orchestration logic.

### 5.3 History/Condenser should be tied to a stable “Turn Record”

Before implementing OpenHands-like condenser strategies, define a stable “turn record” schema:
- perception summary + action + outcome + errors

Then:
- store it in a `HistoryStore` (your current `HistoryManager` can evolve into this)
- optionally run condenser policies on that store (Android thresholds should be conservative to protect battery/network)

---

## 6. Checklist: Android Agent Infra “Must Haves”

- **Lifecycle**: clear start/pause/resume/stop semantics + cleanup.
- **Cancellation**: cooperative cancellation everywhere (LLM calls, actions, perception).
- **Timeouts**: perception/action timeouts with recoverable outcomes.
- **Serialization**: one UI-action lane (avoid concurrent gestures).
- **Observability**: structured turn logs + bounded persistence for debugging.
- **Safety**: permission dialog policy + risky action approval.
- **Fallbacks**: when accessibility tree is insufficient (without silently escalating privacy risk).


