# Agent Identity / Personality Configuration

## Goal

Support configurable agent identity and personality without changing the execution model or muddying prompt ownership.

Android Agent already has a strong prompt split:
- `AgentDef` owns role/tool/delegation policy
- tool descriptions own tool semantics
- app skills own app-specific guidance
- runtime input owns task/history/observation

The design should preserve that split while making the agent's identity, values, and communication style configurable per session.

## Current Constraints

From the current code/docs:

- [`AgentDef`](app/src/main/kotlin/com/moonkey/androidagent/agent/definition/AgentDef.kt) is the source of truth for each role's `executionRole`, `allowedTools`, delegation requirement, and `systemPrompt`.
- [`StandaloneAgentDef`](app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt), [`PlannerAgentDef`](app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt), and [`ExecutorAgentDef`](app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt) currently embed one large prompt string each.
- [`SessionAgentRunner`](app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt) resolves prompt templates once at session start and passes the resolved string into `AgentExecutionConfig`.
- [`turn_prompt_anatomy.md`](doc/main/agent/turn_prompt_anatomy.md) makes prompt ownership explicit:
  - system prompt = cross-tool policy
  - tool descriptions = tool usage semantics
  - app skills = app/package guidance
- Delegated executor runs currently come from a static executor definition path, so planner/executor personality cannot cleanly inherit from one session-level selection.

This means the problem is not "how do we add more prompt text?" The real problem is "how do we separate immutable role contract from configurable personality without re-centralizing tool/app guidance into the system prompt?"

## Design

### 1. Split role contract from identity profile

Introduce two distinct concepts:

- **Role contract**: immutable instructions tied to `AgentExecutionRole`
- **Identity profile**: configurable instructions tied to session/persona selection

Role contract owns:
- role definition
- critical rules
- execution loop
- working-memory policy
- task modes
- completion doctrine
- tool/delegation boundaries

Identity profile owns:
- identity and voice
- operating values / priorities
- user communication contract
- optional role-specific style addenda

Identity profiles do **not** own:
- tool semantics
- app knowledge
- allowed tool sets
- delegation topology
- turn limits
- model routing
- safety-critical execution rules

That hard boundary is the core design rule.

### 2. Keep the current prompt anatomy, add a small identity layer

Keep the current system-prompt shape, but insert identity-specific sections ahead of the operational rules:

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

Interpretation:

- `Role`, `Critical Rules`, `Execution Loop`, `Working Memory`, `Task Modes`, and `Completion` remain role-contract owned.
- `Identity`, `Principles`, and `User Contract` come from the selected identity profile.
- `Device Environment` stays runtime-rendered exactly as today.

This keeps personality configurable while preserving the existing prompt architecture described in [`turn_prompt_anatomy.md`](doc/main/agent/turn_prompt_anatomy.md).

### 3. Adapt the OpenClaw idea to Android Agent's current architecture

From the source brief, the useful pieces map as follows:

- `IDENTITY.dev.md` -> `Identity`
- `SOUL.dev.md` -> `Principles`
- `USER.dev.md` -> `User Contract`
- `AGENTS.dev.md` -> optional per-role addenda (`STANDALONE`, `PLANNER`, `EXECUTOR`)
- `TOOLS.dev.md` -> **not part of identity**

`TOOLS.dev.md` is intentionally excluded because Android Agent already moved tool-local guidance into tool descriptions. Pulling tool usage policy back into persona files would be a regression.

Likewise, app/package behavior remains in app skills, not identity profiles.

### 4. Make identity selection session-scoped

Add session-level persona selection, e.g. `identityProfileId` in `SessionConfig`.

Rules:

- The selected identity is frozen when the session starts.
- A running session does not change personality mid-task.
- In `BASIC` mode, the standalone agent uses the selected profile.
- In `PRO` mode, both planner and executor use the same selected profile.
- Role-specific differences come from small addenda, not separate unrelated personas.

This avoids prompt drift during a task and keeps planner/executor behavior coherent.

### 5. Compose instructions through one dedicated runtime component

Add a dedicated instruction composer, for example:

- `AgentIdentityRepository`
- `AgentInstructionComposer`

Responsibilities:

- `AgentIdentityRepository` loads a validated `IdentityProfile`
- `AgentInstructionComposer` combines `AgentDef` role contract + `IdentityProfile` + device template values into the final instruction string

This implies `AgentDef` should stop exposing one monolithic `systemPrompt: String`. Instead it should expose a structured role contract or prompt sections that the composer renders into the final system prompt.

This is the key simplification:

- code owns hard execution policy
- config/assets own soft persona
- one composer owns final rendering

### 6. Fix delegated executor inheritance at the architecture level

The executor cannot keep a permanently static prompt if identity is session-configurable.

Design change:

- built-in sub-agent definitions should reference the executor role, not a permanently pre-rendered prompt string
- the sub-agent runner should resolve the executor's final instructions at delegation time using the parent session's `identityProfileId`

Result:

- planner and executor share one persona pack
- only role-specific style addenda differ
- delegation behavior stays role-contract driven

Without this change, planner/executor identity will drift and the feature will be inconsistent in the one place where multi-agent behavior matters most.

## Data Model

Conceptual model:

```kotlin
data class IdentityProfile(
    val id: String,
    val identity: String,
    val principles: String,
    val userContract: String,
    val roleAddenda: Map<AgentExecutionRole, String> = emptyMap()
)

data class RoleContract(
    val role: String,
    val criticalRules: String,
    val executionLoop: String,
    val workingMemory: String,
    val taskModes: String,
    val completion: String
)
```

Notes:

- `RoleContract` is code-owned and versioned with the app.
- `IdentityProfile` is config-owned.
- `roleAddenda` is optional. Missing addendum means "no extra style for this role."

## Storage Format

Use a fixed asset layout instead of free-form prompt blobs:

```text
app/src/main/assets/agent_identities/<id>/
  IDENTITY.md
  PRINCIPLES.md
  USER.md
  STANDALONE.md   # optional
  PLANNER.md      # optional
  EXECUTOR.md     # optional
```

Why this format:

- it matches the repo's existing pattern of markdown assets for prompt-like content
- it avoids parsing markdown headings or inventing a mini DSL
- it keeps authoring/diffing simple
- it gives the runtime a fixed schema without requiring a large parser

The runtime contract is the fixed file set, not arbitrary markdown structure.

## Runtime Interaction

### Session startup

1. Validate and resolve the selected `identityProfileId`
2. Resolve the main `AgentDef` from `AgentMode`
3. Load the `IdentityProfile`
4. Compose the final instruction string
5. Freeze it into `AgentExecutionConfig`

### Main turn loop

- Turns continue exactly as they do today
- Only the source of the `instructions` string changes

### Delegation

1. Planner delegates an atomic task
2. Sub-agent runner resolves executor instructions using the same `identityProfileId`
3. Executor runs with its executor role contract + shared identity profile + executor addendum

### Session end

- Identity selection is discarded with the session
- Settings changes only affect future sessions

This keeps identity resolution outside the turn loop and makes prompt behavior deterministic for a given session.

## Validation Rules

Identity profiles are external input and should be validated as data, not trusted as raw prompt blobs.

Required:

- fixed allowed files/fields only
- required sections: `IDENTITY`, `PRINCIPLES`, `USER`
- bounded length per section
- plain text/markdown only

Forbidden ownership:

- tool allowlist changes
- model selection/routing
- app-skill content
- delegation topology changes

Missing optional role addenda should resolve to empty content.

The system does not need semantic NLP validation in v1. Schema validation, size bounds, and clear prompt ownership are enough.

## Recommended Preset Scope

Built-in presets should stay small:

- `balanced`: default, close to today's behavior
- `efficient`: terser, more execution-forward
- `careful`: more explicit about verification and blockers

Do **not** add automatic task-specific persona switching in v1.

Reason:

- task-specific guidance already has two stronger homes: app skills and current observation
- auto-switching adds hidden behavior changes
- session-level selection is simpler to debug and evaluate

## Components Affected

Expected touch points when implementing:

- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/AgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/SubAgentRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionConfig.kt`
- new identity repository/composer classes
- new assets under `app/src/main/assets/agent_identities/`

## Trade-offs

### Why this design wins

- It solves the actual problem: configurable personality without blurring execution policy.
- It preserves the prompt ownership model already documented in the repo.
- It keeps planner and executor aligned under one session identity.
- It avoids reintroducing tool/app guidance into the system prompt.
- It gives a clean path to future user customization.

### Alternatives considered

1. **Keep one raw `systemPrompt` string and inject `{{persona}}`**
   - Too brittle. Prompt ownership remains unclear and the monolith survives.

2. **Move all prompt logic into editable files**
   - Too much power in the wrong layer. Tool rules and role safety would become configuration instead of contract.

3. **Auto-select persona by task/app**
   - Higher complexity, worse observability, and unnecessary for the core problem.

## Non-goals

- changing tool schemas or tool descriptions
- replacing app skills
- mid-session persona mutation
- backward-compat prompt shims
- a full natural-language prompt editor as the primary storage model

## Recommendation

Implement the architectural split now:

- `AgentDef` -> role contract
- identity assets/config -> identity profile
- one runtime composer -> final instructions

Start with a single built-in `balanced` profile if needed, but build the runtime around the final separation from day one. That avoids a second prompt refactor when presets or user customization are added.
