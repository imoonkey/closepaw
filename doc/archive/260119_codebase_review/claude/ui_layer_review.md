# UI Layer Review

> **Module**: `MainActivity.kt`, `AgentService.kt`, `service/`, `ui/`, `util/`
> **Reviewer**: Claude
> **Date**: January 19, 2026

## Summary

The UI Layer provides user interaction surfaces:
- `MainActivity`: Compose-based main UI with API key input and status display
- `AgentService`: AccessibilityService that hosts the agent session
- `OverlayManager`: Floating control bar for agent control
- `AgentScreen`: Compose UI components
- `StatusUtils`: Shared status message processing

---

## High-Risk Issues (Must Fix)

### H1. AgentService.instance is Racey Global Singleton
**Location**: `AgentService.kt:28-30`

**Problem**: `instance` is a mutable global accessed without synchronization:
```kotlin
companion object {
    @Volatile
    var instance: AgentService? = null
        private set

    var statusCallback: ((String) -> Unit)? = null  // Not even volatile!
}
```

While `instance` has `@Volatile`, `statusCallback` doesn't, and both are accessed from different threads:
- `instance` is set in `onServiceConnected()` (system callback, unknown thread)
- `instance` is read in `MainActivity.startAgent()` (main thread)
- `statusCallback` is set in `MainActivity.onCreate()` (main thread)
- `statusCallback` is invoked from coroutines (potentially other threads)

**Impact**: Potential null visibility issues, stale callback references.

**Fix**: Use proper synchronization or an event bus:
```kotlin
companion object {
    @Volatile
    private var _instance: AgentService? = null
    
    val instance: AgentService?
        get() = _instance
    
    // Use Flow for status instead of callback
    private val _statusFlow = MutableSharedFlow<String>(replay = 1)
    val statusFlow: SharedFlow<String> = _statusFlow.asSharedFlow()
    
    internal fun emitStatus(status: String) {
        // Called from coroutine context
        _statusFlow.tryEmit(status)
    }
}
```

---

### H2. Event Collection Cancellation On SessionCompleted
**Location**: `AgentService.kt:95-101`

**Problem**: Event observation continues even after session completes:
```kotlin
private fun observeSession(agentSession: AgentSession) {
    scope.launch {
        agentSession.events.collect { event ->
            handleEvent(event)
        }
    }
}
```

The collection job is never cancelled. When a new session starts, a NEW collector is launched but the old one may still be active (on the closed channel).

**Impact**: Memory leak (hanging coroutines), potential double event handling.

**Fix**: Track and cancel the collector job:
```kotlin
private var eventCollectorJob: Job? = null

private fun observeSession(agentSession: AgentSession) {
    eventCollectorJob?.cancel()  // Cancel previous collector
    
    eventCollectorJob = scope.launch {
        agentSession.events.collect { event ->
            handleEvent(event)
        }
    }
}

override fun onDestroy() {
    eventCollectorJob?.cancel()
    // ...
}
```

---

### H3. MainActivity State Leaks via statusCallback
**Location**: `MainActivity.kt:53-63`, `MainActivity.kt:109-111`

**Problem**: `statusCallback` captures `this` (MainActivity) reference:
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    // ...
    AgentService.statusCallback = { status ->
        runOnUiThread {
            statusLines = (statusLines + status).takeLast(MAX_STATUS_LINES)
            // 'this' reference is captured
        }
    }
}

override fun onDestroy() {
    AgentService.statusCallback = null  // Cleared, but race with ongoing callbacks
}
```

If a callback is in-flight when `onDestroy()` runs, `runOnUiThread` will be called on a destroyed activity.

**Impact**: Memory leak, potential crash on destroyed activity.

**Fix**: Use lifecycle-aware collection:
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Use lifecycle-aware collection
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            AgentService.statusFlow.collect { status ->
                statusLines = (statusLines + status).takeLast(MAX_STATUS_LINES)
                // ...
            }
        }
    }
}
```

---

### H4. OverlayManager View References Not Nulled on Hide
**Location**: `OverlayManager.kt:200-208`

**Problem**: The `hide()` function nulls some references but may leave view references:
```kotlin
fun hide() {
    if (overlayView != null) {
        windowManager.removeView(overlayView)
        overlayView = null
        statusText = null
        statusDot = null
        pauseButton = null
        pauseIconText = null
    }
}
```

The views removed from WindowManager but any pending `post {}` callbacks may still execute on removed views.

**Impact**: Potential crashes or undefined behavior.

**Fix**: Cancel pending posts:
```kotlin
fun hide() {
    val view = overlayView ?: return
    
    // Remove all pending callbacks
    statusText?.removeCallbacks(null)
    pauseButton?.removeCallbacks(null)
    
    windowManager.removeView(view)
    
    overlayView = null
    statusText = null
    statusDot = null
    pauseButton = null
    pauseIconText = null
}
```

---

### H5. API Key Stored in External Storage Insecurely
**Location**: `MainActivity.kt:136-149`

**Problem**: API key is loaded from external storage without encryption:
```kotlin
private fun loadApiKeyFromFile() {
    try {
        val file = File(Environment.getExternalStorageDirectory(), "api_key.txt")
        if (file.exists()) {
            val key = file.readText().trim()
            if (key.isNotBlank() && key.startsWith("sk-")) {
                apiKey = key
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not load API key from file: ${e.message}")
    }
}
```

Issues:
1. External storage is world-readable on many devices
2. Plain text storage of sensitive API key
3. `Environment.getExternalStorageDirectory()` is deprecated

**Impact**: API key exposed to other apps or malicious actors.

**Fix**: Use encrypted shared preferences or Android Keystore:
```kotlin
private fun loadApiKey() {
    val encryptedPrefs = EncryptedSharedPreferences.create(
        "secure_prefs",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        this,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    apiKey = encryptedPrefs.getString("api_key", "") ?: ""
}

private fun saveApiKey(key: String) {
    encryptedPrefs.edit().putString("api_key", key).apply()
}
```

---

## Medium Issues (Should Fix)

### M1. Auto-Start Delay is Arbitrary
**Location**: `MainActivity.kt:130-133`

**Problem**: Auto-start uses a fixed 500ms delay:
```kotlin
if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
    Log.d(TAG, "Auto-start requested")
    window.decorView.postDelayed({ startAgent() }, 500)  // Magic number
}
```

This doesn't guarantee Compose UI is ready. Could be too short or too long.

**Impact**: Race condition with UI initialization.

**Fix**: Use Compose's lifecycle:
```kotlin
if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
    // Let Compose initialize first
    setContent {
        LaunchedEffect(Unit) {
            startAgent()
        }
        // ... rest of UI
    }
}
```

---

### M2. StatusUtils.EMOJI_PATTERN Missing Emojis
**Location**: `StatusUtils.kt:15`

**Problem**: The emoji pattern doesn't match all emojis used in the codebase:
```kotlin
private val EMOJI_PATTERN = Regex("[✅❌⚠️🧠🔧💡👀🚀🛑✓]")
```

Missing emojis used in Agent.kt:
- `⏸️` (paused)
- `▶️` (resuming)

**Impact**: These emojis won't be cleaned from status text.

**Fix**:
```kotlin
private val EMOJI_PATTERN = Regex("[✅❌⚠️🧠🔧💡👀🚀🛑✓⏸️▶️]")
```

Or better, use a Unicode-aware pattern:
```kotlin
private val EMOJI_PATTERN = Regex("[\\p{So}\\p{Sk}]|[\\u2600-\\u26FF]|[✓✗]")
```

---

### M3. AgentScreen State Hoisting Incomplete
**Location**: `AgentScreen.kt:38-44` vs `MainActivity.kt:41-46`

**Problem**: State is in MainActivity, not hoisted properly:
```kotlin
// MainActivity has:
private var apiKey by mutableStateOf("")
private var goal by mutableStateOf("")
// ...

// AgentScreen receives as data class:
data class AgentUiState(
    val apiKey: String = "",
    val goal: String = "",
    // ...
)
```

This creates unnecessary recomposition - any state change recreates the entire `AgentUiState` object.

**Impact**: Performance overhead, though minor for this simple UI.

**Fix**: Pass individual state holders or use a ViewModel:
```kotlin
// Option 1: Individual state
@Composable
fun AgentScreen(
    apiKey: State<String>,
    goal: State<String>,
    // ...
)

// Option 2: ViewModel (recommended for larger apps)
class AgentViewModel : ViewModel() {
    var apiKey by mutableStateOf("")
    var goal by mutableStateOf("")
    // ...
}
```

---

### M4. OverlayManager Colors Hardcoded
**Location**: `OverlayManager.kt:38-46`

**Problem**: Colors are hardcoded instead of using theme:
```kotlin
private val colorBackground = 0xFFFFFFFF.toInt()
private val colorBorder = 0xFFE9E9E7.toInt()
// ...
```

These should match the Compose theme colors defined in `Color.kt`.

**Impact**: Visual inconsistency if theme colors change.

**Fix**: Reference theme colors or share color definitions:
```kotlin
// In a shared colors module
object AgentColors {
    val Background = Color(0xFFFFFFFF)
    val Border = Color(0xFFE9E9E7)
    // ...
}

// OverlayManager uses:
private val colorBackground = AgentColors.Background.toArgb()
```

---

### M5. Terminal Status Detection Fragile
**Location**: `StatusUtils.kt:86-101`

**Problem**: Terminal status detection relies on string matching:
```kotlin
fun isTerminalStatus(status: String): Boolean {
    if (status.contains("retrying", ignoreCase = true)) {
        return false
    }
    
    val type = getStatusType(status)
    
    return type == StatusType.SUCCESS || 
           type == StatusType.ERROR ||
           status.contains("stopped") ||
           status.contains("completed") ||
           // ...
}
```

Any change to status message wording breaks this detection.

**Impact**: UI may not reset `isRunning` state correctly.

**Fix**: Use structured events rather than parsing status strings:
```kotlin
// In AgentEvent, add a completion flag
data class StatusUpdate(
    // ...
    val isTerminal: Boolean = false
)

// Or use a separate terminal event
data class SessionTerminated(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val reason: String
) : AgentEvent
```

---

### M6. onServiceConnected() May Race with runAgent()
**Location**: `AgentService.kt:40-44` vs `AgentService.kt:152`

**Problem**: `runAgent()` can be called before service is fully initialized:
```kotlin
override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    // ...
    overlayManager = OverlayManager(...)  // Late initialization
}

fun runAgent(goal: String, apiKey: String, maxSteps: Int = 20) {
    // ...
    overlayManager?.show()  // May be null if called too early
}
```

**Impact**: Overlay won't show if runAgent called immediately after service starts.

**Fix**: Ensure proper initialization order or guard against null:
```kotlin
fun runAgent(goal: String, apiKey: String, maxSteps: Int = 20) {
    val overlay = overlayManager
    if (overlay == null) {
        Log.w(TAG, "OverlayManager not initialized yet")
        // Retry after short delay or queue the request
        return
    }
    
    overlay.show()
    // ...
}
```

---

## Low-Risk Suggestions (Nice to Have)

### L1. StatusLog Could Virtualize Long Lists
**Location**: `AgentScreen.kt:337-386`

For very long status logs, consider using `LazyColumn`:
```kotlin
LazyColumn(
    modifier = Modifier.fillMaxSize(),
    state = scrollState
) {
    itemsIndexed(statusLines) { index, line ->
        StatusLine(line, isLatest = index == statusLines.lastIndex)
    }
}
```

---

### L2. OverlayManager Could Support Dark Theme
**Location**: `OverlayManager.kt`

The overlay uses hardcoded light colors. Consider detecting system theme:
```kotlin
val isDarkTheme = (context.resources.configuration.uiMode and 
    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    
val colorBackground = if (isDarkTheme) 0xFF2F3437.toInt() else 0xFFFFFFFF.toInt()
```

---

### L3. AgentScreen Preview Missing
**Location**: `AgentScreen.kt`

Add `@Preview` composable for development:
```kotlin
@Preview(showBackground = true)
@Composable
private fun AgentScreenPreview() {
    AgentTheme {
        AgentScreen(
            state = AgentUiState(
                apiKey = "sk-test...",
                goal = "Open Settings",
                statusLines = listOf("Ready", "Starting agent..."),
                isServiceEnabled = true,
                isRunning = false
            ),
            onApiKeyChange = {},
            onGoalChange = {},
            onStartClick = {},
            onAccessibilityClick = {}
        )
    }
}
```

---

### L4. Error Toast in startAgent() Could Be Snackbar
**Location**: `MainActivity.kt:158-164`

Toast for overlay permission is hard to see. Consider Snackbar:
```kotlin
if (!Settings.canDrawOverlays(this)) {
    // Show snackbar instead of toast
    // Would require ScaffoldState in Compose
}
```

---

## Questions

1. **Multiple activities**: Can multiple MainActivity instances exist (e.g., multi-window)? Would they fight over the singleton callback?

2. **Service rebinding**: If the accessibility service is disabled and re-enabled, does the session state persist or is it lost?

3. **Background execution limits**: Android restricts background execution. Does the agent continue working when the app is backgrounded? How are the restrictions handled?
