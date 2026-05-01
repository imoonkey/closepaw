# Agent Skills

agentskills.io-compatible skill system: discoverable task/capability instructions the agent can activate on demand. Coexists with — but is distinct from — the per-package App Skill system.

## Two Skill Types

| | App Skill | Agent Skill |
|---|-----------|-------------|
| Purpose | Foreground-app UI guidance | Task/capability guidance |
| Location | `app/src/main/assets/app_skills/<package>/SKILL.md` (bundled) | `context.filesDir/skills/<name>/SKILL.md` (installed) |
| Discovery | Keyed by foreground package, loaded each turn | Catalog scanned once at session start |
| Activation | Automatic per turn | Explicit (`/skill-name` mention or `activate_skill` tool) |
| In prompt | App skill section (per-turn body) | Catalog one-liners in system prompt; bodies on demand |
| Naming | `name: app-<short-name>` (e.g. `app-settings`) | `name: <skill-name>` (e.g. `calendar-date-math`) |
| Frontmatter parser | Shared `SkillFrontmatterParser` | Shared `SkillFrontmatterParser` |

## Frontmatter Format

Both skill types use agentskills.io-compatible YAML frontmatter parsed by `SkillFrontmatterParser` (SnakeYAML + lenient regex fallback):

```yaml
---
name: <slug>
description: <one-line summary, ≤1024 chars>
license: optional
compatibility: optional
allowed-tools: optional advisory list
metadata:
  package: com.example.app   # required for App Skills only
  any: free-form k/v
---

<full body — Markdown>
```

Validation:
- `name`: 1-64 chars, regex `^[a-z][a-z0-9-]{0,63}$` (lowercase-hyphen)
- `description`: required, 1-1024 chars, sanitized to single line (newlines/control chars → space)
- Directory name must equal `name` (Agent Skills only) or contain `metadata.package` (App Skills)
- Invalid skills are skipped with `Log.w`

`allowed-tools` is advisory — it does not alter role allowlists. Skills run inside the same `PolicyEngine` boundaries as any tool call.

## Runtime Components

```
AgentSkillManager (session-scoped, in SessionServices)
├── AgentSkillCatalog (immutable, scans filesDir/skills/ once)
│   └── Map<name, AgentSkillEntry(name, description, filePath)>
└── activeSkills: LinkedHashSet<name>   // @Synchronized
```

- `AgentSkillCatalog`: scans `filesDir/skills/*/SKILL.md` one level deep, validates each, builds the immutable name → entry map.
- `AgentSkillManager`: `@Synchronized` activate/mention APIs. `activate(name)` always reads the file fresh and returns the body (idempotent — re-activation re-delivers the body, supporting post-compaction reload).
- `ActivateSkillTool`: registered only when catalog is non-empty. Returns full skill body as a tool result; `PolicyEngine.Allow` (no special turn policy needed).
- `SessionServices.copy()` preserves the same manager instance so delegated executors inherit activations.

## Activation Paths

### Catalog (always)

The catalog section is appended to the system prompt by `TurnPlanningPhaseRunner`:

```text
## Available Skills
The following skills provide specialized instructions. Call activate_skill
with a skill's name to load its full instructions.

- calendar-date-math: Compute exact date ranges for calendar/task queries.
- image-table-reading: Extract tabular values from screenshots before entering forms.
```

Omitted entirely when no skills are discovered. The model decides when to call `activate_skill` based on the catalog descriptions.

### Explicit `/skill-name` Mention (per turn)

When the user goal contains `/skill-name`, `TurnPlanningPhaseRunner` calls `activateExplicitMentions(goal)` before the first prompt build. Matched skills' bodies are injected as a single user message before the observation:

```text
## Skill: calendar-date-math
<full body>
```

Boundary regex `(?:^|(?<=\s))/([a-z][a-z0-9-]{0,63})(?![/\w-])` requires a real word boundary after the slash — `/data/local/tmp` and `/usr/local` do not match.

Unknown mentions are silently filtered (catalog lookup before activation).

### Model-Driven `activate_skill(name)`

The model autonomously calls the tool. Returns the body as `ToolExecutionResult.Success`. The body enters conversation history as a tool result and participates in normal compaction. After compaction, the model can re-call `activate_skill` to reload the body — `activate()` always re-reads the file.

## Conflict Priority

Active skills sit at the **bottom** of the priority stack:

1. System prompt (role, rules, completion doctrine, policy)
2. Current user goal
3. Screen evidence
4. Recalled memory
5. Foreground app skill
6. Active Agent Skill bodies
7. Agent Skill catalog (one-liners)

This is the reason explicit `/skill-name` bodies inject as user-role messages, **not** appended to the system prompt — they must not gain system-level authority.

## Trust Model

In v1, all discovered skills are trusted. Installation (ADB push to `filesDir/skills/`) is the consent gate. Code is structured so a trust-checking layer can be inserted later for download/import flows.

Catalog descriptions are sanitized (single-line, ≤1024 chars) before reaching the system prompt to defeat catalog-time prompt injection.

## Code Layout

| File | Purpose |
|------|---------|
| `agent/cognition/skills/SkillFrontmatterParser.kt` | YAML + lenient fallback parser, shared by both skill types |
| `agent/cognition/skills/SkillFrontmatter.kt` | Parsed frontmatter data class |
| `agent/cognition/skills/AgentSkillCatalog.kt` | One-time discovery + validation |
| `agent/cognition/skills/AgentSkillEntry.kt` | name, description, filePath |
| `agent/cognition/skills/AgentSkillManager.kt` | Active-set, activate(), `/skill-name` mention parsing |
| `tool/impl/ActivateSkillTool.kt` | The tool the model calls |
| `tool/ToolName.kt` | `ActivateSkill` (non-screen-changing) |
| `session/SessionServices.kt` | Constructs and holds `AgentSkillManager` |
| `session/SessionToolingBootstrapper.kt` | Conditional `ActivateSkillTool` registration |
| `agent/TurnPlanningPhaseRunner.kt` | Catalog → system prompt; `/skill-name` → activated bodies |
| `agent/cognition/prompt/AppSkillRepository.kt` | Loads App Skills via shared `SkillFrontmatterParser` |

## Installing a Skill (v1)

```bash
# Create skill directory on device
adb shell 'run-as ai.closepaw mkdir -p files/skills/<skill-name>'

# Push SKILL.md
adb push /local/SKILL.md /data/local/tmp/SKILL.md
adb shell 'run-as ai.closepaw cp /data/local/tmp/SKILL.md files/skills/<skill-name>/SKILL.md'
```

Catalog refresh requires a new session (catalog is immutable per session).

## Test Coverage

- `SkillFrontmatterParserTest` — valid/malformed/missing fields/metadata maps
- `AgentSkillCatalogTest` — discovery, invalid skip, empty, sanitization
- `AgentSkillManagerTest` — activate, idempotent, mention parsing, boundary, concurrency, read failure
- `ActivateSkillToolTest` — valid, unknown, body return path
- `AppSkillAssetIntegrityTest` — parses every real `app_skills/*/SKILL.md` at build time, asserts `name: app-*` + `metadata.package` matches directory
