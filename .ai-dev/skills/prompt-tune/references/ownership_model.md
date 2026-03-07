# Ownership Model — Decision Tree

Distilled from `doc/autotune/round_4/prompt_refactor/final/design.md`.

## Decision Tree

Given a proposed rule or change, walk top-to-bottom:

```
1. Is it about a specific app's workflow, pitfall, or UI quirk?
   YES → APP SKILL: assets/app_skills/<package>/SKILL.md
   NO  → continue

2. Is it about a specific tool's parameters, preconditions, or limitations?
   YES → TOOL DESCRIPTION: tool/impl/<ToolName>Tool.kt
   NO  → continue

3. Is it cross-tool behavioral policy (retry, fallback, completion, evidence, memory)?
   YES → CORE PROMPT: agent/definition/StandaloneAgentDef.kt
   NO  → continue

4. Is it an overfit patch for a single eval task with no generalizable value?
   YES → REMOVE: do not add anywhere
   NO  → re-examine; it likely fits one of the above categories
```

## What Goes Where

### Core System Prompt
- Agent role and success definition
- Turn contract (one action, then observe)
- Evidence-driven behavior
- Retry / pivot policy
- Cross-tool coordination (e.g., shell-to-UI fallback)
- Working-memory policy
- Task modes (manipulation / information / blocked / unsupported)
- Completion policy
- Global environment warnings (e.g., ignore capsule controls)

### Tool Descriptions
- What the tool does
- Parameters and valid combinations
- Tool-local preconditions and limitations
- Tool-local examples
- Examples per tool:
  - `mobile_action`: selector forms, scroll vs swipe semantics, coordinate fallback
  - `shell`: file-oriented scope, blocked command classes, read-only examples
  - `open_app`: direct launch behavior
  - `scratchpad`: read/write/delete semantics
  - `write_todos`: full-list replacement semantics
  - `complete_task`: completion contract (tool-local part)

### App Skills
- App-specific navigation workflows
- Inaccessible widgets or misleading screens
- App-specific entry / edit / save flows
- App-specific shell caveats
- App-specific formatting conventions
- Examples: calendar date navigation, Markor file dialog quirks, Pro Expense save/verify flow

## Anti-Patterns

| Anti-Pattern | Why It's Wrong | Fix |
|---|---|---|
| App tip in core prompt | Bloats global prompt, irrelevant when app not active | Move to `app_skills/<package>/SKILL.md` |
| Tool mechanics in core prompt | Duplicates tool description, drifts over time | Move to tool's `description` property |
| Cross-tool policy in tool description | Creates inconsistency across tools | Move to core prompt |
| Eval-specific hack as permanent rule | Overfits to one task, may hurt others | Remove or generalize |
| Same rule in multiple layers | Maintenance burden, drift risk | Keep in one layer only |
