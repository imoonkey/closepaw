# Path B Implementation Plan: CodexResponseClient

Ref: `doc/todo/openai_oauth/path_b_design.md`

## Phases

### Phase 1: Request Builder + SSE Parser (parallel, no deps)
- **1a**: `llm/CodexRequestBuilder.kt` — ResponseInputItem/FunctionTool → JSON
- **1b**: `llm/CodexSseParser.kt` — SSE parsing, event mapping, ToolCallAccumulator

### Phase 2: Core Client (depends on Phase 1)
- `llm/CodexResponseClient.kt` — OkHttp streaming + non-streaming, error handling, account ID extraction

### Phase 3: Factory Routing (depends on Phase 2)
- `llm/LLMClientFactory.kt` — `isOAuth()` detection, cache key update
- `app/AppSettingsState.kt` — `authMethod` property, `__AUTH_METHOD_OPENAI` in `buildApiKeys()`

### Phase 4: Code Review + Build Verify + Docs
- `/code-review` on all changed files
- Build verification
- Doc updates

## Key Design Decisions
- Auth method flows from `OnboardingStore` → `AppSettingsState.authMethod` → `buildApiKeys()` → `__AUTH_METHOD_OPENAI` signal
- `MainActivity` loads auth method from `onboardingStore` into `settingsState` on create + after onboarding
- No new enum values, no model catalog changes, no session config changes
