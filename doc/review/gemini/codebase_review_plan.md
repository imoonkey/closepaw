# Codebase Review Plan

This plan divides the codebase review into manageable subtasks to ensure a comprehensive and independent analysis of the Android Agent project.

## Review Philosophy
- ** rigorous**: "Linus Torvalds" style - high standards, no mercy for sloppy code.
- **Independent**: Clean slate review, ignoring previous reviews in `doc/review/codex`.
- **First Principles**: Evaluate against the stated design philosophy in `infra_summary.md` and general software engineering best practices (SOLID, Clean Code).

## Subtasks

### 1. Core Agent Logic Review
- **Scope**: `app/src/main/kotlin/com/moonkey/androidagent/agent/`
- **Focus**:
    - `Agent.kt`: ReAct loop correctness, state management, lifecycle handling.
    - `Turn.kt`: LLM interaction, prompt construction, parsing logic, context management.
    - `AgentConfig.kt`: Configuration parameters and defaults.
- **Goals**: Verify the "brain" of the agent. Ensure the Perceive-Think-Act-Observe loop is robust and handles errors gracefully.

### 2. Session and Protocol Review
- **Scope**:
    - `app/src/main/kotlin/com/moonkey/androidagent/session/`
    - `app/src/main/kotlin/com/moonkey/androidagent/protocol/`
- **Focus**:
    - `AgentSession.kt`: Lifecycle management, Op processing, Event emission.
    - `SessionServices.kt`: Dependency injection graph.
    - Protocol definitions (`Op.kt`, `AgentEvent.kt`, `SessionState.kt`).
- **Goals**: Validate the "Thin Session Layer" principle. Check for race conditions, state inconsistencies, and protocol adherence.

### 3. Tooling and Platform Review
- **Scope**:
    - `app/src/main/kotlin/com/moonkey/androidagent/tools/`
    - `app/src/main/kotlin/com/moonkey/androidagent/infra/tools/`
    - `app/src/main/kotlin/com/moonkey/androidagent/platform/`
- **Focus**:
    - `ToolRouter.kt`: Execution state machine, policy enforcement.
    - `BaseTool.kt`: Abstraction, observation capture (`capturePostActionObservation`).
    - Tool implementations (`ClickTool`, `TypeTool`, etc.).
    - `AndroidPlatform` & `AccessibilityPlatform`: Abstraction layer correctness.
- **Goals**: Verify tool execution safety, observation reliability, and platform abstraction leakage.

### 4. Data, Perception, and Infrastructure Review
- **Scope**:
    - `app/src/main/kotlin/com/moonkey/androidagent/data/`
    - `app/src/main/kotlin/com/moonkey/androidagent/infra/` (excluding tools)
- **Focus**:
    - `Perceptor.kt`: Screen snapshotting, tree traversal, token budget management.
    - `LLMClient.kt`: API interaction, error handling, retries.
    - `HistoryManager.kt`, `PolicyEngine.kt`, `ToolRegistry.kt`.
- **Goals**: Assess the quality of inputs (perception) and infrastructure support.

### 5. UI and Android Services Review
- **Scope**:
    - `app/src/main/kotlin/com/moonkey/androidagent/ui/`
    - `app/src/main/kotlin/com/moonkey/androidagent/service/`
    - `app/src/main/kotlin/com/moonkey/androidagent/AgentService.kt`
    - `app/src/main/kotlin/com/moonkey/androidagent/MainActivity.kt`
- **Focus**:
    - Jetpack Compose implementation.
    - Service lifecycle and binding.
    - Overlay management.
    - Event consumption and UI updates.
- **Goals**: Ensure a responsive, modern UI that correctly reflects agent state.

## Final Output
- `doc/review/gemini/overall_code_review.md`: Summary of findings, critical issues, and strategic recommendations.
