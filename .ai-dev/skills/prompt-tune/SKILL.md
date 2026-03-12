---
name: prompt-tune
description: Apply prompt, tool description, and app skill changes based on cog-tune diagnosis. Enforces the three-layer ownership model (core prompt → tool descriptions → app skills) to keep changes in the right place.
---

# Prompt Tune

## Overview

Treatment skill for agent cognition issues. Takes a diagnosis (from `/cog-tune` or `/autotune` analysis) and applies the change to the correct ownership layer. Prevents prompt drift, duplication, and overfit patches.

## Principles

**MUST read `../autotune/references/tuning_principles.md` before applying any change.** Every prompt, tool-description, or app-skill change must satisfy its three gates:

- **Anti-overfit**: reject patches that only help one eval task.
- **Token minimalism**: keep the smallest instruction that still changes behavior.
- **Generalization**: prefer rules that also help unseen real-user tasks in the same workflow family.

## When to Use

- After `/cog-tune` produces a diagnosis with proposed changes
- After `/autotune` analysis identifies prompt/tool/skill improvements
- When adding new app-specific knowledge
- When refactoring existing prompt text across layers

Do NOT use for diagnosis — that is `/cog-tune`'s job.

## Three-Layer Ownership Model

> **Core system prompt** owns cross-tool behavioral policy.
> **Tool descriptions** own tool-local semantics.
> **App skills** own package-specific workflows, pitfalls, and non-obvious UI knowledge.

One rule, one owner. See `references/ownership_model.md` for the full decision tree.

## Workflow

### 1. Classify the change

For each proposed fix from the diagnosis, determine ownership:

| If the rule is... | Owner | File |
|---|---|---|
| Cross-tool behavioral policy | Core prompt | `agent/definition/StandaloneAgentDef.kt` |
| Tool-local mechanics/parameters | Tool description | `tool/impl/<ToolName>Tool.kt` |
| App-specific workflow/pitfall | App skill | `assets/app_skills/<package>/SKILL.md` |
| System-injected warning text | Infra | `agent/cognition/policy/LoopDetectionPolicy.kt`, `ExecutorStepPolicy.kt` |
| None of the above | Remove | Do not add anywhere |

Use the decision tree in `references/ownership_model.md` when ownership is ambiguous.

### 2. Read the target before editing

Always read the current content of the target file first. Understand what already exists before adding or modifying.

### 3. Apply the change

**Core prompt** (`StandaloneAgentDef.kt`):
- Target: ~80-100 lines. If approaching 120+, audit for content that belongs in tool descriptions or app skills.
- 7 sections: Role, Critical Rules, Execution Loop, Working Memory, Task Modes, Completion, Device Environment.
- Rules should be concise imperatives. No examples in the core prompt — those belong in tool descriptions.

**Tool descriptions** (`tool/impl/*Tool.kt`):
- Each tool's `description` property is self-contained.
- Include: what the tool does, parameter semantics, valid combinations, tool-local preconditions, tool-local limitations, tool-local examples.
- Do NOT duplicate cross-tool policy (retry logic, fallback strategy) here.

**App skills** (`app/src/main/assets/app_skills/<package>/SKILL.md`):
- Use full package name as directory name.
- Follow the standard format (see existing skills like `net.gsantner.markor/SKILL.md`).
- Content: stable app-specific rules, non-obvious workflows, app-specific pitfalls, app-specific shell caveats.
- Keep concise — the entire file is loaded every turn when the app is foreground.
- No tool API docs, no global retry policy, no duplicated core prompt rules.

### 4. Anti-pattern check

Before finalizing, verify:

- [ ] **No duplication**: The same rule does not appear in multiple layers.
- [ ] **No cross-layer leakage**: Core prompt has no app-specific content. Tool descriptions have no cross-tool policy. App skills have no tool API docs.
- [ ] **No overfit**: The change passes `../autotune/references/tuning_principles.md` and is generalizable, not a one-off patch for a single eval task.
- [ ] **Conciseness**: Core prompt stays under ~100 lines. App skills stay under ~20 lines. Tool descriptions stay focused on tool-local semantics.
- [ ] **No phantom rules**: Avoid adding rules that address hypothetical problems. Every rule should trace to observed evidence.

### 5. Validate

- Build: `./gradlew assembleDebug`
- For core prompt or tool description changes: run a quick debug session to verify the prompt assembles correctly.
- For app skill changes: verify the file loads by checking `PromptBuilder` output in debug logs.

## Key Files

### Prompt layers
- Core system prompt: `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
- Prompt assembly: `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptBuilder.kt`
- App skills directory: `app/src/main/assets/app_skills/<package>/SKILL.md`

### Tool descriptions
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ShellTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/CompleteTaskTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ScratchpadTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/WriteTodosTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/AskUserTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/SystemButtonTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/WaitTool.kt`

### Design reference
- Ownership model design: `doc/autotune/round_4/prompt_refactor/final/design.md`
- Shared tuning principles: `.ai-dev/skills/autotune/references/tuning_principles.md`

### Existing app skills
Find all with: `ls app/src/main/assets/app_skills/`

## LLM Best Practices

### Prompting
- Put clear, explicit instructions first; be specific about desired behavior.
- Use few-shot examples only when needed and only in tool descriptions, not the core prompt.
- Define success criteria and evaluate with targeted tests; not all failures are prompt issues.

### Tool definitions
- Define tools with clear names, descriptions, and JSON input schemas; the model relies on these definitions.
- Prefer strict schema validation to reduce malformed tool calls.
- Provide tool-call examples for ambiguous tools or arguments.

### Context management
- Keep system prompt stable; move task-specific detail into user context (app skills) or artifacts.
- Trim redundant history; summarize and point to artifacts for evidence.
- **System-injected warnings** (stable screen, final turn) are part of the LLM's input. They must state facts only ("Screen has not changed for 5 turns"), never opinions or strategy suggestions ("Try a different approach"). The LLM is the reasoning engine — give it facts and let it decide.

### Sources
- [Anthropic prompt engineering overview](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/overview)
- [Anthropic tool use overview](https://docs.anthropic.com/en/docs/build-with-claude/tool-use/overview)
- [OpenAI prompting guide](https://platform.openai.com/docs/guides/prompting)
- [OpenAI function calling guide](https://platform.openai.com/docs/guides/function-calling)
