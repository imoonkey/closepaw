# Android Agent

AI-powered Android automation using accessibility services to operate apps. Kotlin/Jetpack Compose app that perceives screen state and executes actions via natural language.

## Quick Reference

**Build**: `./gradlew assembleDebug`  
**Test**: `./gradlew test`  
**Lint**: `./gradlew lint`  
**Run**: `./scripts/debug-run.sh "Open Settings"`  
**Logs**: `./scripts/logs.sh`  
**Full Check**: `./gradlew clean assembleDebug lint test`

**Architecture Docs**: `doc/main/`  
**Dev Workflow**: `doc/dev/development.md`

## Critical Rules

### Code Style
- Kotlin idioms: sealed classes, data classes, extension functions
- Immutability: `val` over `var`, `copy()` for modifications
- Null safety: prefer `?.`, `?:`, avoid `!!`
- Coroutines: structured concurrency, main-safe dispatchers
- Max 400 lines/file, extract when larger

### Android-Specific
- Lifecycle-aware: no static Context refs, scope coroutines to lifecycle
- Main-safe: heavy work on `Dispatchers.IO` or `Default`
- Accessibility: follow service best practices, handle edge cases
- Permissions: check at runtime, graceful degradation

### Security
- No hardcoded API keys or secrets
- Environment variables for sensitive data
- Validate all external input
- Sanitize accessibility tree data

### Testing
- Unit tests for core logic (state machines, data transforms)
- MockK for mocking
- Selective TDD for critical paths

## File Structure

```
app/src/main/kotlin/com/moonkey/androidagent/
├── agent/          # Core agent orchestration
├── session/        # Session management, state
├── protocol/       # Events, errors, types
├── tool/           # Tool implementations
├── perception/     # Screen perception (a11y tree)
├── platform/       # Android platform integrations
├── llm/            # LLM client
├── ui/             # Compose UI
└── history/        # Session history persistence
```

## Key Patterns

### State Management
```kotlin
sealed class SessionState {
    object Idle : SessionState()
    data class Running(val taskId: String) : SessionState()
    data class Error(val error: AgentError) : SessionState()
}
```

### Error Handling
```kotlin
sealed class AgentError {
    data class ToolFailed(val tool: String, val reason: String) : AgentError()
    data class LLMError(val message: String) : AgentError()
}
```

### Coroutine Scopes
```kotlin
// Activity/Fragment: lifecycleScope
// ViewModel: viewModelScope  
// Service: custom scope with SupervisorJob
```

## Agent Skills
- `/plan` - Create implementation plan before coding
- `/autotune` - Run one eval-tune round and stop for human review by default
- `/autotune-loop` - Run autonomous multi-round autotune via `loop_state.json`
- `/prompt-tune` - Apply prompt, tool, and app-skill changes in the correct ownership layer
- `/verify` - Run build + lint + tests
- `/build-fix` - Fix Gradle errors incrementally
- `/update-docs` - Sync docs with code changes
- `/tdd` - Test-driven development for core logic
- `/code-review` - Systematic code review
- `/strategic-compact` - Context compaction at task boundaries
- `/orchestrate` - Chain skills/agents for complex workflows
- `/action-debug` - Debug failed tool executions by isolating action layer
- `/cog-tune` - Analyze agent cognition using traces and eval results (includes quick visual debug)
- `/ux-visual-debug` - End-to-end UX QA from user perspective via ADB
- `/align` - Align design between Codex and Claude
- `/coding-standards` - Android/Kotlin coding conventions (auto-applied during code changes)
- `/ultra-think` - Deep strategic thinking for high-impact decisions
- `/ralph-loop` - Start Ralph Loop in current session (`/cancel-ralph` to stop)

## Git

Conventional commits: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`
