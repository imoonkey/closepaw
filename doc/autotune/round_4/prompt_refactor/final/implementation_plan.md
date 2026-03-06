# Prompt Refactor Implementation Plan

## Scope

Implement the final prompt-refactor design from `design.md` by:

- replacing the monolithic standalone/planner prompts with a smaller ownership-based core prompt
- moving duplicated tool-local guidance into tool descriptions
- adding package-scoped app-skill assets loaded per turn from the foreground package
- updating tests, docs, review notes, and verification artifacts

## Phase 1: Runtime And Prompt Refactor

### Goals

1. Add asset-backed app-skill loading keyed by full package name.
2. Inject the active app skill into per-turn prompt input between Working Memory and Observation.
3. Rewrite the standalone and planner system prompts to the new ownership model.
4. Enrich tool descriptions so removed prompt guidance still has a clear owner.

### Affected Files

- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptBuilder.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ShellTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/CompleteTaskTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt`
- `app/src/main/assets/app_skills/.../SKILL.md`
- `app/src/test/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptBuilderTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/...` for new repository/runtime tests

### Key Design Decisions

- Keep app-skill loading deliberately simple: foreground package -> `app_skills/<package>/SKILL.md` -> whole-file load.
- Do not widen `ScreenSnapshot`; carry package name as turn context instead.
- Keep observation as the last prompt block.
- Remove app-specific prompt text from static prompts instead of leaving shadow copies behind.
- Prefer one shared repository abstraction over ad hoc asset reads in the planning runner.

## Test Plan

- Add unit tests for app-skill repository success/miss behavior.
- Extend `PromptBuilderTest` to verify `History -> Working Memory -> App Skill -> Observation` ordering.
- Run targeted JVM tests during development.
- Run repo verification at the end with at least `./gradlew test`.

## Review Plan

- Produce a review file in this folder after implementation.
- Fix any correctness or ownership issues found before final verification.

## Docs Plan

- Update architecture/dev docs for prompt ownership and app-skill asset loading.
- Append a concise entry to `doc/changelog.md`.
