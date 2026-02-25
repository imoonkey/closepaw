# Evaluation Design for Android Agent

## Problem Statement

We have a native Android Agent that runs on-device via accessibility services, triggered by ADB intents. We need to measure its core capability: **task success rate across diverse, reproducible tasks.**

The challenge: existing eval frameworks (AndroidWorld, MobileWorld) are Python-based and expect an agent that sends ADB commands from the host. Our agent runs *on the device itself*, receiving a goal string and autonomously executing actions through the accessibility service.

## Architecture Gap Analysis

| Aspect | Eval Frameworks Expect | Our Agent Does |
|--------|----------------------|----------------|
| **Agent location** | Host-side Python process | On-device Android app |
| **Action execution** | Host sends ADB tap/type/swipe | App uses AccessibilityService |
| **Control granularity** | Per-action `step()` calls | Autonomous ReAct loop, runs to completion |
| **Screen perception** | Host captures via ADB screencap | App reads accessibility tree directly (+ optional screenshot) |
| **Completion signal** | Agent returns `done=True` | Logcat events (`TaskCompleted` / `SessionCompleted`) + trace artifacts |

**Key insight**: The gap is in the *agent runner*, not the *task definitions or evaluation logic*. AndroidWorld's task setup (`initialize_task`) and evaluation (`is_successful`) are agent-agnostic — they check device state (SQLite DBs, filesystem, UI) regardless of how actions were performed.

## Recommended Strategy: Three Tiers

### Tier 0: Manual QA Smoke Tests

**Goal**: Establish a minimal regression safety net.

**Approach**: Curate 10-15 tasks across core capabilities, run via `debug-run.sh`, manually verify.

**Task Selection** (covering key capability axes):

| # | Task | App | Capabilities Tested |
|---|------|-----|---------------------|
| 1 | Open Settings | Settings | App launch, navigation |
| 2 | Turn on Wi-Fi | Settings | Toggle, state change |
| 3 | Create a contact "John Doe" with number 555-1234 | Contacts | Text input, form filling |
| 4 | Set an alarm for 7:30 AM | Clock | Time picker, confirm |
| 5 | Send SMS "Hello" to 555-0000 | Messages | Cross-field input, send action |
| 6 | Open Chrome and search "weather today" | Chrome | App switch, text input, search |
| 7 | Take a photo | Camera | App launch, tap action |
| 8 | Create a calendar event "Meeting" tomorrow at 2pm | Calendar | Date/time picking, form |
| 9 | Open Gmail, read the first email | Gmail | Navigation, list interaction |
| 10 | Turn on Do Not Disturb | Settings | Deep navigation, toggle |
| 11 | Open YouTube and search for "Kotlin tutorial" | YouTube | App + web hybrid |
| 12 | Change display brightness to maximum | Settings | Slider interaction |

**Execution**:
```bash
# Run each task (default: pro mode, accessibility_only perception)
./scripts/debug-run.sh "Create a contact named John Doe with number 555-1234"

# Or explicitly specify mode and model
./scripts/debug-run.sh --pro --model minimax-m2.5 "Set an alarm for 7:30 AM"

# After completion, manually check:
# 1. Did the agent report GoalAchieved? (check stop_reason in run_summary.json)
# 2. Is the actual state correct? (contact exists, alarm set, etc.)
# 3. Review trace for quality: turn count, any loops, error recovery
```

**Recording Results**:
Create a simple spreadsheet or markdown table per run:

```markdown
| Task | Mode | Model | Result | Turns | Stop Reason | Notes |
|------|------|-------|--------|-------|-------------|-------|
| Create contact | pro | minimax-m2.5 | PASS | 8 | GoalAchieved | Clean execution |
| Set alarm | pro | minimax-m2.5 | FAIL | 15 | MaxTurnsReached | Stuck on time picker |
```

**Value**: Fast, zero infrastructure, catches obvious regressions. Run before every significant change.

---

### Tier 1: AndroidWorld Bridge Runner

**Goal**: Automated, reproducible evaluation against AndroidWorld's 116 tasks with ground-truth evaluation.

**Why AndroidWorld over MobileWorld**:
- Simpler setup (no self-hosted backends like Mattermost/Mastodon)
- Lighter infrastructure (emulator only, no Docker-in-Docker)
- Well-established benchmark with clear baselines
- 116 tasks is sufficient for capability measurement
- MobileWorld can come later for advanced scenarios

#### Design: Custom Runner with Task Library Reuse

Instead of implementing AndroidWorld's `EnvironmentInteractingAgent` interface (which expects per-action `step()` calls), we write a **custom runner** that reuses AndroidWorld's task infrastructure:

```
┌─────────────────────────────────────────────────────────┐
│                    Eval Runner (Python)                   │
│                                                           │
│  1. Load task from AndroidWorld task library              │
│  2. task.initialize_task(env)  ← sets up device state    │
│  3. Trigger native agent via ADB intent                  │
│  4. Poll for completion (logcat / trace)                 │
│  5. Extract answer from trace (for info-retrieval tasks) │
│  6. score = task.is_successful(env)  ← evaluates result  │
│  7. Log results + pull trace artifacts                   │
│  8. task.tear_down(env)  ← cleanup                       │
│  9. Force-stop agent + restore snapshot for next task    │
└─────────────────────────────────────────────────────────┘
```

#### File Structure

```
eval/
├── README.md                    # Setup & usage guide
├── requirements.txt             # Python deps (android_world, etc.)
├── setup_emulator.py            # One-time emulator + app setup
├── runner.py                    # Main eval runner
├── bridge.py                    # Agent bridge (ADB intent + completion monitor)
├── config.py                    # Eval configuration
├── trace_parser.py              # Parse trace artifacts (run_summary, complete_task answer)
├── results/
│   └── {timestamp}/
│       ├── summary.json         # Aggregate scores
│       ├── per_task.json        # Per-task results
│       └── traces/              # Pulled trace artifacts
└── analysis/
    ├── report.py                # Generate human-readable reports
    └── compare.py               # Compare runs (regression detection)
```

#### Core Components

**1. Agent Bridge (`bridge.py`)**

The bridge triggers our native agent and monitors for completion:

```python
class NativeAgentBridge:
    """Bridges between eval runner and on-device Android Agent."""

    PACKAGE = "com.moonkey.androidagent"
    ACTIVITY = f"{PACKAGE}/.app.MainActivity"

    # Mirrors debug-run.sh line 409 — must stay in sync
    COMPLETION_PATTERNS = [
        "AgentSession: Emitted event: TaskCompleted",
        "AgentService: Received event: TaskCompleted",
        "AgentSession: Emitted event: SessionCompleted",
        "AgentService: Session completed",
        "AgentService: Task completed",
    ]
    ERROR_PATTERNS = [
        "AgentSession: Emitted event: SessionError",
        "AgentService: Session error",
        "Fatal error",
    ]

    def __init__(self, serial: str, config: AgentConfig):
        self.serial = serial
        self.config = config
        self.run_id = None

    def run_task(self, goal: str, max_wait_seconds: int = 300) -> RunResult:
        """Run agent on a task and wait for completion."""
        self.run_id = f"eval_{int(time.time())}"

        # Clear logcat
        adb(self.serial, "logcat -c")

        # Start agent via intent (mirrors debug-run.sh)
        intent_extras = self._build_intent_extras(goal)
        adb(self.serial, f"am start -n {self.ACTIVITY} "
            f"--activity-clear-top --activity-single-top {intent_extras}")

        # Poll logcat for completion
        start_time = time.time()
        while time.time() - start_time < max_wait_seconds:
            status = self._check_completion()
            if status != "running":
                return RunResult(
                    status=status,
                    duration=time.time() - start_time,
                    run_id=self.run_id,
                )
            time.sleep(2)

        return RunResult(status="timeout", duration=max_wait_seconds, run_id=self.run_id)

    def force_stop(self):
        """Force-stop agent app to prevent state leakage between tasks."""
        adb(self.serial, f"am force-stop {self.PACKAGE}")

    def pull_trace(self, output_dir: str):
        """Pull trace artifacts from device."""
        device_trace_dir = self._device_trace_dir()
        adb(self.serial, f"pull {device_trace_dir} {output_dir}")

    def _device_trace_dir(self) -> str:
        return f"/sdcard/Android/data/{self.PACKAGE}/files/inspection-trace/{self.run_id}"

    def _check_completion(self) -> str:
        """Multi-signal completion detection."""
        # Signal 1: Logcat events (primary — matches debug-run.sh)
        logcat = adb(self.serial, "logcat -d -s AgentSession AgentService")
        if any(p in logcat for p in self.COMPLETION_PATTERNS):
            return "completed"
        if any(p in logcat for p in self.ERROR_PATTERNS):
            return "error"

        # Signal 2: Trace run_summary artifact written (secondary)
        # run_summary.json lives in artifacts/run_summary/ within the trace dir
        trace_dir = self._device_trace_dir()
        summary_path = f"{trace_dir}/artifacts/run_summary/run_summary.json"
        if adb(self.serial, f"shell ls {summary_path} 2>/dev/null").strip():
            return "completed"

        return "running"

    def _build_intent_extras(self, goal: str) -> str:
        """Build ADB intent extras matching MainActivity's expected format.

        Ref: MainActivity.kt EXTRA_* constants, MainActivityIntentPayload.kt
        """
        extras = (
            f"--es goal '{_shell_escape(goal)}' "
            f"--es agent_mode '{self.config.agent_mode}' "
            f"--es llm_backend '{self.config.llm_backend}' "
            f"--es perception_mode '{self.config.perception_mode}' "
            f"--es platform_mode '{self.config.platform_mode}' "
            f"--es main_model '{self.config.main_model}' "
            f"--ez auto_start true "
            f"--ez fresh_session true "
            f"--ez debug_mode {str(self.config.debug_mode).lower()} "
            f"--ez trace_enabled true "
            f"--es trace_run_id '{self.run_id}' "
        )

        # Add executor model if configured (for Pro mode planner/executor split)
        if self.config.executor_model:
            extras += f"--es executor_model '{self.config.executor_model}' "

        # Add API keys (only non-empty ones)
        if self.config.api_key:
            extras += f"--es api_key '{self.config.api_key}' "
        if self.config.openrouter_api_key:
            extras += f"--es openrouter_api_key '{self.config.openrouter_api_key}' "
        if self.config.novita_api_key:
            extras += f"--es novita_api_key '{self.config.novita_api_key}' "

        return extras
```

**2. Trace Parser (`trace_parser.py`)**

Parses the trace artifacts to extract completion details and the `complete_task` answer:

```python
@dataclass
class TraceAnalysis:
    """Parsed trace summary + answer extraction."""
    # From artifacts/run_summary/run_summary.json
    stop_reason: str          # "GoalAchieved", "MaxTurnsReached", "Error", etc.
    turns_executed: int
    turns_started: int
    turns_completed: int
    turn_errors: int
    llm_requests: int
    llm_responses: int
    tool_calls: int
    tool_successes: int
    tool_failures: int
    duration_ms: int

    # Extracted from trace.jsonl tool_call events
    answer: str | None = None       # complete_task answer field
    task_status: str | None = None  # complete_task status: "success" or "failure"

    # Derived analysis
    had_loops: bool = False
    delegation_count: int = 0       # Pro mode delegate_task calls


def parse_trace(trace_dir: str) -> TraceAnalysis:
    """Parse trace artifacts from a pulled trace directory.

    Expected structure:
        trace_dir/
        ├── trace.jsonl          # Event log (JSONL)
        ├── meta.json            # Run metadata
        └── artifacts/
            ├── run_summary/run_summary.json
            ├── tool_call_args/  # Tool call argument JSONs
            └── ...
    """
    # 1. Parse run summary
    summary = _load_json(f"{trace_dir}/artifacts/run_summary/run_summary.json")

    # 2. Parse trace events for complete_task answer and analysis
    events = _load_jsonl(f"{trace_dir}/trace.jsonl")
    answer, task_status = _extract_complete_task_answer(events, trace_dir)

    return TraceAnalysis(
        stop_reason=summary["stop_reason"],
        turns_executed=summary["turns_executed"],
        turns_started=summary["turns_started"],
        turns_completed=summary["turns_completed"],
        turn_errors=summary["turn_errors"],
        llm_requests=summary["llm_requests"],
        llm_responses=summary["llm_responses"],
        tool_calls=summary["tool_calls"],
        tool_successes=summary["tool_successes"],
        tool_failures=summary["tool_failures"],
        duration_ms=summary["duration_ms"],
        answer=answer,
        task_status=task_status,
        had_loops=_detect_repeated_actions(events),
        delegation_count=_count_delegations(events),
    )


def _extract_complete_task_answer(events: list[dict], trace_dir: str) -> tuple[str | None, str | None]:
    """Extract the answer from the complete_task tool call.

    The complete_task tool has two required parameters:
      - status: "success" or "failure"
      - answer: string response to return to user

    We find it by looking for tool_call events where data.name == "complete_task",
    then reading the corresponding tool_call_args artifact.
    """
    for event in events:
        if event.get("type") == "tool_call":
            data = event.get("data", {})
            if data.get("name") == "complete_task":
                # Try to get args from artifact reference
                for artifact in event.get("artifacts", []):
                    if artifact.get("kind") == "tool_call_args":
                        args_path = f"{trace_dir}/{artifact['path']}"
                        args = _load_json(args_path)
                        return args.get("answer"), args.get("status")
    return None, None
```

**3. Eval Runner (`runner.py`)**

```python
class EvalRunner:
    """Runs AndroidWorld tasks against our native agent."""

    def __init__(self, env: AsyncEnv, bridge: NativeAgentBridge, config: EvalConfig):
        self.env = env
        self.bridge = bridge
        self.config = config
        self.results = []

    def run_suite(self, tasks: list[TaskEval]) -> SuiteResult:
        for task in tasks:
            result = self.run_single_task(task)
            self.results.append(result)
            self._save_incremental()  # Save after each task (crash-resilient)
        return self._compile_results()

    def run_single_task(self, task: TaskEval) -> TaskResult:
        task_name = task.__class__.__name__
        logger.info(f"Running task: {task_name}")

        try:
            # 1. Setup: initialize task state on device
            task.initialize_task(self.env)
            time.sleep(1)  # Let state settle

            # 2. Go home (clean starting point)
            adb(self.bridge.serial, "input keyevent KEYCODE_HOME")
            time.sleep(0.5)

            # 3. Run our agent
            max_wait = int(task.complexity * 120)  # Scale timeout by complexity
            run_result = self.bridge.run_task(task.goal, max_wait_seconds=max_wait)

            # 4. Pull trace and parse for answer + metrics
            trace_dir = f"{self.config.output_dir}/traces/{task_name}"
            self.bridge.pull_trace(trace_dir)
            trace = parse_trace(trace_dir)

            # 5. For information-retrieval tasks, inject answer into env
            if trace.answer:
                self._inject_answer(task, trace.answer)

            # 6. Evaluate success using AndroidWorld's ground truth
            score = 0.0
            if run_result.status == "completed":
                score = task.is_successful(self.env)

            # 7. Cleanup
            task.tear_down(self.env)

            return TaskResult(
                task_name=task_name,
                goal=task.goal,
                score=score,
                run_status=run_result.status,
                stop_reason=trace.stop_reason,
                task_status=trace.task_status,
                duration=run_result.duration,
                run_id=run_result.run_id,
                turns=trace.turns_executed,
                tool_calls=trace.tool_calls,
                tool_failures=trace.tool_failures,
                answer=trace.answer,
                complexity=task.complexity,
            )

        except Exception as e:
            logger.error(f"Task {task_name} failed: {e}")
            return TaskResult(
                task_name=task_name,
                goal=task.goal,
                score=0.0,
                run_status="runner_error",
                error=str(e),
            )
        finally:
            # Force-stop agent app to prevent state leakage
            self.bridge.force_stop()
            # Restore emulator snapshot for clean state
            self._restore_snapshot()

    def _inject_answer(self, task: TaskEval, answer: str):
        """Make the agent's answer available to AndroidWorld's evaluation.

        Some tasks (info-retrieval) check for the agent's answer via
        env.interaction_cache or similar mechanisms. This requires
        investigation per-task during implementation.

        Possible approaches:
        1. Write answer to a device file that eval reads
        2. Set it via ADB broadcast that the eval environment picks up
        3. Directly populate env.interaction_cache if the API allows
        """
        # TODO: Implement based on AndroidWorld's answer evaluation mechanism.
        # Key investigation: how does AndroidWorld's is_successful() access
        # the agent's answer for info-retrieval tasks?
        pass
```

**4. Configuration (`config.py`)**

```python
@dataclass
class AgentConfig:
    """Agent configuration passed via ADB intent extras.

    Ref: MainActivity.kt EXTRA_* constants
    Ref: MainActivityIntentPayload.kt for normalization
    Ref: SessionConfig.kt for enum definitions
    """
    # Agent mode
    agent_mode: str = "pro"                # "basic" or "pro"

    # LLM configuration
    llm_backend: str = "openai"            # "openai", "openrouter", "novita", "local"
    main_model: str = "minimax-m2.5"       # Model key from llm_models.json
    executor_model: str | None = None      # Optional: separate model for executor agents

    # Perception
    perception_mode: str = "accessibility_only"  # "accessibility_only", "screenshot_only", "hybrid"

    # Platform
    platform_mode: str = "accessibility"   # "accessibility" or "virtual_display"

    # API keys (from environment variables)
    api_key: str = ""                      # OPENAI_API_KEY
    openrouter_api_key: str = ""           # OPENROUTER_API_KEY
    novita_api_key: str = ""               # NOVITA_API_KEY

    # Debug
    debug_mode: bool = False

    @classmethod
    def from_env(cls, **overrides) -> "AgentConfig":
        """Create config with API keys from environment variables."""
        return cls(
            api_key=os.environ.get("OPENAI_API_KEY", ""),
            openrouter_api_key=os.environ.get("OPENROUTER_API_KEY", ""),
            novita_api_key=os.environ.get("NOVITA_API_KEY", ""),
            **overrides,
        )


@dataclass
class EvalConfig:
    output_dir: str = "eval/results/{timestamp}"
    task_filter: list[str] | None = None     # Run specific tasks
    app_filter: list[str] | None = None      # Run tasks for specific apps
    max_tasks: int | None = None             # Limit for quick runs
    snapshot_name: str = "eval_clean"        # AVD snapshot to restore
    parallel: bool = False                   # Future: parallel eval on multiple emulators
```

#### Setup Steps

1. **Create Android emulator** matching AndroidWorld's spec (Pixel 6, API 33)
2. **Install AndroidWorld apps** via their setup script
3. **Install our APK** on the same emulator
4. **Grant accessibility service permissions** (required for agent operation)
5. **Create snapshot** of clean state (`eval_clean`)
6. **Configure** API keys via environment variables

```bash
# One-time setup
cd eval
pip install -r requirements.txt
python setup_emulator.py \
    --install-apps \
    --install-agent-apk ../app/build/outputs/apk/debug/app-debug.apk \
    --grant-a11y-permission \
    --create-snapshot

# Run evaluation
python runner.py --mode pro --model minimax-m2.5 --tasks "all" --output results/$(date +%Y%m%d)
python runner.py --mode pro --apps "contacts,calendar,clock" --output results/quick_run
python runner.py --mode basic --tasks "CreateContact,SetAlarm" --output results/smoke

# Compare runs
python analysis/compare.py results/20260215 results/20260217
```

#### Completion Detection Robustness

The bridge monitors logcat for agent completion. To make this robust:

1. **Primary signal**: Logcat patterns (kept in sync with `debug-run.sh` line 409)
2. **Secondary signal**: Trace `run_summary.json` existence at `artifacts/run_summary/run_summary.json`
3. **Tertiary signal**: Timeout with generous bounds (complexity x 120s)
4. **Cleanup**: Force-stop agent app between tasks to prevent state leakage

**Session completion signals and their meanings** (from `CompletionReason.kt`):

| CompletionReason | Meaning | Eval Interpretation |
|-----------------|---------|---------------------|
| `GOAL_ACHIEVED` | Agent believes task is done | Check ground truth |
| `MAX_TURNS` | Hit turn limit | Likely failure |
| `TASK_IMPOSSIBLE` | Agent gave up | Definite failure |
| `USER_STOPPED` | External stop signal | Runner error |
| `ERROR` | Runtime error | Runner error |
| `INTERRUPTED` | Session interrupted | Runner error |

---

### Tier 2: CI Integration & Regression Detection

**Goal**: Automated eval on every significant change, with regression alerts.

#### CI Pipeline

```yaml
# .github/workflows/eval.yml (conceptual)
eval:
  trigger:
    - manual
    - weekly schedule
    - on PR with label "needs-eval"

  steps:
    - Build APK
    - Start emulator (cached snapshot)
    - Install APK
    - Run eval suite (subset for PR, full for weekly)
    - Compare against baseline
    - Post results to PR / dashboard
```

#### Regression Detection

```python
def compare_runs(baseline: SuiteResult, current: SuiteResult) -> RegressionReport:
    """Compare two eval runs to detect regressions."""
    regressions = []
    improvements = []

    for task_name in baseline.tasks:
        base_score = baseline.tasks[task_name].score
        curr_score = current.tasks[task_name].score

        if curr_score < base_score:
            regressions.append(Regression(task_name, base_score, curr_score))
        elif curr_score > base_score:
            improvements.append(Improvement(task_name, base_score, curr_score))

    return RegressionReport(
        baseline_overall=baseline.overall_score,
        current_overall=current.overall_score,
        regressions=regressions,
        improvements=improvements,
        is_regression=len(regressions) > 0 and
                      current.overall_score < baseline.overall_score - 0.02,
    )
```

#### Eval Subsets for Different Contexts

| Context | Tasks | Purpose |
|---------|-------|---------|
| **Smoke** (per commit) | 10 hand-picked | Catch obvious breaks |
| **Core** (per PR) | 30 tasks across key apps | Capability coverage |
| **Full** (weekly/release) | All 116 AndroidWorld tasks | Complete benchmark |
| **Focused** (after specific change) | Tasks for affected capability | Targeted validation |

---

### Tier 3: MobileWorld Integration (Optional, Future)

**When**: After Tier 1 is stable and we want to evaluate:
- Cross-app workflows (62% of MobileWorld tasks)
- Long-horizon tasks (28+ steps average)
- Agent-user interaction capabilities

**Additional complexity**:
- Docker-in-Docker for Mattermost/Mastodon backends
- AVD snapshot management for complex initial states
- Backend state verification (DB queries)

**Recommendation**: Only pursue after reaching >50% on AndroidWorld. MobileWorld is harder and the infrastructure cost is higher.

---

## Metrics & Analysis

### Primary Metrics

| Metric | Description | Target |
|--------|-------------|--------|
| **Task Success Rate (TSR)** | % of tasks scored 1.0 | Track over time |
| **Partial Success Rate** | % of tasks scored > 0.0 | Captures near-misses |
| **Completion Rate** | % of tasks where agent reported GoalAchieved (vs timeout/error) | > 90% |
| **Average Turns** | Mean `turns_executed` per task | Lower is better |
| **Tool Failure Rate** | `tool_failures / tool_calls` | Lower is better |
| **Efficiency** | TSR / avg_turns | Higher is better |

### Breakdown Dimensions

- **By app**: Which apps does the agent handle well/poorly?
- **By complexity**: Performance vs task complexity (1-step vs 10-step)
- **By action type**: Click, type, scroll, navigate — where are failures?
- **By mode**: Basic vs Pro — when does planning/delegation help?
- **By model**: Performance across different LLM backends/models
- **By stop reason**: GoalAchieved vs MaxTurnsReached vs TaskImpossible vs Error
- **By failure mode**: Timeout, wrong action, stuck in loop, wrong completion, tool failures

### Trace-Based Analysis

Our agent's rich trace artifacts enable deeper analysis beyond pass/fail:

```python
def analyze_trace(trace_dir: str) -> TraceAnalysis:
    """Extract insights from agent trace artifacts.

    Trace directory structure:
        trace_dir/
        ├── trace.jsonl                      # Event log
        ├── meta.json                        # Run metadata (device, config, etc.)
        └── artifacts/
            ├── run_summary/run_summary.json # Aggregate metrics
            ├── raw_a11y_tree/               # Per-turn accessibility trees
            ├── screenshot/                  # Per-turn screenshots
            ├── llm_full_prompt/             # Full LLM prompts
            ├── llm_response_text/           # LLM responses
            ├── tool_call_args/              # Tool call arguments
            └── tool_result/                 # Tool execution results
    """
    trace = parse_trace(trace_dir)
    events = _load_jsonl(f"{trace_dir}/trace.jsonl")

    return TraceAnalysis(
        # From run_summary.json
        stop_reason=trace.stop_reason,
        turns_executed=trace.turns_executed,
        tool_calls=trace.tool_calls,
        tool_failures=trace.tool_failures,
        duration_ms=trace.duration_ms,

        # Derived from trace.jsonl events
        had_loops=_detect_repeated_actions(events),
        delegation_count=_count_delegations(events),       # Pro mode
        error_recovery_attempts=_count_retries(events),
    )
```

---

## Implementation Priority

```
Phase 1:  Tier 0 — Manual QA smoke test suite
          Start Tier 1 — Set up AndroidWorld emulator, install apps + our APK

Phase 2:  Tier 1 — Implement bridge.py, trace_parser.py, runner.py
          Run first automated eval, establish baseline

Phase 3:  Tier 1 — Polish runner, add result analysis and comparison
          Create eval subsets (smoke, core, full)
          Investigate answer injection for info-retrieval tasks

Phase 4:  Tier 2 — CI integration (GitHub Actions or local cron)
          Regression detection, result dashboards

Ongoing:  Run full eval weekly, smoke on every PR
          Analyze failure patterns → drive agent improvements
```

## Key Design Decisions

### 1. Why reuse AndroidWorld's task library instead of writing our own eval?

- **Ground truth**: AndroidWorld's `is_successful()` methods check actual device state (SQLite, filesystem), not just whether the agent *thinks* it succeeded. This is far more reliable than screenshot comparison or agent self-report.
- **Reproducibility**: Dynamic task instantiation with random parameters creates millions of variations, preventing overfitting.
- **Comparability**: Results are directly comparable with published baselines (T3A, M3A, etc.).
- **Maintenance**: Task definitions and evaluation logic are maintained by the AndroidWorld project.

### 2. Why a custom runner instead of implementing their agent interface?

- **Granularity mismatch**: Their interface expects per-action `step()` calls. Our agent runs an autonomous ReAct loop. Faking per-action steps would require intercepting every accessibility action — fragile and unnecessary.
- **Simplicity**: The custom runner is ~300 lines of Python. The agent interface adaptation would be much more complex.
- **Flexibility**: The custom runner can easily support both Basic and Pro modes, different LLM backends/models, perception modes, and custom timeouts.

### 3. Why not MobileWorld first?

- **Infrastructure**: MobileWorld requires Docker-in-Docker with Mattermost, Mastodon, and Mall backends. AndroidWorld needs only an emulator.
- **Baseline first**: We need to establish performance on simpler tasks before tackling cross-app workflows.
- **Overlap**: Many MobileWorld tasks overlap with AndroidWorld capabilities. The unique value (user interaction) is less critical for core capability measurement.

### 4. The `complete_task` answer field

Some AndroidWorld tasks are *information retrieval* tasks (e.g., "What is the next calendar event?"). They evaluate the agent's answer.

Our agent's `complete_task` tool (defined in `tool/impl/CompleteTaskTool.kt`) accepts:
- `status`: required, `"success"` or `"failure"`
- `answer`: required, string response text

The bridge extracts this from the trace via `trace_parser.py`:
1. Parse `trace.jsonl` for `tool_call` events where `data.name == "complete_task"`
2. Read the corresponding `tool_call_args` artifact for the `answer` and `status` fields
3. Inject the answer into AndroidWorld's evaluation context

**Open question**: How exactly does `is_successful()` access the agent's answer for info-retrieval tasks? This requires investigation during implementation — the answer may need to be written to a device file, set via broadcast, or injected into `env.interaction_cache`.

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| AndroidWorld API version incompatibility | Medium | High | Pin version, test setup early |
| Emulator flakiness (slow, crashes) | High | Medium | Snapshot restore, retry logic, timeouts |
| Agent completion detection unreliable | Low | High | Multi-signal detection (logcat + trace artifact) |
| Some tasks need apps not on our device | Low | Medium | Use AndroidWorld's setup script for all apps |
| Evaluation functions need host-side state | Medium | Medium | Investigate during implementation, adapt as needed |
| Accessibility service permission lost after snapshot restore | Medium | Medium | Verify permission in setup, re-grant if needed |
| Info-retrieval task answer injection | Medium | Medium | Investigate AndroidWorld's answer evaluation API early |

## Summary

The recommended approach is a **three-tier strategy** that balances immediate value with long-term rigor:

1. **Tier 0**: Manual QA with debug-run.sh — immediate regression safety net
2. **Tier 1**: AndroidWorld bridge runner — automated, reproducible, comparable benchmark
3. **Tier 2**: CI integration — continuous regression detection

The core technical insight is that we can **reuse AndroidWorld's task definitions and evaluation logic** while **replacing only the agent runner** with our ADB-intent-based bridge. This gives us ground-truth evaluation with minimal integration effort.
