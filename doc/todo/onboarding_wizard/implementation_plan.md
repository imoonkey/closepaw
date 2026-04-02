# Onboarding Wizard — Implementation Plan

## Phases

Following the eng design's task breakdown. Each phase is independently committable.

### Phase 1: OnboardingStore + State Model
**Files**: `OnboardingStore.kt`, `OnboardingState.kt`
**Scope**:
- `OnboardingStore`: own prefs file `"onboarding_prefs"`, load/save step outcomes, encrypted draft key via `AppSettingsStore`'s secure prefs pattern, legacy migration (detect existing usage → auto-complete)
- `OnboardingState.kt`: sealed classes (`WizardStep`, `StepOutcome`, `StepOutcomes`, `PermissionStepState`, `ApiKeyStepState`, `DemoStepState`)
- Schema version handling
**No deps.**

### Phase 2: Root Routing + UI Shell
**Files**: `MainActivity.kt` (modify), `OnboardingScreen.kt`, `OnboardingShell.kt`, `OnboardingSteps.kt` (stubs)
**Scope**:
- MainActivity: create OnboardingStore, check completion, route to onboarding vs chat
- Eval bypass: `EXTRA_FRESH_SESSION` + `EXTRA_GOAL` → skip onboarding
- OnboardingScreen: full-screen composable routing to per-step content
- OnboardingShell: shared scaffold (title, step count, progress bar)
- Stub composables for each step (placeholder content)
- Back exits app during onboarding
**Deps**: Phase 1

### Phase 3: Permission Steps + Monitor
**Files**: `PermissionStateMonitor.kt`, permission step composables in `OnboardingSteps.kt`
**Scope**:
- PermissionStateMonitor: live checks for A11y (`AgentService.instance`), Overlay (`canDrawOverlays`), Battery (`isIgnoringBatteryOptimizations`)
- A11y polling (200ms × 15 = 3s after return)
- Permission step composables with CTA, status card, unsatisfied state
- Battery skip + fallback intent
- Auto-advance on satisfy (~400ms delay)
**Deps**: Phase 1, 2

### Phase 4: Credential Validation
**Files**: `LlmCredentialValidator.kt`, API key step composable
**Scope**:
- Direct OkHttp validation against derived provider endpoint
- Error mapping: 401/403 → InvalidKey, 5xx/timeout → TransientError
- Secure text field with visibility toggle
- Save to AppSettingsStore only on success
- Existing key pre-population
- Settings sync after validation
**Deps**: Phase 1, 2

### Phase 5: Demo Session Runner
**Files**: `OnboardingDemoController.kt`, demo step composable
**Scope**:
- Create throwaway AgentSession with BASIC mode
- Preflight gate check (A11y + Overlay + apiKey)
- Submit "Open the Settings app", observe events
- Success: GOAL_ACHIEVED + last package == com.android.settings
- 60s watchdog timeout
- BringMainActivityToFront on background completion
- Clean shutdown
**Deps**: Phase 3, 4

### Phase 6: Complete Screen + ViewModel Wiring
**Files**: `OnboardingViewModel.kt`, complete step composable, final wiring
**Scope**:
- ViewModel with Channel<OnboardingEffect> for one-shot effects
- Wire all steps together through ViewModel
- Complete screen with checklist summary
- "Start Using" sets onboardingCompleted = true
- Full end-to-end flow test
**Deps**: Phase 1-5

### Phase 7: Post-Onboarding Repair Card
**Files**: `PermissionRepairCard.kt`, `MainActivityContent.kt` (modify)
**Scope**:
- PermissionRepairCard composable with priority-based display
- MainActivityContent passes repair model to chat
- Only shown when onboardingCompleted = true AND permission revoked
- Battery repair only if was DONE (not SKIPPED)
**Deps**: Phase 2, 3

## Execution Order

Phase 1 → Phase 2 → Phase 3 & 4 (parallel) → Phase 5 → Phase 6 → Phase 7

## Notes
- ViewModel is assembled in Phase 6 but logic helpers built in earlier phases
- Each phase commit must leave the build green (stubs where needed)
- Use ChatTheme, existing color scheme, Material3 components throughout
