# Plan: `open_intent` Tool (V1)

## Context

Agent currently navigates to app screens via UI clicks. Many destinations are reachable via exported intents — faster, more reliable, deterministic. This adds an `open_intent` tool that fires arbitrary Android intents, complementing `open_app` (which only launches launcher activities).

## Files to Create

### 1. `app/src/main/kotlin/ai/closepaw/tool/impl/OpenIntentTool.kt`

New `ToolSpec` implementation. Follows `OpenAppTool.kt` pattern.

**Parameters:**
- `action` (string, required) — e.g., `android.intent.action.VIEW`
- `data` (string, optional) — URI string
- `package` (string, optional) — target package (makes intent explicit)
- `component` (string, optional) — `pkg/.Activity` or `pkg/full.Activity`
- `extras` (object, optional) — key-value map (string/int/boolean/float values)
- `category` (string, optional) — e.g., `android.intent.category.BROWSABLE`
- `type` (string, optional) — MIME type
- `flags` (int, optional) — additional intent flags (OR'd with `FLAG_ACTIVITY_NEW_TASK`)

**Invocation logic (V1 — startActivity only):**
1. Build `android.content.Intent` from params
2. Always add `FLAG_ACTIVITY_NEW_TASK` (service context)
3. Extract target package: from `package` param, or from `component`, or resolve via `PackageManager.resolveActivity()`
4. BLOCKED tier check via `context.appClassifier`
5. Call `context.platform.fireIntent(intent)`
6. 800ms UI settle → `captureScreen()` → `buildObservation()`

**Validation:**
- `action` required, non-blank
- `extras` values must be String, Int/Long, Boolean, or Double/Float — reject other types
- `component` format: must contain `/`
- `flags` must be Int if present

## Files to Modify

### 2. `app/src/main/kotlin/ai/closepaw/platform/AndroidPlatform.kt`
Add interface method:
```kotlin
suspend fun fireIntent(intent: android.content.Intent): ActionResult
```
Default implementation throws `UnsupportedOperationException` (safe for MockPlatform in tests).

### 3. `app/src/main/kotlin/ai/closepaw/platform/AccessibilityPlatform.kt`
Implement `fireIntent`:
```kotlin
override suspend fun fireIntent(intent: Intent): ActionResult {
    return withContext(Dispatchers.Main) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            ActionResult.Success("Intent fired: ${intent.action}")
        } catch (e: SecurityException) {
            ActionResult.Failure("Permission denied: ${e.message}")
        } catch (e: Exception) {
            ActionResult.Failure("Failed to fire intent: ${e.message}")
        }
    }
}
```

### 4. `app/src/main/kotlin/ai/closepaw/platform/virtualdisplay/VirtualDisplayPlatform.kt`
Implement `fireIntent` with display targeting:
```kotlin
override suspend fun fireIntent(intent: Intent): ActionResult {
    return try {
        arbiter.withRunningLease { appController.fireIntent(intent) }
    } catch (e: PlatformNotRunningException) {
        ActionResult.Failure("Virtual display not running (${e.message})")
    }
}
```

### 5. `app/src/main/kotlin/ai/closepaw/platform/virtualdisplay/VirtualDisplayAppController.kt`
Add `fireIntent` method — same dual-path pattern as `launchApp` (Shizuku shell `am start` with `--display` flag, fallback to `shizuku.launchOnDisplay`). Extract component from intent for shell path; if no component, use intent-based fallback only.

### 6. `app/src/main/kotlin/ai/closepaw/tool/ToolName.kt`
Add:
```kotlin
data object OpenIntent : ToolName(
    raw = "open_intent",
    canonical = "open_intent",
    displayName = "Open intent"
)
```
- Add to `from()` when-clause
- Add to `isScreenChanging` → `true`

### 7. `app/src/main/kotlin/ai/closepaw/session/SessionToolingBootstrapper.kt`
Add registration line in `registerBuiltInTools`:
```kotlin
if (ToolName.OpenIntent.raw in allowedToolNames) register(OpenIntentTool())
```

### 8. `app/src/main/kotlin/ai/closepaw/tool/ToolRouter.kt`
Extend destination package resolution (around line 109):
```kotlin
val destinationPackage = when (toolName) {
    "open_app" -> resolveOpenAppDestination(params, context.platform)
    "open_intent" -> resolveOpenIntentDestination(params)
    else -> null
}
```
Add `resolveOpenIntentDestination`: extract package from `package` param or parse from `component` param. Simple string extraction, no I/O needed.

### 9. Role definitions — add `"open_intent"` to allowedTools:
- `StandaloneAgentDef.kt` — yes (standalone agent does everything)
- `ExecutorAgentDef.kt` — yes (executor fires intents directly)
- `PlannerAgentDef.kt` — yes (planner can fire intents like it can `open_app`)
- Update system prompts to mention `open_intent` alongside `open_app`

## Build Order

1. **Platform layer**: `AndroidPlatform.fireIntent()` interface + `AccessibilityPlatform` + `VirtualDisplayPlatform` + `VirtualDisplayAppController`
2. **ToolName**: Add `OpenIntent` to enum
3. **OpenIntentTool.kt**: Create the tool
4. **ToolRouter**: Add destination resolution for `open_intent`
5. **SessionToolingBootstrapper**: Register the tool
6. **Role defs**: Add to allowedTools + update prompts
7. **Build + verify**

## Verification

1. `./gradlew assembleDebug` — must compile
2. `./gradlew test` — existing tests must pass
3. Manual test via debug-run: `./scripts/debug-run.sh "Open Chrome settings"` — agent should be able to use `open_intent` with `action=VIEW, component=com.android.chrome/com.android.chrome.settings.MainSettings`
