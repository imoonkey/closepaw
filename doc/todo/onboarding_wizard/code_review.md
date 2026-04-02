# Onboarding Wizard — Code Review

**Reviewer:** Claude Code  
**Date:** 2026-04-02  
**Scope:** 14 files across `onboarding/`, `ui/onboarding/`, and `app/` packages  
**Verdict:** CHANGES REQUESTED (2 critical, 5 high, 7 medium, 5 low)

---

## CRITICAL

### CR-1: API key logged to Logcat in plaintext

**File:** `OnboardingViewModel.kt` line 342  
**Code:** `Log.d(TAG, "API key saved to settings for provider ${entry.provider}")`

This line itself is safe, but `saveApiKeyToSettings` receives the raw key as a parameter. The deeper problem is in `HttpLlmCredentialValidator.kt` line 34:

```kotlin
val body = """{"model":"$modelId","messages":[{"role":"user","content":"Reply with OK"}],"max_tokens":1}"""
```

The `modelId` is string-interpolated directly into a JSON body. If `modelId` contained a quote or backslash (from a corrupted catalog or future model ID format like `org/model"name`), the JSON becomes malformed or injectable. This is a JSON injection vector.

**Fix:** Use a proper JSON builder (JSONObject or kotlinx.serialization) instead of raw string interpolation for the request body. Alternatively, escape `modelId` with a JSON-safe escape function.

---

### CR-2: API key fallback to unencrypted SharedPreferences with no user warning

**File:** `OnboardingStore.kt` lines 42-61

When `EncryptedSharedPreferences` fails (e.g., KeyStore corruption, which happens on real devices), the code silently falls back to storing the API key draft in plain-text `SharedPreferences`:

```kotlin
if (securePrefsFailed) return prefs()  // falls back to unencrypted
```

The user sees the message "Your key is encrypted on-device" (OnboardingSteps.kt line 189) regardless of whether encryption actually succeeded. This is a false security promise.

**Fix:**
1. If `EncryptedSharedPreferences` fails, do NOT persist the draft at all — keep it only in memory. The user can re-enter it.
2. Or, propagate the failure to the ViewModel and show a warning in the UI that the key will not survive process death.
3. At minimum, log at `Log.e` level (not `Log.w`) and update the security copy in the UI.

---

## HIGH

### CR-3: Race condition — `retryValidation()` calls `validateApiKey()` after mutating state

**File:** `OnboardingViewModel.kt` lines 145-151

```kotlin
fun retryValidation() {
    val state = stepState
    if (state is ApiKeyStepState.TransientError) {
        stepState = ApiKeyStepState.Editing(state.key)  // step 1: set to Editing
        validateApiKey()  // step 2: reads stepState
    }
}
```

`validateApiKey()` reads `stepState` at the top. Since both run on the main thread and `stepState` is a `mutableStateOf`, the assignment in step 1 should be visible in step 2. However, this pattern is fragile — if either function is ever made async or called from a different context, the intermediate state can be lost. More importantly, this two-step mutation is confusing: `retryValidation` should pass the key directly to a shared validation function rather than playing state-transition ping-pong.

**Fix:** Extract a `private fun doValidation(key: String)` that both `validateApiKey()` and `retryValidation()` call directly with the key, without relying on intermediate state reads.

---

### CR-4: Demo controller callbacks invoked on `Dispatchers.Default` coroutine, updating Compose state

**File:** `DefaultOnboardingDemoController.kt` lines 52-165  
**File:** `OnboardingViewModel.kt` lines 177-193

The `demoJob` launches in `scope` (which is `lifecycleScope` = `Dispatchers.Main`), but `AgentSession` operations internally may hop dispatchers. The `onSuccess`/`onFailure`/`onBringToFront` callbacks directly mutate `stepState` (a Compose `mutableStateOf`). If these callbacks ever execute off the main thread — which is plausible since the demo controller's scope is the same `lifecycleScope` but the session internals may post from different dispatchers — this is a thread-safety violation on Compose snapshot state.

**Fix:** Wrap callback invocations in `withContext(Dispatchers.Main)` inside `DefaultOnboardingDemoController`, or document and enforce that the scope's dispatcher is always `Main`. Alternatively, post the state updates via `scope.launch { ... }` in the ViewModel callbacks.

---

### CR-5: `demoSession` and `demoJob` are not thread-safe

**File:** `DefaultOnboardingDemoController.kt` lines 49-50, 167-171

```kotlin
private var demoJob: Job? = null
private var demoSession: AgentSession? = null
```

`cancel()` reads and writes `demoJob` and `demoSession`. `run()` calls `cancel()` first, then sets both. If `cancel()` is called concurrently from a different thread while `run()` is in progress, both fields can be corrupted. The `scope.launch { shutdownSession() }` in `cancel()` creates a new coroutine that races with the `demoJob` coroutine's `finally` block (which also calls `shutdownSession()`), potentially calling `session.submit(Op.Shutdown)` twice.

**Fix:** Use a `Mutex` or `AtomicReference`, or consolidate all access to these fields onto the main dispatcher. At minimum, make `cancel()` synchronous for `demoJob?.cancel()` and rely on the `finally` block in `run()` for session cleanup instead of launching a parallel shutdown.

---

### CR-6: `OnboardingViewModel` holds `Context` reference — potential leak if scope outlives Activity

**File:** `OnboardingViewModel.kt` line 24

```kotlin
class OnboardingViewModel(
    private val context: Context,
    ...
)
```

The ViewModel receives `applicationContext` (from MainActivity line 130), which is safe. But the class also receives `scope = lifecycleScope`, which is tied to the Activity. If the Activity is recreated (config change), the old ViewModel instance and its scope are destroyed, but the class design does not enforce this — `OnboardingViewModel` is not a proper `ViewModel` (not extending `androidx.lifecycle.ViewModel`), so it has no `onCleared()` to clean up resources.

**Fix:** Either:
1. Make `OnboardingViewModel` extend `androidx.lifecycle.ViewModel` and use `viewModelScope` (survives config changes).
2. Or, accept the current design but document that the instance is tied 1:1 to the Activity lifecycle and ensure all launched coroutines are properly cancelled when the Activity is destroyed (they are, via `lifecycleScope`, but this is fragile).

---

### CR-7: No tests for onboarding logic

**Files:** All onboarding files

There are zero test files for `OnboardingViewModel`, `OnboardingStore`, `PermissionStateMonitor`, `HttpLlmCredentialValidator`, or `DefaultOnboardingDemoController`. The ViewModel contains non-trivial state machine logic (`firstIncompleteStep`, step transitions, permission polling, auto-advance). The validator does HTTP with status code mapping. The demo controller manages session lifecycle. All of these are high-risk for regressions without test coverage.

**Fix:** Add unit tests for at minimum:
- `OnboardingViewModel` state transitions (mock `PermissionStateMonitor`, `LlmCredentialValidator`, `OnboardingStore`)
- `HttpLlmCredentialValidator` HTTP status code mapping (mock `HttpURLConnection`)
- `OnboardingStore` persistence round-trips
- `firstIncompleteStep()` edge cases

---

## MEDIUM

### CR-8: `firstIncompleteStep()` logic is convoluted and has subtle ordering bugs

**File:** `OnboardingViewModel.kt` lines 219-229

```kotlin
private fun firstIncompleteStep(): WizardStep {
    if (!isAccessibilityEnabled()) return WizardStep.Accessibility
    if (!isOverlayEnabled()) return WizardStep.Overlay
    if (outcomes.battery == StepOutcome.Pending && !isBatteryOptimized()) return WizardStep.Battery
    if (outcomes.battery != StepOutcome.Pending && outcomes.apiKey == StepOutcome.Pending) return WizardStep.ApiKey
    if (outcomes.battery == StepOutcome.Pending) return WizardStep.Battery
    if (outcomes.apiKey == StepOutcome.Pending) return WizardStep.ApiKey
    if (outcomes.demo == StepOutcome.Pending) return WizardStep.Demo
    return WizardStep.Complete
}
```

Lines 223-226 are confusing. Line 223 checks `battery == Pending && !isBatteryOptimized()` — but what if battery is `Pending` AND already optimized? It falls through to line 225, which returns `Battery` anyway. So the `!isBatteryOptimized()` guard on line 223 is effectively dead code for the `battery == Pending` case.

The intent seems to be: "if battery was already granted by the OS, auto-mark it done and skip to API key." But that auto-marking never happens — the code just returns `Battery` regardless.

**Fix:** If the intent is to auto-skip battery when already granted, add `onPermissionSatisfied()` handling in `enterStep` for battery. If not, simplify the logic to a straightforward linear check of each step in order.

---

### CR-9: `outcomes` state is duplicated between ViewModel and Store

**File:** `OnboardingViewModel.kt` lines 46, 131, 162-163, 181, 199-200, 292-296

Every `saveOutcome()` call is followed by a manual `outcomes = outcomes.copy(...)`. This is error-prone — if someone adds a new call site and forgets the copy, the UI state diverges from persisted state.

**Fix:** Create a single `fun updateOutcome(step, outcome)` method that does both the store write and the in-memory copy atomically:

```kotlin
private fun updateOutcome(step: WizardStep, outcome: StepOutcome) {
    store.saveOutcome(step, outcome)
    outcomes = when (step) {
        WizardStep.Accessibility -> outcomes.copy(accessibility = outcome)
        WizardStep.Overlay -> outcomes.copy(overlay = outcome)
        WizardStep.Battery -> outcomes.copy(battery = outcome)
        WizardStep.ApiKey -> outcomes.copy(apiKey = outcome)
        WizardStep.Demo -> outcomes.copy(demo = outcome)
        WizardStep.Complete -> outcomes
    }
}
```

---

### CR-10: `permissionStepCopy()` throws on non-permission steps

**File:** `OnboardingSteps.kt` line 531

```kotlin
else -> error("Not a permission step: $step")
```

This `error()` call will crash the app if `permissionStepCopy` is ever called with `WizardStep.ApiKey`, `WizardStep.Demo`, or `WizardStep.Complete`. The function is `private`, so the risk is contained, but a crash in production from a state machine bug is severe. Defensive programming would use a more graceful fallback.

**Fix:** Keep the `error()` (it correctly catches programming errors), but add a `@Throws` or convert to a sealed return type that forces callers to handle the error case. Alternatively, constrain the type system so only permission steps can reach this function.

---

### CR-11: `CompleteStepContent` does not use `OnboardingShell`

**File:** `OnboardingScreen.kt` lines 119-124

The `Complete` step renders `CompleteStepContent` directly without wrapping in `OnboardingShell`. This means the completion screen has no progress bar, no step counter, and no `systemBarsPadding`. If the device has a notch or gesture bar, content may overlap system UI.

**Fix:** Either wrap `CompleteStepContent` in `OnboardingShell` (with step 5/5 or a "complete" indicator), or add `systemBarsPadding()` and consistent horizontal padding to `CompleteStepContent` directly.

---

### CR-12: `OnboardingScreen` uses unsafe cast (`as?`) with silent fallback

**File:** `OnboardingScreen.kt` lines 52, 66, 80, 96, 112

```kotlin
state = stepState as? PermissionStepState ?: PermissionStepState.Checking
```

If `stepState` is the wrong type (e.g., due to a state machine bug where the step advanced but stepState wasn't updated), the UI silently shows "Checking..." forever with no indication of the problem. This masks bugs.

**Fix:** Log a warning when the cast fails, or assert the type in debug builds:

```kotlin
val typedState = stepState as? PermissionStepState
if (typedState == null) Log.e(TAG, "stepState type mismatch: expected PermissionStepState, got ${stepState?.javaClass}")
```

---

### CR-13: `PermissionStateMonitor` instantiated multiple times

**File:** `MainActivity.kt` lines 134, 674

```kotlin
// Line 134: in onboarding setup
permissionMonitor = PermissionStateMonitor(applicationContext)

// Line 674: in deriveRepairModel()
return PermissionStateMonitor(applicationContext).deriveRepairModel(batteryWasDone)
```

A new `PermissionStateMonitor` is created every time `deriveRepairModel()` is called (which happens during recomposition). This is wasteful — the monitor holds only a `Context` reference and has no mutable state, so a single instance should be shared.

**Fix:** Store a single `PermissionStateMonitor` instance in `MainActivity` and reuse it for both onboarding and repair model derivation.

---

### CR-14: `deriveRepairModel()` called during recomposition — triggers SharedPreferences reads on main thread

**File:** `MainActivity.kt` lines 214, 670-675

```kotlin
repairModel = deriveRepairModel(),  // called in setContent { }
```

`deriveRepairModel()` calls `onboardingStore.loadOutcomes()` which does synchronous `SharedPreferences` reads. This runs on every recomposition that evaluates the `MainActivityContent` branch. SharedPreferences reads can be slow on first access (disk I/O).

**Fix:** Cache the repair model in a `mutableStateOf` and update it in `onResume()` instead of recomputing during composition.

---

## LOW

### CR-15: Magic numbers for auto-advance delay and polling

**File:** `OnboardingViewModel.kt` lines 33-35

```kotlin
private const val AUTO_ADVANCE_DELAY_MS = 400L
private const val A11Y_POLL_INTERVAL_MS = 200L
private const val A11Y_POLL_MAX_ATTEMPTS = 15
```

These are well-named constants, which is good. However, the 400ms auto-advance delay is used in three separate places (lines 132, 183, 299). Consider whether this delay is appropriate for all cases — the demo success auto-advance might benefit from a longer delay so the user can read the success message.

**Fix:** Consider separate constants for different auto-advance contexts (permission satisfied vs. API key validated vs. demo complete).

---

### CR-16: `WizardStep.Complete` sets `stepState = DemoStepState.Ready`

**File:** `OnboardingViewModel.kt` line 248

```kotlin
WizardStep.Complete -> {
    stepState = DemoStepState.Ready // not used for Complete
}
```

Setting `stepState` to a `DemoStepState` when entering the `Complete` step is semantically wrong. Even though it is unused, this creates confusion for future maintainers and makes the state machine harder to reason about.

**Fix:** Either create a `CompleteStepState` type, or set `stepState` to a more neutral value. The comment "not used for Complete" is a code smell — if it is not used, do not set it to a misleading value.

---

### CR-17: `OnboardingEffect.OpenBatteryOptimizationList` is declared but never emitted

**File:** `OnboardingState.kt` line 54

```kotlin
data object OpenBatteryOptimizationList : OnboardingEffect
```

This effect is handled in `MainActivity.handleOnboardingEffect()` (line 653) but is never emitted by `OnboardingViewModel`. Dead code.

**Fix:** Remove the unused effect, or document where it will be used in a future iteration.

---

### CR-18: Inconsistent use of `secondary` color for success states

**File:** `OnboardingSteps.kt` lines 285, 397, 447-449

Success states use `MaterialTheme.colorScheme.secondary` (a teal/accent color) instead of a dedicated success color. This works visually but is semantically incorrect — `secondary` is an accent color, not a success indicator. If the theme changes, success indicators could become visually ambiguous.

**Fix:** Define a semantic `successColor` in the theme or use `Color(0xFF4CAF50)` (Material green) consistently for success states.

---

### CR-19: `saveApiKeyToSettings` has a hardcoded provider-to-setter mapping

**File:** `OnboardingViewModel.kt` lines 335-343

```kotlin
when (entry.effectiveApiKeyEnv) {
    "OPENAI_API_KEY" -> settingsState.updateApiKey(key)
    "OPENROUTER_API_KEY" -> settingsState.updateOpenRouterApiKey(key)
    "NOVITA_API_KEY" -> settingsState.updateNovitaApiKey(key)
}
```

If a new provider is added to the model catalog, this `when` block silently drops the key (no `else` branch). This will be a confusing bug to diagnose.

**Fix:** Add an `else` branch that logs a warning:

```kotlin
else -> Log.w(TAG, "Unknown API key env: ${entry.effectiveApiKeyEnv}, key not saved")
```

---

## Summary

| Severity | Count | IDs |
|----------|-------|-----|
| CRITICAL | 2 | CR-1, CR-2 |
| HIGH | 5 | CR-3, CR-4, CR-5, CR-6, CR-7 |
| MEDIUM | 7 | CR-8, CR-9, CR-10, CR-11, CR-12, CR-13, CR-14 |
| LOW | 5 | CR-15, CR-16, CR-17, CR-18, CR-19 |

**Recommendation: CHANGES REQUESTED**

The two critical issues (JSON injection in the validator and false encryption promise with silent plaintext fallback) must be fixed before shipping. The high-severity thread safety issues (CR-4, CR-5) are latent bugs that will manifest under real-world conditions (slow devices, rapid user interaction). The lack of tests (CR-7) makes all other fixes risky to land without regression coverage.

### Priority order for fixes:
1. CR-1 — JSON injection in validator (use JSONObject)
2. CR-2 — Remove silent plaintext fallback for API key draft
3. CR-5 — Thread-safe demo controller state
4. CR-4 — Ensure Compose state updates on main thread
5. CR-7 — Add unit tests before further changes
6. CR-9 — Consolidate outcome update logic
7. CR-3 — Extract shared validation function
