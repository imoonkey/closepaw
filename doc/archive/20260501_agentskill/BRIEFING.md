# Agent Skills Support — Design Briefing

## Goal

Design minimal/KISS support for the [agentskills.io](https://agentskills.io/) standard in the ClosePaw Android agent.

## What Is Agent Skills?

A standardized, open format for giving AI agents new capabilities. At its core: a folder containing a `SKILL.md` file with YAML frontmatter (name, description) and markdown instructions.

```
skill-name/
├── SKILL.md          # Required: metadata + instructions
├── scripts/          # Optional: executable code
├── references/       # Optional: documentation
└── assets/           # Optional: templates, resources
```

### SKILL.md Format

```markdown
---
name: skill-name
description: What this skill does and when to use it. Max 1024 chars.
license: Apache-2.0          # optional
compatibility: Requires X    # optional, max 500 chars
metadata:                    # optional, string→string map
  author: example-org
  version: "1.0"
allowed-tools: Bash(git:*) Read  # optional, experimental
---

Instructions in markdown. The agent reads this when the skill is activated.
```

### Key Constraints

- `name`: 1-64 chars, lowercase + hyphens, must match parent directory name
- `description`: 1-1024 chars, used for discovery/matching
- Body: recommended <500 lines, <5000 tokens

### Progressive Disclosure (3 tiers)

1. **Catalog** (~100 tokens/skill): name + description loaded at session start
2. **Instructions** (<5000 tokens): full SKILL.md body loaded on activation
3. **Resources** (as needed): scripts/, references/, assets/ loaded on demand

### Discovery Directories

Standard locations to scan:
- Project-level: `<project>/.agents/skills/` and `<project>/.<client>/skills/`
- User-level: `~/.<client>/skills/` and `~/.agents/skills/`

### Activation Modes

- **Model-driven**: model reads catalog, decides skill is relevant, loads it
- **User-explicit**: user triggers via slash command or mention syntax

### Client Implementation Guide Key Points

- Parse YAML frontmatter leniently (handle unquoted colons, etc.)
- Name collisions: project-level overrides user-level
- Trust: gate project-level skills on user trust
- Protect skill content from context compaction
- Deduplicate activations (don't re-inject same skill)
- Allowlist skill directories for file access permissions

## ClosePaw Current Architecture

### Current App Skill System

- **Interface**: `AppSkillRepository` with `load(packageName: String): String?`
- **Implementation**: `AssetAppSkillRepository` loads from `assets/app_skills/<package>/SKILL.md`
- **17 bundled app skills** — app-specific UI navigation guidance
- **Injection**: Per-turn in `TurnPlanningPhaseRunner.buildAppSkillMessage(currentPackageName)`
- **Prompt position**: After history/memory, before current observation
- **Only foreground app skill is loaded per turn**

### Key Integration Points

- `AppSkillRepository.kt` — repository interface (already extensible)
- `PromptBuilder.kt` — assembles prompt with skill injection
- `TurnPlanningPhaseRunner.kt` — orchestrates LLM calls, loads app skill
- `SessionServices.kt` — DI/bootstrap, creates repository
- `SessionToolingBootstrapper.kt` — registers tools at session start
- `ToolRegistry.kt` — tool registration and lookup

### Tool System

- `ToolSpec` interface: name, description, parameterSchema, validate, createInvocation
- `ToolRegistry`: register, get, getAll, generateResponsesApiTools()
- `ToolRouter`: 7-state execution machine (VALIDATING → POLICY_CHECK → AWAITING_APPROVAL → EXECUTING → SUCCESS/ERROR)
- `PolicyEngine`: Allow/Deny/AskUser based on tool type, app tier, approval mode

### Agent Roles

- `AgentRoleDef`: name, executionRole, systemPrompt, allowedTools, delegatable
- Standalone, Planner, Executor roles
- BASIC mode = Standalone, PRO mode = Planner+Executor

### LLM Integration

- Abstract `LLMClient` with streaming support
- Multiple backends: OpenAI, Codex, OpenRouter, Novita, Local LFM
- Model catalog from `assets/llm_models.json`

### Memory System

- Scope-based: USER, DEVICE, APP (per package)
- Sections: FACTS, PITFALLS, PREFERENCES, VERIFICATION, APP_SKILL_OVERRIDES
- Markdown files in `context.filesDir/memory/`
- Recall per turn with elastic token budget

### Existing Skill Design Framework (appskill_topdown)

The existing app skills have been designed with:
- Top-down structure ordered by failure cost
- Content ownership: app-local facts only, no solver logic
- Tier system: Minimal (2-5 lines), Standard (6-12), Complex (13-18, max 20)
- YAML frontmatter is currently stripped by the loader

## Design Constraints

1. **KISS** — minimal changes to existing architecture
2. **Android context** — this is a mobile agent, not a desktop coding agent. Skills must make sense for Android automation tasks
3. **Backward compatible** — existing 17 app skills must continue to work
4. **Token budget** — mobile LLM context windows are smaller; progressive disclosure matters more
5. **No file system access** — Android apps can't scan arbitrary directories. Skills need to come from: bundled assets, app's private storage, or network
6. **Existing patterns** — reuse `AppSkillRepository`, `ToolRegistry`, `PromptBuilder` patterns
7. **Security** — untrusted skills must not bypass PolicyEngine or inject harmful instructions

## Key Design Questions

1. **Where do Agent Skills live?** Android can't scan `~/.agents/skills/`. What's the equivalent?
2. **How are skills discovered?** At session start? On demand? From a registry?
3. **How do Agent Skills coexist with app skills?** Same system or parallel?
4. **Should the agent support skill activation (model chooses to load)?** Or just pre-load relevant skills?
5. **What about scripts/?** Android can't run arbitrary bash/python. What's the adaptation?
6. **How are skills installed/managed?** UI? API? Bundled only?
7. **What's the trust model?** Which skills are auto-trusted vs need approval?
