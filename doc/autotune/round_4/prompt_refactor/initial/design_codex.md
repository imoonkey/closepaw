# System Prompt Refactor Design for Android Agent

## Goal

Refactor the standalone agent prompt into a smaller, stable core with clear ownership boundaries:

- The system prompt should define global behavior, not act as a dumping ground.
- Tool descriptions should own tool-local semantics and examples.
- App-specific and task-specific quirks should be injected separately, only when relevant.
- The result should be easier to reason about, easier to tune, and harder to overfit.

This design covers the current state, reference-agent patterns, the proposed structure, the system-prompt/tool split, and a migration plan.

## 1. Current State Analysis

### Current structure map

Current prompt: [StandaloneAgentDef.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt#L22)

| Prompt area | Lines | What it contains | Assessment |
|---|---:|---|---|
| Role | 24-28 | Agent identity and top-level goal | Good |
| Core Loop | 30-35 | Basic observe-act-verify loop | Good |
| Calling Conventions | 37-47 | Turn shape, batching, scratchpad/todo usage | Mostly good, but mixed with tool guidance |
| Tool subsections | 49-107 | `mobile_action`, `open_app`, `shell`, `ask_user`, `complete_task` behavior | Mixed ownership; lots of duplication |
| Information-Gathering / QA | 109-126 | Field semantics, scratchpad pattern, completion behavior | Important, but cross-cuts completion and memory |
| Vision Limitations | 128-129 | One specific failure policy | Fine, but narrow |
| App Tips | 131-199 | A mix of app-specific tips, cross-app heuristics, and evaluator hacks | Chaotic |
| Device Environment | 201-204 | Dynamic runtime context | Good |

### What is well-structured

1. The first two sections are clean. Role + Core Loop give the right mental model quickly.
2. The prompt already distinguishes between stable runtime context and behavior rules.
3. It has the right core concerns: evidence-based action, memory discipline, verification, and completion.

### What is chaotic

1. Rule ownership is unclear. The prompt contains:
   - global policy
   - tool API guidance
   - tool-specific heuristics
   - task-mode guidance
   - app-specific instructions
   - one-off benchmark fixes
2. The `## App Tips` bucket is not actually app-only. It contains:
   - real app quirks (`Calendar`, `Markor`, `OpenTracks`, `Retro Music`)
   - cross-app rules (`File Operations`, `Form Filling`, `Multi-Item Operations`, `Working Memory`)
   - task-format rules (`Nh` means 24-hour time, blank line means `\n\n`)
3. QA / information-retrieval behavior is bolted on as a separate mini-prompt instead of being treated as a first-class task mode.
4. Completion guidance is overloaded. `complete_task` behavior, QA formatting, file verification, and “don’t waste turns” all live in the same area.
5. The prompt is doing too much tool documentation that is already partially owned by the tools.

### General principles vs app-specific hacks

#### Stable general principles already in the prompt

- Observe, act, verify
- Use evidence from current screen state
- Prefer semantic targets over coordinates
- Avoid blind retries
- Use memory for multi-step work
- Verify before completing
- Use ask-user only when genuinely necessary

These belong in the core system prompt.

#### App-specific knowledge currently mixed into the prompt

- Calendar month grid is not accessible; use agenda/list flow
- Markor has separate name/extension fields
- OpenTracks activity type is hidden behind edit flow
- Retro Music playlist flow prefers long-press multi-select

These do not belong in the static core prompt. They should be dynamically injected.

#### Likely overfit or weakly scoped rules

- “If running low on turns (< 5 remaining), call complete_task with what you have”
- Highly specific workflow prescriptions for some apps
- Several file/task heuristics that look evaluator-shaped rather than generally reusable

These should be reviewed individually and either:

- moved to targeted hints if they are real recurring constraints, or
- deleted if they are benchmark patches.

### Prompt/tool overlap

The biggest maintainability problem is duplicated semantics.

| Tool | Tool description ownership | Prompt duplication | Result |
|---|---|---|---|
| `mobile_action` | target modes, click/long press/type/scroll/swipe semantics, content-direction scroll, coordinate swipe | selector preferences, coordinate fallback, scroll-vs-swipe, enter-button guidance, retry policy | Tool semantics and global action policy are entangled |
| `open_app` | “Always use this to open apps” | “Call `open_app` directly”, “Do NOT go Home first”, “Do NOT open launcher manually” | Same rule appears twice |
| `shell` | file-only usage + examples + blocked command class | file-only usage, allowed examples, “stop after two failures”, OCR/root warnings | Mostly duplicated, partly conflicting ownership |
| `scratchpad` | write/read/delete API, batching screen facts, truncated values | batching, use for extracted facts, use read only for truncated values | Same workflow guidance in both places |
| `write_todos` | full-list replace semantics, status model, “don’t use for 1-2 actions” | “use for multi-step repeated operations” | Overlap, but incomplete in both places |
| `ask_user` | allowed use cases and non-use cases | same decision policy restated in prompt | Drift risk |
| `complete_task` | when to call, always provide answer | large verification checklist, formatting expectations, “don’t do post-pass” | Tool stays minimal while prompt carries all completion policy |

Current tool descriptions:

- [MobileActionTool.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt#L28)
- [ShellTool.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ShellTool.kt#L19)
- [ScratchpadTool.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ScratchpadTool.kt#L19)
- [WriteTodosTool.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/impl/WriteTodosTool.kt#L21)
- [OpenAppTool.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt#L80)
- [AskUserTool.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/impl/AskUserTool.kt#L37)
- [CompleteTaskTool.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/impl/CompleteTaskTool.kt#L28)

### Root problem

The real problem is not just “the prompt is long”. The problem is that there is no rule ownership model.

Without ownership:

- tuning adds rules to the easiest place, not the right place
- system prompt and tool descriptions drift apart
- app quirks become permanent global policy
- prompt length grows in the least reusable layer

## 2. Reference Analysis

## MAI-UI

Primary prompt file: [.reference/mobile_agent/MAI-UI/src/prompt.py](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/MAI-UI/src/prompt.py)

### Structure

- Role sentence
- Output format
- Action space
- Small note block
- Optional MCP tools block in the ask-user variant

### Prompt/tool split

- The action API lives almost entirely in the system prompt.
- There is effectively no separate tool-description layer for the built-in mobile actions.
- MCP tools are injected separately when present.

### App-specific tips

- Very little app-specific guidance in the base prompt.
- Available apps are injected, but not app workflow tips.

### QA / information tasks

- Uses an `answer` action in the action space.
- No long QA playbook; the prompt stays generic.

### Takeaway

MAI-UI is structurally simple, but it achieves that partly by collapsing action semantics into the prompt. That is cleaner than our current duplication, but worse than a clean prompt-plus-tool ownership model.

## MobileAgent

Relevant files:

- V1/V2 monolithic prompt patterns: [.reference/mobile_agent/MobileAgent/Mobile-Agent-v1/MobileAgent/prompt.py](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/MobileAgent/Mobile-Agent-v1/MobileAgent/prompt.py), [.reference/mobile_agent/MobileAgent/Mobile-Agent-v2/MobileAgent/prompt.py](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/MobileAgent/Mobile-Agent-v2/MobileAgent/prompt.py)
- V3 multi-agent prompts: [.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py)
- V3.5 tool-centric prompt: [.reference/mobile_agent/MobileAgent/Mobile-Agent-v3.5/mobile_use/utils.py](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/MobileAgent/Mobile-Agent-v3.5/mobile_use/utils.py#L293)
- Android World T3A-style reference: [.reference/mobile_agent/autodevice_android_world/android_world/agents/t3a.py](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/autodevice_android_world/android_world/agents/t3a.py)

### V3 manager/executor pattern

#### Structure

- Manager prompt: user request, historical operations, plan, notes, guidelines, output format
- Executor prompt: user request, plan, current subgoal, progress status, atomic actions, recent history
- Separate action reflector and note-taker prompts

#### Prompt/tool split

- Atomic action descriptions are embedded in the executor prompt, not in external tool descriptions.
- Additional knowledge is injected into dedicated slots:
  - `additional_knowledge_manager`
  - `additional_knowledge_executor`
  - task-specific notes for special apps/tasks

#### App-specific tips

- Injected into dedicated “Guidelines” / task-note areas.
- Not mixed into the role or action list.

#### QA / information tasks

- Explicit requirement that plans needing an answer must include an `answer` action.
- Question-answer tasks are handled as a distinct end-state, not buried in completion rules.

### V3.5 tool-centric pattern

#### Structure

- `# Tools`
- function signature block
- fixed response format
- short rules about brevity/output order

#### Prompt/tool split

- This is the cleanest tool-centric reference in the set.
- The prompt is mostly about output shape and interaction contract.
- Tool semantics live inside the function signature/tool description block.

#### App-specific tips

- Almost none in the system prompt.
- Current date is added in the user prompt rather than turned into a long global rules section.

#### QA / information tasks

- `answer` and `terminate` are part of the tool interface.
- No extra QA rulebook in the core prompt.

### Takeaway

MobileAgent shows both ends of the spectrum:

- older/mid versions push action semantics into prompts
- newer tool-centric versions keep the prompt smaller and push operational details into tools

The V3.5 direction is closer to what we want.

## DroidRun

Relevant files:

- CodeAct system prompt: [.reference/mobile_agent/droidrun/droidrun/config/prompts/codeact/tools_system.jinja2](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/droidrun/droidrun/config/prompts/codeact/tools_system.jinja2)
- Python-code variant: [.reference/mobile_agent/droidrun/droidrun/config/prompts/codeact/system.jinja2](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/droidrun/droidrun/config/prompts/codeact/system.jinja2)
- Manager prompt: [.reference/mobile_agent/droidrun/droidrun/config/prompts/manager/system.jinja2](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/droidrun/droidrun/config/prompts/manager/system.jinja2)
- Executor prompt: [.reference/mobile_agent/droidrun/droidrun/config/prompts/executor/system.jinja2](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/droidrun/droidrun/config/prompts/executor/system.jinja2)

### Structure

CodeAct prompt:

- role
- tool call format
- context
- available tools
- response format
- important rules

Manager/executor split:

- manager owns planning, memory, hints, output requirements
- executor is deliberately low-level and literal

### Prompt/tool split

- CodeAct relies heavily on `{{ tool_descriptions }}`.
- The system prompt stays focused on response protocol and a few cross-tool rules.
- Manager prompt can inject `app_card`, `important_notes`, custom actions, secrets, scripting support.

### App-specific tips

- Injected as `app_card` or `important_notes`, not hardcoded into the universal prompt.

### QA / information tasks

- Output requirements are injected when needed.
- Completion/output formatting is shaped by task schema, not by a long static rules section.

### Takeaway

DroidRun has the cleanest ownership model in the reference set:

- system prompt = protocol + global behavior
- tool descriptions = operational API
- app/task notes = dynamic injection

## minitap-mobile-use

Relevant files:

- Planner: [.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.md)
- Cortex: [.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/cortex/cortex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/cortex/cortex.md)
- Executor: [.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/executor/executor.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/executor/executor.md)
- Contextor: [.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/contextor/contextor.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/contextor/contextor.md)
- Hopper: [.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/hopper/hopper.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/hopper/hopper.md)
- Tools list injection: [.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/index.py](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/index.py)

### Structure

- Strong role separation: Planner, Cortex, Executor, Contextor, Hopper, Outputter
- Each prompt is short and role-pure
- Runtime context determines behavior, not giant static prompts

### Prompt/tool split

- Prompts mostly reference available tools by name.
- Tool schemas/descriptions carry the API surface.
- Prompts focus on reasoning policy for the specific role.

### App-specific tips

- Injected through runtime state:
  - current foreground app
  - locked app
  - video-recording mode
  - current subgoal
- Not stored as a giant static app-tip appendix.

### QA / information tasks

- Information fidelity is isolated:
  - Cortex says “Data fidelity over helpfulness”
  - Hopper extracts exactly-as-is from batch data
  - Outputter handles final structured response

### Takeaway

minitap’s core lesson is not “use more agents”. It is “separate concerns aggressively”.

For our single-agent design, the transferable idea is:

- keep the core prompt role-pure
- move extraction/output rules into explicit task-mode behavior
- keep app/task hints out of the base prompt

## autodevice_android_world

Relevant files:

- T3A: [.reference/mobile_agent/autodevice_android_world/android_world/agents/t3a.py](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/autodevice_android_world/android_world/agents/t3a.py)
- Autodev planner/executor prompts: [.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/prompts.py](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/prompts.py)
- Planner tools: [.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/planner_tools.py](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/planner_tools.py)
- Executor tools: [.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/executor_tools.py](/Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/executor_tools.py)

### Structure

Two relevant patterns appear here:

1. T3A:
   - action list
   - general guidance
   - optional task-specific additional guidelines
2. Autodev:
   - very long planner and executor prompts
   - large collections of task heuristics and app-specific rules
   - separate planner and executor tool surfaces

### Prompt/tool split

- T3A keeps actions in the prompt and allows separate additional guidelines.
- Autodev has both tool descriptions and a huge prompt-level policy layer.

### App-specific tips

- T3A handles them by appending `additional_guidelines`.
- Autodev embeds many such tips directly into the planner/executor prompt bodies.

### QA / information tasks

- Strong explicit handling:
  - identify count/search tasks
  - require `answer()` before finish
  - detailed extraction/playbook logic

### Takeaway

Autodev is the closest warning sign to our current state: once task-specific fixes start accumulating in the core prompt, the prompt becomes hard to own, hard to prune, and hard to generalize.

## Patterns found across references

### Pattern 1: The cleanest prompts are small and role-pure

DroidRun CodeAct, MobileAgent V3.5, and minitap all keep the base prompt focused on:

- role
- response/tool call format
- a few global behavior rules

### Pattern 2: Tool-local behavior works best when owned by tool descriptions

The most maintainable references either:

- put tool semantics in tool descriptions/function signatures, or
- put all action semantics in the prompt, but do not duplicate them elsewhere

Our current system does both, which is the worst combination.

### Pattern 3: App-specific knowledge should be injected, not globalized

The best references use:

- `additional_guidelines`
- `app_card`
- `important_notes`
- runtime state fields

They do not keep a permanent global appendix of app tips.

### Pattern 4: QA/information tasks should be treated as a mode, not a patch

References handle information tasks by:

- explicit `answer` end-state
- structured output requirements
- dedicated extraction/output roles

They do not mix QA rules loosely into completion, memory, and app tips.

## 3. Proposed New Structure

## Design principles

1. One rule, one owner.
2. The system prompt should contain only stable cross-tool policy.
3. Tool descriptions should be operationally self-contained for their own API.
4. App and evaluator quirks should be injected only when relevant.
5. QA/information work should be a first-class task mode.

## Proposed section hierarchy for the system prompt

Target: 6-7 short sections, no per-tool subsections.

### 1. Role And Success Standard

Purpose:

- define the agent as a direct UI executor
- define end-to-end success
- define evidence-based completion

Contents:

- complete the user’s goal directly
- act from current observation, not assumptions
- finish once the requested outcome is achieved and verified

### 2. Turn Contract

Purpose:

- define turn shape and tool-calling protocol

Contents:

- use structured function calls only
- batch cognitive updates with the next action when possible
- prefer at most one screen-affecting action per turn, then observe

### 3. Execution Policy

Purpose:

- define cross-tool action behavior

Contents:

- prefer grounded/semantic targets
- avoid blind retries; pivot after failure or loop warning
- ignore agent capsule UI controls
- do not brute-force impossible/unsupported tasks

This section replaces most of the current `### mobile_action` subsection except tool-local API details.

### 4. Working Memory And Planning

Purpose:

- define when to use scratchpad and todos as general behaviors

Contents:

- store extracted facts before leaving a screen
- batch relevant observations into one scratchpad write
- use todos for repeated/multi-step work, not short tasks
- once a working repetitive flow is proven, reuse it

### 5. Task Modes

Purpose:

- separate action tasks from information tasks and blocked tasks

Sub-modes:

- Manipulation mode: act, verify, continue
- Information mode: identify requested field, gather evidence, keep exact findings, answer in requested format
- Blocked mode: ask user only when missing information or physical intervention is unavoidable
- Unsupported mode: fail quickly for capabilities we do not have, such as image-reading without vision

This section absorbs the current QA block and the ask-user/vision policy.

### 6. Completion Policy

Purpose:

- define global completion behavior

Contents:

- complete only when no more screen action is needed in the same turn
- verify the exact thing requested: filename, field, count, deletion, edited content
- do not perform a redundant post-completion rerun of the workflow
- keep the user-facing answer concise and factual

### 7. Dynamic Context

Purpose:

- carry runtime-only information

Contents:

- device model
- screen size
- current date
- optional app/task hints block

## Example prompt skeleton

```text
You are a standalone Android automation agent.

## Role And Success
- Complete the user's goal end-to-end by acting directly in the Android UI.
- Base every action on the current observation.
- Finish once the requested result is achieved and verified.

## Turn Contract
- Use structured tool calls only.
- You may batch cognitive updates with the next action.
- Prefer at most one screen-affecting action per turn, then observe.

## Execution Policy
- Prefer grounded selectors over guesswork.
- Do not repeat failed actions blindly; change strategy after failure or loop warning.
- Ignore the agent's own capsule controls if they appear on screen.

## Working Memory And Planning
- Write down facts before leaving a screen.
- Batch screen findings into a single scratchpad write when possible.
- Use todos for multi-step or repeated work.

## Task Modes
- Manipulation: perform the next verified UI step.
- Information: identify the exact requested field, gather evidence, answer in the requested format.
- Blocked: ask the user only when necessary.
- Unsupported: fail quickly when the task requires unavailable capabilities.

## Completion
- Call complete_task only when no further screen action is needed in the same turn.
- Verify the exact requested outcome before completing.
- Do not run a redundant post-completion pass.

## Context
- Device: ...
- Screen: ...
- Date: ...
{{dynamic_hints}}
```

## Dynamic hints design

App-specific tips should not stay in the static prompt. They should move to a separate hint layer.

### Proposed hint buckets

1. `AppHint`
   - keyed by package/app family
   - example: Calendar agenda-view limitation
2. `TaskHint`
   - keyed by task pattern
   - example: blank line means `\n\n`
   - example: exact filename matching
3. `EnvironmentHint`
   - keyed by runtime environment
   - example: ignore capsule controls

### Hint quality bar

Only keep a hint if it is:

- stable across runs
- broadly useful
- not already implied by a tool description
- not a one-off evaluator patch

### Injection policy

- inject only hints relevant to the current goal/app
- cap the hint block to a small number of bullets
- keep hints terse and operational

This is the key change for the current `## App Tips` section:

- real app quirks move to `AppHint`
- cross-app rules move to core prompt or tool descriptions
- weak one-off hacks are removed

## 4. System Prompt vs Tool Description Split Principle

## Recommended split

The right split is not:

- “tool descriptions should be minimal API docs only”

and not:

- “tool descriptions should fully repeat all behavioral guidance”

The right split is:

- system prompt owns global cross-tool behavior
- tool descriptions own tool-local operational semantics
- dynamic hints own app/task-specific knowledge

### What belongs in the system prompt

- agent role and success definition
- turn structure and batching policy
- evidence/retry/pivot policy
- memory/planning policy across tools
- QA/information-task behavior
- completion policy
- environment-only warnings such as “ignore your own controls”

### What belongs in tool descriptions

- what the tool does
- parameters and valid combinations
- tool-local preconditions
- tool-local examples
- tool-local limitations and “do not use for X” rules

Examples:

- `scroll` vs `swipe` belongs in `mobile_action`
- shell file-only scope belongs in `shell`
- “always use this to open apps” belongs in `open_app`
- scratchpad batching/read-truncation behavior belongs in `scratchpad`
- todo replacement semantics belong in `write_todos`
- ask-user request types belong in `ask_user`

### What belongs in dynamic hints

- app navigation quirks
- inaccessible widgets in specific apps
- category/field semantics for specific apps
- app-specific save/rename/dialog behavior
- evaluator-specific but still recurring task patterns, if they survive the quality bar

### Explicit answer to the key question

Should tool descriptions be minimal or self-contained?

Answer:

Tool descriptions should be self-contained for tool use, but not for overall agent behavior.

That means:

- a tool description should be sufficient to use the tool correctly
- it should not restate system-wide retry policy, completion policy, or QA policy unless the rule is intrinsic to that tool

This gives us local correctness without duplication.

## QA / information-gathering policy

QA tasks should remain in the system prompt as a task mode, not be spread across tools.

Reason:

- the behavior spans `mobile_action`, `scratchpad`, `shell`, and `complete_task`
- it is a task-level policy, not a tool-local policy

Recommended QA rules:

1. Identify the exact requested field or count.
2. Gather evidence while browsing.
3. Preserve fidelity; do not infer missing data.
4. Answer in exactly the requested format.
5. Complete immediately after the answer is ready.

This is simpler than the current QA mini-prompt while still preserving the key behavior.

## Right granularity of sections

The current prompt is too granular in the wrong place:

- many micro-subsections
- several are really ownership leaks from tools or app hints

The new prompt should use coarse, behavior-level sections:

- 6-7 top-level sections
- 1-4 bullets each
- no per-tool subsections in the base prompt

If a rule requires a per-tool heading, it probably belongs in that tool’s description instead.

## 5. Migration Plan

## Phase 1: Inventory and tag every existing rule

Take every current rule and tag it as one of:

- `CORE_PROMPT`
- `TOOL_DESC:<tool_name>`
- `APP_HINT:<app>`
- `TASK_HINT`
- `REMOVE`

This step is the most important because it forces ownership decisions.

## Phase 2: Extract tool-owned rules out of the system prompt

Move duplicated rules into the relevant tool descriptions:

- `mobile_action`: selector choice, scroll/swipe semantics, coordinate-last-resort guidance
- `open_app`: direct app open policy
- `shell`: file-only scope, allowed examples, stop conditions if still considered tool-local
- `scratchpad`: batching, read only for truncated values
- `write_todos`: when to use, full-list replace semantics
- `ask_user`: allowed request classes
- `complete_task`: required params and minimal completion contract

Goal: after extraction, the system prompt should not have per-tool subsections.

## Phase 3: Rebuild the system prompt from sections

Replace the large raw string with section-owned pieces, ideally via a small builder/composer.

Suggested code shape:

- `StandalonePromptSections.kt`
- `PromptHintCatalog.kt`
- `PromptHintSelector.kt`
- `StandaloneAgentDef.kt` only assembles the final prompt

This is not about abstraction for its own sake. It enforces ownership and makes future edits local.

## Phase 4: Create a dynamic hint registry

Move app/task tips out of the base prompt into a registry.

Initial migrations:

- Calendar tips → `AppHint(Calendar)`
- Markor tips → `AppHint(Markor)`
- OpenTracks tips → `AppHint(OpenTracks)`
- Retro Music tips → `AppHint(Retro Music)`
- blank line / exact filename / date label semantics → `TaskHint`

Remove or defer tips that do not clear the quality bar.

## Phase 5: Simplify QA behavior into a task-mode section

Replace the long QA block with a compact mode description in the core prompt.

Keep only the essential invariants:

- exact requested field
- evidence-backed answer
- requested format
- answer/complete promptly

If more task-specific QA rules are needed later, add them as `TaskHint`, not to the base prompt.

## Phase 6: Add prompt ownership checks

Add lightweight tests or review rules so the prompt does not regress into a dumping ground.

Recommended checks:

- no per-tool sections in the core prompt
- app hints are injected from the registry, not hardcoded in the core string
- duplicated phrases between core prompt and tool descriptions are minimized
- snapshot test for the assembled standalone prompt

## Phase 7: Evaluate and prune

After implementation:

1. run prompt-focused evals across:
   - direct manipulation tasks
   - information-retrieval tasks
   - multi-item tasks
   - app-specific tasks (Calendar, Markor, OpenTracks, Retro Music)
2. compare failures
3. only re-add rules in the narrowest correct owner layer

## Trade-offs

### Why this design wins

- shorter core prompt
- less duplication
- clearer tuning surface
- app knowledge becomes modular
- less risk that one benchmark fix changes all tasks

### Cost

- some work to move rules and add a hint registry
- slightly more assembly logic
- requires discipline when adding future rules

That cost is worth paying because the current failure mode is structural, not cosmetic.

## Final recommendation

Refactor toward a three-layer ownership model:

1. Core system prompt: stable cross-tool behavior only
2. Tool descriptions: self-contained tool-local semantics
3. Dynamic hints: app/task-specific guidance injected only when relevant

If we do only one thing, it should be this: remove per-tool and app-tip content from the static prompt and give every rule a single owner. That is the change that will make future tuning rounds cleaner instead of messier.
