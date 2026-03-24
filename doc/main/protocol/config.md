# Session Configuration

> SessionConfig and related configuration types.
> -> See: [overview](overview.md) for protocol architecture.
> Last updated: 2026-03-05 (commit: 0b5b379)

## SessionConfig

> See: `protocol/SessionConfig.kt`

```kotlin
data class SessionConfig(
    val maxTurns: Int = 50,
    val actionDelayMs: Long = 2000,
    val approvalMode: ApprovalMode = ApprovalMode.SMART,
    val agentMode: AgentMode = AgentMode.PRO,
    val llm: SessionLlmConfig,
    val debugMode: Boolean = false,
    val traceEnabled: Boolean = false,
    val traceRunId: String? = null,
    val perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT,
    val mainModel: String = "glm-5",
    val executorModel: String? = null,
    val platformMode: PlatformMode = PlatformMode.ACCESSIBILITY,
    val excludedTools: Set<String> = emptySet()
)
```

| Setting | Default | Description |
|---------|---------|-------------|
| `maxTurns` | 50 | Max turns before auto-stop |
| `actionDelayMs` | 2000 | Delay after actions for UI settle |
| `approvalMode` | `SMART` | `ALWAYS_ASK` / `AUTO_APPROVE` / `SMART` |
| `agentMode` | `PRO` | `BASIC` (standalone) or `PRO` (planner + executor) |
| `mainModel` | `glm-5` | Model for standalone/planner agents |
| `executorModel` | null | Model for executor (falls back to `mainModel`) |
| `traceEnabled` | false | Persist full JSONL trace events/artifacts |
| `platformMode` | `ACCESSIBILITY` | `ACCESSIBILITY` or `VIRTUAL_DISPLAY` |
| `excludedTools` | empty | Tool names to exclude (e.g., for eval) |

## SessionLlmConfig

```kotlin
data class SessionLlmConfig(
    val backendType: LLMBackendType = LLMBackendType.OPENAI,
    val localConfig: LocalLLMConfig? = null
)
```

## PlatformMode

| Mode | Platform | Description |
|------|----------|-------------|
| `ACCESSIBILITY` | `AccessibilityPlatform` | Standard mode using Android Accessibility APIs |
| `VIRTUAL_DISPLAY` | `VirtualDisplayPlatform` | Runs apps on virtual display via Shizuku |

## AgentMode

| Mode | Behavior |
|------|----------|
| `BASIC` | One standalone agent with direct UI tools |
| `PRO` | Planner agent with delegated executor via `delegate_task` |

## LLMBackendType

| Backend | Description |
|---------|-------------|
| `OPENAI` | Cloud API (OpenAI, OpenRouter, Novita via model catalog) |
| `LOCAL` | On-device LLM via Leap SDK |

## ApprovalMode

| Mode | Behavior |
|------|----------|
| `ALWAYS_ASK` | Always ask before executing any tool |
| `AUTO_APPROVE` | Never ask, auto-approve all |
| `SMART` | Auto-approve low-risk, ask for high-risk |
