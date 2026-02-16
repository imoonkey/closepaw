status: draft

# Refactor 03: Virtual Display Stack Decomposition

Date: 2026-02-16
Goal: make VD platform reliable and easier to evolve by isolating state transitions, capture strategy, and Shizuku transport.

## Problem
`VirtualDisplayPlatform` and `ShizukuClient` combine state machine logic, reflection transport, shell fallbacks, viewer interaction handling, and capture strategy in very large files.

## Scope
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/ShizukuClient.kt`

## Design
1. Introduce explicit surface/capture state machine.
- States: `ImageReader`, `LivePreview`, `Recovering`.
- Transition functions with guarded preconditions.

2. Split capture strategy.
- `ImageReaderCapture` and `PixelCopyCapture` interfaces with retry/fallback policy in coordinator.

3. Split Shizuku transport adapters.
- `DisplayManagerTransport`, `InputManagerTransport`, `ShellTransport`.
- Keep reflection and API-level branching out of platform orchestration class.

4. Keep behavior identical initially.
- No change to user-visible flow.

## Phases
### Phase 1
- Structural extraction only, no semantic changes.

### Phase 2
- Add unit tests for transition and fallback policies.

### Phase 3
- Tighten error reporting and telemetry for capture/injection failures.

## Risks
- Reflection API differences across Android versions.
- Mitigation: keep transport wrappers thin and preserve current invocation signatures first.

## Verification
- Existing VD behavior preserved in manual viewer and headless modes.
- Virtual display tests and smoke runs succeed.
