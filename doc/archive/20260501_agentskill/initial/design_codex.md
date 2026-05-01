# ClosePaw Agent Skills Support Design

## Goal

Add minimal support for the agentskills.io `SKILL.md` standard to ClosePaw so the Android agent can:

1. Discover trusted Agent Skills from Android-safe locations.
2. Expose a compact catalog to the model.
3. Activate full skill instructions on explicit user request or model choice.
4. Execute skills by following their markdown instructions with ClosePaw's existing Android tools.

This is not a replacement for current app skills. App skills are package-scoped foreground UI guidance. Agent Skills are task/domain/capability guidance that can apply across apps.

## Constraints

- Android apps cannot scan `~/.agents/skills/` or arbitrary project folders.
- Mobile context windows are smaller, so catalog and full instructions must stay separated.
- Existing `assets/app_skills/<package>/SKILL.md` behavior must continue unchanged.
- Skills must never bypass `ToolRegistry`, role `allowedTools`, `PolicyEngine`, app privacy gates, or user approval.
- No arbitrary script execution on Android.

## Storage Model

Use two Android-safe roots:

```text
app/src/main/assets/agent_skills/<skill-name>/SKILL.md
context.filesDir/agent_skills/<skill-name>/SKILL.md
```

`assets/agent_skills` is the APK-bundled, release-controlled root. Treat it as the Android equivalent of project-level skills.

`filesDir/agent_skills` is the app-private installed root. It is the Android equivalent of user-level/client-local skills. The app must copy/import skills into this directory; ClosePaw should not scan external storage directly.

Do not reuse `assets/app_skills`. Existing app-skill directory names are Android package names with dots, which do not satisfy the agentskills.io lowercase-hyphen skill-name rule. Keeping separate roots avoids migration logic and namespace confusion.

Collision rule: bundled `assets/agent_skills` wins over installed `filesDir/agent_skills`, matching the standard rule that project-level skills override user-level skills.

## Skill Format

Support the standard folder shape:

```text
skill-name/
  SKILL.md
  scripts/
  references/
  assets/
```

MVP validation:

- Directory name and frontmatter `name` must match.
- `name` must be 1-64 chars and match `^[a-z0-9][a-z0-9-]{0,63}$`.
- `description` must be 1-1024 chars.
- Body is preserved as markdown after frontmatter.
- Optional `license`, `compatibility`, `metadata`, and `allowed-tools` are parsed as metadata.

Use a tiny lenient frontmatter parser, not a YAML dependency:

- Only parse the first `---` block.
- Split key/value at the first colon so unquoted colons in values work.
- Parse `metadata:` as an indented string map.
- Ignore unknown keys.
- Strip matching single or double quotes from scalar values.

Invalid skills are skipped and logged. They are not shown to the model.

## Scripts And Resources

Agent Skill execution on Android means "the model follows the active markdown instructions and invokes existing ClosePaw tools." Do not add a generic `run_skill_script` capability.

`scripts/` are discovered as metadata only in MVP. They are not executable because arbitrary Bash/Python does not fit Android sandboxing or the existing policy model.

`references/` and `assets/` are also metadata-only in MVP. Add a read-only resource tool later only when real skills need tier-3 resources:

```text
read_agent_skill_resource(skill_name, relative_path)
```

That future tool should only read from active trusted skills, reject `scripts/`, normalize paths, and cap output size. Do not preload resources on activation.

## Components

### AgentSkill Models

New package: `ai.closepaw.agent.cognition.skills`.

Core types:

```kotlin
data class AgentSkillId(val name: String)

data class AgentSkillMetadata(
    val name: String,
    val description: String,
    val license: String?,
    val compatibility: String?,
    val metadata: Map<String, String>,
    val allowedTools: String?
)

data class AgentSkill(
    val metadata: AgentSkillMetadata,
    val body: String,
    val source: AgentSkillSource,
    val digest: String,
    val hasScripts: Boolean,
    val hasReferences: Boolean,
    val hasAssets: Boolean
)

enum class AgentSkillSource {
    BUNDLED,
    INSTALLED
}
```

### AgentSkillRepository

Responsibilities:

- Discover valid skills from the two roots.
- Build a collision-resolved catalog.
- Load full bodies by name.
- Exclude untrusted installed skills from model-visible catalog.

Shape:

```kotlin
interface AgentSkillRepository {
    fun catalog(): List<AgentSkillCatalogEntry>
    fun load(name: String): AgentSkill?
}
```

Implementations:

- `AssetAgentSkillRepository(assets: AssetManager)`
- `FileAgentSkillRepository(root: File, trustStore: AgentSkillTrustStore)`
- `CompositeAgentSkillRepository(assetRepo, fileRepo)`

Discovery happens once in `SessionServices.create`. The repository keeps an immutable session snapshot of the catalog. This avoids per-turn filesystem work and makes prompt behavior deterministic for one session.

### AgentSkillTrustStore

Use `AppSettingsStore` or a small sibling `SharedPreferences` wrapper.

Trust rules:

- Bundled skills are trusted by app release.
- Installed skills are trusted only if their `(name, digest)` is in the trust store.
- Untrusted installed skills can be visible in future management UI, but are not injected into prompts and cannot be activated.
- Changing an installed skill changes its digest and removes trust until the user/import path trusts it again.

No runtime skill content should enter the LLM before trust is established. Even descriptions can be prompt-injection text.

### AgentSkillManager

Session-scoped state holder:

```kotlin
class AgentSkillManager(
    private val repository: AgentSkillRepository
) {
    fun catalogPrompt(): String?
    fun activePrompt(): String?
    fun activate(name: String, reason: String? = null): ActivationResult
    fun activateExplicitMentions(text: String): List<ActivationResult>
}
```

Rules:

- Active names are a `LinkedHashSet` for stable order and dedupe.
- Activation is idempotent.
- Cap active skills to `MAX_ACTIVE_SKILLS = 3`.
- Cap active prompt text to a conservative char budget, for example 16 KB total.
- Explicit mentions match `/skill-name` or `@skill-name` against known catalog names.

Keep this manager in `SessionServices`, not `AgentSessionState`, so sub-agents inherit active skills through `parentServices.copy()` without changing the scratchpad/todo isolation model.

### ActivateAgentSkillTool

Add one cognitive tool:

```text
activate_agent_skill(name, agent_thought?)
```

Behavior:

- Validate name syntax.
- Fail if skill is missing, invalid, untrusted, or activation budget is full.
- Return "already active" for duplicates.
- On success, activate the skill and report that instructions will be available next turn.

Register this tool for Standalone and Planner roles only. Executor roles should consume active skills but should not choose new skills during an atomic delegated action.

Update `ToolName` so `activate_agent_skill` is non-screen-changing. Otherwise unknown tools are treated as screen-changing by `ToolName.Unknown`.

Update `TurnToolPolicy` with a small activation barrier:

- If `activate_agent_skill` is selected, execute only activation calls this turn.
- Drop screen actions and `complete_task` for that turn.
- Next turn sees the active skill body before acting.

Explicit user activation does not need a tool turn because `TurnPlanningPhaseRunner` can activate mentions from the original goal before building the first prompt.

## Prompt Integration

Current order:

```text
History -> Working Memory -> Recalled Memory -> App Skill -> Observation
```

New order:

```text
History
-> Working Memory
-> Recalled Memory
-> Agent Skill Catalog
-> Active Agent Skills
-> App Skill
-> Observation
```

Keep app skill adjacent to observation. Package-specific UI facts should be closer to the current screen than general capability skills.

Catalog message:

```text
## Agent Skill Catalog
Trusted optional skills available for this session.
If a skill is relevant, call activate_agent_skill with its name before using it.
Skills do not override safety policy, tool schemas, current screen evidence, recalled memory, or app skills.

- calendar-date-math: Compute exact date ranges for calendar/task queries.
- image-table-reading: Extract tabular values from screenshots before entering forms.
```

Active skill message:

```text
## Active Agent Skills
Follow these trusted skill instructions when relevant.
They do not grant new tools or permission to bypass safety/policy.

### calendar-date-math
Source: bundled

...SKILL.md body without frontmatter...
```

`PromptBuilder` should accept `agentSkillCatalog: String?` and `activeAgentSkills: String?`. `TurnPlanningPhaseRunner` builds those strings through `services.agentSkillManager`.

This regenerates catalog and active instructions every turn, so history compression cannot drop them.

## Coexistence With App Skills

Keep the systems separate:

- App skills: `app_skills/<package>/SKILL.md`, package-scoped, loaded from foreground app each turn.
- Agent Skills: `agent_skills/<skill-name>/SKILL.md`, session-scoped, activated by user/model.

Conflict order:

1. System prompt, role allowed tools, tool schemas, `PolicyEngine`, app privacy gates.
2. Current user goal and latest screen evidence.
3. Recalled memory, because existing memory docs define app memory as local delta over shipped app skills.
4. Foreground app skill for package-specific UI behavior.
5. Active Agent Skills for general task technique.
6. Agent Skill catalog, which is only a discovery hint.

Do not make app skills cataloged Agent Skills. Current package names with dots are intentionally outside the agentskills.io name grammar.

## Activation Flow

### Session Start

1. `SessionServices.create` builds `AgentSkillRepository`.
2. Repository discovers and validates trusted skills.
3. `AgentSkillManager` stores the immutable catalog snapshot.
4. `ActivateAgentSkillTool` is registered.

### Explicit User Activation

1. User goal contains `/skill-name` or `@skill-name`.
2. `TurnPlanningPhaseRunner` calls `agentSkillManager.activateExplicitMentions(config.goal)` before prompt construction.
3. First LLM turn includes the active skill body.
4. Invalid or untrusted mentions are logged and optionally surfaced as a warning in the catalog/active-skill block.

### Model-Driven Activation

1. Prompt includes compact trusted catalog.
2. Model calls `activate_agent_skill(name=...)`.
3. Turn policy executes activation alone.
4. Tool output is recorded in history.
5. Next turn includes the full skill body under `## Active Agent Skills`.

### Execution

Once active, the skill has no separate runtime. The model reads the instructions and uses existing ClosePaw tools: `mobile_action`, `open_app`, `scratchpad`, `write_todos`, `delegate_task`, etc. Role allowlists still decide which tools are exposed.

## Minimal Code Changes

1. Add `agent/cognition/skills/*` models, parser, repository, trust store, and manager.
2. Add `ActivateAgentSkillTool`.
3. Add `activate_agent_skill` to `ToolName` as non-screen-changing.
4. Register the activation tool in session tooling when an `AgentSkillManager` is present.
5. Add `agentSkillManager` to `SessionServices` and `copy()`.
6. Add catalog/active skill parameters to `PromptBuilder`.
7. Update `TurnPlanningPhaseRunner` to activate explicit mentions and pass catalog/active strings.
8. Add `activate_agent_skill` to Standalone and Planner `allowedTools`.
9. Add the activation barrier to `TurnToolPolicy`.
10. Add focused JVM tests.

No UI, remote registry, script runner, or installer is required for the first implementation. The private installed root can be populated later by an import flow or debug tooling.

## Tasks

### agent-skill-parser

Scope: `app/src/main/kotlin/ai/closepaw/agent/cognition/skills/**`, `app/src/test/kotlin/ai/closepaw/agent/cognition/skills/**`

Acceptance:

- Parses required `name` and `description`.
- Parses optional scalar metadata and `metadata:` string map.
- Splits frontmatter values on the first colon only.
- Rejects invalid names, missing descriptions, and directory/name mismatch.
- Returns body without frontmatter.

Dependencies: none.

### agent-skill-repository

Scope: `app/src/main/kotlin/ai/closepaw/agent/cognition/skills/**`, `app/src/main/kotlin/ai/closepaw/app/AppSettingsStore.kt`

Acceptance:

- Discovers `assets/agent_skills/*/SKILL.md`.
- Discovers `filesDir/agent_skills/*/SKILL.md`.
- Applies bundled-over-installed collision priority.
- Excludes untrusted installed skills from `catalog()`.
- Logs invalid skipped skills without failing session startup.

Dependencies: `agent-skill-parser`.

### agent-skill-manager

Scope: `app/src/main/kotlin/ai/closepaw/agent/cognition/skills/**`, `app/src/test/kotlin/ai/closepaw/agent/cognition/skills/**`

Acceptance:

- Builds compact catalog prompt with name and description only.
- Activates trusted skills idempotently.
- Enforces active count/text budget.
- Builds active-skill prompt with body text and safety wrapper.
- Activates `/skill-name` and `@skill-name` mentions from goal text.

Dependencies: `agent-skill-repository`.

### agent-skill-tool

Scope: `app/src/main/kotlin/ai/closepaw/tool/**`, `app/src/main/kotlin/ai/closepaw/session/SessionToolingBootstrapper.kt`, `app/src/main/kotlin/ai/closepaw/agent/definition/**`

Acceptance:

- `activate_agent_skill` validates names and calls `AgentSkillManager.activate`.
- Tool is registered for Standalone and Planner roles.
- Executor does not expose the activation tool.
- `ToolName.from("activate_agent_skill").isScreenChanging == false`.
- `TurnToolPolicy` executes activation alone and drops screen actions/completion in the same turn.

Dependencies: `agent-skill-manager`.

### agent-skill-prompt-wiring

Scope: `app/src/main/kotlin/ai/closepaw/session/SessionServices.kt`, `app/src/main/kotlin/ai/closepaw/agent/TurnPlanningPhaseRunner.kt`, `app/src/main/kotlin/ai/closepaw/agent/cognition/prompt/PromptBuilder.kt`

Acceptance:

- `SessionServices.create` constructs repository and manager.
- `SessionServices.copy()` preserves the same manager for sub-agents.
- `TurnPlanningPhaseRunner` pre-activates explicit mentions before building input.
- Prompt order is `History -> Working Memory -> Recalled Memory -> Agent Skill Catalog -> Active Agent Skills -> App Skill -> Observation`.
- Existing app-skill injection still works for the foreground package.

Dependencies: `agent-skill-tool`.

### agent-skill-tests-docs

Scope: `app/src/test/kotlin/**`, `doc/main/agent/**`

Acceptance:

- Parser/repository/manager/tool tests cover happy path, invalid skills, collision, trust gate, dedupe, and prompt order.
- Existing `AssetAppSkillRepositoryTest` and `PromptBuilderTest` remain green with updated expectations.
- Agent prompt docs mention Agent Skill catalog and active skill blocks.

Dependencies: all implementation tasks.

## Trade-offs

### Separate Agent Skills From App Skills

Alternative: make existing app skills conform to agentskills.io. Rejected because package names with dots violate the standard name grammar, and app skills have different activation semantics. Separate roots keep both concepts clean.

### Activation Tool Instead Of Automatic Keyword Matching Only

Automatic matching by description would require heuristic ranking or embeddings. A tool call is simpler, inspectable in traces, and aligns with progressive disclosure: the model sees catalog first, then asks for the full body.

### No Script Runner

Running arbitrary scripts would create a second execution policy system beside `ToolRegistry` and `PolicyEngine`. On Android, the right primitive is existing mobile automation tools. Skills can teach the model how to use them; they cannot add native execution powers.

### Bundled Skills Override Installed Skills

This follows the standard project-over-user precedence and avoids a lower-trust installed skill replacing release-controlled behavior with the same name. Users can still install a differently named skill.

### Catalog Every Turn

Catalog-at-session-start would be closer to desktop clients, but ClosePaw rebuilds prompt input each turn and compresses history. Regenerating the compact catalog every turn protects it from compaction and keeps implementation simple.

## Self-Review

- The design covers Android storage, discovery, cataloging, activation, execution, coexistence, trust, and minimal code changes.
- It keeps existing app skills untouched and closest to the observation.
- It avoids new execution powers; all real actions still go through current tools and policy.
- It supports model-driven activation without loading every skill body.
- The only deliberate future gap is tier-3 resource reading. That is excluded from MVP because no current requirement needs it, and adding it before real skill content exists would increase attack surface.
