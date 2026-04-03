status: draft

# Onboarding Wizard — Engineering Design

Date: 2026-04-02
UX spec: `doc/todo/onboarding_wizard/ux_design.md`

---

## 1. Goal

Implement the first-launch onboarding wizard inside `MainActivity`, not a separate activity. Gate chat behind Accessibility, Overlay, and a validated cloud key; treat Battery as skippable; run a real demo task; resume at first incomplete step after process death; never replay after completion.

## 2. Approach

1. Single-activity. `MainActivity` chooses root route: `Onboarding` or `Chat`.
2. Own `ViewModel` plus three helpers: permission monitor, credential validator, demo controller.
3. Persist durable outcomes only. Current step derived from stored outcomes + live permission checks.
4. Derive onboarding provider/model from `AppSettingsStore.DEFAULT_MODEL` via `ModelCatalog` (currently resolves to `glm-5` → OPENROUTER → `OPENROUTER_API_KEY`).
5. Post-onboarding repair is separate — revoked permissions show in-chat repair card, not wizard replay.

---

## 3. Component Architecture

### 3.1 File Layout

```
app/src/main/kotlin/com/moonkey/androidagent/
├── app/
│   └── MainActivity.kt                     # Modified — route to onboarding or chat
├── onboarding/
│   ├── OnboardingViewModel.kt              # State machine, persistence, side effects
│   ├── OnboardingState.kt                  # Sealed classes for wizard + step states
│   ├── OnboardingStore.kt                  # SharedPreferences persistence (own prefs file)
│   ├── PermissionStateMonitor.kt           # Live permission checks
│   ├── LlmCredentialValidator.kt           # Direct HTTP key validation
│   └── OnboardingDemoController.kt         # Demo session lifecycle
└── ui/
    ├── onboarding/
    │   ├── OnboardingScreen.kt             # Full-screen shell + step router
    │   ├── OnboardingSteps.kt              # Per-step composables
    │   └── OnboardingShell.kt              # Shared scaffold: title, progress, step count
    └── chat/
        └── components/
            └── PermissionRepairCard.kt     # Post-onboarding repair UI
```

### 3.2 Composable Hierarchy

```kotlin
// MainActivity.setContent
ChatTheme {
    if (onboardingRequired) {
        OnboardingScreen(viewModel = onboardingViewModel, onComplete = { ... })
    } else {
        MainActivityContent(repairCard = repairModel, ...)  // existing chat
    }
}
```

No Jetpack Navigation — matches existing `showSettings` pattern of state-driven UI.

### 3.3 Composable Contracts

```kotlin
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onComplete: () -> Unit)

@Composable
fun OnboardingShell(stepIndex: Int, totalSteps: Int, title: String, content: @Composable () -> Unit)

@Composable fun PermissionStepContent(step: WizardStep, state: PermissionStepState, vm: OnboardingViewModel)
@Composable fun ApiKeyStepContent(state: ApiKeyStepState, vm: OnboardingViewModel)
@Composable fun DemoStepContent(state: DemoStepState, vm: OnboardingViewModel)
@Composable fun CompleteStepContent(outcomes: StepOutcomes, onFinish: () -> Unit)
```

---

## 4. State Management

### 4.1 Root Routing

`MainActivity` owns a root destination (`Onboarding` or `Chat`):
1. If `onboardingCompleted == true` → `Chat`.
2. Else compute `firstIncompleteStep()` from persisted outcomes + live checks → `Onboarding`.

### 4.2 State Model

```kotlin
enum class WizardStep { Accessibility, Overlay, Battery, ApiKey, Demo, Complete }

enum class StepOutcome { Pending, Done, Skipped }

data class StepOutcomes(
    val accessibility: StepOutcome = StepOutcome.Pending,
    val overlay: StepOutcome = StepOutcome.Pending,
    val battery: StepOutcome = StepOutcome.Pending,
    val apiKey: StepOutcome = StepOutcome.Pending,
    val demo: StepOutcome = StepOutcome.Pending
)

// Per-step state — one active at a time
sealed interface OnboardingStepState

sealed interface PermissionStepState : OnboardingStepState {
    data object Checking : PermissionStepState
    data object Ready : PermissionStepState
    data object OpeningSettings : PermissionStepState
    data object Satisfied : PermissionStepState
    data object Unsatisfied : PermissionStepState
    data object Skipped : PermissionStepState       // Battery only
}

sealed interface ApiKeyStepState : OnboardingStepState {
    data object Empty : ApiKeyStepState
    data class Editing(val key: String) : ApiKeyStepState
    data class Validating(val key: String) : ApiKeyStepState
    data class Invalid(val key: String, val message: String) : ApiKeyStepState
    data class TransientError(val key: String, val message: String) : ApiKeyStepState
    data class Valid(val key: String) : ApiKeyStepState
}

sealed interface DemoStepState : OnboardingStepState {
    data object Ready : DemoStepState
    data object Preflight : DemoStepState
    data object Running : DemoStepState
    data class Success(val message: String) : DemoStepState
    data class Failure(val reason: String) : DemoStepState
    data object Skipped : DemoStepState
}
```

### 4.3 ViewModel

```kotlin
class OnboardingViewModel(
    private val store: OnboardingStore,
    private val settingsState: AppSettingsState,
    private val modelCatalog: ModelCatalog,
    private val validator: LlmCredentialValidator,
    private val demoController: OnboardingDemoController,
    private val permissionMonitor: PermissionStateMonitor
) {
    val currentStep: State<WizardStep>
    val stepState: State<OnboardingStepState>
    val outcomes: State<StepOutcomes>

    fun onHostResumed()                    // re-check current step on onResume
    fun openSystemSettings()               // launch intent for current step
    fun onApiKeyChanged(key: String)
    fun validateApiKey()
    fun retryValidation()
    fun startDemo()
    fun skipStep()                         // Battery or Demo only
    fun finish()                           // Complete → set onboardingCompleted
}
```

### 4.4 One-shot Effects

Emit via `Channel<OnboardingEffect>` consumed by the composable:

- `OpenAccessibilitySettings`
- `OpenOverlaySettings`
- `OpenBatteryOptimization` (primary) / `OpenBatteryOptimizationList` (fallback)
- `BringMainActivityToFront`

### 4.5 Persistence Rules

- Persist step outcomes after every durable transition. Do not persist transient UI state.
- Do not write API key to `AppSettingsStore` while user is typing — save only after validation.
- Keep unvalidated key draft in encrypted storage (survives process death without re-entry).
- Clear encrypted draft on successful validation or when field is emptied.
- `onboardingCompleted` is set only when user taps the CTA on `CompleteStep`, not when Demo succeeds.

### 4.6 Settings Sync

After successful validation, `OnboardingViewModel` saves key to `AppSettingsStore` via the correct provider method. `MainActivity` refreshes `settingsState` from store so chat and settings sheet see the new value.

---

## 5. Permission Detection

### 5.1 Per-Permission Checks

| Step | Live check | CTA intent | Notes |
|---|---|---|---|
| Accessibility | `AgentService.instance != null` | `Settings.ACTION_ACCESSIBILITY_SETTINGS` | Service binding can lag toggle — poll 200ms intervals up to 3s after return. |
| Overlay | `Settings.canDrawOverlays(context)` | `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` with `package:` URI | Immediate check. |
| Battery | `PowerManager.isIgnoringBatteryOptimizations(pkgName)` | Primary: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with `package:` URI. Fallback: `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` on `ActivityNotFoundException`. | Always keep "Continue without this" visible. |

### 5.2 Auto-Advance

Driven by lifecycle, not polling:

1. `MainActivity.onResume()` → `onboardingViewModel.onHostResumed()`.
2. ViewModel re-checks only the current step.
3. If satisfied → persist outcome, show brief success (~400ms), advance.
4. If still missing and user had launched settings → move to `Unsatisfied`.

### 5.3 First Incomplete Step Resolver

Order: Accessibility → Overlay → Battery → API Key → Demo.

Rules:
- Live hard-gate failure beats stored `DONE` while onboarding is incomplete.
- Battery `SKIPPED` counts as complete.
- API key complete only when onboarding validation stored `DONE` (key presence alone not enough).

---

## 6. API Key Validation

### 6.1 Canonical Onboarding Target

Derive from `AppSettingsStore.DEFAULT_MODEL` via `ModelCatalog`:
1. Resolve model → provider, api type, modelId, baseUrl, apiKeyEnv.
2. Use for field label, validator, and demo session.

Currently: `glm-5` → OPENROUTER → `OPENROUTER_API_KEY` → `https://openrouter.ai/api/v1/chat/completions`.

### 6.2 Validator: Direct HTTP

Use direct OkHttp (not `ChatCompletionClient`) for exact HTTP status code mapping and bounded timeouts.

**Request**: minimal real inference — `{ model, messages: [{ role: "user", content: "Reply with OK" }], max_tokens: 1 }`.

**Timeout**: connect 5s, read 20s, call 20s. No automatic retries (UI has explicit Retry).

### 6.3 Error Mapping

| Condition | Result |
|---|---|
| HTTP 200 | `Valid` |
| HTTP 401 / 403 | `InvalidKey` — "That key was rejected. Check the value and try again." |
| HTTP 408 / 429 / 5xx | `TransientError` — "We couldn't reach the model provider." |
| IOException / SocketTimeoutException / SSL error | `TransientError` — "Check your internet connection." |
| HTTP 400 / 404 | `TransientError` — provider config issue |

Only `Valid` saves the key to `AppSettingsStore`.

### 6.4 Existing Key Detection

On API key step entry, if key already present in settings → pre-populate in `Editing` state (not auto-validate; may be stale). If step outcome already `Done` → auto-advance.

---

## 7. Demo Task Integration

### 7.1 Demo Controller

`OnboardingDemoController` creates a throwaway `AgentSession`, observes it, and tears it down. Not bound to `SessionCoordinator` or `ChatViewModel`.

### 7.2 Session Config

- backend: `LLMBackendType.OPENAI` (cloud path)
- model: `AppSettingsStore.DEFAULT_MODEL`
- agent mode: `AgentMode.BASIC` (simplest, most reliable for demo)
- perception: `PerceptionConfig.AccessibilityOnly`
- platform: `PlatformMode.ACCESSIBILITY`
- max turns: `5`
- goal: `"Open the Settings app"` (fixed)

### 7.3 Run Flow

1. User taps "Run Demo" → `Preflight`.
2. Preflight re-checks A11y + Overlay + apiKey outcome == DONE.
3. If hard gate broken → route back to first broken step with banner.
4. Create fresh `AgentSession`, attach to `AgentService` via `observeExternalSession(...)`.
5. Subscribe to `session.events`, submit demo goal.
6. Record last observed `ScreenCaptured.packageName`.
7. **Success**: `TaskCompleted.reason == GOAL_ACHIEVED` AND last package is `com.android.settings`.
8. **Failure**: ERROR / MAX_TURNS / timeout / verification mismatch.
9. If app backgrounded when terminal event arrives → emit `BringMainActivityToFront`.
10. Persist `onboarding_demo = done` or `skipped`. Neither sets `onboardingCompleted`.
11. Shut down demo session before chat becomes available.

**Timeout**: 60-second watchdog. If no terminal event → cancel session, show failure.

### 7.4 Session Cleanup

- Cancel event collection job
- `demoSession?.submit(Op.Shutdown)`
- Null out session reference
- Demo session stays in history as proof; chat starts fresh

### 7.5 Eval Pipeline Bypass

Intents with `EXTRA_FRESH_SESSION = true` AND `EXTRA_GOAL` set (eval/debug-run mode) bypass onboarding entirely — go straight to chat. This keeps `debug-run.sh` and eval scripts working.

---

## 8. Data Model

### 8.1 OnboardingStore

Separate prefs file `"onboarding_prefs"` (not AppSettingsStore — keeps concerns separated).

**Plain prefs:**

| Key | Type | Values |
|---|---|---|
| `schema_version` | Int | `1` (migration marker) |
| `onboarding_completed` | Boolean | Final gate |
| `step_accessibility` | String | `pending` / `done` |
| `step_overlay` | String | `pending` / `done` |
| `step_battery` | String | `pending` / `done` / `skipped` |
| `step_api_key` | String | `pending` / `done` |
| `step_demo` | String | `pending` / `done` / `skipped` |

**Encrypted prefs** (via AppSettingsStore's secure prefs):

| Key | Type | Meaning |
|---|---|---|
| `onboarding_api_key_draft` | String? | Unvalidated draft. Cleared on success or empty. |

**Not persisted** (derived from live state on every launch):
- Current step, transient UI state (Checking/Validating/Running), permission booleans, demo failure message.

### 8.2 Invariants

- `onboarding_completed = true` only when user taps "Start Using" on Complete screen.
- Step outcomes written on each durable transition. Enable resume.
- If `onboarding_completed = true`, step outcomes are never read again.
- Clear data → full wizard on next launch (correct).

---

## 9. Integration with Existing Code

### 9.1 MainActivity Changes (~30 lines)

1. Create `OnboardingStore`, check `onboardingCompleted`.
2. If onboarding needed → create `OnboardingViewModel` + helpers.
3. `setContent`: `if (onboardingRequired) OnboardingScreen() else MainActivityContent()`.
4. `onResume()` → `onboardingViewModel?.onHostResumed()`.
5. Intent handling: if eval mode (`EXTRA_FRESH_SESSION` + `EXTRA_GOAL`) → bypass. Otherwise defer goal dispatch until onboarding complete.

### 9.2 Unchanged Files

| File | Why unchanged |
|---|---|
| `MainActivityContent.kt` | Accepts optional repair card model, otherwise same |
| `AppSettingsStore.kt` | API key save methods already exist |
| `AppSettingsState.kt` | updateApiKey() called after validation, not during typing |
| `AgentService.kt` | Already exposes `instance`, `observeExternalSession` |
| `ChatViewModel.kt` | No onboarding awareness needed |
| `SettingsSheet.kt` | Not reachable during onboarding |

### 9.3 Post-Onboarding Repair Card

`PermissionStateMonitor` derives `PermissionRepairModel?` only when `onboardingCompleted == true`. Passed through `MainActivityContent` → `ChatScreen`.

| Permission revoked | Repair priority |
|---|---|
| Accessibility | Blocking (can't automate) |
| Overlay | High (no control UI) |
| Battery (was DONE, not SKIPPED) | Low (reliability warning) |

---

## 10. Migration

### 10.1 Rule

When `schema_version` is absent (first launch after update):
1. Check for legacy usage evidence: session history, stored provider key, non-empty allow-list, non-default settings.
2. If evidence exists → `onboarding_completed = true`, `schema_version = 1`.
3. Otherwise → `onboarding_completed = false`, `schema_version = 1`.

Existing users skip onboarding. Brand-new installs enter wizard.

### 10.2 Partial Setup

User has some permissions but not all → wizard starts at first incomplete step. Already-satisfied steps auto-advance with brief checkmark.

---

## 11. Implementation Tasks

### Task 1: `onboarding-store-and-migration`
**Scope**: `OnboardingStore.kt`, `OnboardingState.kt`
**Criteria**: Load/save step outcomes, encrypted draft key, legacy migration.
**Deps**: none

### Task 2: `main-activity-root-routing`
**Scope**: `MainActivity.kt`, `OnboardingScreen.kt`, `OnboardingSteps.kt`, `OnboardingShell.kt`
**Criteria**: Route onboarding vs chat, back exits app, settings unreachable during onboarding, eval bypass.
**Deps**: Task 1

### Task 3: `permission-step-monitoring`
**Scope**: `PermissionStateMonitor.kt`, permission step UI
**Criteria**: A11y/Overlay/Battery checks on entry + onResume, auto-advance, battery fallback + skip.
**Deps**: Task 1, Task 2

### Task 4: `credential-validation`
**Scope**: `LlmCredentialValidator.kt`, API key step UI
**Criteria**: Direct HTTP validation, auth vs network error mapping, save only on success, settings sync.
**Deps**: Task 1, Task 2

### Task 5: `demo-session-runner`
**Scope**: `OnboardingDemoController.kt`, demo step UI
**Criteria**: Real session, preflight gate check, GOAL_ACHIEVED + com.android.settings verification, 60s timeout, clean shutdown.
**Deps**: Task 3, Task 4

### Task 6: `post-onboarding-repair-card`
**Scope**: `PermissionRepairCard.kt`, `MainActivityContent.kt`
**Criteria**: Targeted repair cards with priority, no wizard replay, battery only if was DONE.
**Deps**: Task 2, Task 3

---

## 12. Trade-offs

- **No Navigation Compose**: root `when` switch is simpler than a nav graph for two routes.
- **No generic wizard framework**: six explicit states are clearer than reusable abstraction.
- **No full step-state persistence**: outcomes + encrypted draft are enough; everything else derived.
- **No chat-session reuse for demo**: hard boundary keeps onboarding logic out of chat lifecycle.
- **No provider picker**: default model defines the required provider; advanced choice stays in settings.
- **Direct HTTP over ChatCompletionClient**: need exact status codes for auth vs network distinction.
