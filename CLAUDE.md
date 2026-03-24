# Android Agent

AI-powered Android automation using accessibility services. Kotlin/Jetpack Compose.

## Quick Reference

**Build**: `./gradlew assembleDebug`
**Test**: `./gradlew test`
**Lint**: `./gradlew lint`
**Run**: `./scripts/debug-run.sh "Open Settings"`
**Full Check**: `./gradlew clean assembleDebug lint test`

**Architecture Docs**: `doc/main/`
**Dev Workflow**: `doc/dev/development.md`

## Critical Rules

Global rules apply (`~/.claude/CLAUDE.md`). Project-specific additions:

- Lifecycle-aware: no static Context refs, scope coroutines to lifecycle
- Main-safe: heavy work on `Dispatchers.IO` or `Default`
- Accessibility: follow service best practices, handle edge cases
- Sanitize accessibility tree data

## Agent Skills

- `/autotune` - One eval-tune round, stops for human review
- `/autotune-loop` - Autonomous multi-round autotune via `loop_state.json`
- `/prompt-tune` - Apply prompt, tool, and app-skill changes per ownership layer
- `/verify` - Build + lint + tests
- `/build-fix` - Fix Gradle errors incrementally
- `/update-docs` - Sync docs with code changes
- `/tdd` - Test-driven development for core logic
- `/code-review` - Systematic code review
- `/orchestrate` - Chain skills/agents for complex workflows
- `/action-debug` - Debug failed tool executions by isolating action layer
- `/cog-tune` - Analyze agent cognition using traces and eval results
- `/ux-visual-debug` - End-to-end UX QA from user perspective via ADB
- `/align` - Align design between Codex and Claude
- `/coding-standards` - Android/Kotlin coding conventions (auto-applied)
- `/ultra-think` - Deep strategic thinking for high-impact decisions
- `/ralph-loop` - Start Ralph Loop (`/cancel-ralph` to stop)

## Git

Conventional commits: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`
