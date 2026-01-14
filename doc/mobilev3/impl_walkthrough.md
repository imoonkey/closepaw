# Walkthrough: Mobile-Agent-v3 Adaptation

## Overview
We have successfully refactored the Android Agent to use the **Mobile-Agent-v3** architecture. The single monolithic loop has been replaced with a multi-agent orchestration system.

## Changes

### 1. New Domain Layer (`domain/`)
- **State ([InfoPool](file:///Users/moonkey/workspace/androidagent/.reference/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py#6-47))**: Centralized session state management.
- **Agents ([Manager](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/domain/agents/Manager.kt#12-88), [Executor](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/domain/agents/Executor.kt#12-102), [Reflector](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/domain/agents/Reflector.kt#12-83))**: Specialized agents with distinct responsibilities.
    - [Manager](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/domain/agents/Manager.kt#12-88): Plans high-level steps.
    - [Executor](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/domain/agents/Executor.kt#12-102): Executes atomic actions.
    - [Reflector](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/domain/agents/Reflector.kt#12-83): Verifies action outcomes.
- **Models**: Defined [AgentAction](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/domain/models/Models.kt#7-23), [ValidationOutcome](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/domain/models/Models.kt#26-36), and [ScreenSnapshot](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/domain/models/Models.kt#39-45).

### 2. New Data Layer (`data/`)
- **Perception (`Perceptor`)**: Replaced `Sanitizer`. Now returns rich [ScreenSnapshot](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/domain/models/Models.kt#39-45) objects.
- **LLM (`LLMClient`)**: Refactored to support generic Chat inputs (System/User messages).

### 3. New Service Layer (`service/`)
- **[AgentOrchestrator](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/service/AgentOrchestrator.kt#20-168)**: Manages the life-cycle loop: Perception -> Reflection -> Planning -> Execution.
- **[ActionDispatcher](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/service/ActionDispatcher.kt#11-120)**: Handles low-level Android gestures.

### 4. Component Updates
- **[AgentService](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/AgentService.kt#18-91)**: Stripped down to be a lightweight container for [AgentOrchestrator](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/service/AgentOrchestrator.kt#20-168).
- **[MainActivity](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/MainActivity.kt#14-92)**: Retained compatibility with the new service structure.

### 5. Overlay & Control
- **[OverlayManager](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/service/OverlayManager.kt#13-101)**: Floating window with Status, Pause/Resume, and Stop controls.
- **Permissions**: Added `SYSTEM_ALERT_WINDOW` to manifest and request logic in [MainActivity](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/MainActivity.kt#14-92).
- **Orchestration**: Added pause/resume logic to [AgentOrchestrator](file:///Users/moonkey/workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/service/AgentOrchestrator.kt#20-168) to support "Take Over" functionality.

## Verification
- **Compilation**: The project compiles successfully (`clean assembleDebug`).
- **Architecture**: Follows Clean Architecture principles.
- **Logic**: Ported prompts and logic from Mobile-Agent-v3 python scripts to Kotlin.

## Next Steps
- **Emulator Testing**: Install the APK and run a real task.
- **Prompt Tuning**: The prompts are direct ports; they may need adjusting for OpenAI's specific quirks vs AutoGLM.
