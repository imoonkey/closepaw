# Agent Skills — Implementation Summary

**Status:** done · **Completed:** 2026-05-01

## What Shipped

agentskills.io-compatible Agent Skills system. Distinct from (and coexisting with) the per-package App Skill system. Skills installed to `context.filesDir/skills/<name>/SKILL.md` are discovered at session start, listed as a one-line catalog in the system prompt, and activated either by explicit `/skill-name` mention in the user goal or by the model calling `activate_skill(name)`.

App Skills frontmatter migrated to the same agentskills.io-compatible format (`name: app-<short-name>` + `metadata.package: <pkg>`); both skill types now share `SkillFrontmatterParser`.

## Components

| Layer | File |
|-------|------|
| Frontmatter parsing | `agent/cognition/skills/SkillFrontmatterParser.kt`, `SkillFrontmatter.kt` |
| Discovery | `agent/cognition/skills/AgentSkillCatalog.kt`, `AgentSkillEntry.kt` |
| Activation | `agent/cognition/skills/AgentSkillManager.kt` (`@Synchronized`, idempotent re-read) |
| Tool | `tool/impl/ActivateSkillTool.kt`, `tool/ToolName.kt` (non-screen-changing) |
| Wiring | `session/SessionServices.kt`, `session/SessionToolingBootstrapper.kt` (conditional registration), `agent/TurnPlanningPhaseRunner.kt` |
| Role allowlists | `agent/definition/{Standalone,Planner,Executor}AgentDef.kt` (added `activate_skill`) |
| App Skill loader unify | `agent/cognition/prompt/AppSkillRepository.kt` (uses shared `SkillFrontmatterParser`, removed local `FRONTMATTER_REGEX`) |
| App Skill migration | All 17 `app/src/main/assets/app_skills/*/SKILL.md` |

## Key Decisions

1. **Single skill root** — `context.filesDir/skills/`, no bundled assets. Install via ADB push in v1.
2. **Catalog in system prompt, bodies on demand** — keeps prompt small; bodies enter conversation history as tool results and follow normal compaction.
3. **Explicit-mention bodies are user-role messages, not system prompt** — preserves the design priority where Active Agent Skills sit below user goal, screen evidence, and app skills.
4. **Re-activation is idempotent and re-delivers the body** — supports model re-calling `activate_skill` after compaction. `AlreadyActive` result is dead code.
5. **Description sanitization** — newlines/control chars replaced with space, trim, ≤1024 chars. Defeats catalog-time prompt injection.
6. **`@Synchronized` activate paths** — `AgentSkillManager` instance is shared across `SessionServices.copy()` (so delegated executors inherit activations).
7. **App skill naming convention** — `app-<short-name>` (e.g. `app-settings`, `app-chrome`), package in `metadata.package`. Directory still keyed by package.
8. **Conditional tool registration** — `activate_skill` only registered when catalog is non-empty.
9. **App Skill loader unification** — depends on the frontmatter migration (the new parser rejects `name: com.android.settings` because dots fail the lowercase-hyphen regex).

## Verification

- Unit tests: `SkillFrontmatterParserTest`, `AgentSkillCatalogTest`, `AgentSkillManagerTest`, `ActivateSkillToolTest`, `AppSkillAssetIntegrityTest` (parses every real `app_skills/*/SKILL.md`)
- Build: `./gradlew assembleDebug` green
- Two codex review rounds — Round 1 found 1 CRITICAL + 3 HIGH (synchronized state, body discarded, read-failure poisoning, description bounds, mention boundary). Round 2 found 3 HIGH (system-prompt authority, re-activation no-body, multiline injection). All 7 issues fixed.
- Real-device QA on nubia (P0110, Tailscale + cproxy): 5/5 PASS for agent skill scenarios + Settings app skill loads correctly with new frontmatter format.

## Project Files

- Design: `final/design.md`
- Cross-agent discussion: `discussion/0001…0005.md`
- Reference analysis: `final/reference_analysis.md`
- Initial designs (claude vs codex): `initial/`
