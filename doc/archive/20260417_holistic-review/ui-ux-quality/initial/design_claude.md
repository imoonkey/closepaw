# UI/UX Code Quality Review

**Scope**: 55 files in `app/src/main/kotlin/com/moonkey/androidagent/ui/`
**Date**: 2026-04-08

---

## Perspective A: Compose Best Practices & Performance

### A1. Recomposition & Stability

**A1.1 MessageBubble creates SimpleDateFormat on every recomposition** [MEDIUM]
- File: `chat/components/MessageBubble.kt:188`
- `formatTime()` allocates `SimpleDateFormat` each call. During fast streaming, `AgentBubble` recomposes frequently, allocating a new formatter per frame.
- Impact: GC pressure during streaming. `SimpleDateFormat` is also not thread-safe.
- Fix: Use the `TimeUtils.DateTimeFormatter` approach already in `session/TimeUtils.kt`, or move to a top-level val.

**A1.2 ChatScreen reads `viewModel.messages` list directly in composition** [LOW]
- File: `chat/ChatScreen.kt:61`
- `val messages = viewModel.messages` reads a `SnapshotStateList` through a `List` getter. This works but means Compose tracks the entire backing list. Any mutation (even to a single item's property via `messages[index] = ...` in `ChatEventReducer`) triggers full recomposition of the `MessageList` composable.
- Impact: Currently acceptable because `items(key = { it.id })` limits per-item recomposition. But the `List<ChatMessage>` getter returning the snapshot list is fragile for future refactors.

**A1.3 ActionCard uses `remember { mutableStateOf(false) }` for expand state** [OK]
- File: `chat/components/ActionCard.kt:63`
- Correctly scoped. If the card leaves composition (scrolled off), expand state resets -- acceptable UX for chat.

**A1.4 SmartCapsuleSurface has correct `remember` for renderSpec and navSpec** [OK]
- File: `capsule/surface/SmartCapsuleSurface.kt:69-77`
- Keys are correct: `mode, isStopPending, resolvedPreviousMode, transientThought`. Spec recomputation only happens on actual state change.

**A1.5 ActionStatusIcon spins a custom rotation on CircularProgressIndicator** [LOW]
- File: `chat/components/ActionCard.kt:170-187`
- `CircularProgressIndicator` already animates internally. The extra `infiniteTransition.animateFloat` for rotation is redundant -- the CPI is being rotated while also rotating itself internally.
- Impact: Double rotation animation. Visually might look fine but wastes composition cycles. Either use indeterminate CPI without external rotation, or use a static ring with the external rotation.

**A1.6 ChatScreen fallback StateFlows for capsule mode** [OK-ISH]
- File: `chat/ChatScreen.kt:66-71`
- Creating `remember { MutableStateFlow(...) }` as fallback when `stateHolder` is null is a valid pattern. The `remember` ensures stability. Acceptable.

### A2. Side Effects

**A2.1 Auto-scroll LaunchedEffect keyed on `messages.size`** [MEDIUM]
- File: `chat/ChatScreen.kt:192-196`
- `LaunchedEffect(messages.size)` fires on every message count change, which is correct. However, during streaming, content blocks within a single message update frequently but the message count stays the same. Users reading earlier messages will not be force-scrolled during streaming -- good. But when a new action card adds a new item, the scroll jumps.
- Potential issue: `animateScrollToItem` is called even if user has scrolled up to read history. Should add a "is user at bottom" check to avoid hijacking scroll position.

**A2.2 CapsuleOverlayHost starts observers in `show()` but cancels in `hide()`** [OK]
- File: `overlay/compose/CapsuleOverlayHost.kt:193-195, 199-204`
- Focus observer and touchability observer are properly scoped to show/hide lifecycle. Job cleanup is correct.

**A2.3 SettingsSheet LaunchedEffect for drawer session loading** [OK]
- File: `chat/ChatScreen.kt:77-81`
- `LaunchedEffect(drawerState.isOpen)` fires when drawer opens, triggers session load. Clean and correct.

**A2.4 CapsuleRow3 auto-focus with LaunchedEffect** [OK]
- File: `capsule/surface/SmartCapsuleSurfaceParts.kt:184-189`
- Keyed on `autoFocusInput, inputEnabled`. Correctly requests focus only when both conditions are true.

### A3. State Hoisting & Architecture

**A3.1 ChatEventReducer uses `synchronized(stateLock)` for Compose state** [MEDIUM]
- File: `chat/ChatEventReducer.kt:38`
- Synchronizing around `SnapshotStateList` mutations is correct for thread safety, but the `synchronized` block should be minimal. Currently the entire `handle()` dispatches inside the lock, which is fine since individual handlers are fast.
- However, the `stateLock` object is shared between `ChatEventReducer`, `ChatViewModel`, and `ChatSessionHistoryController` via constructor injection. This coupling is tight. A single mutex or dispatching all mutations to Main would be cleaner.

**A3.2 CapsuleStateHolder is a clean single-source-of-truth** [GOOD]
- File: `overlay/CapsuleStateHolder.kt`
- All state transitions are guarded. The `setMode()` private function tracks `previousMode`. Auto-hide is properly scoped. This is well-designed.

**A3.3 SettingsSheet parameter count** [MEDIUM]
- File: `settings/SettingsSheet.kt:30-67`
- 38 parameters. This is at the boundary of maintainability. The function exists as a pass-through from Activity/BottomSheet to child pages. Each page selectively uses a subset.
- Impact: Any new setting requires editing this signature plus every call site. Consider a `SettingsState` data class to bundle related groups.

**A3.4 SmartCapsuleSurface input state is local** [OK]
- File: `capsule/surface/SmartCapsuleSurface.kt:65`
- `inputText` is managed locally via `remember { mutableStateOf("") }`. This is correct -- the input is ephemeral and doesn't need to survive recomposition or be hoisted to ViewModel.

### A4. Overlay System Architecture

**A4.1 OverlayComposeHost is a clean utility** [GOOD]
- File: `overlay/compose/OverlayComposeHost.kt`
- 82 lines. Single responsibility: manage a ComposeView in a WindowManager overlay. Used by Glow, Island, Capsule, and Visualizer hosts. Correct `ViewCompositionStrategy.DisposeOnDetachedFromWindow`.

**A4.2 CapsuleOverlayHost callback soup** [MEDIUM]
- File: `overlay/compose/CapsuleOverlayHost.kt:52-62`
- 12 nullable callback properties set via assignment (`onTakeover`, `onResume`, etc.). This is fragile -- any missed assignment is a silent no-op.
- Impact: Debugging missed wiring is hard. Consider a sealed callback interface or a single lambda dispatcher.

**A4.3 ServiceLifecycleOwner is minimal and correct** [GOOD]
- File: `overlay/compose/ServiceLifecycleOwner.kt`
- 41 lines. Handles the lifecycle bridge for Compose in AccessibilityService. Guards against double init. Correct lifecycle event sequence.

**A4.4 VisualizerOverlayHost owns its own CoroutineScope** [OK]
- File: `overlay/compose/VisualizerOverlayHost.kt:44`
- `CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())` is correct for independent lifecycle. Properly cancelled in `dispose()`.

### A5. Theme System

**A5.1 Color.kt has extensive duplication** [MEDIUM]
- File: `theme/Color.kt`
- 156 lines. Many colors are defined twice: once in the "general" section and again in the "Chat UI Colors" section with identical values (e.g., `Primary = 0xFF3B3B3B` and `ChatPrimary = 0xFF3B3B3B`, `Accent = 0xFF10A37F` and `ChatSecondary = 0xFF10A37F`).
- Impact: Two parallel color vocabularies. Changes to the design require updating both. The "general" set (Background, Surface, Primary, etc.) appears unused -- everything references the `Chat*` variants.

**A5.2 WindowInsets utility is clean but arguably unnecessary** [LOW]
- File: `theme/WindowInsets.kt`
- `AppWindowInsets` wraps `WindowInsets.systemBars` etc. with no transformation. It adds a layer of indirection for documentation purposes only.
- Impact: Low. The documentation is useful, but the wrapper itself adds no logic.

**A5.3 ChatTheme status bar configuration** [OK]
- File: `theme/Theme.kt:140-158`
- Correctly handles API < 35 deprecation. `SideEffect` is the right choice for imperative system bar configuration.

**A5.4 StatusIslandCompose hardcodes colors** [LOW]
- File: `overlay/compose/StatusIslandCompose.kt:35-36, 55`
- `color = Color.White` and `color = Color(0xFF171717)` bypass the theme. This is intentional for overlay (which renders outside the app's theme), but fragile if dark mode support extends to overlays.

---

## Perspective B: UX Design Quality

### B1. Chat Experience

**B1.1 Auto-scroll hijacks user's position** [HIGH]
- Same as A2.1. If a user scrolls up to re-read an earlier message during a streaming agent response, the next message count change snaps them back to bottom. This is a known anti-pattern in chat UIs.
- Impact: Frustrating UX for users trying to review conversation history during active tasks.
- Fix: Track whether user is at/near bottom. Only auto-scroll if they are.

**B1.2 Empty state suggestions are hardcoded** [LOW]
- File: `chat/components/EmptyState.kt:79-93`
- Three static suggestions. These are fine for now but feel generic. No dynamic suggestions based on app state or recent usage.
- Impact: Low. Static suggestions are standard for v1.

**B1.3 Agent message timestamp only shows when complete** [OK]
- File: `chat/components/MessageBubble.kt:174`
- Correct UX: showing a timestamp on a streaming message would be confusing since the time isn't final.

**B1.4 Thinking indicator appears inside the bubble surface** [OK]
- File: `chat/components/MessageBubble.kt:124-126`
- ThinkingIndicator renders inside the agent bubble's Surface, giving visual continuity.

### B2. Smart Capsule UX

**B2.1 CapsuleRenderSpec is the right abstraction** [GOOD]
- The `CapsuleMode -> CapsuleRenderSpec` mapping centralizes all visual decisions. Both overlay and in-app renderers consume the same spec. This eliminates visual drift.

**B2.2 Supplement input disabled during A11y execution** [GOOD]
- File: `capsule/surface/SmartCapsuleSurface.kt:83-88`
- Input is disabled when `Running + ACCESSIBILITY` mode, preventing the user from typing while the agent is performing gestures (which would interfere with the input method service).

**B2.3 WaitingForApproval shows Allow/Session/Always/Deny** [GOOD]
- File: `capsule/surface/SmartCapsuleSurfaceParts.kt:76-113`
- Four distinct approval scopes. "Session" and "Always" only show when `packageName != null`. Clean conditional rendering.

**B2.4 Stop button disabled state ("Stopping...")** [GOOD]
- File: `overlay/model/CapsuleRenderSpec.kt:162-164`
- `isStopPending` drives a disabled "Stopping..." label. Prevents double-tap. Clean UX feedback.

**B2.5 Done mode auto-hides after 3s** [OK]
- File: `overlay/CapsuleStateHolder.kt:297-303`
- 3 seconds is reasonable. The auto-hide transitions to `Hidden`, which is correct.

**B2.6 Supplement confirmation flash ("Received, will apply next step")** [GOOD]
- File: `overlay/compose/CapsuleOverlayHost.kt:241-254`
- Transient thought with differentiated message based on `isAgentMidTurn`. Good feedback design.

### B3. Settings UX

**B3.1 Settings navigation is well-structured** [GOOD]
- Three-level hub: Home -> LLM Auth / Agent Behavior / Permissions. AnimatedContent with slide transitions. Clean back/close header pattern.

**B3.2 LlmAuthSettingsPage tab switching side effects** [MEDIUM]
- File: `settings/LlmAuthSettingsPage.kt:88-117`
- Switching tabs triggers `onBackendChange` and `onAuthMethodChange` immediately. If the user is just exploring, they change their backend without intending to.
- Impact: Tapping "Local" tab switches to LOCAL backend. Tapping back to "API Key" switches back. Every tab change is a state mutation. This could cause unintended model resets via `canonicalizeModels`.

**B3.3 Version string shown twice** [LOW]
- File: `settings/SettingsHomePage.kt:64-70` and `settings/PermissionsAdvancedSettingsPage.kt:108-114`
- Version info appears on both the home page and the Permissions & Advanced page. Redundant.

**B3.4 PerceptionModeSelector uses raw strings** [LOW]
- File: `settings/SettingsWidgets.kt:327-331`
- Modes are `"accessibility_only"`, `"hybrid"`, `"screenshot_only"` -- raw strings matched in multiple places. Should be an enum.

### B4. Onboarding UX

**B4.1 OnboardingScreen is clean and well-structured** [GOOD]
- Step routing via `when(currentStep)` with `OnboardingShell` providing shared scaffold. Effects collected via `LaunchedEffect`. Each step is self-contained.

**B4.2 Battery step skip CTA is "Continue without this"** [GOOD]
- Clear, non-judgmental language. Shows only for Battery (optional) step.

**B4.3 Complete step shows outcomes checklist** [GOOD]
- Visual summary with check/cross icons per step. Good closure experience.

**B4.4 Back button uses raw Icon + clickable instead of IconButton** [LOW]
- File: `onboarding/OnboardingShell.kt:56-63`
- `Icon(modifier = Modifier.clickable { onBack() })` lacks the 48dp touch target that `IconButton` provides.
- Impact: Small touch target, hard to tap.

### B5. Navigation Drawer

**B5.1 Session delete has no confirmation** [MEDIUM]
- File: `navigation/NavigationDrawer.kt:274-284`
- Delete button directly calls `onDelete`. No confirmation dialog. Accidental deletion is irreversible.
- Impact: Data loss. Users may accidentally tap the small (32dp) delete button.

**B5.2 Drawer width is responsive** [GOOD]
- `fillMaxWidth(0.85f).widthIn(max = 320.dp)` -- responsive on tablets, constrained on phones. Good.

**B5.3 Empty sessions message** [OK]
- "No saved sessions / Your chat history will appear here" -- clear and appropriate.

### B6. Overlay Visualizer

**B6.1 Action visualizer is clean and minimal** [GOOD]
- Click ripple animation, swipe trail with lerped endpoints. Auto-removes after duration. No leak path.

**B6.2 Canvas uses `withFrameMillis` for animation** [OK]
- File: `overlay/compose/ActionVisualizerCompose.kt:52-57`
- The `while(true) { withFrameMillis }` loop runs only when items are present, thanks to `LaunchedEffect(items.isNotEmpty())` key. Correct lifecycle.

### B7. Accessibility (Android A11Y for the app itself)

**B7.1 Many composables lack contentDescription** [MEDIUM]
- `EmptyState` icon: `contentDescription = null` (decorative -- OK)
- `StatusIslandCompose`: no contentDescription on the outer Surface (clickable without description)
- `NavIconButton`: `contentDescription = null` for all navigation icons
- `CapsuleRow1`: no contentDescription on the dot
- Impact: Screen reader users cannot identify clickable overlay elements.

**B7.2 SuggestionChip uses raw clickable instead of Surface onClick** [LOW]
- File: `chat/components/EmptyState.kt:108-111`
- `Surface` with `Modifier.clickable` works but Surface's `onClick` parameter provides built-in a11y semantics. Currently the a11y tree sees the clickable modifier but the Surface role may not be "button".

---

## Summary of Findings

| Severity | Count | Category |
|----------|-------|----------|
| HIGH     | 1     | Auto-scroll hijack |
| MEDIUM   | 8     | Recomposition, state, UX patterns |
| LOW      | 8     | Minor polish, consistency |
| GOOD     | 14    | Things done well |

### Top 3 Issues
1. **Auto-scroll hijacks user scroll position** during active streaming/actions
2. **Color.kt duplication** -- two parallel color vocabularies, unused "general" set
3. **SettingsSheet 38 parameters** -- maintainability ceiling
