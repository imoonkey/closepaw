# ClosePaw Agent Skills Support — Final Design

## Goal

Add minimal agentskills.io `SKILL.md` support to ClosePaw so the Android agent can discover, catalog, activate, and use general-purpose Agent Skills alongside the existing foreground-app skill system.

Agent Skills are task/capability guidance. Existing app skills are package-scoped UI guidance. The two systems coexist without changing `assets/app_skills/<package>/SKILL.md` behavior.

Success criteria:

- Skills discoverable from Android-safe locations
- Model sees a compact catalog without loading every skill body
- Model or user can activate a skill
- Skill instructions never bypass ClosePaw tools, role allowlists, `PolicyEngine`, app privacy gates, or user approval

## Non-Goals (v1)

- No remote registry or install UI (v1 uses ADB push; future: skill hub integration)
- No migration of existing app skills into Agent Skills
- No new permission paths around Android sandboxing
- No special compaction handling
- No activation budget enforcement (context window is the natural budget)
- No activation turn barrier (existing TurnToolPolicy is sufficient)

## Existing Architecture to Preserve

Current app skills:

- Live under `app/src/main/assets/app_skills/<package>/SKILL.md`
- Loaded by `AppSkillRepository.load(packageName)`
- Injected per turn by `TurnPlanningPhaseRunner`
- Keyed by foreground Android package name

App skill frontmatter will be upgraded to agentskills.io-compatible format — adding `name` and `metadata.package` fields. Naming convention: `app-<short-name>`, where short-name is the user-recognizable app name (e.g., `app-chrome`, `app-uber`, `app-tasks`). Package name stored in `metadata.package`. Directory structure and load path unchanged. Both skill types share the same frontmatter parser.

Current prompt order is unchanged:

```text
History -> Working Memory -> Recalled Memory -> App Skill -> Observation
```

Agent Skills do not alter this order. Catalog is appended to the system prompt. Skill body enters history as a tool result.

## Skill Root

Single skill root:

```text
context.filesDir/skills/<skill-name>/SKILL.md
```

No bundled assets. All skills are installed to `filesDir/skills/`, where shell can access them directly.

- v1: install via ADB push
- Future: integrate with skill hubs (e.g., Hermes Agent), filter for compatibility, copy to filesDir

Discovery scans one level deep only — no recursion.

## Skill Format

Standard agentskills.io directory structure:

```text
skill-name/
  SKILL.md
  scripts/       # optional
  references/    # optional
  assets/        # optional
```

MVP validation:

- Directory name must match frontmatter `name`
- `name`: 1-64 chars, lowercase-hyphen
- `description`: required, 1-1024 chars
- Invalid skills are skipped and logged, not shown to the model

## Frontmatter Parsing

SnakeYAML + lenient fallback:

- SnakeYAML parses full YAML including nested `metadata` map
- Malformed YAML (e.g., unquoted colons): catch exception, fall back to regex extraction of `name` + `description`
- Parses all fields: name, description, license, compatibility, allowed-tools, metadata
- v1 behavior depends only on name + description; other fields are informational
- App skills share this parser — `metadata.package` stores the Android package name

## Scripts and Resources

Skills live in `filesDir/`, so the model accesses all files via `ShellTool`:

- **Simple shell scripts**: executable via ShellTool (Android mksh, not bash)
- **Bash-specific syntax and Python**: not available
- **references/ and assets/**: readable via shell commands (`cat`, etc.)
- **No dedicated resource tool needed** — all files are on the filesystem
- `allowed-tools` frontmatter is advisory only, does not alter role allowlists

## Runtime Model

### Data Model

```kotlin
data class AgentSkillEntry(
    val name: String,
    val description: String,
    val filePath: String   // absolute path in filesDir
)
```

Catalog is immutable within a session. Body is not preloaded — read from filePath on activation.

### Trust

In v1, all discovered skills are trusted. Installation (ADB push) is the consent gate. Code is structured so a trust-checking layer can be inserted later for download/import flows.

### Catalog

The skill catalog is part of the system prompt, sent automatically with every LLM call. No per-turn logic needed.

Format:

```text
## Available Skills
The following skills provide specialized instructions. Call activate_skill
with a skill's name to load its full instructions.

- calendar-date-math: Compute exact date ranges for calendar/task queries.
- image-table-reading: Extract tabular values from screenshots before entering forms.
```

Omitted when no skills are discovered.

### Manager

Session-scoped `AgentSkillManager`:

```kotlin
class AgentSkillManager(
    private val skillsDir: File
) {
    fun catalogPrompt(): String?
    fun activate(name: String): ActivationResult
    fun activateExplicitMentions(text: String): List<ActivationResult>
}
```

Responsibilities:

- Produce compact catalog text for system prompt
- Track active skill names in stable order (LinkedHashSet)
- Deduplicate activation (idempotent)
- Support `/skill-name` explicit activation — match against known catalog names at word boundaries

Stored in `SessionServices`. `SessionServices.copy()` preserves it so delegated executors consume skills activated by the planner.

### Activation Tool

`activate_skill` tool, available to all roles:

```text
activate_skill(name, agent_thought?)
```

Tool description (one line): `"Activate a skill by name. See the skill catalog in the system prompt for available skills."`

`activate_skill` is a regular non-screen-changing tool with no special turn policy. Existing `TurnToolPolicy` handles its coexistence with other tools correctly.

Behavior:

- Validate skill name against catalog
- Return "already active" for duplicates
- On success: read SKILL.md, strip frontmatter, return full body as tool result
- Never grant additional tools or permissions

Registration:

- `ToolName`: mark as non-screen-changing (`Unknown` defaults to screen-changing)
- Add to all `AgentRoleDef.allowedTools` (Standalone, Planner, Executor)
- Register only when catalog is non-empty

## Compaction

No special handling. Skill body enters conversation history as a tool result and participates in normal compaction. The catalog remains visible in the system prompt, so the model can re-call `activate_skill` to reload a compacted skill.

## Activation Flows

### Session Start

1. `SessionServices.create` scans `filesDir/skills/*/SKILL.md`, builds catalog
2. Invalid skills are skipped and logged. All valid skills are trusted in v1
3. `AgentSkillManager` stores the immutable catalog
4. Catalog section appended to system prompt
5. `ActivateSkillTool` registered only when catalog is non-empty

### User-Explicit Activation

1. User goal contains `/skill-name`
2. `TurnPlanningPhaseRunner` calls `activateExplicitMentions(goal)` before building input
3. Known skills are active on the first LLM turn (body injected as additional context)
4. Unknown mentions are logged

Only `/skill-name` syntax is supported. Matched against known catalog names at word boundaries.

### Model-Driven Activation

1. Model sees catalog in system prompt
2. Model autonomously decides to call `activate_skill(name=...)`
3. Tool reads SKILL.md, strips frontmatter, returns body
4. Model has instructions this turn, follows them using existing tools

### Execution

No separate skill executor. A skill is prompt context. All tool calls still flow through `ToolRouter` and `PolicyEngine`.

## Coexistence and Conflict Priority

App skills and Agent Skills remain separate:

- App Skill: foreground package guidance, loaded automatically each turn
- Agent Skill: task/capability guidance, activated by user or model

Conflict priority (high → low):

1. System prompt, role allowlists, tool schemas, `PolicyEngine`, privacy gates
2. Current user goal and screen evidence
3. Recalled memory
4. Foreground app skill
5. Active Agent Skills
6. Agent Skill catalog

## Code Changes

**New:**

- `app/src/main/kotlin/ai/closepaw/agent/cognition/skills/**` — parser, catalog, manager
- `app/src/main/kotlin/ai/closepaw/tool/impl/ActivateSkillTool.kt`

**Modified:**

- `ToolName.kt` — add `ActivateSkill` variant, non-screen-changing
- `SessionServices.kt` — add `AgentSkillManager`
- `SessionToolingBootstrapper.kt` — conditionally register `ActivateSkillTool`
- `StandaloneAgentDef.kt`, `PlannerAgentDef.kt`, `ExecutorAgentDef.kt` — add `"activate_skill"` to allowedTools
- `TurnPlanningPhaseRunner.kt` — call `activateExplicitMentions`, append catalog to system prompt

**Not modified:**

- `PromptBuilder.kt` — no changes
- `TurnToolPolicy.kt` — no changes (`activate_skill` is a regular non-screen-changing tool)
- `AppSkillRepository.kt` — no changes (frontmatter format upgrade does not affect load behavior since loader already strips frontmatter)

## Implementation Tasks

### skill-frontmatter-parser

Scope: `agent/cognition/skills/SkillFrontmatterParser.kt`, tests

- SnakeYAML parse full YAML including metadata map
- Malformed YAML fallback for name + description
- Return parsed frontmatter + body
- Reusable by app skills

### skill-catalog

Scope: `agent/cognition/skills/AgentSkillCatalog.kt`, tests

- Scan `filesDir/skills/*/SKILL.md`
- Parse frontmatter, build name → AgentSkillEntry map
- Skip and log invalid skills
- `catalogPrompt()` returns system prompt section

### skill-manager

Scope: `agent/cognition/skills/AgentSkillManager.kt`, tests

- Idempotent activation
- `/skill-name` mention activation
- Preserved across `SessionServices.copy()`

### activation-tool

Scope: `tool/impl/ActivateSkillTool.kt`, `ToolName.kt`, role defs

- ToolSpec implementation, reads SKILL.md body
- `ToolName` non-screen-changing
- All roles' allowedTools updated

### session-integration

Scope: `SessionServices.kt`, `SessionToolingBootstrapper.kt`, `TurnPlanningPhaseRunner.kt`

- `SessionServices.create` builds manager
- Catalog appended to system prompt
- Conditional tool registration
- Explicit mention activation before first turn

### tests

- Parser: valid, malformed, missing fields, metadata map
- Catalog: discovery, invalid skills, empty
- Manager: activation, dedupe, mention parsing
- Tool: valid, unknown, duplicate
- Existing app-skill tests remain green

## Resolved Decisions

1. **Single skill root**: `context.filesDir/skills/` only, no bundled assets
2. **Trust (v1)**: all discovered skills trusted; code structured for future trust-checking
3. **Catalog location**: system prompt section (per Claude Code / Codex / pi reference implementations)
4. **Tool result**: returns full skill body immediately
5. **No activation barrier**: `activate_skill` is a regular non-screen-changing tool; existing TurnToolPolicy is sufficient
6. **No budget enforcement**: v1 has very few skills; context window is the natural budget
7. **Frontmatter**: SnakeYAML + lenient fallback, parse all fields including metadata map
8. **Tool name**: `activate_skill`
9. **Activation paths**: `/skill-name` (user-explicit) + model autonomously calls `activate_skill` (model-driven)
10. **All roles**: register `activate_skill`, no role restriction
11. **Compaction**: no special handling; skill body compacts normally, model can re-activate
12. **PromptBuilder**: not modified
13. **TurnToolPolicy**: not modified
14. **App skill compatibility**: upgrade frontmatter to agentskills.io-compatible, name as `app-<short-name>` (e.g., `app-chrome`, `app-tasks`), `metadata.package` stores package name, loader behavior unchanged
