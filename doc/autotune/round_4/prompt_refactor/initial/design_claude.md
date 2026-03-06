# System Prompt Refactor Design

## Goal

Restructure the Android agent's system prompt (StandaloneAgentDef.kt) from its current patched-together state into a clean, principled architecture. The current prompt is ~205 lines of accumulated tuning patches with unclear hierarchy, overfit app tips, and content duplication between the system prompt and tool descriptions.

## 1. Current State Analysis

### Structure Map

The current prompt has these sections (line references to `StandaloneAgentDef.kt`):

| Section | Lines | Content |
|---------|-------|---------|
| Role | 24-28 | Identity + "not a planner-only role" |
| Core Loop | 30-36 | 5-step observe/act/verify cycle |
| Calling Conventions | 39-47 | Multi-tool batching, cognitive + screen action per turn |
| mobile_action tips | 49-67 | Targeting, loop/cycle recovery, scroll vs swipe, own-UI avoidance |
| open_app tips | 69-72 | Don't go Home first |
| shell tips | 74-86 | Allowed/blocked uses, 2-failure limit |
| ask_user tips | 88-91 | When to use, prefer assumptions |
| complete_task tips | 93-107 | Pre-completion checklist, verification rules |
| Information-Gathering / QA | 109-127 | Field identification, scratchpad accumulation, answer formatting |
| Vision Limitations | 128-129 | Bail on image-reading tasks |
| App Tips | 131-198 | 10 subsections: Date Labels, Calendar, Expense, General, Markor, OpenTracks, Retro Music, File Operations, Form Filling, Multi-Item Operations, Working Memory |
| Device Environment | 201-204 | Template variables |

### Problems Identified

**P1: Section hierarchy is flat and inconsistent.** "Calling Conventions" is under "## Tools" but "Information-Gathering / QA" is a peer of "## Tools" despite being behavioral guidance. App Tips is a peer section of the agent loop itself.

**P2: Tool guidance duplicates tool descriptions.** The system prompt re-explains scroll vs swipe semantics (lines 58-62) even though `MobileActionTool.description` already documents it. The shell section (lines 74-86) repeats what `ShellTool.description` already says. The open_app section (lines 69-72) duplicates `OpenAppTool.description`.

**P3: App-specific tips are overfit and growing unboundedly.** Calendar tips (8 lines), Markor tips (5 lines), OpenTracks tips (4 lines), Retro Music tips (2 lines) — these are essentially eval-task-specific patches. They'll only grow with each tuning round and pollute the general-purpose prompt.

**P4: Behavioral strategies are mixed with API docs.** Lines 56-57 (loop/cycle recovery) and lines 93-107 (completion checklist) are high-value cognitive guidance mixed with tool parameter docs.

**P5: No clear separation between "how to think" and "how to act."** The prompt jumps between reasoning strategy (QA accumulation, working memory) and mechanical tool-calling rules (batching, one action per turn).

**P6: Redundancy within the prompt itself.** "Avoid repeated identical actions" appears in mobile_action tips. "If an action fails, switch strategy" appears there too. Then "Strategy pivot" adds a 3-attempt rule. Then loop/cycle warning adds another variation. These are all the same principle stated 4 times.

### Content Overlap: System Prompt vs Tool Descriptions

| Content | System Prompt | Tool Description |
|---------|--------------|------------------|
| Scroll direction semantics | Lines 58-62 | MobileActionTool.description |
| Shell allowed/blocked commands | Lines 74-86 | ShellTool.description |
| Open app: don't go Home | Lines 69-72 | OpenAppTool.description |
| Scratchpad: capture all data in one write | Line 45 | ScratchpadTool.description |
| complete_task: call only after verifying | Lines 94-107 | CompleteTaskTool.description |
| write_todos: multi-step tasks | Line 47 | WriteTodosTool.description |

**Diagnosis:** Almost every tool's behavioral guidance is stated twice — once in the system prompt and once in the tool description. This wastes tokens and creates maintenance burden (changes must be synced in two places).

## 2. Reference Analysis

### MAI-UI (Alibaba)

**Architecture:** Single-agent, vision-based. Prompt is ~50 lines.

**Structure:**
1. Role identity (1 line)
2. Output Format (XML tags for thinking + tool_call)
3. Action Space (flat list of action JSON schemas)
4. Note (available apps, behavioral reminder)

**Key patterns:**
- Tool descriptions are inline in the system prompt as "Action Space" — no separate tool description mechanism
- Extremely minimal: no app-specific tips, no recovery strategies, no working memory guidance
- All behavioral guidance fits in 3-4 bullet points under "Note"

**Split principle:** Everything in system prompt (no tool descriptions at all — uses XML output format)

### MobileAgent V2/V3 (Alibaba)

**Architecture:** Multi-agent: Manager (planning) + Executor (actions) + Reflect + Memory + Process agents.

**Structure (V2 Executor):**
1. Background (instruction, screen dimensions)
2. Screenshot information (extracted clickable elements)
3. Keyboard status
4. Hint (optional additional info)
5. History operations
6. Progress (completed content)
7. Memory (stored observations)
8. Last operation (error reflection)
9. Response requirements (action space)
10. Output format

**V3 Manager prompt structure:**
1. User Request
2. Historical Operations
3. Plan
4. Current Subgoal
5. Progress Status
6. Guidelines (general + task-specific injected dynamically via `additional_knowledge_manager`)
7. Output format

**Key patterns:**
- App-specific tips are injected dynamically via `additional_knowledge_manager`/`additional_knowledge_executor` — NOT hardcoded in the base prompt
- Separate "Memory" and "Progress" sections managed by dedicated agents
- Reflection agent does post-action verification (separate prompt entirely)
- Each agent has a focused, single-purpose prompt

**Split principle:** Tool descriptions embedded in system prompt as "Response requirements". App knowledge injected dynamically.

### DroidRun

**Architecture:** Manager-Executor split with optional CodeAct and Scripter modes.

**Manager prompt structure (Jinja2 template):**
1. Role identity
2. User Request
3. Device Date (conditional)
4. App Card (conditional — app-specific tips injected here!)
5. Important Notes (conditional)
6. Error History / Stuck detection (conditional)
7. Guidelines (general + text manipulation + memory usage)
8. Custom Actions (conditional)
9. Available Secrets (conditional)
10. Scripter Execution (conditional)
11. Output Requirements (conditional)
12. Output format

**Executor prompt structure:**
1. Role identity + CRITICAL rules
2. User Request
3. App Card (conditional)
4. Device State (conditional)
5. Overall Plan
6. Current Subgoal
7. Subgoal Parsing Mode
8. Progress Status
9. Guidelines (general + action-related + text-related)
10. Atomic Actions (tool schema)
11. Available Secrets (conditional)
12. Action History
13. Output format

**Key patterns:**
- **App Card pattern**: App-specific knowledge is injected as a conditional `{{ app_card }}` block — NOT in the base prompt. This is the cleanest solution to the app-tips problem.
- **Conditional sections**: Heavy use of Jinja2 `{% if %}` for optional sections (secrets, scripter, error history)
- **Guidelines separated from tool docs**: "Guidelines" section is purely behavioral. Tool schemas are listed separately under "Atomic Actions"
- Tool descriptions are minimal parameter docs; behavioral guidance lives in system prompt Guidelines

**Split principle:** Tool descriptions = parameter API docs only. Behavioral guidance (when/how to use tools) lives in system prompt Guidelines. App-specific tips injected via App Card.

### minitap-mobile-use

**Architecture:** 5-agent system: Orchestrator, Planner, Cortex (brain), Executor (hands), Contextor.

**Cortex (main reasoning agent) structure:**
1. Critical Rules (5 numbered rules — most important first)
2. Perception (2-sense model: UI hierarchy + screenshot)
3. Element Targeting (mandatory targeting pattern)
4. Tools & Actions (table format: action → tool → notes)
5. Swipe Physics
6. Form Filling
7. App Lock Mode (conditional)
8. Output Format
9. Example
10. Input (template variables)

**Planner structure:**
1. Critical Rules (conditional: app already open, app lock)
2. Planning Guidelines (what good subgoals look like)
3. Replanning
4. Output Format
5. Examples (good vs bad, replanning after failure)

**Executor structure:** Ultra-minimal (15 lines). "Parse decisions, call tools, include agent_thought. Don't reason about strategy."

**Key patterns:**
- **Critical Rules first** — most important constraints at top of prompt, before any other content
- **Table format** for tool/action mappings — compact and scannable
- **Examples section** with good/bad patterns — concrete over abstract
- **Separation of concerns** is extreme: Cortex handles ALL reasoning, Executor is a dumb dispatcher
- No app-specific tips at all — relies on LLM's general knowledge
- Very clean structure: rules → perception → tools → output format

**Split principle:** Tool descriptions in the executor/tool schema. Behavioral guidance in Cortex prompt. No duplication because agents are cleanly separated.

### autodevice (Android World)

**Architecture:** Single-agent planner-executor with detailed system prompt.

**Planner prompt structure:**
1. Role + critical date reasoning rules
2. Date Range Interpretation (detailed rules)
3. Workflow (5-step: analyze → plan → execute → verify → answer)
4. Planning Strategy
5. Optimization Tasks
6. Executor Instructions (multi-item, conditional, text ops, merge, duplicate deletion)
7. Operations (files, forms, multi-app)
8. Screen Analysis
9. Text Input
10. Navigation
11. System Settings
12. Executor Failure Handling
13. Count/Search Tasks
14. Completion

**Executor prompt structure:**
1. Role + critical rules
2. Completing Subgoals
3. Text input
4. Merge/Concatenate
5. File Naming
6. Filtering and Searching
7. Scrolling (loop prevention)
8. Scratchpad
9. Duplicate Deletion
10. Navigation
11. Max Steps Reached / Last 10 Steps

**Key patterns:**
- **By far the longest prompts** (~400 lines planner + ~400 lines executor)
- Heavy duplication between planner and executor (merge rules, file naming, duplicate deletion stated in both)
- App-specific patterns (brightness sliders, file naming dialogs) hardcoded into system prompt
- Task-type-specific sections (count/search, optimization, duplicate deletion) — very detailed but overfit
- This is what our prompt would become if we keep adding patches

**Split principle:** Tool descriptions via `{{ tool_descriptions }}` template variable. Everything else in system prompt. No clean separation — a cautionary example.

### Cross-Reference Pattern Summary

| Pattern | MAI-UI | MobileAgent V3 | DroidRun | minitap | autodevice | **Ours** |
|---------|--------|----------------|----------|---------|------------|----------|
| App tips location | None | Dynamic injection | App Card (Jinja2) | None | Hardcoded | Hardcoded |
| Tool desc style | Inline | Inline | Minimal param docs | Table + notes | Template var | Full descriptions |
| Behavioral guidance | Minimal | Split across agents | Guidelines section | Critical Rules | Inline everywhere | Mixed everywhere |
| Critical rules first | No | No | No | **Yes** | No | No |
| Examples in prompt | No | No | No | **Yes** | **Yes** | No |
| Conditional sections | No | No | **Yes** (Jinja2) | **Yes** (Jinja2) | **Yes** (Jinja2) | No |

## 3. Proposed New Structure

### Design Principles

1. **Critical Rules First** (from minitap): Most impactful behavioral rules at the top of the prompt. LLMs attend more to the beginning.

2. **Self-Contained Tool Descriptions** (refined from current): Tool descriptions carry their own API docs AND basic usage guidance. System prompt does NOT repeat tool mechanics.

3. **System Prompt = Cognitive Strategy**: The system prompt teaches the agent *how to think*, not *how to call tools*. Think of it as a training manual, not an API reference.

4. **App Tips → Dynamic Injection** (from DroidRun/MobileAgent): App-specific tips are NOT in the base prompt. They are injected conditionally when the agent is operating in a known app context.

5. **Task-Type Guidance → Collapsed** (lesson from autodevice): Avoid expanding into task-type-specific mega-sections. Instead, distill patterns into general principles that apply across task types.

### Proposed Section Hierarchy

```
## Role
  Who you are, what you do (2-3 lines)

## Critical Rules
  5-7 numbered rules. Most important behavioral constraints.
  - One screen action per turn, observe before next
  - Batch cognitive tools with screen actions
  - Never repeat failed actions — switch strategy
  - Loop/cycle warning = immediate strategy pivot
  - Evidence-driven: act on what's on screen, not assumptions
  - Complete promptly when done — no post-completion verification loops
  - Own capsule UI elements: NEVER interact

## Execution Loop
  The observe → think → act → verify cycle (compact, 4-5 lines)

## Strategies
  ### Recovery
    What to do when stuck (3-attempt pivot, shell↔UI fallback)
  ### Information Gathering
    Scratchpad accumulation, turn-budget awareness, partial > nothing
  ### Multi-Step Tasks
    write_todos for planning, batch selection, workflow recording
  ### Verification
    Pre-completion checklist (generic, not app-specific)

## App Context
  {{app_tips}}  ← dynamically injected, empty by default

## Environment
  Device model, screen size, current date
```

**Total target: ~80-100 lines** (down from ~205).

### What Moves Where

| Current Content | Destination |
|----------------|-------------|
| Role (24-28) | **Role** section (trimmed) |
| Core Loop (30-36) | **Execution Loop** (compressed) |
| Calling Conventions (39-47) | **Critical Rules** (batching, one action per turn) |
| mobile_action tips (49-67) | Split: targeting → `MobileActionTool.description`. Loop recovery → **Critical Rules**. Scroll vs swipe → `MobileActionTool.description`. Own UI → **Critical Rules**. |
| open_app tips (69-72) | **DELETE** — already in `OpenAppTool.description` |
| shell tips (74-86) | **DELETE** — already in `ShellTool.description`. Add 2-failure rule to ShellTool.description. |
| ask_user tips (88-91) | **DELETE** — already in `AskUserTool.description` |
| complete_task tips (93-107) | Generic checklist → **Strategies > Verification**. File/data specifics → `CompleteTaskTool.description` |
| Info-Gathering / QA (109-127) | **Strategies > Information Gathering** (generalized) |
| Vision Limitations (128-129) | `CompleteTaskTool.description` or DELETE (edge case) |
| Date Labels (131-136) | **App Context** (dynamic injection) |
| Calendar (137-148) | **App Context** (dynamic injection) |
| Expense (149-152) | **App Context** (dynamic injection) |
| General / "Nh" time format (153-154) | **App Context** (dynamic injection) |
| Markor (156-161) | **App Context** (dynamic injection) |
| OpenTracks (163-166) | **App Context** (dynamic injection) |
| Retro Music (168-170) | **App Context** (dynamic injection) |
| File Operations (172-179) | **Strategies > Verification** (generic principle: verify exact match before acting) |
| Form Filling (181-186) | **Critical Rules** (type with element_index directly) |
| Multi-Item Operations (188-193) | **Strategies > Multi-Step Tasks** |
| Working Memory (195-199) | **Strategies > Information Gathering** |
| Device Environment (201-204) | **Environment** |

## 4. System Prompt vs Tool Description Split Principle

### The Principle

> **Tool descriptions are self-contained API references**: they document what the tool does, its parameters, basic usage patterns, and tool-specific constraints (e.g., "shell is read-only", "only one targeting method").
>
> **The system prompt is a cognitive playbook**: it teaches general thinking strategies, cross-tool coordination, and behavioral principles that span multiple tools.

### Decision Rules

Put it in the **tool description** if:
- It's about how to call this specific tool (parameters, targeting, examples)
- It's a constraint specific to this tool (shell: file ops only; scroll: content direction)
- It would be useful even without reading the system prompt

Put it in the **system prompt** if:
- It coordinates behavior across multiple tools (batch scratchpad with screen actions)
- It's a thinking strategy (evidence-driven verification, pivot after 3 failures)
- It's about when to choose one tool over another
- It's a global behavioral constraint (one screen action per turn)

**Never put it in both.** If the system prompt needs to reference a tool behavior, it should reference the tool by name, not re-explain the behavior.

### Specific Adjustments to Tool Descriptions

**MobileActionTool** — add to description:
- "Prefer semantic selectors (element_index, text) over coordinate taps. Coordinates are last resort."
- Scroll vs swipe guidance is already there — keep as-is
- Remove from system prompt: lines 49-67 entirely

**ShellTool** — add to description:
- "After 2 failed commands with the same approach, switch to UI strategy."
- "Do NOT use for: creating files apps need to see (shell writes won't sync), reading images, root operations."
- Remove from system prompt: lines 74-86

**CompleteTaskTool** — add to description:
- Pre-completion checklist (re-read goal, verify exact match, check filename/data field)
- "Call only after verifying outcome. Do not run post-completion verification passes."
- Remove from system prompt: lines 93-107

**ScratchpadTool** — already well-documented, no changes needed.

**WriteTodosTool** — already well-documented, no changes needed.

**OpenAppTool** — already says "don't navigate drawer". No changes needed.

**AskUserTool** — already documents when to use. No changes needed.

## 5. App Tips: Dynamic Injection Design

### Mechanism

Replace the hardcoded "## App Tips" section with a template variable:

```kotlin
override val systemPrompt: String = """
    ...
    ## App Context
    {{app_tips}}

    ## Environment
    ...
""".trimIndent()
```

### App Tip Resolution

When building the system prompt at runtime:
1. Detect current foreground app (package name from accessibility service)
2. Look up app-specific tips from a static registry (e.g., `AppTips.kt` map)
3. If app is known → inject its tips. If unknown → inject nothing (or a minimal "No specific tips for this app").
4. **Also inject Date Labels and the "Nh = 24h time" tip always** — these are cross-app.

### App Tips Registry

```kotlin
object AppTips {
    // Always injected (cross-app)
    val GLOBAL = """
        - "Nh" format = 24-hour time: "5h" = 05:00, "13h" = 13:00. Never add 12 to hours < 12.
        - Some apps show relative dates (day of week, "tomorrow"). Use the device date to map between absolute dates in the goal and UI labels.
    """.trimIndent()

    val BY_PACKAGE = mapOf(
        "com.simplemobiletools.calendar.pro" to """...""",
        "net.gsantner.markor" to """...""",
        "de.dennisguse.opentracks" to """...""",
        // etc.
    )
}
```

### Trade-off: Static Registry vs Remove Entirely

**Option A: Dynamic injection (recommended)**
- Keeps valuable app-specific knowledge that directly impacts eval scores
- Prevents prompt bloat — only relevant tips are present
- Maintenance: tips live in one place (AppTips.kt), easy to add/edit
- Tokens: ~20-40 tokens when active vs ~100+ tokens for all tips always

**Option B: Remove all app tips**
- Simplest prompt, relies on LLM general knowledge
- Risk: eval regressions on Calendar (NumberPicker), Markor (extension field), OpenTracks (activity type vs track name)
- minitap takes this approach but uses vision-based grounding which helps compensate

**Decision: Option A.** The app tips encode genuinely non-obvious knowledge (e.g., Markor's extension field is a separate input, OpenTracks activity type requires edit view). Removing them would cause eval regressions. Dynamic injection gives the best of both worlds.

## 6. Migration Plan

### Phase 1: Tool Description Enrichment (Low Risk)

Move duplicated content from system prompt into tool descriptions. This is additive — tool descriptions get richer, but nothing is removed from the system prompt yet.

**Files changed:**
- `MobileActionTool.kt` — enrich description
- `ShellTool.kt` — enrich description
- `CompleteTaskTool.kt` — enrich description

**Validation:** Run eval on current task set. Scores should be unchanged (information is now stated twice, which is strictly more than before).

### Phase 2: Create App Tips Infrastructure

Add `AppTips.kt` registry and template variable injection in the prompt builder.

**Files changed:**
- New: `agent/definition/AppTips.kt`
- Modified: `StandaloneAgentDef.kt` — replace hardcoded app tips with `{{app_tips}}` placeholder
- Modified: prompt assembly code (wherever `systemPrompt` is interpolated) — inject app tips

**Validation:** Run eval. Behavior should be identical since the same tips are present, just sourced differently.

### Phase 3: System Prompt Restructure

Rewrite `StandaloneAgentDef.systemPrompt` with the new section hierarchy. Remove all content that now lives in tool descriptions or AppTips.

**Files changed:**
- `StandaloneAgentDef.kt` — full rewrite of systemPrompt string

**Validation:** Full eval run. This is the high-risk phase. Compare scores against baseline. Expect:
- Neutral or positive on most tasks (cleaner prompt, less noise)
- Possible regression on tasks that depended on specific phrasing (monitor closely)

### Phase 4: Iterate

If regressions appear in Phase 3, analyze which specific guidance was lost and add it back — but in the correct location (tool description, app tips, or strategy section), not as a patch to the prompt.

## Trade-offs

### Considered: Multi-Agent Split (like DroidRun/minitap)

Splitting into Planner + Executor would be the "correct" architecture long-term but:
- Major architectural change beyond prompt refactoring
- Our single-agent model works well for the current eval scope
- Adds latency (2 LLM calls per turn)
- **Decision:** Not in scope. This design is for prompt refactoring within the existing single-agent model.

### Considered: Conditional Sections via Templating (like DroidRun's Jinja2)

We could make more sections conditional (e.g., only include multi-item guidance when the task mentions "all" or "each"):
- Adds complexity to prompt assembly
- Risk of incorrect detection → missing guidance
- **Decision:** Only app tips are conditional. Other sections stay static. Revisit if token budget becomes a constraint.

### Considered: Examples in Prompt (like minitap/autodevice)

Adding good/bad examples for common patterns:
- Effective for grounding behavior
- Significantly increases prompt length
- **Decision:** Not in initial refactor. Consider adding 1-2 compact examples in a future tuning round if specific patterns remain problematic.
