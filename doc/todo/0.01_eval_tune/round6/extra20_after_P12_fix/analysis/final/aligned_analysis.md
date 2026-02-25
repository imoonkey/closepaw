# Extra 20 Tasks — Aligned Root Cause Analysis & Recommendations

## Overview

| Metric | Value |
|---|---|
| Model | qwen3.5 (qwen/qwen3.5-plus-02-15) |
| Perception | accessibility_only |
| Tasks | 20 unique tasks across 3 runs (22 attempts including 2 infra retries) |
| Pass Rate | 1/20 (5.0%) — ExpenseDeleteDuplicates only |

---

## Agreed Root Cause Taxonomy

| Bucket | Count | Tasks |
|---|---|---|
| **ASK_USER_BLOCKED** | 6 | SimpleCalendarAddOneEventInTwoWeeks, SimpleCalendarAddOneEventRelativeDay, SimpleCalendarAddOneEventTomorrow, SimpleCalendarAddRepeatingEvent, SimpleCalendarDeleteEvents, SimpleCalendarDeleteEventsOnRelativeDay |
| **Infra/Environment** | 2 (4 attempts) | AudioRecorderRecordAudio ×2 (app not installed), ExpenseAddMultipleFromGallery ×2 (ADB timeout) |
| **Perception Hard Ceiling** | 2 | BrowserDraw (canvas color values invisible to a11y), BrowserMaze (maze grid invisible to a11y) |
| **Cognitive / Reasoning Error** | 3 | AudioRecorderRecordAudioWithFileName (skipped filename), CameraTakeVideo (never switched to video mode), ClockStopWatchPausedVerify (confused stopped vs paused — agent never started stopwatch) |
| **Turn Budget Exhaustion** | 3 | ExpenseAddMultiple (2/3 done, 3rd ~90%), SimpleCalendarAddOneEvent (app resolution + date picker), SimpleCalendarDeleteOneEvent (app resolution + date picker + search) |
| **Cross-App Perception + Strategy** | 1 | ExpenseAddMultipleFromMarkor (Markor content extraction weak + strategy thrash near deadline) |
| **Eval Visibility / Validator Mismatch (likely false negatives)** | 2 | ClockStopWatchRunning, ContactsNewContactDraft |
| **Success** | 1 | ExpenseDeleteDuplicates |

### Alignment Update: Root Cause Granularity

**Decision**: Use finer-grained taxonomy for actionability; keep Codex 4-bucket taxonomy only as a secondary roll-up metric.

Why:
- Perception → needs screenshot/vision (no prompt fix possible)
- Cognitive → addressable via prompt engineering
- Turn Budget → addressable via budget increase or overhead reduction
- Eval visibility mismatch → bridge/eval integration fix, not model tuning

---

## Agreed Cross-Cutting Problems

### 1. ASK_USER_BLOCKED (6 tasks, 30% of failures)

**Consensus**: Agent calls `ask_user` for tasks with relative/ambiguous dates, which is blocked in eval. All 6 tasks had zero productive turns.

**Notable**: SimpleCalendarAddRepeatingEvent had an absolute date ("October 29, 2023") but still triggered ask_user — the model over-applies the ask_user reflex.

**Additional finding from Codex trace (SimpleCalendarAddOneEventInTwoWeeks)**: The agent actually executed 4 turns (open_app failed, opened Calendar, waited, then ask_user) despite per_task.jsonl reporting turns=0. This means ask_user was preceded by the same app resolution failure pattern.

### 2. `open_app("Simple Calendar Pro")` Resolution Failure

**Consensus**: The open_app resolver doesn't match "Simple Calendar Pro" to the installed app. Agent falls back to "Calendar" (Google Calendar), hits GMS sign-in, wastes 12-13 turns finding Simple Calendar Pro via the app drawer.

**Impact**: Affects every calendar task that actually executed (SimpleCalendarAddOneEvent, SimpleCalendarDeleteOneEvent, and all ASK_USER_BLOCKED ones burned 2-4 turns on this before asking).

### 3. Perception Limitations with accessibility_only

**Consensus**: BrowserDraw and BrowserMaze are **impossible** without screenshot-based perception. Canvas elements expose zero visual content through the accessibility tree.

**Aligned view**: This is mixed perception + strategy. The Markor EditText visibility ceiling caused extraction failure, then the agent oscillated between apps late (turns 25-30), exhausting budget without execution.

### 4. Premature GoalAchieved / Cognitive Errors

**Consensus**: Multiple tasks where the agent declared success without completing all goal requirements:

| Task | What was missed | Aligned bucket |
|---|---|---|
| AudioRecorderRecordAudioWithFileName | Never typed the filename | Cognitive |
| CameraTakeVideo | Never switched to video mode | Cognitive |
| ClockStopWatchPausedVerify | Declared "already stopped" instead of Start→Pause | Cognitive |

### 5. Turn Budget Exhaustion for Near-Complete Tasks

**Consensus**: Three tasks hit MaxTurnsReached while making real progress.

| Task | Progress when stopped | Turns wasted on overhead |
|---|---|---|
| ExpenseAddMultiple | 2/3 expenses saved, 3rd at 90% | 4 turns (write_todos) |
| SimpleCalendarAddOneEvent | Title/description done, date at Aug 2023 (needed Oct) | 13 turns (app resolution) + 2 turns (write_todos) |
| SimpleCalendarDeleteOneEvent | Searched event, near delete | 13 turns (app resolution) |

### 6. Eval Visibility Mismatch (Likely False Negatives)

**Key evidence**:

1. Bridge currently strips all other accessibility services and keeps only AgentService before each run:
   - `eval/aw_bridge/native_agent_bridge.py` lines 259-267.
2. For `ContactsNewContactDraft`, trace pre-complete state shows `Grace`, `Taylor`, `799-802-1530`, and `Delete Work Phone`, but runner scoring logs:
   - `Missing 'first' UI element`, `Missing 'last' UI element`, `Missing 'phone' UI element`, `Missing 'phone_label' UI element` (runner.log lines 728-731).
3. `ClockStopWatchRunning` pre-complete a11y contains `Pause` and `Lap` (matching validator intent), yet scored 0.0.

**Aligned interpretation**: bridge stripping AccessibilityForwarder causes AndroidWorld's UI-state validators to read an empty/stale tree, producing false negatives. This affects at least ClockStopWatchRunning and ContactsNewContactDraft.

**Note on ClockStopWatchPausedVerify (dual fault)**: The validator visibility issue exists here too (`Start present: False, Stopwatch: 0` in runner.log despite Start button being on-screen). However, the agent also made a genuine cognitive error — it declared "already stopped" without ever pressing Start then Pause. Even with a fixed validator, this task would still score 0.0. Primary bucket: Cognitive. The eval visibility issue is an independent, secondary concern.

---

## Agreed Recommendations

### P0 — Immediate (would unblock 8+ tasks)

1. **Fix `open_app` resolver for "Simple Calendar Pro"** → map to `com.simplemobiletools.calendar.pro`. Saves 12-13 turns per calendar task.
    - [Note: 可以做] 
2. **Block `ask_user` in eval mode** — either remove the tool from the eval tool set or add system prompt instruction: "Never ask the user for clarification. Use device date/time for relative dates. Make reasonable assumptions."
    - [Note: 可以做，但要干净，不要太hacky] 
3. **Do not strip AndroidWorld's AccessibilityForwarder during eval scoring** — keep both services enabled, or re-enable forwarder right before `task.is_successful(env)` to ensure validator reads the real post-task UI tree.
    - [Note:可以做；这个之前是为了看AndroidWorld的accessibility forwarder会不会影响我的Android agent app的accessibility actions。后来好像发现没有太多关系,你也可以把这儿的一些AndroidWorld accessibility forwarder的权限removal都给删掉,就给它付权限就好了,你就保证任何时候我的app跟它的eval的accessibility forwarder都有权限就行。]

### P1 — High Impact (would flip 3+ tasks)

4. **Pre-completion verification prompt**: "Before calling complete_task, verify every parameter in the original goal has been explicitly addressed." Targets: AudioRecorderRecordAudioWithFileName, CameraTakeVideo, ClockStopWatchPausedVerify.
[Note:可以做。但是这个要怎么做？你回去看看其实agent都尝试去verify了。AudioRecorder那个不确定有没有verify，但是另外两个agent都是去verify了,Camera Take Video那个他把照片错当成了Video,Clock Stopwatch paused verify那个他去看了确实是paused,这个是Task Initialization该跑没跑吗?还是说这个的是verification的原因?Eval scroing搞错了,我不确定这个到底是怎么回事。]
5. **Reduce write_todos frequency**: write_todos consumed 2-5 turns per task with no contribution to task completion. Either reduce frequency in system prompt or remove from eval tool set.
[Note: 可以先去掉。但是是comment掉tool enablement和相关的system prompt instruction，等以后如果需要再加回来。]
6. **Dynamic turn budget**: Tasks requiring 3+ sequential multi-field operations (e.g., "Add 3 expenses") should get 40-50 turns.
[Note: 现在这个max turn 30是写死的，这个要怎么改比较clean？如果都搞成40，我怕有一些agent在一些简单任务上，因为执行有误，重复无效操作，而造成token浪费]

### P2 — Structural

7. **Screenshot perception for canvas tasks** — BrowserDraw/BrowserMaze need vision.
[Note: 看看sop/adhoc/reference_analysis.md mobile agents里主要依赖a11y perception的agent这个问题都是怎么解决的？是把screenshot getting设置成一个tool，还是他们默认都改成有screenshot perception，不再只依赖a11y tree了？如果是一个tool，这个tool又是具体怎么设计的？]
8. **File content tool** — for Markor-type cross-app reading, a `read_file` or `read_clipboard` tool is more reliable than navigating the file viewer UI.
[Note: 可以做。看看sop/adhoc/reference_analysis.md mobile agents有没有设计直接read file的tool的。我想设计个shell tool得了，这样灵活度更高，你看看mobile agents reference有没有这么做的。coding agent不用看了，肯定有这个tool。]

### P3 — Validation

9. **Add scoring diagnostics** — log current activity, package, ui element count, and key matcher hits at scoring time for all UI-based validators. This will quickly separate agent failure vs evaluator visibility issues.
[Note:可以做，但要做得干净。]

---

## Open Questions for User Decision

1. **Evaluator compatibility strategy**: keep both accessibility services enabled throughout eval, or keep AgentService-only during execution and re-enable forwarder only before scripted scoring?
Note: 都enable吧。但现在task之间有时候android agent app会loss a11y service permission，需要每个task开始时候，确保有权限。

2. **ExpenseAddMultipleFromMarkor weighting**: should it be tracked under Perception or Cross-App Strategy as primary? Current aligned view is mixed; owner may choose one primary bucket for dashboard simplicity.
Note: skipped for now.

3. **Metrics reporting**: when infra retries happen, should headline KPI stay `1/20 unique tasks` or include attempts (`1/22`)?
Note: 1/20 unique tasks。infra应该够稳定，就不该因为a11y permission没有而retry。