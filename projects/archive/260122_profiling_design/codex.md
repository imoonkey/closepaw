# System Performance Profiling Design (Codex)

## Goals
- Build a repeatable profiling workflow that explains end-to-end (E2E) time per session.
- Quantify time spent per turn and per phase in the ReAct loop.
- Identify top contributors to E2E latency and propose changes that can cut E2E time by 50%.

## Non-Goals
- Implementing optimizations in this document.
- Changing task success behavior or UX flows.

## System Overview (Source of Truth: Code)
The core execution loop is implemented in `Agent.executeTurn()` and follows:
Perception (screen capture) -> Planning (LLM) -> Execution (tools) -> Observation (post-action screen).
Key call sites:
- `Agent.executeTurn()` for turn orchestration and delays.
- `Turn.run()` for LLM request and response parsing.
- `ToolRouter.execute()` for tool lifecycle, approval waits, and execution.
- `BaseToolInvocation.execute()` for action execution and post-action observation.
- `AccessibilityPlatform.captureScreen()` and `Perceptor.snapshot()` for perception.
- `Perceptor.toPromptJson()` for prompt JSON generation.

Current fixed waits and limits that directly impact time:
- Turn-level UI settle delay after each turn: `SessionConfig.actionDelayMs` (default 2000 ms) is passed into `AgentConfig.uiSettleDelayMs`.
- Post-action observation wait: `BaseToolInvocation.UI_SETTLE_DELAY_MS = 300 ms`.
- Fallback observation capture wait in `Agent.captureObservationWithSnapshot()` = 500 ms.
- Gesture durations: tap 100 ms, swipe 300 ms; gesture timeout 5000 ms.
- Approval wait timeout: 60 seconds.
- Perception caps: `MAX_ELEMENTS = 80`, `MAX_STRING_LENGTH = 60`.

## Profiling Questions
- What is the median and p90 E2E time per session and per turn?
- Which phases dominate (screen capture, LLM, tool execution, observation, fixed waits)?
- How many turns are required per task and why?
- How much time is lost to fixed delays vs actual work?
- How often do LLM retries or approval waits dominate?

## Metrics and Time Budget
Primary metrics:
- Session E2E duration (start -> SessionCompleted).
- Turn duration and phase durations.
- LLM latency (request -> response).
- Perception latency (captureScreen + snapshot + JSON).
- Tool execution latency (ToolRouter.execute -> ToolCallResult).
- Observation latency (post-action capture path).
- Fixed delay time (actionDelayMs, UI settle, fallback capture delay).

Secondary metrics:
- Number of turns per session.
- Number of tool calls per turn.
- Perception element count per snapshot.
- Prompt size (approx chars or token estimate via HistoryManager).
- LLM retry counts and backoff time.

Derived budgets:
- `T_turn = T_capture + T_llm + T_tools + T_observe + T_settle`
- `T_session = sum(T_turn) + approval_wait_time`

## Instrumentation Plan
### 1) Event Timeline (Existing Events)
Leverage `AgentEvent` timestamps already emitted by `Agent` and `AgentSession`.
Signals to compute:
- Turn start and completion from `AgentEvent.TurnStarted` and `TurnCompleted`.
- Phase changes via `AgentEvent.TurnPhaseChanged`.
- Perception capture via `AgentEvent.ScreenCaptured`.
- Tool completion via `AgentEvent.ActionExecuted`.

Action:
- Add a lightweight event collector in `AgentService` (or a debug-only logger) that writes a JSON Lines file to `debug-output/profiling.jsonl` with event name, timestamp, sessionId, turnId, and optional fields (tool name, element count, success).

### 2) Explicit Span Timing (Code-Level)
Add start/stop timing logs (or trace sections) around key blocks:
- `Agent.executeTurn()`:
  - `captureScreen()`
  - `Turn.run()` (LLM)
  - Each tool call execution block
  - Observation capture fallback path
- `Turn.run()`:
  - `LLMClient.chatWithTools()` duration
  - Response parsing duration
- `ToolRouter.execute()`:
  - validation + policy decision
  - approval wait
  - tool invocation execute
- `BaseToolInvocation.capturePostActionObservation()`
- `AccessibilityPlatform.captureScreen()` and `Perceptor.snapshot()` + `Perceptor.toPromptJson()`

Minimal implementation approach:
- Use `System.currentTimeMillis()` for coarse timing and `android.os.SystemClock.elapsedRealtimeNanos()` for precise spans.
- Emit structured logs with a stable prefix, e.g. `PERF_SPAN` for logcat parsing.

### 3) Trace-Based Profiling
Add `android.os.Trace.beginSection()` and `endSection()` around the same spans.
Then capture with Android Studio System Trace or Perfetto to correlate CPU, binder, and coroutine scheduling.

### 4) Visual Debugging Correlation
Use `scripts/debug-run.sh` to capture `turn_N.png` and `turn_N_log.txt`.
Enhance the profiling JSON to include `turnNumber` and timestamps so screenshots can be mapped to timeline segments.
This enables a visual view: what the agent saw at each timing spike.

## Data Output Format
Use a JSON Lines file to support streaming and easy parsing:
```
{"ts":1700000000000,"type":"TURN_START","sessionId":"...","turn":1}
{"ts":1700000000100,"type":"CAPTURE_END","turn":1,"elements":42,"ms":85}
{"ts":1700000001500,"type":"LLM_END","turn":1,"ms":1250,"model":"gpt-4o"}
{"ts":1700000002200,"type":"TOOL_END","turn":1,"tool":"click","ms":420,"success":true}
{"ts":1700000005000,"type":"TURN_END","turn":1,"ms":5000}
```
Data should be written to `debug-output/profiling.jsonl` in debug builds only.

## Experiment Design
### Task Set (Baseline)
Use a small, repeatable set:
- "Open Settings"
- "Open Chrome"
- "Open Settings and toggle Wi-Fi"
- "Search for Bluetooth"

### Procedure
- Same device, same OS version, same network.
- Clear app state between runs.
- Run each task 5 to 10 times; report median and p90.
- Capture logs, screenshots, and traces for every run.

### Outputs
- Per-task E2E time distribution.
- Per-turn time breakdown.
- Top 3 dominant phases per task.

## Optimization Hypotheses (Ranked)
These are candidates to validate after baseline profiling.

### 1) Reduce Fixed Waits (High Impact)
Why: Fixed waits are a guaranteed additive cost per turn.
Where:
- `SessionConfig.actionDelayMs` default 2000 ms (per turn).
- `BaseToolInvocation.UI_SETTLE_DELAY_MS = 300 ms`.
- `Agent.captureObservationWithSnapshot()` adds 500 ms when used.
Idea:
- Adaptive settle: wait only until screen changes stabilize or a max cap is reached.
- Per-action delay: shorter for taps, longer for scrolls, configurable by tool type.
Expected gain: high if turns are many.

### 2) Reduce Redundant Screen Captures (High Impact)
Why: Multiple captures per turn can be expensive.
Where:
- Initial capture in `Agent.executeTurn()`.
- Post-action capture in `BaseToolInvocation`.
- Fallback capture in `Agent.captureObservationWithSnapshot()`.
Idea:
- Reuse `ToolObservation.ScreenState` when available to skip extra captures.
- Skip initial capture if previous observation is recent and no UI action happened.
Expected gain: medium to high depending on tool frequency.

### 3) Reduce Prompt Size and LLM Latency (High Impact)
Why: LLM call is often dominant.
Where:
- `Perceptor.toPromptJson()` uses pretty JSON; `MAX_ELEMENTS = 80`.
Idea:
- Compact JSON (no indentation).
- Dynamic element budget (favor interactive elements, fewer total elements).
- Shorten strings further for non-interactive nodes.
- Switch model to `gpt-4o-mini` for faster iterations or use a fast-first, slow-fallback strategy.
Expected gain: high if LLM latency dominates.

### 4) Reduce Turn Count (Medium Impact)
Why: Fewer turns reduce all fixed costs.
Idea:
- Strengthen system prompt to encourage multi-tool execution in one turn.
- Provide stronger tool affordances (e.g., allow "type" + "click" combos).
Expected gain: medium; depends on task complexity.

### 5) Avoid Long Approval Stalls (Medium Impact)
Why: 60s approval waits can dominate.
Idea:
- Measure approval frequency and time-to-approve.
- Use SMART mode rules to reduce approvals for low-risk actions.

## Risks and Safeguards
- Shorter delays can reduce reliability; use A/B tests and success rate tracking.
- Smaller prompts can reduce accuracy; measure success rate and recovery turn count.
- Additional instrumentation adds overhead; keep it debug-only and minimal.

## Proposed Next Steps
1) Implement event timeline logging to `debug-output/profiling.jsonl`.
2) Add Trace spans for key phases and collect a Perfetto trace for baseline.
3) Run baseline tasks and compute time breakdown.
4) Pick the top 2 bottlenecks and prototype improvements.
5) Re-run the same baseline and verify E2E time reduction and success rate.
