# Agent Skills

> Last updated: 2026-05-17 (per-skill disable toggle with next-session semantics)

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
├── disabledNames: Set<String>            // snapshot from AppSettingsStore at construction
└── activeSkills: LinkedHashSet<name>     // @Synchronized
```

- `AgentSkillCatalog`: scans `filesDir/skills/*/SKILL.md` one level deep, validates each, builds the immutable name → entry map.
- `AgentSkillManager`: `@Synchronized` activate/mention APIs. The `entries` view, the catalog prompt, `activate()`, and `activateExplicitMentions()` all filter `disabledNames` out — disabled skills are invisible to the model. `activate(name)` re-reads the file fresh so post-compaction reload works (idempotent body redelivery).
- `ActivateSkillTool`: registered only when the catalog (after disable-filter) is non-empty. Disabled skills return `ActivationResult.Disabled` → tool-side `Failure` instructing the model to ask the user to re-enable under *Agent Behavior → Tools*. Non-disabled paths use `PolicyEngine.Allow`.
- `SessionServices.copy()` preserves the same manager instance so delegated executors inherit activations and the same disabled snapshot.

### Disable filter (next-session semantics)

`AppSettingsStore.disabledAgentSkills` is a persisted `StateFlow<Set<String>>`
backed by `disabled_agent_skills` in plain `SharedPreferences` (JSON array of
skill names). Writes go through `setSkillDisabled(name, disabled)`, serialized
on a `Mutex` to keep concurrent toggles from racing on the read-modify-write of
the in-memory set and the prefs commit.

The filter applies **at session creation**: `SessionServices.create(...)` reads
`disabledAgentSkills.value` once and passes a defensive copy into
`AgentSkillManager`. A running session is unaffected by toggle changes — flip
the switch and the new value lands the next time the user starts a session.

Settings surfaces this to the user: the Tools row's subtitle reads
*"Takes effect next session"* when the skill is disabled **and** a session is
running (see [settings.md → Agent Behavior → Tools](../app/settings.md#agent-behavior--tools)).

The model never sees disabled skills: the catalog prompt omits them, the
explicit `/skill-name` mention parser drops them before activation, and
`activate_skill(name)` returns `Disabled` (translated to a tool failure that
prompts the model to ask the user to re-enable in Settings).

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
| `agent/cognition/skills/BundledAgentSkillInstaller.kt` | Seeds bundled Agent Skills from APK assets into `filesDir/skills` |

## Bundled Agent Skill Seeds

Runtime Agent Skills are still loaded only from `context.filesDir/skills/<name>/`; APK assets are
seed/update sources, not the catalog source of truth. `SessionServices.create()` installs bundled
skills before constructing `AgentSkillManager`, so the session catalog sees the installed runtime
copy.

Current bundled seed:

```text
app/src/main/assets/agent_skills/browser-use/
  SKILL.md
  scripts/page.js
  scripts/tabs.js
  scripts/input.js
```

The installer copies the whole skill directory into `context.filesDir/skills/browser-use/`,
rewrites `{{SKILL_DIR}}` in installed `SKILL.md` to that absolute runtime path, and writes
`.install-complete` only after the copy succeeds. Session bootstrap treats missing sentinel as no
previous successful install: first-install failure aborts session creation, while refresh failure
after a sentinel-marked install logs a warning and keeps the previous install.

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
- `BundledAgentSkillInstallerTest` — bundled copy, `{{SKILL_DIR}}` substitution, idempotent overwrite, real asset substitution
- `BrowserUseSkillAssetTest` — real `browser-use` asset files and snippet grouping
- `SessionServicesBundledSkillInstallTest` — first-install failure, sentinel-gated refresh fallback
