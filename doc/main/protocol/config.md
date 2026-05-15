# Session Configuration

> SessionConfig and related configuration types.
> -> See: [overview](overview.md) for protocol architecture.
> Last updated: 2026-05-15

## SessionConfig

> See: `protocol/SessionConfig.kt`

```kotlin
data class SessionConfig(
    val maxTurns: Int = 50,
    val actionDelayMs: Long = 2000,
    val approvalMode: ApprovalMode = ApprovalMode.SMART,
    val llm: SessionLlmConfig = SessionLlmConfig(),
    val debugMode: Boolean = false,
    val traceEnabled: Boolean = false,
    val traceRunId: String? = null,
    val perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT,
    val mainModel: String = "glm-5",
    val subagentModel: String? = null,
    val platformMode: PlatformMode = PlatformMode.ACCESSIBILITY,
    val excludedTools: Set<String> = emptySet()
)
```

| Setting | Default | Description |
|---------|---------|-------------|
| `maxTurns` | 50 | Max turns before auto-stop |
| `actionDelayMs` | 2000 | Delay after actions for UI settle |
| `approvalMode` | `SMART` | `ALWAYS_ASK` / `AUTO_APPROVE` / `SMART` |
| `mainModel` | `glm-5` | Model for the main agent |
| `subagentModel` | null | Model for delegated subagents (falls back to `mainModel`) |
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

## LLMBackendType

| Backend | Description |
|---------|-------------|
| `OPENAI` | Cloud API (OpenAI, OpenRouter, Novita via model catalog) |
| `LOCAL` | On-device LLM via Leap SDK |

## ApprovalMode

Approval decisions are driven by **app tier** (BLOCKED/CAUTIOUS/NORMAL) combined with the approval mode. BLOCKED apps are always denied regardless of mode. See [tools.md](../infra/tools.md) for PolicyEngine details.

| Mode | NORMAL apps | CAUTIOUS apps | BLOCKED apps |
|------|-------------|---------------|--------------|
| `ALWAYS_ASK` | Ask user | Ask user | Deny (always) |
| `AUTO_APPROVE` | Allow | Allow | Deny (always) |
| `SMART` | Allow | Ask user | Deny (always) |
