# Agent Identity and Personality - Aligned Design

## Goal

Adopt OpenClaw's useful idea of structured agent identity files without breaking Android Agent's existing prompt ownership model.

The design must achieve four things:

1. Prompt authoring for agent behavior should stop depending on editing Kotlin string literals.
2. Identity/personality should become configurable per session.
3. Safety-critical execution policy must stay clearly separated from soft persona/style.
4. Planner and executor must stay aligned under one session identity.

## Ground Truth From Current Repo

Today:

- `AgentDef` owns `executionRole`, `allowedTools`, delegation requirements, and a monolithic `systemPrompt`.
- `StandaloneAgentDef`, `PlannerAgentDef`, and `ExecutorAgentDef` each embed a large multi-line prompt string.
- `SessionAgentRunner` resolves template variables once and freezes the final prompt into `AgentExecutionConfig`.
- `PromptBuilder` already keeps other prompt layers separate:
  - tool semantics live in tool descriptions
  - app/package behavior lives in `app_skills/<package>/SKILL.md`
  - task/history/observation live in runtime input items

That means the remaining system prompt content is mostly role contract and agent identity. The design should split those two cleanly, not re-mix them.

## Design Principles

1. Keep ownership explicit.
2. Keep runtime assembly simple.
3. Use files for authoring, not for adding a new mini language.
4. Do not re-centralize tool rules or app knowledge into persona files.
5. Freeze identity selection at session start.
6. Make planner/executor inheritance automatic.

## Final Architecture

### 1. Two prompt layers, not one

The final instructions are composed from two distinct layers:

1. **Role contract**
   - immutable for a given app build
   - owns role definition, critical rules, execution loop, working memory policy, task modes, completion doctrine
   - file-authored for maintainability
   - not user-configurable

2. **Identity profile**
   - session-selected
   - owns identity, values, communication style, and optional role addenda
   - configurable through preset selection in v1
   - can later support validated custom profiles

Everything else keeps its current owner:

- tool usage semantics: tool descriptions
- app/package guidance: app skills
- task/history/observation: runtime input items

### 2. Asset layout

Use two separate asset trees so the boundary is impossible to miss:

```text
app/src/main/assets/
  agent_contracts/
    standalone/
      10_role.md
      20_rules.md
      30_execution.md
      40_memory.md
      50_task_modes.md
      60_completion.md
      90_environment.md
    planner/
      10_role.md
      20_rules.md
      30_execution.md
      40_memory.md
      50_task_modes.md
      60_completion.md
      90_environment.md
    executor/
      10_role.md
      20_rules.md
      30_execution.md
      40_memory.md
      50_task_modes.md
      60_completion.md
      90_environment.md

  agent_identities/
    balanced/
      IDENTITY.md
      PRINCIPLES.md
      USER.md
      STANDALONE.md
      PLANNER.md
      EXECUTOR.md
    efficient/
      IDENTITY.md
      PRINCIPLES.md
      USER.md
    careful/
      IDENTITY.md
      PRINCIPLES.md
      USER.md
```

Rules:

- `agent_contracts/` files are part of the shipped app contract. They are prompt text, but they are not "persona presets."
- `agent_identities/` files are the personality layer. Optional role addenda default to empty.
- Number prefixes define contract section order.
- Identity files use a fixed schema instead of heading parsing.

### 3. Runtime types

Conceptually:

```kotlin
data class IdentityProfile(
    val id: String,
    val identity: String,
    val principles: String,
    val userContract: String,
    val roleAddenda: Map<AgentExecutionRole, String> = emptyMap()
)
```

`AgentDef` stops owning raw prompt text. It keeps only role metadata and a role key:

```kotlin
internal abstract class AgentDef {
    abstract val id: String
    abstract val executionRole: AgentExecutionRole
    abstract val promptRole: String
    abstract val allowedTools: Set<String>
    abstract val requiresDelegationToolRegistration: Boolean
}
```

New runtime components:

- `AgentContractRepository`
  - loads ordered contract sections from `agent_contracts/<role>/`
- `AgentIdentityRepository`
  - loads and validates `agent_identities/<id>/`
- `AgentInstructionComposer`
  - assembles final instructions from contract + identity + device template values

### 4. Final prompt shape

The assembled system prompt is still one plain string. Its sections become:

1. Role
2. Identity
3. Principles
4. User Contract
5. Critical Rules
6. Execution Loop
7. Working Memory
8. Task Modes
9. Completion
10. Device Environment

Ownership:

- sections 1, 5, 6, 7, 8, 9, 10 come from the role contract
- sections 2, 3, 4 come from the selected identity profile
- optional role addenda from the identity profile are inserted under the relevant role section

This gives file-based authoring without losing the hard boundary between contract and persona.

### 5. Session-scoped identity selection

Add `identityProfileId: String` to `SessionConfig`.

Selection flow:

1. `AppSettingsStore` persists the selected profile id.
2. Session creation copies it into `SessionConfig`.
3. `SessionAgentRunner` resolves the main `AgentDef`.
4. `AgentInstructionComposer` builds the instructions from:
   - `promptRole`
   - `identityProfileId`
   - device template values
5. The resulting instruction string is frozen into `AgentExecutionConfig`.

Rules:

- identity does not change mid-session
- settings changes only affect future sessions
- invalid ids fall back to `balanced`
- fallback must be explicit and logged

### 6. Planner and executor inheritance

In `PRO` mode:

- planner and executor share the same `identityProfileId`
- executor uses `promptRole = "executor"` with the same identity profile
- optional `EXECUTOR.md` addendum may alter tone or emphasis, but not tool policy

This fixes the current gap where delegated execution is effectively tied to a static executor prompt path.

### 7. Validation and safety rules

Identity profiles are externalized content and must be validated.

Required:

- only the allowed files may exist
- `IDENTITY.md`, `PRINCIPLES.md`, and `USER.md` are required
- section length bounds
- plain text or markdown only

Forbidden ownership in identity files:

- tool allowlist changes
- model routing
- delegation topology
- app skill content
- screen-specific or task-specific instructions

Role contracts are not user-editable in v1. That is the key safety boundary.

## Why this design

### What it takes from the Claude design

- prompt authoring moves out of Kotlin string literals and into files
- the asset-loading pattern matches existing app-skill usage
- the runtime change stays localized to instruction sourcing

### What it takes from the Codex design

- role contract and identity profile are different things
- identity is session-scoped
- planner/executor inheritance is a first-class design requirement
- tool/app guidance must remain outside the persona layer

### Why not fully editable prompt packs

That would blur:

- immutable execution contract
- tunable personality
- tool guidance
- app/package guidance

The repo has already been moving in the opposite direction. Reversing that would be a mistake.

### Why not keep role contract only in code

That would preserve ownership, but it would miss the brief's immediate operational win: prompt iteration without rebuild for the main contract text.

The correct compromise is:

- contract text is file-authored
- contract ownership remains internal and protected
- identity is the configurable layer

## Preset scope for v1

Ship only a small preset set:

- `balanced` - default, closest to current behavior
- `efficient` - terser, more execution-forward
- `careful` - more explicit about verification and blockers

Do not add:

- task-based auto persona switching
- app-based auto persona switching
- free-form user text editing of the full system prompt

Those all add hidden behavior changes or collapse the ownership boundary.

## Affected code paths

Expected implementation touch points:

- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/AgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/SubAgentRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionConfig.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt`
- new repository/composer classes under the prompt/agent layer
- new assets under `app/src/main/assets/agent_contracts/` and `app/src/main/assets/agent_identities/`

## Non-goals

- changing tool schemas or tool descriptions
- replacing app skills
- mid-session identity mutation
- backward-compatibility shims around the old prompt strings
- task-specific persona auto-selection
- full free-form prompt editing in v1

## Recommendation

Implement the split now:

1. extract current role prompt content into internal contract assets
2. introduce session-scoped identity profiles as a separate asset layer
3. compose final instructions in one runtime component
4. keep planner/executor on the same selected identity

That gets the maintainability win from file-based prompt authoring and the product win from configurable identity without weakening prompt ownership discipline.
