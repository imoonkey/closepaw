# Evaluation Design for Android Agent

## Problem Statement

We have a native Android Agent that runs on-device via accessibility services, triggered by ADB intents. We need to measure its core capability: **task success rate across diverse, reproducible tasks.**

The challenge: existing eval frameworks (AndroidWorld, MobileWorld) are Python-based and expect an agent that sends ADB commands from the host. Our agent runs *on the device itself*, receiving a goal string and autonomously executing actions through the accessibility service.

## Architecture Gap Analysis

| Aspect | Eval Frameworks Expect | Our Agent Does |
|--------|----------------------|----------------|
| **Agent location** | Host-side Python process | On-device Android app |
| **Action execution** | Host sends ADB tap/type/swipe | App uses AccessibilityService |
| **Control granularity** | Per-action `step()` calls | Autonomous loop, runs to completion |
| **Screen perception** | Host captures via ADB screencap | App reads accessibility tree directly |
| **Completion signal** | Agent returns `done=True` | Logcat event / trace artifact |

**Key insight**: The gap is in the *agent runner*, not the *task definitions or evaluation logic*. AndroidWorld's task setup (`initialize_task`) and evaluation (`is_successful`) are agent-agnostic — they check device state (SQLite DBs, filesystem, UI) regardless of how actions were performed.

## Recommended Strategy: Three Tiers

### Tier 0: Manual QA Smoke Tests (Immediate, ~1 day)

**Goal**: Establish a minimal regression safety net today.

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
# Run each task
./scripts/debug-run.sh --pro "Create a contact named John Doe with number 555-1234"

# After completion, manually check:
# 1. Did the agent report GoalAchieved?
# 2. Is the actual state correct? (contact exists, alarm set, etc.)
# 3. Review trace for quality: turn count, any loops, error recovery
```

**Recording Results**:
Create a simple spreadsheet or markdown table per run:

```markdown
| Task | Mode | Result | Turns | Notes |
|------|------|--------|-------|-------|
| Create contact | pro | PASS | 8 | Clean execution |
| Set alarm | pro | FAIL | 15 | Stuck on time picker |
```

**Value**: Fast, zero infrastructure, catches obvious regressions. Run before every significant change.

---

### Tier 1: AndroidWorld Bridge Runner (1-2 weeks)

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
│  5. score = task.is_successful(env)  ← evaluates result  │
│  6. Log results + pull trace artifacts                   │
│  7. task.tear_down(env)  ← cleanup                       │
│  8. Restore snapshot for next task                       │
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
    COMPLETION_PATTERNS = [
        "AgentSession: Emitted event: SessionCompleted",
        "AgentService: Session completed",
    ]
    ERROR_PATTERNS = [
        "AgentSession: Emitted event: SessionError",
        "AgentService: Session error",
    ]
    
    def __init__(self, serial: str, config: AgentConfig):
        self.serial = serial        # ADB device serial
        self.config = config        # agent_mode, llm_backend, api_key, etc.
        self.run_id = None
    
    def run_task(self, goal: str, max_wait_seconds: int = 300) -> RunResult:
        """Run agent on a task and wait for completion."""
        self.run_id = f"eval_{int(time.time())}"
        
        # Clear logcat
        adb(self.serial, "logcat -c")
        
        # Start agent via intent (mirrors debug-run.sh)
        intent_extras = self._build_intent_extras(goal)
        adb(self.serial, f"am start -n {self.PACKAGE}/.app.MainActivity "
            f"--activity-clear-top --activity-single-top {intent_extras}")
        
        # Poll logcat for completion
        start_time = time.time()
        while time.time() - start_time < max_wait_seconds:
            logcat = adb(self.serial, "logcat -d -s AgentSession AgentService")
            
            if any(p in logcat for p in self.COMPLETION_PATTERNS):
                return RunResult(
                    status="completed",
                    duration=time.time() - start_time,
                    run_id=self.run_id,
                )
            if any(p in logcat for p in self.ERROR_PATTERNS):
                return RunResult(
                    status="error",
                    duration=time.time() - start_time,
                    run_id=self.run_id,
                )
            time.sleep(2)  # Poll interval
        
        return RunResult(status="timeout", duration=max_wait_seconds, run_id=self.run_id)
    
    def pull_trace(self, output_dir: str):
        """Pull trace artifacts from device."""
        device_trace = f"/sdcard/Android/data/{self.PACKAGE}/files/inspection-trace/{self.run_id}"
        adb(self.serial, f"pull {device_trace} {output_dir}")
    
    def _build_intent_extras(self, goal: str) -> str:
        return (
            f'--es goal "{goal}" '
            f'--es agent_mode "{self.config.agent_mode}" '
            f'--es llm_backend "{self.config.llm_backend}" '
            f'--es api_key "{self.config.api_key}" '
            f'--ez auto_start true '
            f'--ez fresh_session true '
            f'--ez trace_enabled true '
            f'--es trace_run_id "{self.run_id}" '
        )
```

**2. Eval Runner (`runner.py`)**

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
            adb(self.serial, "input keyevent KEYCODE_HOME")
            time.sleep(0.5)
            
            # 3. Run our agent
            max_wait = int(task.complexity * 120)  # Scale timeout by complexity
            run_result = self.bridge.run_task(task.goal, max_wait_seconds=max_wait)
            
            # 4. Evaluate success using AndroidWorld's ground truth
            score = 0.0
            if run_result.status == "completed":
                score = task.is_successful(self.env)
            
            # 5. Pull trace for analysis
            trace_dir = f"{self.config.output_dir}/traces/{task_name}"
            self.bridge.pull_trace(trace_dir)
            
            # 6. Cleanup
            task.tear_down(self.env)
            
            return TaskResult(
                task_name=task_name,
                goal=task.goal,
                score=score,
                run_status=run_result.status,
                duration=run_result.duration,
                run_id=run_result.run_id,
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
            # Restore emulator snapshot for clean state
            self._restore_snapshot()
```

**3. Configuration (`config.py`)**

```python
@dataclass
class AgentConfig:
    agent_mode: str = "pro"           # "basic" or "pro"
    llm_backend: str = "openai"       # "openai" or "local"
    api_key: str = ""                 # From env var
    screenshot_input: bool = False

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
4. **Create snapshot** of clean state (`eval_clean`)
5. **Configure** API key, agent mode, etc.

```bash
# One-time setup
cd eval
pip install -r requirements.txt
python setup_emulator.py --install-apps --install-agent-apk ../app/build/outputs/apk/debug/app-debug.apk --create-snapshot

# Run evaluation
python runner.py --mode pro --tasks "all" --output results/$(date +%Y%m%d)
python runner.py --mode pro --apps "contacts,calendar,clock" --output results/quick_run
python runner.py --mode basic --tasks "CreateContact,SetAlarm" --output results/smoke
```

#### Completion Detection Robustness

The bridge monitors logcat for agent completion. To make this robust:

1. **Primary signal**: Logcat patterns (already used by `debug-run.sh`)
2. **Secondary signal**: Trace file existence — the agent writes `run_summary.json` on completion
3. **Tertiary signal**: Timeout with generous bounds (complexity × 120s)
4. **Cleanup**: Force-stop agent app between tasks to prevent state leakage

```python
def _check_completion(self) -> str:
    """Multi-signal completion detection."""
    # Signal 1: Logcat events
    logcat = adb("logcat -d -s AgentSession AgentService")
    if "SessionCompleted" in logcat:
        return "completed"
    if "SessionError" in logcat:
        return "error"
    
    # Signal 2: Trace summary file written
    trace_dir = f"/sdcard/Android/data/{PACKAGE}/files/inspection-trace/{self.run_id}"
    if adb(f"shell ls {trace_dir}/run_summary.json 2>/dev/null").strip():
        return "completed"
    
    return "running"
```

---

### Tier 2: CI Integration & Regression Detection (2-4 weeks, after Tier 1)

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

| Context | Tasks | Time | Purpose |
|---------|-------|------|---------|
| **Smoke** (per commit) | 10 hand-picked | ~15 min | Catch obvious breaks |
| **Core** (per PR) | 30 tasks across key apps | ~45 min | Capability coverage |
| **Full** (weekly/release) | All 116 AndroidWorld tasks | ~4-6 hours | Complete benchmark |
| **Focused** (after specific change) | Tasks for affected capability | ~10-30 min | Targeted validation |

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
- MCP tool integration evaluation

**Recommendation**: Only pursue after reaching >50% on AndroidWorld. MobileWorld is harder and the infrastructure cost is higher.

---

## Metrics & Analysis

### Primary Metrics

| Metric | Description | Target |
|--------|-------------|--------|
| **Task Success Rate (TSR)** | % of tasks scored 1.0 | Track over time |
| **Partial Success Rate** | % of tasks scored > 0.0 | Captures near-misses |
| **Completion Rate** | % of tasks where agent reported completion (vs timeout/error) | > 90% |
| **Average Turns** | Mean turns per task | Lower is better |
| **Efficiency** | TSR / avg_turns | Higher is better |

### Breakdown Dimensions

- **By app**: Which apps does the agent handle well/poorly?
- **By complexity**: Performance vs task complexity (1-step vs 10-step)
- **By action type**: Click, type, scroll, navigate — where are failures?
- **By mode**: Basic vs Pro — when does planning help?
- **By failure mode**: Timeout, wrong action, stuck in loop, wrong completion

### Trace-Based Analysis

Our agent's rich trace artifacts enable deeper analysis beyond pass/fail:

```python
def analyze_trace(trace_dir: str) -> TraceAnalysis:
    """Extract insights from agent trace artifacts."""
    summary = load_json(f"{trace_dir}/run_summary.json")
    events = load_jsonl(f"{trace_dir}/trace.jsonl")
    
    return TraceAnalysis(
        total_turns=summary["totalTurns"],
        tool_calls=summary["toolCalls"],
        tool_failures=summary["toolFailures"],
        had_loops=detect_repeated_actions(events),
        planning_quality=assess_todo_progression(events),  # Pro mode
        delegation_count=count_delegations(events),         # Pro mode
        error_recovery_attempts=count_retries(events),
    )
```

---

## Implementation Priority

```
Week 1:  Tier 0 — Manual QA smoke test suite
         Start Tier 1 — Set up AndroidWorld emulator, install apps + our APK

Week 2:  Tier 1 — Implement bridge.py + runner.py
         Run first automated eval, establish baseline

Week 3:  Tier 1 — Polish runner, add result analysis and comparison
         Create eval subsets (smoke, core, full)

Week 4:  Tier 2 — CI integration (GitHub Actions or local cron)
         Regression detection, result dashboards

Ongoing: Run full eval weekly, smoke on every PR
         Analyze failure patterns → drive agent improvements
```

## Key Design Decisions

### 1. Why reuse AndroidWorld's task library instead of writing our own eval?

- **Ground truth**: AndroidWorld's `is_successful()` methods check actual device state (SQLite, filesystem), not just whether the agent *thinks* it succeeded. This is far more reliable than screenshot comparison or agent self-report.
- **Reproducibility**: Dynamic task instantiation with random parameters creates millions of variations, preventing overfitting.
- **Comparability**: Results are directly comparable with published baselines (T3A, M3A, etc.).
- **Maintenance**: Task definitions and evaluation logic are maintained by the AndroidWorld project.

### 2. Why a custom runner instead of implementing their agent interface?

- **Granularity mismatch**: Their interface expects per-action `step()` calls. Our agent runs autonomously. Faking per-action steps would require intercepting every accessibility action — fragile and unnecessary.
- **Simplicity**: The custom runner is ~200 lines of Python. The agent interface adaptation would be much more complex.
- **Flexibility**: The custom runner can easily support both Basic and Pro modes, different LLM backends, and custom timeouts.

### 3. Why not MobileWorld first?

- **Infrastructure**: MobileWorld requires Docker-in-Docker with Mattermost, Mastodon, and Mall backends. AndroidWorld needs only an emulator.
- **Baseline first**: We need to establish performance on simpler tasks before tackling cross-app workflows.
- **Overlap**: Many MobileWorld tasks overlap with AndroidWorld capabilities. The unique value (MCP, user interaction) is less critical for core capability measurement.

### 4. What about the `complete_task` answer field?

Some AndroidWorld tasks are *information retrieval* tasks (e.g., "What is the next calendar event?"). They evaluate the agent's answer. Our agent's `complete_task` tool has an `answer` field. The bridge should:

1. Parse the `complete_task` tool call from the trace
2. Extract the `answer` field
3. Make it available to the evaluation function (some tasks check `env.interaction_cache` or similar)

This needs investigation during Tier 1 implementation — we may need to write the answer to a device file or broadcast it so the eval function can access it.

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| AndroidWorld API version incompatibility | Medium | High | Pin version, test setup early |
| Emulator flakiness (slow, crashes) | High | Medium | Snapshot restore, retry logic, timeouts |
| Agent completion detection unreliable | Low | High | Multi-signal detection (logcat + trace file) |
| Some tasks need apps not on our device | Low | Medium | Use AndroidWorld's setup script for all apps |
| Evaluation functions need host-side state | Medium | Medium | Investigate during Tier 1, adapt as needed |
| Token cost for full 116-task eval | Low | Low | ~$5-15 per full run at current GPT-4o pricing |

## Summary

The recommended approach is a **three-tier strategy** that balances immediate value with long-term rigor:

1. **Tier 0 (now)**: Manual QA with debug-run.sh — immediate regression safety net
2. **Tier 1 (weeks 1-3)**: AndroidWorld bridge runner — automated, reproducible, comparable benchmark
3. **Tier 2 (week 4+)**: CI integration — continuous regression detection

The core technical insight is that we can **reuse AndroidWorld's task definitions and evaluation logic** while **replacing only the agent runner** with our ADB-intent-based bridge. This gives us ground-truth evaluation with minimal integration effort.
