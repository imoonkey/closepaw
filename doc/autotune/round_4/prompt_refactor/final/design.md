# System Prompt Refactor — Final Design

## Goal

Refactor the Android agent's current prompt from a patched, duplicated ~205-line blob into a three-layer system with clear ownership:

1. **Core system prompt** — stable cross-tool behavioral policy
2. **Tool descriptions** — self-contained tool-local semantics
3. **App skills** — app-specific knowledge modules stored in generic `SKILL.md` format

This is the final direction after alignment plus a later simplification: the earlier `dynamic hints` / `task hint` idea is replaced by **package-scoped app skills**.

## 1. Root Problem

The current prompt has no durable ownership model. Without ownership:

- tuning rounds add rules to the easiest place, not the right place
- tool descriptions and system prompt drift apart
- app quirks become permanent global policy
- one-off evaluator patches accumulate in the least reusable layer

The real problem is not just prompt length. It is that the prompt mixes six different kinds of content:

- global behavior policy
- turn-shape / execution policy
- tool-local mechanics
- task-mode guidance
- app-specific workflows and quirks
- overfit patches from prior rounds

These must be separated.

## 2. Current State Analysis

### Structure Map

| Section | Lines | Content | Assessment |
|---------|-------|---------|------------|
| Role | 24-28 | Agent identity | Good |
| Core Loop | 30-35 | Observe-act-verify | Good |
| Calling Conventions | 37-47 | Turn shape, batching | Mixed with tool guidance |
| Tool subsections | 49-107 | Per-tool behavioral tips | Heavy duplication with tool descriptions |
| Information-Gathering / QA | 109-126 | QA playbook | Important, but bolted on |
| Vision Limitations | 128-129 | Fail-fast policy | Fine but narrow |
| App Tips | 131-199 | App quirks + cross-app rules + eval patches | Chaotic, wrong owner |
| Device Environment | 201-204 | Runtime context | Good |

### Overlap With Tool Descriptions

| Content | In System Prompt | Also in Tool Description |
|---------|------------------|--------------------------|
| Scroll direction semantics | 58-62 | `MobileActionTool.description` |
| Shell allowed/blocked uses | 74-86 | `ShellTool.description` |
| Open app: don't go Home | 69-72 | `OpenAppTool.description` |
| Scratchpad batching mechanics | 45 | `ScratchpadTool.description` |
| `complete_task` verification | 94-107 | `CompleteTaskTool.description` |
| `write_todos` multi-step usage | 47 | `WriteTodosTool.description` |

Nearly every tool's behavior appears in both places.

### Internal Redundancy

The retry / pivot rule appears multiple times in slightly different forms:

- avoid repeated identical actions
- if an action fails, switch strategy
- after 3+ attempts, pivot
- loop warning means try something different immediately

This should become one consolidated rule in one place.

## 3. Reference Analysis Summary

### Mobile-Agent Pattern

The mobile-agent references still support the same high-level conclusion:

1. The cleanest base prompts are small and role-pure.
2. Tool-local mechanics should not be duplicated in the base prompt.
3. App-specific knowledge should be modular and conditional, not hardcoded into global prompt text.
4. QA / information gathering should be handled as a task mode, not as a late prompt appendix.
5. `autodevice` remains the anti-pattern: too much policy and app knowledge stuffed into one prompt.

### Coding-Agent Skill Pattern

The newer refinement is to borrow the **generic skill format** used by coding agents:

- each skill has an entry file: `SKILL.md`
- the entry file is the load unit
- large skills can split extra detail into referenced files
- the runtime only needs a small trigger + loader mechanism

This is a better fit for app-specific Android knowledge than inventing a custom hint taxonomy. App-specific rules are not “tiny hints”; they are small, structured knowledge modules.

## 4. Ownership Model

### Principle

> **Core system prompt** owns cross-tool behavioral policy.  
> **Tool descriptions** own tool-local semantics.  
> **App skills** own package-specific workflows, pitfalls, and non-obvious UI knowledge.

One rule should have one owner.

### What Goes In The Core System Prompt

- agent role and success definition
- turn contract
- evidence-driven behavior
- retry / pivot policy
- cross-tool coordination
- working-memory policy
- task modes
- completion policy
- global environment warnings, such as ignoring the agent's own capsule controls

### What Goes In Tool Descriptions

- what the tool does
- parameters and valid combinations
- tool-local preconditions
- tool-local limitations
- tool-local examples

Examples:

- `mobile_action`: selector forms, scroll vs swipe semantics, coordinate fallback
- `shell`: file-oriented scope, blocked command classes, read-only examples
- `open_app`: direct launch behavior
- `scratchpad`: read/write/delete semantics
- `write_todos`: full-list replacement semantics

### What Goes In App Skills

- app-specific navigation workflows
- inaccessible widgets or misleading screens in that app
- app-specific entry / edit / save flows
- app-specific shell caveats
- app-specific formatting conventions that are meaningful only inside that app

Examples:

- Calendar: agenda view, date navigation, time-picker behavior
- Markor: file dialog quirks, extension field behavior, refresh caveats
- Pro Expense: save/verify flow, duplicate-scan workflow if genuinely stable

### No Standalone Task-Hint Layer

We explicitly do **not** keep a separate `TaskHint` layer.

If a rule is:

- truly global and stable -> it belongs in the core prompt
- tool-local -> it belongs in the tool description
- app-specific -> it belongs in that app's skill
- none of the above -> it is probably an overfit patch and should be removed

This is simpler and forces cleaner ownership.

## 5. Proposed Core System Prompt Structure

Target: 7 concise sections, ~80-100 lines total.

```text
## Role
  Identity + success definition

## Critical Rules
  1. Use structured tool calls only
  2. Prefer at most one screen-affecting action per turn, then observe
  3. Batch cognitive updates with the next action when practical
  4. Do not repeat failed actions blindly; pivot after failure or loop warning
  5. Ignore the agent's own capsule controls
  6. Act from current screen evidence, not assumptions

## Execution Loop
  Observe -> decide -> act -> verify -> continue or complete

## Working Memory
  When to use scratchpad and todos

## Task Modes
  Manipulation / Information / Blocked / Unsupported

## Completion
  Verify exact requested outcome before complete_task

## Context
  Static device, screen, date
```

App skills are **not** part of the static system prompt. They are injected as separate per-turn context when an app skill is active.

## 6. What Moves Where

| Current Content | Destination |
|----------------|-------------|
| Role (24-28) | **Role** |
| Core Loop (30-35) | **Critical Rules** + **Execution Loop** |
| Calling Conventions (39-47) | **Critical Rules** |
| `mobile_action` targeting (49-55) | `MobileActionTool.description` |
| Loop / cycle recovery (56-57) | **Critical Rules** |
| Scroll vs swipe (58-62) | `MobileActionTool.description` |
| Own capsule UI (63-67) | **Critical Rules** |
| `open_app` tips (69-72) | `OpenAppTool.description` |
| shell tips (74-86) | `ShellTool.description` for scope; **Critical Rules** for cross-tool fallback |
| `ask_user` tips (88-91) | `AskUserTool.description` |
| `complete_task` verification (93-107) | **Completion** + `CompleteTaskTool.description` |
| QA / Information Gathering (109-127) | **Task Modes > Information** |
| Vision limitations (128-129) | **Task Modes > Unsupported** |
| Date-label / relative-date tips (131-136) | Relevant app skill(s) only if tied to real app workflows; otherwise remove |
| Calendar tips (137-148) | `app_skills/<calendar-package>/SKILL.md` |
| Expense tips (149-152) | `app_skills/com.arduia.expense/SKILL.md` or remove if overfit |
| `"Nh"` format (153-154) | Relevant app skill(s) only if needed there; otherwise remove |
| Markor tips (156-161) | `app_skills/net.gsantner.markor/SKILL.md` |
| OpenTracks tips (163-166) | `app_skills/de.dennisguse.opentracks/SKILL.md` |
| Retro Music tips (168-170) | `app_skills/<retro-music-package>/SKILL.md` |
| File operation reminders (172-179) | **Completion** or remove if app-specific |
| Form filling (181-186) | **Critical Rules** or tool description |
| Multi-item operations (188-193) | **Working Memory** |
| Working memory (195-199) | **Working Memory** |
| Device environment (201-204) | **Context** |

## 7. App Skill Design

### Format

Adopt the generic skill format directly:

```text
app/src/main/assets/app_skills/
  com.google.android.calendar/
    SKILL.md
  com.arduia.expense/
    SKILL.md
  net.gsantner.markor/
    SKILL.md
```

Use the **full package name** as the directory name. This gives deterministic matching and avoids alias ambiguity.

### Runtime Semantics

V1 keeps the runtime simple:

1. detect the current foreground package
2. look for `app_skills/<full-package-name>/SKILL.md`
3. if it exists, load the **entire `SKILL.md`**
4. inject it as a separate per-turn context block

That is the whole mechanism.

### Why Whole-File Load

Follow generic skill semantics:

- `SKILL.md` is the entry file
- load unit is the whole file
- we do **not** invent file-section slicing

If a skill grows too large, split detail into additional files later. Progressive disclosure should happen by **splitting files**, not by loading arbitrary subsections from inside one file.

### V1 Scope

Keep this deliberately narrow:

- foreground-package activation only
- whole `SKILL.md` load only
- no task-hint system
- no section loader
- no alias map
- no symlink handling
- no automatic follow-up loading of referenced files

If package variants matter later, add a very small explicit mapping. Do not design it now.

### Content Guidelines For `SKILL.md`

Because V1 loads the whole file every time the app is active, `SKILL.md` itself must stay concise.

It should contain only:

- stable, app-specific rules
- non-obvious workflows
- app-specific pitfalls
- app-specific shell caveats
- anything that materially changes action choice inside that app

It should not contain:

- tool API docs
- global retry policy
- duplicated core prompt rules
- one-off eval hacks

### Example Shape

```md
---
name: com.arduia.expense
description: App-specific guidance for Pro Expense.
---

# Pro Expense Skill

- Verify saved expense fields after each save.
- For duplicate-removal tasks, scan the full visible list systematically before deleting.
- Prefer app UI over shell for app state changes.
```

This is intentionally plain. The value is ownership and trigger semantics, not fancy formatting.

## 8. Runtime Integration

### Injection Point

App skills should be injected per turn, not interpolated into the static system prompt.

Why:

- `SessionAgentRunner.resolvePromptTemplates()` resolves the system prompt once per session
- `AgentTurnRunner.capturePreTurnSnapshot()` already reads the current foreground package per turn
- `PromptBuilder` already assembles per-turn user context

This is also better than a "load once when the app first opens" model:

- no extra session-state machine for loaded skills
- no stale skill state after app switches
- no dependence on history retention or compression
- the active app's skill always stays close to the current screen observation

### Minimal Wiring

Keep the implementation small:

1. add skill files under `app/src/main/assets/app_skills/<package>/SKILL.md`
2. add `AppSkillRepository` backed by `AssetManager`
3. store the repository in `SessionServices`
4. thread `currentPackageName: String?` into `TurnPlanningPhaseRunner.runPlanningPhase(...)`
5. load skill text if the asset exists
6. extend `PromptBuilder.buildInputItems(..., appSkill: String? = null)`
7. inject one optional user message between **Working Memory** and **Observation**

Do **not** load a skill only once and keep it as latent session state. Resolve it fresh each turn from the current foreground package.

Suggested prompt item:

```text
## App Skill
Package: net.gsantner.markor

<contents of SKILL.md>
```

Prompt order should stay:

1. History
2. Working Memory
3. App Skill
4. Observation

Observation should remain last, because it is the freshest task evidence. The app skill should frame how to interpret and act within the app, then the model should immediately see the current screen.

Do **not** widen `ScreenSnapshot` to carry package metadata. Package name is turn context, not perception state.

## 9. Tool Description Changes

These remain largely the same as the aligned design:

- **`MobileActionTool`**: keep selector forms, action list, scroll vs swipe semantics, coordinate fallback
- **`ShellTool`**: explicitly document file-only scope and blocked classes of commands
- **`CompleteTaskTool`**: keep the completion contract, but not the whole global completion doctrine
- **`ScratchpadTool` / `WriteTodosTool`**: keep tool-local semantics only

Cross-tool strategy stays in the core prompt, not in tool descriptions.

That includes:

- retry / pivot policy
- shell-to-UI fallback policy
- QA / information gathering behavior
- completion verification doctrine

## 10. Migration Plan

### Phase 0: Rule Inventory

Tag every current prompt rule as one of:

- `CORE_PROMPT`
- `TOOL_DESC:<tool>`
- `APP_SKILL:<full-package-name>`
- `REMOVE`

This forces ownership before edits begin.

### Phase 1: Enrich Tool Descriptions

Move duplicated tool-local mechanics into their owning tool descriptions.

**Files:** `MobileActionTool.kt`, `ShellTool.kt`, `CompleteTaskTool.kt`

**Validation:** behavior should remain unchanged because information is temporarily duplicated.

### Phase 2: App Skill Infrastructure

Add the minimal app-skill runtime path.

**Files:**

- new asset directory under `app/src/main/assets/app_skills/`
- new `AppSkillRepository`
- `SessionServices.kt`
- `AgentTurnRunner.kt`
- `TurnPlanningPhaseRunner.kt`
- `PromptBuilder.kt`

**Validation:** with one sample skill present, the planning prompt should include one extra app-skill context block when that package is foreground.

### Phase 3: Move App Tips Into App Skills

Create initial `SKILL.md` files for the apps that already have hardcoded prompt tips.

Start with the apps that already have the strongest evidence:

- Markor
- Pro Expense
- OpenTracks
- whichever calendar app the current tips actually target

At this phase, the system prompt can still temporarily carry the old tips for safety if needed.

### Phase 4: Rewrite The Core Prompt

Replace the current prompt with the new 7-section structure and delete content that now lives in tool descriptions or app skills.

**Files:** `StandaloneAgentDef.kt` and parallel planner prompt if needed

**Validation:** full eval run against the same task mix used for the refactor baseline.

### Phase 5: Eval And Prune

Run evals across:

- manipulation tasks
- information / QA tasks
- app-specific tasks
- multi-item tasks

Then:

1. inspect regressions
2. re-add missing rules only in the narrowest correct owner
3. remove weak app-skill content that is just evaluator overfit

## 11. Ownership Guardrails

After the refactor, maintain these invariants:

- no per-tool subsections in the core prompt
- no app-specific appendices inside the core prompt
- app-specific knowledge lives under `app_skills/<full-package-name>/SKILL.md`
- no standalone task-hint / global-tip dumping ground
- no duplicated rule text across prompt layers
- every new tuning patch must declare its owner layer first

## 12. Trade-offs

### Why This Design Wins

- fixes the real ownership problem
- keeps the core prompt small and readable
- reuses a proven generic `SKILL.md` pattern instead of inventing another registry format
- keeps app knowledge modular and package-scoped
- follows KISS: one trigger, one file, one injection point

### Costs

- requires a small runtime loader for asset-backed skills
- introduces another source of context text to maintain
- forces discipline when deciding whether something is truly app-specific

### Explicitly Out Of Scope

- planner / executor refactor
- task-hint layer
- section-level skill slicing
- alias handling for package variants
- symlink-based skill sharing
- automatic referenced-file loading

Those can be added later if real evidence justifies them.
