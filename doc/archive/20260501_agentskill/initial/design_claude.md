# Agent Skills (agentskills.io) Support — Design

## Goal

Add agentskills.io standard support to ClosePaw so the agent can discover, catalog, and activate general-purpose Agent Skills alongside the existing app-specific skill system. The agent should be able to load specialized instructions on demand when a task matches a skill's description, following the spec's progressive disclosure model — adapted for the Android mobile context where filesystem scanning, script execution, and large context windows are unavailable.

## Key Insight: Two Orthogonal Skill Systems

ClosePaw already has an app skill system. Agent Skills don't replace it — they complement it.

| Dimension | App Skills (existing) | Agent Skills (new) |
|-----------|----------------------|-------------------|
| **Keyed by** | Package name (foreground app) | Task description (what user wants) |
| **Activated by** | Automatic per-turn (foreground app changed) | Model-driven (catalog match) or user-explicit |
| **Purpose** | App-specific UI navigation guidance | General-purpose capabilities |
| **Lifecycle** | Loaded fresh each turn | Loaded once, persists in conversation |
| **Injection** | PromptBuilder slot → user message | Tool call result in conversation history |
| **Cardinality** | 0 or 1 per turn | 0 to N per session |

Both can be active simultaneously — a user navigating a PDF app could have both the app's UI skill (how to tap through menus) and a "pdf-processing" Agent Skill (how to extract and transform PDF content) loaded.

**Design principle: the existing app skill system is not modified at all.**

## Android Adaptation

The spec assumes a filesystem-capable desktop agent. ClosePaw runs in an Android sandbox. Key translations:

| Spec Concept | Android Equivalent |
|---|---|
| `~/.agents/skills/` (user-level) | `context.filesDir/agent_skills/` (app private storage) |
| `<project>/.agents/skills/` (project-level) | `assets/agent_skills/` (bundled with APK) |
| Filesystem scanning | `AssetManager.list()` + `File.listFiles()` |
| `scripts/` execution | Not supported — read-only reference material |
| `allowed-tools` | Ignored in v1 — session-level PolicyEngine governs all tool access |
| Model reads files via tool | `activate_skill` dedicated tool returns content |

## Approach

### Progressive Disclosure (3 tiers)

**Tier 1 — Catalog (~50-100 tokens/skill):** At session start, scan both skill sources, parse SKILL.md frontmatter, and append a skill catalog section to the system prompt. The model sees names + descriptions of all available skills from the first turn.

**Tier 2 — Instructions (<5000 tokens):** Model calls `activate_skill(name)` tool when a task matches a skill's description. Tool returns the full SKILL.md body (frontmatter stripped). Tracked to prevent re-injection.

**Tier 3 — Resources (as needed):** Deferred to v2. Skills can reference files in `references/` and `assets/` subdirectories, but on Android the model has no file-read tool. A `read_skill_resource` tool could be added later.

### Discovery

Two skill sources, scanned at session start:

```
Source 1 — Bundled (ships with APK):
  assets/agent_skills/
  ├── pdf-processing/
  │   └── SKILL.md
  └── data-analysis/
      └── SKILL.md

Source 2 — Installed (user-added, persists across sessions):
  context.filesDir/agent_skills/
  ├── custom-workflow/
  │   └── SKILL.md
  └── pdf-processing/        ← overrides bundled
      └── SKILL.md
```

**Precedence:** Installed overrides bundled (same name). Matches the spec's "project overrides user" convention — installed skills are more specific/recent.

**Scan rules:**
- Each subdirectory must contain a `SKILL.md` file
- Skip directories without SKILL.md
- No recursive scanning (one level deep)
- Cap at 50 skills to prevent catalog bloat

### Frontmatter Parsing

Simple key-value parser — no SnakeYAML dependency. We only need `name` and `description`.

```
Input:                          Output:
---                             SkillFrontmatter(
name: pdf-processing              name = "pdf-processing",
description: Extract PDFs...      description = "Extract PDFs..."
---                             )
                                body = "# PDF Processing\n..."
# PDF Processing
...
```

**Lenient rules** (per spec client guide):
- Name doesn't match directory → warn, load anyway
- Name exceeds 64 chars → warn, load anyway
- Description missing/empty → skip skill, log error
- YAML unparseable → skip skill, log error
- Unquoted colons in values → handled by reading to end-of-line

### Catalog Injection

Appended to the system prompt at session start:

```markdown
## Agent Skills

Specialized skills available for complex tasks. When a task matches
a skill's description, call activate_skill with the skill name to
load its full instructions before proceeding.

Available skills:
- pdf-processing: Extract PDF text, fill forms, merge files.
- data-analysis: Analyze datasets, generate charts, create reports.
```

If no skills are discovered, this section is omitted entirely.

### Activation via Tool

```
Tool: activate_skill
Parameters: { "name": "pdf-processing" }
Returns: Full SKILL.md body (markdown instructions)
```

The tool:
1. Validates `name` against the catalog (rejects unknown names)
2. Checks deduplication (returns brief "already activated" note if re-requested)
3. Reads full SKILL.md from the skill's source (assets or files)
4. Strips frontmatter, returns body content
5. Records activation (for dedup tracking)

The returned content enters the conversation as a tool result — the model's standard mechanism for receiving information. No special PromptBuilder changes needed.

### Trust Model

| Source | Trust Level | Rationale |
|--------|------------|-----------|
| Bundled (`assets/`) | Auto-trusted | Ships with the APK, same trust as app skills |
| Installed (`filesDir/`) | Trusted | User explicitly installed them — installation is the consent gate |
| Remote/downloaded | Future — requires consent UI | Not in v1 scope |

All activated skill instructions go through the existing PolicyEngine for any tool calls they produce. Skills cannot bypass the approval flow or escalate permissions.

### Skill Installation (v1 scope)

v1 supports bundled skills only. The installed-skills directory (`filesDir/agent_skills/`) is scanned but no installation UI is provided. Skills can be placed there via:
- ADB push (development/testing)
- Future: download API, settings UI, import from shared storage

## Components

### New Files

**`AgentSkillEntry.kt`** — Data class for parsed skill metadata:
```kotlin
data class AgentSkillEntry(
    val name: String,
    val description: String,
    val source: SkillSource,    // BUNDLED or INSTALLED
    val skillPath: String       // path within source (for loading body)
)

enum class SkillSource { BUNDLED, INSTALLED }
```

**`AgentSkillCatalog.kt`** — Discovery, parsing, and lookup:
```kotlin
class AgentSkillCatalog(
    private val assets: AssetManager,
    private val installedSkillsDir: File
) {
    private val skills: Map<String, AgentSkillEntry>  // built at construction
    private val activated: MutableSet<String> = mutableSetOf()

    fun getAll(): List<AgentSkillEntry>
    fun get(name: String): AgentSkillEntry?
    fun isActivated(name: String): Boolean
    fun markActivated(name: String)
    fun buildCatalogPrompt(): String?           // null if no skills
    fun loadInstructions(name: String): String?  // full body, frontmatter stripped
}
```

Construction scans both sources and builds the map. Installed overrides bundled on name collision.

**`ActivateSkillTool.kt`** — ToolSpec implementation:
```kotlin
class ActivateSkillTool(
    private val catalog: AgentSkillCatalog
) : ToolSpec {
    override val name = "activate_skill"
    override val description = "Load specialized instructions for a task. ..."
    override val parameterSchema = /* { "name": { "type": "string" } } */

    override fun validate(params: JSONObject): ValidationResult
    override fun createInvocation(params: JSONObject): ToolInvocation
}
```

The tool description includes the catalog inline (alternative to system prompt injection):
```
Load specialized skill instructions. Call when a task matches a skill's description.

Available skills:
- pdf-processing: Extract PDF text, fill forms, merge files.
- data-analysis: Analyze datasets, generate charts, create reports.
```

**Design choice:** Catalog in tool description vs. system prompt. Tool description is cleaner — it couples discovery with activation, keeps the system prompt unchanged, and only the model that can call tools sees the catalog. If no skills exist, the tool isn't registered and the model doesn't see any catalog noise.

**`SkillFrontmatterParser.kt`** — Lenient YAML frontmatter parser:
```kotlin
object SkillFrontmatterParser {
    data class ParseResult(
        val name: String?,
        val description: String?,
        val body: String
    )

    fun parse(content: String): ParseResult
}
```

Parses `---` delimited frontmatter, extracts `name` and `description` key-value pairs, returns body. Handles unquoted colons, missing fields, malformed YAML.

### Modified Files

**`SessionServices.kt`** — Add `AgentSkillCatalog` as a session-scoped service:
```kotlin
// In create():
val agentSkillCatalog = AgentSkillCatalog(
    assets = context.assets,
    installedSkillsDir = File(context.filesDir, "agent_skills")
)

// In constructor:
val agentSkillCatalog: AgentSkillCatalog = AgentSkillCatalog.EMPTY
```

**`SessionToolingBootstrapper.kt`** — Register `ActivateSkillTool`:
```kotlin
// In create(): accept catalog parameter
// In registerBuiltInTools(): conditionally register
if (agentSkillCatalog.getAll().isNotEmpty()) {
    register(ActivateSkillTool(agentSkillCatalog))
}
```

No tool registered when no skills exist — prevents model confusion.

### Not Modified

- `AppSkillRepository` — untouched, app skills work exactly as before
- `PromptBuilder` — no changes, skill activation goes through tool results
- `TurnPlanningPhaseRunner` — no changes
- `PolicyEngine` — no changes (already governs all tool calls)
- `ToolRouter` — no changes (already handles all tool execution)

## Interactions

### Session Start Flow

```
SessionServices.create()
  │
  ├─ AgentSkillCatalog(assets, filesDir/agent_skills/)
  │    ├─ scan assets/agent_skills/*/SKILL.md
  │    ├─ scan filesDir/agent_skills/*/SKILL.md
  │    ├─ parse frontmatter for each
  │    └─ build name → entry map (installed overrides bundled)
  │
  ├─ SessionToolingBootstrapper.create(agentSkillCatalog)
  │    └─ if catalog non-empty: register ActivateSkillTool(catalog)
  │
  └─ (rest of session setup unchanged)
```

### Activation Flow (Model-Driven)

```
Turn N: Model sees task "merge these PDFs"
  │
  ├─ Model reads tool descriptions, sees activate_skill catalog
  ├─ Model calls: activate_skill(name: "pdf-processing")
  │
  ├─ ToolRouter validates + executes
  │    ├─ ActivateSkillTool checks catalog → found
  │    ├─ checks dedup → not yet activated
  │    ├─ loads SKILL.md body from source
  │    ├─ strips frontmatter
  │    ├─ marks "pdf-processing" as activated
  │    └─ returns instructions as tool result
  │
  └─ Model receives instructions in conversation, follows them

Turn N+1: Model already has instructions, proceeds with task
  │
  └─ (app skill for foreground app also loads independently via PromptBuilder)
```

### Activation Flow (Re-activation / Dedup)

```
Model calls: activate_skill(name: "pdf-processing") (already activated)
  │
  └─ Tool returns: "Skill 'pdf-processing' is already loaded in this session."
```

### Coexistence: App Skill + Agent Skill

```
Turn N: User says "extract text from the PDF I have open"
  │
  ├─ Foreground app: com.adobe.reader
  │    └─ PromptBuilder injects app skill (UI navigation for Adobe Reader)
  │
  ├─ Model also calls activate_skill("pdf-processing")
  │    └─ Tool result: general PDF extraction instructions
  │
  └─ Model has BOTH: how to navigate Adobe Reader UI + how to process PDF content
```

## Trade-offs

### Catalog in tool description vs. system prompt

**Chosen: tool description.** The catalog lives in `ActivateSkillTool.description`, not the system prompt.

- (+) Zero system prompt changes — no risk of breaking existing prompt engineering
- (+) No tool registered when no skills → zero overhead for skill-less sessions
- (+) Natural coupling: discovery and activation in one place
- (-) Tool description is less prominent than system prompt — model might not notice
- Mitigation: LLMs reliably read tool descriptions; this is the approach recommended by the spec's client guide

### Lenient frontmatter parser vs. YAML library

**Chosen: custom lenient parser.** Simple key-value extraction, no SnakeYAML dependency.

- (+) Zero new dependencies
- (+) Handles malformed YAML that strict parsers reject (per spec guidance)
- (+) We only need 2 fields — a full YAML parser is overkill
- (-) Won't handle multiline YAML block scalars or complex nested structures
- Acceptable: `name` is always single-line; `description` is effectively single-line (1024 char limit)

### Dedicated tool vs. file-read activation

**Chosen: dedicated `activate_skill` tool.** The model calls a tool to load skills.

- (+) ClosePaw's model has no filesystem access — file-read isn't an option
- (+) Enables deduplication, structured wrapping, controlled content delivery
- (+) Tool parameter can be constrained to valid skill names
- (-) Extra tool in the registry
- Acceptable: one more tool is negligible

### scripts/ as read-only vs. not supported

**Chosen: not supported in v1.** Scripts directory is ignored during discovery.

- (+) Zero complexity — no need to figure out execution sandboxing
- (+) Most Agent Skills written for mobile won't include bash/python scripts anyway
- (-) Skills with scripts won't work as intended
- Mitigation: `compatibility` field can flag "requires script execution"; catalog can filter these out or warn

### Installed skills directory (filesDir) vs. bundled only

**Chosen: scan both, but no installation UI in v1.** The installed path is scanned but expected to be empty initially.

- (+) Architecture supports user-installed skills from day one
- (+) Enables dev/testing via ADB push
- (+) No dead code — the scanning code works for both sources
- (-) No user-facing way to install skills yet
- Acceptable: installation UI is a separate feature; the foundation should be ready

## Tasks

### 1. `skill-frontmatter-parser`
**Scope:** `app/src/main/kotlin/ai/closepaw/agent/skill/SkillFrontmatterParser.kt`, tests  
**AC:** Parses valid frontmatter extracting name + description. Handles: missing fields (returns null), unquoted colons, extra whitespace, missing closing `---`. Returns body content with frontmatter stripped.  
**Depends on:** nothing

### 2. `agent-skill-catalog`
**Scope:** `app/src/main/kotlin/ai/closepaw/agent/skill/AgentSkillEntry.kt`, `AgentSkillCatalog.kt`, tests  
**AC:** Discovers skills from assets (`agent_skills/`) and private storage. Parses frontmatter via parser. Handles name collisions (installed wins). Builds catalog map. `buildCatalogPrompt()` returns formatted catalog or null. `loadInstructions()` reads and returns body.  
**Depends on:** skill-frontmatter-parser

### 3. `activate-skill-tool`
**Scope:** `app/src/main/kotlin/ai/closepaw/tool/impl/ActivateSkillTool.kt`, tests  
**AC:** Implements ToolSpec. Validates name against catalog. Loads instructions via catalog. Tracks activation set for dedup. Returns instructions or error for unknown/already-activated skills. Tool description includes dynamic catalog.  
**Depends on:** agent-skill-catalog

### 4. `session-integration`
**Scope:** `SessionServices.kt`, `SessionToolingBootstrapper.kt`  
**AC:** AgentSkillCatalog created in `SessionServices.create()`. Passed to bootstrapper. `ActivateSkillTool` registered only when catalog is non-empty. Existing tests pass. No changes to app skill flow.  
**Depends on:** activate-skill-tool

### 5. `tests`
**Scope:** test files for parser, catalog, tool  
**AC:** Unit tests covering: frontmatter parsing (valid, malformed, missing fields), catalog discovery (both sources, collisions, empty), tool activation (valid, unknown, dedup), session integration (tool registered/not registered based on catalog).  
**Depends on:** all above
