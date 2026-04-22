# Design: Agent Identity & Personality Configuration

## Goal

Make agent system prompts file-based and composable so that:
1. Prompt tuning doesn't require code changes or recompilation
2. Identity (role, rules, style) is structured into independent sections
3. Users can eventually customize agent personality via presets or custom overrides

The brief calls out OpenClaw's `.dev.md` template approach. We adapt the useful parts — structured prompt files, separation of concerns — while staying aligned with the existing prompt architecture (AgentDef → system prompt, app skills → user message, template variables).

---

## Current State

### What exists

- **`AgentDef`** abstract class: `id`, `executionRole`, `systemPrompt` (String), `allowedTools`, `requiresDelegationToolRegistration`
- **Three singletons**: `StandaloneAgentDef`, `PlannerAgentDef`, `ExecutorAgentDef` — each with a hardcoded multi-line `systemPrompt` string
- **`SessionAgentRunner.resolvePromptTemplates()`**: replaces `{{device_model}}`, `{{screen_width}}`, etc. in the system prompt string
- **`AgentDefRegistry`**: maps `AgentMode` → `AgentDef`
- **App skills**: loaded from `assets/app_skills/<package>/SKILL.md` at runtime, injected as a user message (not system prompt)
- **Turn prompt anatomy**: system prompt (instructions) + input items [history, memory, app skill, observation]

### What's wrong

- System prompts are ~70-line Kotlin string literals — editing requires code change + rebuild + reinstall
- All concerns (role, rules, execution loop, memory policy, task modes, completion doctrine) are mixed in one string
- No mechanism for user customization or A/B testing prompt variants
- Template resolution is ad-hoc string replacement scattered in `SessionAgentRunner`

---

## Approach

### Core idea: structured prompt files in assets

Move system prompts from Kotlin string literals to markdown files in `assets/agent_prompts/`. Each agent role gets a directory with section files that are concatenated at load time. This mirrors the app skills pattern (asset files loaded at runtime) but for the system prompt layer.

### File layout

```
assets/agent_prompts/
├── _shared/                    # Sections shared across all roles
│   ├── 10_identity.md          # Agent name, purpose, communication style
│   ├── 50_memory.md            # Working memory policy (scratchpad, todos)
│   └── 90_environment.md       # Device environment template block
├── standalone/
│   ├── 20_role.md              # Standalone role definition
│   ├── 30_rules.md             # Critical rules for standalone
│   ├── 40_execution.md         # Execution loop
│   ├── 60_task_modes.md        # Task mode handling
│   └── 70_completion.md        # Completion doctrine
├── planner/
│   ├── 20_role.md
│   ├── 30_rules.md
│   ├── 40_execution.md
│   ├── 60_task_modes.md
│   └── 70_completion.md
└── executor/
    ├── 20_role.md
    ├── 30_rules.md
    ├── 40_execution.md
    ├── 60_query_types.md       # Executor-specific query handling
    └── 70_completion.md
```

**Numbering convention**: Files are prefixed `NN_` for sort order. Shared sections are interleaved with role-specific sections by number. The loader concatenates `_shared/*` merged with `<role>/*` in numeric order.

**Why not one file per role?** Structured sections enable:
- Sharing common sections (`_shared/`) without duplication
- Replacing individual sections for A/B testing or user overrides
- Clear ownership: identity in one place, rules in another

### Section mapping to OpenClaw concepts

| OpenClaw template | Our section | Notes |
|---|---|---|
| `IDENTITY.dev.md` | `_shared/10_identity.md` | Agent name, style, purpose |
| `SOUL.dev.md` | `_shared/10_identity.md` + `<role>/30_rules.md` | Values = identity; priorities = rules |
| `TOOLS.dev.md` | Not in system prompt | Already lives in tool descriptions + app skills |
| `USER.dev.md` | Not needed yet | User context would be a future persona override |
| `AGENTS.dev.md` | `<role>/20_role.md` | Role definition covers multi-agent coordination |

We intentionally skip `TOOLS.dev.md` and `USER.dev.md` as separate files. Tool semantics already live in tool descriptions (per existing architecture). User profile is a Phase 2 concern.

---

## Components

### 1. `AgentPromptRepository` (new)

Loads and assembles prompt sections from assets. Mirrors `AppSkillRepository` pattern.

```kotlin
internal class AgentPromptRepository(private val assetManager: AssetManager) {

    fun loadSystemPrompt(role: String): String {
        val shared = loadSections("agent_prompts/_shared")
        val roleSpecific = loadSections("agent_prompts/$role")
        return mergeSections(shared, roleSpecific)
    }

    private fun loadSections(dir: String): List<PromptSection> {
        // List files in dir, parse NN_ prefix for ordering, read content
    }

    private fun mergeSections(
        shared: List<PromptSection>,
        role: List<PromptSection>
    ): String {
        // Interleave by numeric prefix, concatenate with \n\n
    }
}

private data class PromptSection(val order: Int, val name: String, val content: String)
```

**Loading strategy**: Eager load on first access per role, cache the assembled string. Prompts change only on APK update, so caching is safe.

### 2. `AgentDef` changes

Remove `systemPrompt` from `AgentDef`. Replace with a `promptRole` identifier that the repository uses to load the right prompt directory.

```kotlin
internal abstract class AgentDef {
    abstract val id: String
    abstract val executionRole: AgentExecutionRole
    abstract val promptRole: String          // "standalone", "planner", "executor"
    abstract val allowedTools: Set<String>
    abstract val requiresDelegationToolRegistration: Boolean
}
```

The concrete `*AgentDef` objects become pure metadata — no more string literals.

```kotlin
internal object StandaloneAgentDef : AgentDef() {
    override val id = "standalone"
    override val executionRole = AgentExecutionRole.STANDALONE
    override val promptRole = "standalone"
    override val allowedTools = setOf(
        "mobile_action", "system_button", "wait", "open_app",
        "scratchpad", "shell", "write_todos", "complete_task", "ask_user"
    )
    override val requiresDelegationToolRegistration = false
}
```

### 3. `SessionAgentRunner` changes

Inject `AgentPromptRepository`. Load prompt via `promptRole` instead of reading `agentDef.systemPrompt`.

```kotlin
// Before
systemPrompt = resolvePromptTemplates(agentDef.systemPrompt)

// After
val rawPrompt = promptRepository.loadSystemPrompt(agentDef.promptRole)
systemPrompt = resolvePromptTemplates(rawPrompt)
```

`resolvePromptTemplates()` stays unchanged — `{{device_model}}` etc. still work because they're in `90_environment.md`.

### 4. Template variable resolution (unchanged)

The `{{variable}}` replacement in `resolvePromptTemplates()` continues to work on the assembled string. No changes needed. The `90_environment.md` file contains the template block:

```markdown
## Device Environment
- Device: {{device_model}} ({{device_manufacturer}})
- Screen: {{screen_width}}x{{screen_height}}
- Date: {{current_date}}
```

### 5. Sub-agent prompt loading

`IsolatedSubAgentRunner` currently gets `ExecutorAgentDef.systemPrompt`. Change to pass `AgentPromptRepository` (or pre-loaded prompt string) so executor prompts are also file-based.

The simplest path: `SessionAgentRunner` pre-loads the executor prompt and passes it through to the delegation tool factory, same as today but sourced from the repository instead of the Kotlin literal.

---

## Interactions

### Prompt assembly flow (per turn)

```
SessionAgentRunner.start()
  │
  ├─ agentDef = AgentDefRegistry.mainFor(mode)
  ├─ rawPrompt = promptRepository.loadSystemPrompt(agentDef.promptRole)  // NEW
  ├─ systemPrompt = resolvePromptTemplates(rawPrompt)                     // unchanged
  │
  └─ Agent.run() → TurnPlanningPhaseRunner
       │
       ├─ instructions = config.systemPrompt        // from above
       ├─ appSkill = loadAppSkill(package)           // unchanged
       │
       └─ Turn.runStreaming(
            systemPrompt = instructions,
            inputItems = [history, memory, appSkill, observation],
            tools = filtered tool schemas
          )
```

No change to `TurnPlanningPhaseRunner`, `PromptBuilder`, or `Turn`. The only change is *where* the system prompt string originates.

### Section merge order example (standalone)

```
_shared/10_identity.md      →  ## Identity
standalone/20_role.md        →  ## Role
standalone/30_rules.md       →  ## Critical Rules
standalone/40_execution.md   →  ## Execution Loop
_shared/50_memory.md         →  ## Working Memory
standalone/60_task_modes.md  →  ## Task Modes
standalone/70_completion.md  →  ## Completion
_shared/90_environment.md    →  ## Device Environment
```

Shared sections slot in by number, giving a coherent document without duplication.

---

## Trade-offs

### Considered: one monolithic `.md` file per role (no sections)

Simpler loader, but loses the ability to share common sections and override individual parts. Would still require duplication of memory/environment blocks across three files. Rejected — marginal complexity increase of section merging pays for itself in maintainability.

### Considered: YAML/JSON prompt config with content references

Over-engineered for the current need. Markdown files are human-readable, editable, and match the existing app skills convention. If we need structured metadata (e.g., section dependencies, conditional inclusion), we can add a manifest later.

### Considered: runtime prompt loading from device storage (not just assets)

Would enable user editing without APK rebuild. But introduces security concerns (prompt injection from filesystem), versioning complexity, and doesn't align with Phase 1 goals. Better to do this in Phase 2 with proper sandboxing.

### Considered: keeping systemPrompt in AgentDef but loading from file

Could work, but conflates "agent definition metadata" with "prompt content loading." Separating concerns (AgentDef = what tools/role, Repository = what to say) is cleaner and makes testing easier.

---

## What's NOT in scope

- **User-facing persona selection UI** (Phase 2) — requires settings screen, persistence, and persona preset definitions
- **Task-specialized personas** (Phase 3) — requires task classification before prompt assembly
- **Tool description externalization** — tool semantics already live in tool descriptions and app skills; no need to move them
- **Prompt versioning / A/B framework** — extracting to files enables this naturally; the framework itself is separate work

---

## Implementation sketch

1. Create `assets/agent_prompts/` directory structure with section files extracted from current `*AgentDef.systemPrompt` strings
2. Implement `AgentPromptRepository` (asset loader + section merger)
3. Change `AgentDef`: replace `systemPrompt: String` with `promptRole: String`
4. Update `SessionAgentRunner` to use `AgentPromptRepository`
5. Update `IsolatedSubAgentRunner` wiring for executor prompt loading
6. Delete hardcoded prompt strings from `*AgentDef.kt`
7. Verify via existing eval suite that prompt content is identical (diff the assembled output against old literals)

Estimated file changes: ~8 files modified, ~20 asset files created, 1 new Kotlin file.
