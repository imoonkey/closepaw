# UI/UX Improvement Plan

**Source**: `doc/todo/ui-ux-quality/review.md`
**Principle**: Reduction over addition. Every item must have concrete UX or perf impact.

---

## Priority 1: Fix (Impacts real users now)

### P1.1 Smart auto-scroll in MessageList
**Review ref**: A2.1, B1.1
**Impact**: Users lose their scroll position during streaming. Frustrating during multi-step tasks.

**Change**: Add "is at bottom" tracking to `MessageList`.
```
File: chat/ChatScreen.kt (MessageList composable)

- Track whether user is near bottom (within 1 item of end)
- Only auto-scroll if user is at bottom
- Show a "scroll to bottom" FAB when user has scrolled up and new messages arrive
```

**Estimated effort**: Small (20 lines added, 0 removed).

---

### P1.2 Session delete confirmation
**Review ref**: B5.1
**Impact**: Irreversible data loss from accidental tap on 32dp delete button.

**Change**: Add confirmation dialog before deleting a session.
```
File: navigation/NavigationDrawer.kt (DrawerSessionItem)

- Wrap onDelete in a showConfirmation state
- Show AlertDialog: "Delete this session? This cannot be undone."
- [Cancel] [Delete]
```

**Estimated effort**: Small (15 lines added).

---

## Priority 2: Improve (Reduce tech debt, prevent future issues)

### P2.1 Consolidate Color.kt
**Review ref**: A5.1
**Impact**: Two parallel color vocabularies cause confusion and risk divergence.

**Change**: Remove unused "general" color section. Keep only the `Chat*` variants that are actually consumed by the theme and composables.
```
File: theme/Color.kt

- Remove lines 12-59 (Background, Surface, Primary, Accent, etc.)
- These are never referenced. All composables use Chat* or direct MaterialTheme tokens.
- Rename Chat* prefix to just the semantic name if desired (future refactor)
```

**Estimated effort**: Small (delete 48 lines). Verify with grep that none of the general names are imported anywhere.

---

### P2.2 Fix MessageBubble formatTime allocation
**Review ref**: A1.1
**Impact**: GC pressure during fast streaming recomposition.

**Change**: Use `java.time.DateTimeFormatter` (thread-safe) as a top-level val, matching the pattern already used in `session/TimeUtils.kt`.
```
File: chat/components/MessageBubble.kt

- Replace SimpleDateFormat with:
  private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
  private fun formatTime(timestamp: Long): String {
      val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
      return ldt.format(timeFormatter)
  }
```

**Estimated effort**: Trivial (3 lines changed).

---

### P2.3 Remove double rotation on ActionStatusIcon
**Review ref**: A1.5
**Impact**: Redundant animation work. Two nested rotation animations.

**Change**: Remove the manual `infiniteTransition` rotation. Use `CircularProgressIndicator` in its indeterminate form without extra rotation.
```
File: chat/components/ActionCard.kt (ActionStatusIcon, Executing case)

- Remove infiniteTransition and rotation animate
- Use plain: CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
```

**Estimated effort**: Trivial (delete 10 lines).

---

### P2.4 Bundle SettingsSheet parameters
**Review ref**: A3.3
**Impact**: 38 parameters. Hard to maintain, hard to add new settings.

**Change**: Group into data classes by settings page.
```
File: settings/SettingsSheet.kt

Introduce:
- LlmAuthState (backend, model, keys, auth, OAuth state, catalog)
- AgentBehaviorState (maxTurns, agentMode, perceptionMode)
- PermissionsState (accessibility, overlay, debug)

SettingsSheet(
    llmAuth: LlmAuthState,
    agentBehavior: AgentBehaviorState,
    permissions: PermissionsState,
    onDismiss: () -> Unit,
)
```

**Estimated effort**: Medium (restructure call sites). No behavioral change.

---

### P2.5 Extract CapsuleOverlayHost callbacks into interface
**Review ref**: A4.2
**Impact**: 12 nullable callback properties are fragile. Missing assignment = silent no-op.

**Change**: Define a callback interface.
```
File: overlay/compose/CapsuleOverlayHost.kt

interface CapsuleOverlayCallbacks {
    fun onTakeover()
    fun onResume()
    fun onSupplement(text: String)
    fun onUserResponse(callId: String, response: String)
    fun onApprovalResponse(callId: String, decision: ApprovalDecision, scope: ApprovalScope, packageName: String?)
    fun onStop()
    fun onOpenApp()
    fun onDismissError()
    fun onMinimize()
    fun onOpenViewer()
    fun onSend(text: String)
}

CapsuleOverlayHost(..., callbacks: CapsuleOverlayCallbacks)
```

**Estimated effort**: Medium (restructure one file + callers).

---

## Priority 3: Polish (Nice to have)

### P3.1 Fix onboarding back button touch target
**Review ref**: B4.4
**Impact**: Back button is a raw `Icon + clickable` with no minimum touch target.

**Change**: Replace with `IconButton` which provides 48dp minimum touch target.
```
File: onboarding/OnboardingShell.kt

- Replace: Icon(modifier = Modifier.size(24.dp).clickable { onBack() })
- With: IconButton(onClick = { onBack() }) { Icon(...) }
```

**Estimated effort**: Trivial.

---

### P3.2 Add contentDescription to overlay interactive elements
**Review ref**: B7.1
**Impact**: Screen reader users cannot identify clickable overlay buttons.

**Change**: Add contentDescription to:
- `NavIconButton`: derive from icon (Minimize, Phone, Visibility)
- `StatusIslandCompose`: add semantics description to clickable Surface

**Estimated effort**: Small (scattered 1-line changes).

---

### P3.3 Make PerceptionMode an enum
**Review ref**: B3.4
**Impact**: Raw strings matched in multiple places risk typos.

**Change**: Define `enum class PerceptionMode { ACCESSIBILITY_ONLY, HYBRID, SCREENSHOT_ONLY }` with `displayName` property. Replace string comparisons.

**Estimated effort**: Small (new enum + update 3-4 comparison sites).

---

### P3.4 Avoid double version display in settings
**Review ref**: B3.3
**Impact**: Redundant info. Minor visual clutter.

**Change**: Remove version text from `PermissionsAdvancedSettingsPage`. Keep it only on `SettingsHomePage`.

**Estimated effort**: Trivial (delete 8 lines).

---

## Items Explicitly NOT Recommended

| Suggestion | Reason for exclusion |
|------------|---------------------|
| Add dark mode to overlays | Overlay colors are intentionally hardcoded for maximum contrast on any background app |
| Remove AppWindowInsets wrapper | Adds documentation value at zero runtime cost |
| Hoist ActionCard expand state to ViewModel | Ephemeral UI state correctly scoped to composition |
| Split ChatViewModel further | Already well-decomposed into ViewModel + EventReducer + SessionHistoryController |
| Add animations to settings pages | AnimatedContent slide transitions already present; more animation adds no value |

---

## Execution Order

```
P1.1 (auto-scroll)  -->  P2.2 (formatTime)  -->  P2.3 (double rotation)
                    \
P1.2 (delete confirm) --> P3.1 (back button) --> P3.2 (a11y descriptions)

P2.1 (color cleanup) --> independent, any time
P2.4 (settings params) --> P2.5 (capsule callbacks) --> larger refactor batch
P3.3 (perception enum) --> P3.4 (version dedup) --> minor polish batch
```

Estimated total: ~2 focused sessions. No P1/P2 item requires more than one file change except P2.4/P2.5 which touch call sites.
