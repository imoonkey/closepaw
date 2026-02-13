status: approved

# Qi Review Note:
1. 注意保证你的code的cleanliness， 不要有重复的代码，不要有冗余的代码，不要有难以理解的代码。
2. 完成后，把冗余的code清理掉。有必要的话，进行必要的code refactoring，来提高代码的可读性。
3. fiduciarily follow steps in sop/system_work.md and sop/code_work.md. 该create的todo items一定要创建，该执行的步骤一定要执行, flatten out your todo items。不要跳步。每个sop里提到的skill一定要在相应的步骤使用。
4. 一个小问题：记得协调glow overlay的状态和smart capsule的状态。现在有时候glow该出现的时候不出现，不该出现的时候出现，状态乱。

# Smart Capsule Round 3 — System Design

**UX Design**: `../ux_design_round3.md` (approved)
**Scope**: 3-row capsule, unified state, main app integration, VD viewer capsule, island expansion, [1][2][3] nav

---

## 0. Architecture Overview

```
AgentSession.events
        ↓
AgentService.handleEvent()
        ↓
ServiceOverlayController
   ├── CapsuleStateHolder (NEW — single state source)
   │      ├── mode: StateFlow<CapsuleMode>
   │      └── context: CapsuleContext
   ├── SmartCapsuleManager (overlay, View-based — updated for 3-row + nav)
   ├── StatusIslandManager (island — updated tap → expand)
   └── EdgeGlowManager (unchanged)
        
ChatViewModel ─── reads CapsuleStateHolder.mode via AgentService.instance
ChatScreen ─── SmartCapsuleCompose (NEW — replaces InputDock)

VirtualDisplayViewerActivity ─── triggers show/hide of overlay capsule
```

**Key change**: CapsuleStateHolder is the single source of truth. SmartCapsuleManager becomes a pure renderer. ChatViewModel no longer manages InputState.

---

## 1. Implementation Stages

| Stage | Scope | Files |
|-------|-------|-------|
| **Stage 6** | CapsuleStateHolder + CapsuleMode update | New: `CapsuleStateHolder.kt`, `CapsuleContext.kt`. Modified: `CapsuleMode.kt`, `ServiceOverlayController.kt`, `SmartCapsuleManager.kt` |
| **Stage 7** | 3-row overlay layout + [1][2][3] nav | Modified: `SmartCapsuleLayoutBuilder.kt`, `SmartCapsuleRenderer.kt`, `SmartCapsuleManager.kt`, `CapsuleViews` |
| **Stage 8** | SmartCapsuleCompose + Main App | New: `SmartCapsuleCompose.kt`. Modified: `ChatScreen.kt`, `ChatViewModel.kt`. Deprecated: `InputDock.kt`, `InputState` |
| **Stage 9** | VD Viewer + Status Island | Modified: `VirtualDisplayViewerActivity.kt`, `StatusIslandManager.kt`, `ServiceOverlayController.kt`, `AgentService.kt` |

---

## 2. Stage 6: CapsuleStateHolder + CapsuleMode Update

### 2.1 CapsuleMode Changes

Remove `SupplementInput`. Row 3 handles supplement input natively.

```kotlin
// File: ui/overlay/model/CapsuleMode.kt
sealed interface CapsuleMode {
    data class Running(val thought: String) : CapsuleMode
    data class TakeoverPending(val lastThought: String) : CapsuleMode
    data class Takeover(val lastThought: String) : CapsuleMode
    data class WaitingForInput(val question: String, val callId: String) : CapsuleMode
    data class WaitingForAction(val instruction: String, val callId: String) : CapsuleMode
    data class Done(val message: String) : CapsuleMode
    data class Error(val message: String) : CapsuleMode
    data object Hidden : CapsuleMode
}
```

Update `isExpanded()`: remove SupplementInput case.
Update `displayThought()`: remove SupplementInput case.

### 2.2 CapsuleContext

```kotlin
// File: ui/overlay/model/CapsuleContext.kt
enum class CapsuleContext {
    MAIN_APP,        // Context A — user is in the Android Agent app
    SCREEN_VIEWING,  // Context B — A11y overlay or VD viewer is showing
    BACKGROUND       // Context C — VD mode, user on own screen, island visible
}
```

### 2.3 CapsuleStateHolder

```kotlin
// File: ui/overlay/CapsuleStateHolder.kt
class CapsuleStateHolder {
    private val _mode = MutableStateFlow<CapsuleMode>(CapsuleMode.Hidden)
    val mode: StateFlow<CapsuleMode> = _mode.asStateFlow()

    private val _context = MutableStateFlow(CapsuleContext.MAIN_APP)
    val context: StateFlow<CapsuleContext> = _context.asStateFlow()

    var platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    var isAgentMidTurn: Boolean = false

    // ── State transitions ──

    fun onTaskStarted(taskId: String, input: String) {
        _mode.value = CapsuleMode.Running("${input.take(30)}...")
    }

    fun onThoughtUpdate(thought: String) {
        _mode.value = CapsuleMode.Running(thought)
    }

    fun onTakeoverRequested() {
        val lastThought = (_mode.value as? CapsuleMode.Running)?.thought ?: ""
        _mode.value = CapsuleMode.TakeoverPending(lastThought)
    }

    fun onTakeoverConfirmed() {
        val lastThought = when (val m = _mode.value) {
            is CapsuleMode.TakeoverPending -> m.lastThought
            is CapsuleMode.Running -> m.thought
            else -> ""
        }
        _mode.value = CapsuleMode.Takeover(lastThought)
    }

    fun onResumed() {
        isAgentMidTurn = false
        _mode.value = CapsuleMode.Running("思考中...")
    }

    fun onAskUser(type: AskUserType, message: String, callId: String) {
        _mode.value = when (type) {
            AskUserType.QUESTION -> CapsuleMode.WaitingForInput(question = message, callId = callId)
            AskUserType.ACTION -> CapsuleMode.WaitingForAction(instruction = message, callId = callId)
        }
    }

    fun onUserResponseSent(callId: String) {
        _mode.value = CapsuleMode.Running("处理答复中...")
    }

    fun onTaskCompleted(reason: CompletionReason) {
        _mode.value = when (reason) {
            CompletionReason.GOAL_ACHIEVED -> CapsuleMode.Done("已完成")
            CompletionReason.MAX_TURNS -> CapsuleMode.Done("已达到最大步数")
            CompletionReason.TASK_IMPOSSIBLE -> CapsuleMode.Done("无法完成任务")
            CompletionReason.USER_STOPPED -> CapsuleMode.Done("已停止")
            CompletionReason.ERROR -> CapsuleMode.Error("发生错误")
            CompletionReason.INTERRUPTED -> CapsuleMode.Done("已中断")
        }
    }

    fun onDoneAutoHide() {
        _mode.value = CapsuleMode.Hidden
    }

    fun onError(message: String) {
        _mode.value = CapsuleMode.Error(message.take(40))
    }

    fun onDismissError() {
        _mode.value = CapsuleMode.Hidden
    }

    // ── Context tracking ──

    fun setContext(ctx: CapsuleContext) {
        _context.value = ctx
    }
}
```

### 2.4 ServiceOverlayController Changes (Stage 6)

- Add `val stateHolder = CapsuleStateHolder()` field
- Replace all direct `capsuleManager.updateMode(...)` calls with `stateHolder.onXxx(...)` calls
- SmartCapsuleManager subscribes to `stateHolder.mode` and renders changes
- StatusIslandManager reads thought from `stateHolder.mode`

The overlay controller continues to decide WHICH renderer to activate based on `platformMode` + `context`. But the STATE is no longer computed inside the controller — it's delegated to `CapsuleStateHolder`.

### 2.5 SmartCapsuleManager Changes (Stage 6)

Remove internal `mode` field. Instead, subscribe to `CapsuleStateHolder.mode`:

```kotlin
class SmartCapsuleManager(private val service: AccessibilityService) {
    // Remove: private var mode: CapsuleMode = CapsuleMode.Hidden
    // Remove: private var previousMode: CapsuleMode = CapsuleMode.Hidden
    // Remove: all onTaskStarted(), onMessageDelta(), etc. event handlers
    // Remove: internal state computation

    // Keep: show(), hide(), dispose()
    // Keep: rendering logic, keyboard handling, debouncing
    // Add: fun renderMode(mode: CapsuleMode, previousMode: CapsuleMode)
    // This is the single entry point for visual updates
}
```

The manager becomes a pure View rendering component. State transitions happen in CapsuleStateHolder.

---

## 3. Stage 7: 3-Row Overlay Layout + Navigation

### 3.1 CapsuleViews Update

Add Row 3 (input) and navigation button views:

```kotlin
internal data class CapsuleViews(
    val container: ViewGroup,
    // Row 1
    val row1: ViewGroup,
    val statusDot: View,
    val thoughtText: TextView,
    // Divider between Row 1 and expanded body / Row 2
    val divider1: View,
    // Expanded body (WaitingFor* states)
    val expandedBody: TextView?,
    // Row 2
    val row2: ViewGroup,
    val primaryButton: ViewGroup,       // 接管/继续/完成
    val primaryIcon: TextView,
    val primaryText: TextView,
    val stopButton: ViewGroup,          // 停止/关闭
    val stopIcon: TextView,
    val stopText: TextView,
    // Navigation buttons (right side of Row 2)
    val navMinimize: View?,             // [1] ⊖
    val navApp: View?,                  // [2] 📱
    val navWatch: View?,                // [3] 👁
    // Divider between Row 2 and Row 3
    val divider2: View,
    // Row 3 (input)
    val row3: ViewGroup,
    val inputEditText: EditText,
    val inputButton: ViewGroup,         // [发送] or [补充]
    val inputButtonText: TextView,
)
```

Changes from current `CapsuleViews`:
- Remove: `supplementButton` (was Row 2 补充 — no longer exists)
- Remove: `supplementInputArea`, `supplementEditText`, `supplementSendButton` (replaced by Row 3)
- Add: `navMinimize`, `navApp`, `navWatch` (navigation icons)
- Add: `row3`, `inputEditText`, `inputButton`, `inputButtonText`
- Rename: `divider` → `divider1`, add `divider2`

### 3.2 SmartCapsuleLayoutBuilder Changes

The layout structure becomes:

```
Container (FrameLayout, side margins)
  └── Card (LinearLayout vertical, rounded corners)
        ├── Row 1 (LinearLayout horizontal) — dot + thought
        ├── Divider 1
        ├── Expanded Body (TextView, GONE by default)
        ├── Row 2 (LinearLayout horizontal) — controls left + nav right
        ├── Divider 2
        └── Row 3 (LinearLayout horizontal) — EditText + button
```

Row 2 internal layout:
```
Row 2 (LinearLayout horizontal)
  ├── [Primary button] (weight 0, wrap_content)
  ├── Spacer(8dp)
  ├── [Stop button] (weight 0, wrap_content)
  ├── Flexible spacer (weight 1)
  ├── [⊖] nav icon (28dp)
  ├── Spacer(4dp)
  ├── [📱] nav icon (28dp)
  ├── Spacer(4dp)
  └── [👁] nav icon (28dp)
```

Row 3 internal layout:
```
Row 3 (LinearLayout horizontal)
  ├── EditText (weight 1)
  ├── Spacer(8dp)
  └── [Action button] (pill shape, "发送" or "补充")
```

### 3.3 Navigation Button Builder

Small icon buttons, not pill-shaped. Simple clickable View with icon text:

```kotlin
private fun buildNavIcon(icon: String, contentDesc: String, onClick: () -> Unit): View {
    return TextView(context).apply {
        text = icon
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        val size = dp(28)
        layoutParams = LinearLayout.LayoutParams(size, size)
        setOnClickListener { onClick() }
        isClickable = true
        isFocusable = true
        contentDescription = contentDesc
        setTextColor(0xFF9CA3AF.toInt())  // Gray default
    }
}
```

### 3.4 SmartCapsuleRenderer Changes

Major updates for 3-row rendering:

**Remove**: `renderSupplementInput()` — no longer exists.

**For every render method**:
- Show/hide Row 3 based on state (visible in Running, Takeover, WaitingForInput; hidden in WaitingForAction, Done, Error)
- Configure Row 3 button text and behavior ("发送" vs "补充")
- Show/hide navigation buttons based on CapsuleContext

**New method**: `configureRow3(v, mode)` — sets placeholder, button text, and visibility.
**New method**: `configureNavButtons(v, context, platformMode)` — shows/hides [1][2][3] per rules.

### 3.5 SmartCapsuleManager Changes (Stage 7)

- Add navigation callbacks: `onMinimize`, `onOpenApp`, `onOpenViewer`
- Replace old `onSupplement` with Row 3 supplement handling
- Row 3 input setup on first tap (focus management)
- `onRow3Submit(text)` — routes to send (idle), supplement (active), or answer (waiting)

**Row 3 input flow in overlay**:
1. Row 3 EditText has `onFocusChangeListener`
2. When EditText gains focus → `setOverlayFocusable(true)`, show keyboard
3. When user taps button → process input, clear field, `setOverlayFocusable(false)`, hide keyboard

---

## 4. Stage 8: SmartCapsuleCompose + Main App

### 4.1 SmartCapsuleCompose

New file: `ui/capsule/SmartCapsuleCompose.kt`

```kotlin
@Composable
fun SmartCapsuleCompose(
    mode: CapsuleMode,
    platformMode: PlatformMode,
    context: CapsuleContext,
    onSend: (String) -> Unit,           // Row 3 [发送] (idle mode)
    onSupplement: (String) -> Unit,     // Row 3 [补充] (active mode)
    onTakeover: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onUserResponse: (String, String) -> Unit,  // (callId, response)
    onDismissError: () -> Unit,
    onNavigate: (NavAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    val isTaskActive = mode !is CapsuleMode.Hidden

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            // Row 1 + Row 2: only when task is active
            if (isTaskActive) {
                CapsuleRow1(mode = mode, onOpenApp = { onNavigate(NavAction.OPEN_APP) })
                HorizontalDivider()
                // Expanded body for WaitingFor*
                ExpandedBody(mode = mode)
                CapsuleRow2(
                    mode = mode, platformMode = platformMode, context = context,
                    onTakeover = onTakeover, onResume = onResume, onStop = onStop,
                    onDone = { /* handle WaitingForAction done */ },
                    onDismissError = onDismissError,
                    onNavigate = onNavigate
                )
                HorizontalDivider()
            }
            // Row 3: always visible (unless WaitingForAction, Done, Error)
            if (shouldShowRow3(mode)) {
                CapsuleRow3(
                    mode = mode,
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onSubmit = {
                        val text = inputText.trim()
                        if (text.isEmpty()) return@CapsuleRow3
                        when (mode) {
                            is CapsuleMode.Hidden -> { onSend(text); inputText = "" }
                            is CapsuleMode.WaitingForInput -> {
                                onUserResponse(mode.callId, text); inputText = ""
                            }
                            else -> { onSupplement(text); inputText = "" }
                        }
                    }
                )
            }
        }
    }
}

enum class NavAction { MINIMIZE, OPEN_APP, OPEN_VIEWER }
```

### 4.2 Row Composables

Each row is a small private composable:

**CapsuleRow1**: Status dot + thought text. Tappable → onOpenApp.
**CapsuleRow2**: Control buttons (left) + nav icons (right). Per-state button config.
**CapsuleRow3**: OutlinedTextField + action button. Placeholder and button text change per mode.
**ExpandedBody**: Question/instruction text for WaitingFor* states.

### 4.3 ChatScreen Changes

```kotlin
// Before:
bottomBar = {
    InputDock(state = uiState.inputState, onSend = ..., onStop = ...)
}

// After:
bottomBar = {
    val capsuleMode by capsuleStateHolder.mode.collectAsStateWithLifecycle()
    SmartCapsuleCompose(
        mode = capsuleMode,
        platformMode = ...,
        context = CapsuleContext.MAIN_APP,
        onSend = viewModel::sendMessage,
        onSupplement = viewModel::sendSupplement,
        onTakeover = viewModel::requestTakeover,
        onResume = viewModel::requestResume,
        onStop = viewModel::stopTask,
        onUserResponse = viewModel::sendUserResponse,
        onDismissError = { /* handle */ },
        onNavigate = { action -> /* handle nav */ }
    )
}
```

### 4.4 ChatViewModel Changes

- Remove `InputState` from `ChatUiState`
- Remove `InputState` enum entirely
- Add methods: `sendSupplement(text)`, `requestTakeover()`, `requestResume()`, `sendUserResponse(callId, response)`
- These methods delegate to `AgentSession.submit(Op.Xxx)`
- The `CapsuleStateHolder` is accessed via `AgentService.instance?.overlayController?.stateHolder`

### 4.5 Deprecate InputDock

`InputDock.kt` is no longer used. Delete or mark deprecated. The `InputState` enum is removed from `ChatMessage.kt`.

---

## 5. Stage 9: VD Viewer + Status Island

### 5.1 VirtualDisplayViewerActivity Changes

**Remove**:
- `ViewerCapsule` composable
- `SwipeUpDismissOverlay` composable
- "Swipe up to exit" hint

**Keep**:
- `LivePreviewSurface` (SurfaceView for VD output)
- Full-screen black background

**Add**:
- On `onStart()`: notify service to show SmartCapsule overlay + set context to SCREEN_VIEWING
- On `onStop()`: notify service to hide capsule overlay + set context to BACKGROUND

The viewer becomes a pure SurfaceView with no built-in controls. The SmartCapsule overlay (managed by SmartCapsuleManager via ServiceOverlayController) provides all UI.

```kotlin
// VirtualDisplayViewerActivity changes:
override fun onStart() {
    super.onStart()
    surfaceView?.let { AgentService.instance?.notifyViewerVisible(it) }
    AgentService.instance?.onViewerOpened()  // NEW
}

override fun onStop() {
    super.onStop()
    AgentService.instance?.notifyViewerHidden()
    AgentService.instance?.onViewerClosed()  // NEW
}
```

### 5.2 AgentService.onViewerOpened/Closed

```kotlin
fun onViewerOpened() {
    overlayController?.let {
        it.stateHolder.setContext(CapsuleContext.SCREEN_VIEWING)
        it.showCapsuleOverlay()  // Show SmartCapsule on real screen
        it.hideIsland()          // Hide island (viewer has capsule)
    }
}

fun onViewerClosed() {
    overlayController?.let {
        it.stateHolder.setContext(CapsuleContext.BACKGROUND)
        it.hideCapsuleOverlay()  // Hide SmartCapsule
        it.showIsland()          // Island reappears
    }
}
```

### 5.3 StatusIslandManager Changes

**Change tap behavior**: Instead of opening VD viewer directly, expand SmartCapsule overlay.

```kotlin
// Before:
setOnClickListener { onTap() }  // onTap opens VDViewerActivity

// After:
setOnClickListener { onExpandCapsule() }  // Expand full capsule overlay
```

New callback: `onExpandCapsule: () -> Unit`

**Remove**: Long-press inline controls (replaced by expanded capsule).
**Remove**: `buildInlineControls()`, `toggleInlineControls()`, `controlsContainer`, `pauseIconText`.

The StatusIsland becomes purely a compact status display. All controls are in the expanded SmartCapsule.

### 5.4 ServiceOverlayController — VD Mode Changes

New methods for capsule overlay management:

```kotlin
fun showCapsuleOverlay() {
    capsuleManager.show()
    // Render current state
    val mode = stateHolder.mode.value
    capsuleManager.renderMode(mode, CapsuleMode.Hidden)
}

fun hideCapsuleOverlay() {
    capsuleManager.hide()
}

fun showIsland() {
    statusIslandManager?.show()
}

fun hideIsland() {
    statusIslandManager?.hide()
}
```

VD mode event handlers updated:
- `onTaskStarted`: show island, update status
- `onThoughtUpdate`: update island status, update capsule if showing
- `onAskUser`: show capsule overlay (auto-expand for ask_user)
- `onSessionTakeover`: update both island and capsule
- `onSessionResumed`: update both, hide capsule if expanded from island

### 5.5 Navigation Action Handling

The SmartCapsuleManager needs navigation callbacks that ServiceOverlayController provides:

```kotlin
// ServiceOverlayController provides:
capsuleManager.onMinimize = {
    hideCapsuleOverlay()
    showIsland()
}
capsuleManager.onOpenApp = {
    hideCapsuleOverlay()
    onOpenApp()  // Launches MainActivity
}
capsuleManager.onOpenViewer = {
    hideCapsuleOverlay()
    // Launch VDViewerActivity via intent
}
```

For in-app navigation (SmartCapsuleCompose), the callbacks go through ChatScreen → Activity:
- MINIMIZE: finish() the activity (go home, island shows)
- OPEN_VIEWER: startActivity(VDViewerActivity intent)

---

## 6. Data Flow Summary

### 6.1 Event Flow (Task Running, A11y Mode)

```
AgentEvent.ThoughtUpdate("打开淘宝")
  → ServiceOverlayController.onThoughtUpdate()
  → stateHolder.onThoughtUpdate("打开淘宝")
  → stateHolder._mode.value = CapsuleMode.Running("打开淘宝")
  → [StateFlow emission]
  → SmartCapsuleManager observes → renders overlay
  → SmartCapsuleCompose observes → renders in-app (if visible)
```

### 6.2 Supplement Flow (Main App)

```
User types in Row 3 → taps [补充]
  → SmartCapsuleCompose.onSubmit() → onSupplement("黑色的")
  → ChatViewModel.sendSupplement("黑色的")
  → AgentSession.submit(Op.Supplement("黑色的"))
  → Agent processes supplement
  → AgentEvent.SupplementReceived emits
  → CapsuleStateHolder: no mode change (stays Running)
  → SmartCapsuleCompose: show "✓ 已收到" flash
```

### 6.3 Context Switch (App → VD Viewer)

```
User taps [3] Watch (in-app)
  → NavAction.OPEN_VIEWER
  → MainActivity starts VDViewerActivity
  → VDViewerActivity.onStart() → AgentService.onViewerOpened()
  → stateHolder.setContext(SCREEN_VIEWING)
  → ServiceOverlayController: show capsule overlay, hide island
  → SmartCapsuleManager.show() + renderMode(current mode)
  [Meanwhile, MainActivity goes to background]
  → handleWindowStateChanged: app in foreground → false (but VD mode, no-op)
```

---

## 7. File Change Summary

### New files
| File | Purpose |
|------|---------|
| `ui/overlay/CapsuleStateHolder.kt` | Unified state management |
| `ui/overlay/model/CapsuleContext.kt` | Context enum |
| `ui/capsule/SmartCapsuleCompose.kt` | Compose capsule widget |

### Modified files
| File | Changes |
|------|---------|
| `ui/overlay/model/CapsuleMode.kt` | Remove SupplementInput |
| `ui/overlay/SmartCapsuleLayoutBuilder.kt` | 3-row layout, nav buttons |
| `ui/overlay/SmartCapsuleRenderer.kt` | 3-row rendering, remove supplement render |
| `ui/overlay/SmartCapsuleManager.kt` | Pure renderer, Row 3 handling, nav callbacks |
| `ui/overlay/StatusIslandManager.kt` | Tap → expand capsule, remove inline controls |
| `app/ServiceOverlayController.kt` | CapsuleStateHolder integration, context management |
| `app/AgentService.kt` | Viewer opened/closed methods, stateHolder access |
| `ui/chat/ChatScreen.kt` | SmartCapsuleCompose replaces InputDock |
| `ui/chat/ChatViewModel.kt` | Remove InputState, add supplement/takeover methods |
| `ui/chat/model/ChatMessage.kt` | Remove InputState enum |
| `ui/viewer/VirtualDisplayViewerActivity.kt` | Remove ViewerCapsule, add viewer lifecycle |

### Deprecated/deleted
| File | Reason |
|------|--------|
| `ui/chat/components/InputDock.kt` | Replaced by SmartCapsuleCompose Row 3 |
