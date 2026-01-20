# UI Layer - Consolidated Review Summary

> **Files**: `MainActivity.kt`, `AgentService.kt`, `service/OverlayManager.kt`, `ui/screen/AgentScreen.kt`, `util/StatusUtils.kt`
> **Reviewers**: Claude, Codex, Gemini

## High-Risk Issues (Must Fix)

### 1. API Key Stored Insecurely
**Consensus**: Claude, Codex
**Location**: `MainActivity.kt:136-149`, `AndroidManifest.xml`

**Problem**: API key loaded from external storage without encryption:
```kotlin
val file = File(Environment.getExternalStorageDirectory(), "api_key.txt")
```

Issues:
1. External storage is world-readable on many devices
2. Plain text storage of sensitive API key
3. `Environment.getExternalStorageDirectory()` is deprecated
4. Uses deprecated `READ/WRITE_EXTERNAL_STORAGE` permissions that don't work on targetSdk 35

**Impact**: API key exposed to other apps or malicious actors.

**Fix**: Use EncryptedSharedPreferences or Android Keystore:
```kotlin
val encryptedPrefs = EncryptedSharedPreferences.create(...)
apiKey = encryptedPrefs.getString("api_key", "")
```

---

### 2. AgentService.instance is Racey Global Singleton
**Reviewer**: Claude
**Location**: `AgentService.kt:28-30`

**Problem**: 
- `instance` has `@Volatile` but `statusCallback` doesn't
- Both accessed from different threads without synchronization
- `statusCallback` captures MainActivity reference

**Impact**: Null visibility issues, stale callback references, potential crashes.

**Fix**: Use Flow for status instead of callback:
```kotlin
private val _statusFlow = MutableSharedFlow<String>(replay = 1)
val statusFlow: SharedFlow<String> = _statusFlow.asSharedFlow()
```

---

### 3. MainActivity State Leaks via statusCallback
**Reviewer**: Claude
**Location**: `MainActivity.kt:53-63`, `MainActivity.kt:109-111`

**Problem**: Callback captures `this` reference. If callback in-flight when onDestroy runs, runOnUiThread called on destroyed activity.

**Impact**: Memory leak, potential crash.

**Fix**: Use lifecycle-aware collection with repeatOnLifecycle.

---

### 4. Event Collection Not Cancelled on Session Complete
**Reviewer**: Claude
**Location**: `AgentService.kt:95-101`

**Problem**: Event observation job never cancelled. New session launches NEW collector but old one may still be active.

**Impact**: Memory leak, potential double event handling.

**Fix**: Track and cancel collector job.

---

### 5. OverlayManager Emoji Rendering Issues
**Reviewer**: Gemini
**Location**: `OverlayManager.kt:171`

**Problem**: Uses text emojis ("⏸", "▶", "⏹") for buttons. Emoji rendering varies wildly across Android versions and OEMs.

**Impact**: May look like blobs or be invisible on some devices.

**Fix**: Use VectorDrawable or bitmap resources.

---

## Medium Issues (Should Fix)

### M1. Multiple Sessions Can Start Concurrently
**Reviewer**: Codex
**Location**: `AgentService.kt` - `runAgent()`

`runAgent()` doesn't prevent starting new session while one is active.

**Impact**: Overlapping loops, inconsistent overlay state.

**Fix**: Guard against concurrent sessions or stop previous before starting new.

---

### M2. Session Event Collection Lifecycle Issues
**Reviewer**: Codex
**Location**: `AgentService.kt` - `observeSession()`

Collector never completes if session flow doesn't close, leaking coroutines across runs.

**Fix**: Cancel collector when session ends.

---

### M3. Status Line Unbounded Growth / Recomposition
**Reviewer**: Gemini
**Location**: `MainActivity.kt:56`

```kotlin
statusLines = (statusLines + status).takeLast(MAX_STATUS_LINES)
```

Creates new List on every update, triggers full recomposition.

**Fix**: Use `mutableStateListOf` for efficient Compose updates.

---

### M4. Service Connection Reliability
**Reviewer**: Gemini
**Location**: `AgentService.kt`

If service crashes and restarts, overlay might get out of sync with session state.

**Fix**: Ensure robust state restoration on reconnect.

---

### M5. Auto-Start Delay is Arbitrary
**Reviewer**: Claude
**Location**: `MainActivity.kt:130-133`

Fixed 500ms delay doesn't guarantee Compose UI ready.

**Fix**: Use Compose LaunchedEffect.

---

### M6. StatusUtils.EMOJI_PATTERN Missing Emojis
**Reviewer**: Claude
**Location**: `StatusUtils.kt:15`

Pattern doesn't match ⏸️ or ▶️ used in Agent.kt.

**Fix**: Use Unicode-aware pattern.

---

### M7. Terminal Status Detection Fragile
**Reviewer**: Claude
**Location**: `StatusUtils.kt:86-101`

Detection relies on string matching. Any wording change breaks detection.

**Impact**: UI may not reset isRunning state correctly.

**Fix**: Use structured events rather than parsing status strings.

---

### M8. onServiceConnected() May Race with runAgent()
**Reviewer**: Claude
**Location**: `AgentService.kt:40-44` vs `AgentService.kt:152`

`runAgent()` can be called before service fully initialized. `overlayManager` may be null.

**Fix**: Guard against null or queue requests.

---

### M9. OverlayManager View References on Hide
**Reviewer**: Claude
**Location**: `OverlayManager.kt:200-208`

Views removed but pending `post {}` callbacks may still execute.

**Fix**: Cancel pending posts before removing views.

---

### M10. OverlayManager Colors Hardcoded
**Reviewer**: Claude
**Location**: `OverlayManager.kt:38-46`

Should match Compose theme colors.

**Fix**: Reference theme colors or share definitions.

---

### M11. Accessibility Service Exported
**Reviewer**: Codex
**Location**: `AndroidManifest.xml`

Service is exported without clear justification. While protected by BIND_ACCESSIBILITY_SERVICE, safer to set false if system still binds.

---

## Low-Risk Suggestions (Nice to Have)

| Issue | Reviewer | Location | Suggestion |
|-------|----------|----------|------------|
| Auto-scroll always jumps to bottom | Codex | `AgentScreen.kt` | Only auto-scroll when near bottom |
| StatusLog could virtualize | Claude | `AgentScreen.kt:337-386` | Use LazyColumn for long lists |
| OverlayManager dark theme | Claude | `OverlayManager.kt` | Detect system theme |
| AgentScreen Preview missing | Claude | `AgentScreen.kt` | Add @Preview composable |
| Toast could be Snackbar | Claude | `MainActivity.kt:158-164` | Better visibility |
| Theme hardcoding | Gemini | `ui/theme/Color.kt` | Support proper dark mode |
| StatusUtils duplication | Gemini | `StatusUtils.kt` | Good job extracting (positive note) |
| AgentScreen state hoisting | Claude | `AgentScreen.kt:38-44` | Use ViewModel |
| Unused XML layout | Codex | `activity_main.xml` | Remove after Compose migration |

---

## Open Questions

1. **Multiple activities**: Can multiple MainActivity instances exist (multi-window)? Would they fight over singleton callback?

2. **Service rebinding**: If accessibility service disabled and re-enabled, does session state persist?

3. **Background execution limits**: Does agent continue when app backgrounded? How are Android restrictions handled?
