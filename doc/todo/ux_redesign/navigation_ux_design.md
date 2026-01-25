# Navigation UX Redesign for Release

> Design document for improving navigation discoverability and user-friendliness.

## Problem Statement

The current app navigation has several UX issues that make it unsuitable for public release:

| Issue | Current State | Problem |
|-------|---------------|---------|
| **Settings Access** | Swipe-up gesture OR long-press on header | Not discoverable - users won't know these gestures exist |
| **History Button** | Icon on top right | Inconsistent with common chat app patterns (ChatGPT, Claude) |
| **New Session** | Inside session history sheet | Not easily accessible during active conversations |
| **Visual Hierarchy** | Minimal header with just title | No clear navigation affordances |

## Design Goals

1. **Discoverability**: All features accessible via visible, tappable UI elements
2. **Familiarity**: Follow established patterns from ChatGPT, Claude, Manus
3. **Efficiency**: Quick access to frequent actions (new chat, settings)
4. **Simplicity**: Clean interface that doesn't overwhelm

---

## Reference: ChatGPT Mobile UX

Based on the ChatGPT app reference:

```
┌─────────────────────────────────────────────────────────┐
│ [≡]        ChatGPT          [👤+] [📷]                  │  ← Header
├─────────────────────────────────────────────────────────┤
│                                                          │
│                    Chat Content                          │
│                                                          │
├─────────────────────────────────────────────────────────┤
│ [+]  Ask ChatGPT...                     [🎤] [🔊]       │  ← Input
└─────────────────────────────────────────────────────────┘

When [≡] is tapped → Side Drawer opens:
┌───────────────────────────┬─────────────────────────────┐
│  [←] Search               │                             │
│  ─────────────────────── │      Main Content           │
│  Today                    │      (dimmed)               │
│   • Chat about X          │                             │
│   • Help me with Y        │                             │
│  ─────────────────────── │                             │
│  Yesterday                │                             │
│   • Previous chat 1       │                             │
│   • Previous chat 2       │                             │
│  ─────────────────────── │                             │
│                           │                             │
│  ┌─────────────────────┐ │                             │
│  │ [👤] User Name      │ │  ← Settings entry point     │
│  │     Settings →       │ │                             │
│  └─────────────────────┘ │                             │
└───────────────────────────┴─────────────────────────────┘
```

---

## Proposed Design

### New Header Layout

```
┌─────────────────────────────────────────────────────────┐
│ [≡]        Android Agent                     [+]        │
│ History    Title/Brand                   New Chat       │
└─────────────────────────────────────────────────────────┘
```

| Position | Element | Action |
|----------|---------|--------|
| **Left** | Hamburger menu (≡) | Opens navigation drawer with history + settings |
| **Center** | "Android Agent" | Brand/title (tap does nothing or shows about) |
| **Right** | Plus button (+) | Quick new conversation |

### Navigation Drawer

Instead of bottom sheets, use a side navigation drawer that contains:

```
┌─────────────────────────────────────┐
│  ┌─────────────────────────────┐    │
│  │ [X]      Sessions           │    │  ← Header
│  └─────────────────────────────┘    │
│                                      │
│  [+ Start New Session]               │  ← Primary action
│                                      │
│  ─────────────────────────────────  │
│  Recent                              │
│  ─────────────────────────────────  │
│                                      │
│  ┌─────────────────────────────┐    │
│  │ "Check my email and..."    │    │
│  │  5 messages • 2 hours ago  │    │
│  └─────────────────────────────┘    │
│                                      │
│  ┌─────────────────────────────┐    │
│  │ "Open Settings app"         │    │
│  │  3 messages • Yesterday    │    │
│  └─────────────────────────────┘    │
│                                      │
│  ... more sessions ...               │
│                                      │
│  ─────────────────────────────────  │
│                                      │
│  ┌─────────────────────────────┐    │
│  │ [⚙] Settings               │    │  ← Settings entry
│  │                             │    │
│  │     Model: GPT-4o           │    │  ← Quick info
│  │     v1.0.0                  │    │
│  └─────────────────────────────┘    │
│                                      │
└─────────────────────────────────────┘
```

### Drawer Structure

**Top Section:**
- Close button (X) or back arrow
- "Sessions" title
- "Start New Session" button

**Middle Section (scrollable):**
- Session list grouped by time (Today, Yesterday, Previous 7 Days, etc.)
- Each session shows: title, message count, relative time
- Swipe-to-delete on each item

**Bottom Section (fixed):**
- Settings entry point with icon
- Quick info: current model, app version
- Tapping opens settings sheet/screen

---

## Component Changes

### 1. ChatHeader.kt

**Before:**
```kotlin
@Composable
fun ChatHeader(
    onSettingsLongPress: () -> Unit,
    onHistoryClick: (() -> Unit)? = null,
)
```

**After:**
```kotlin
@Composable
fun ChatHeader(
    onMenuClick: () -> Unit,           // Opens nav drawer
    onNewConversationClick: () -> Unit, // Quick new chat
    showNewChatButton: Boolean = true,  // Hide during empty state
)
```

**Visual Changes:**
- Replace history icon with hamburger menu (≡) on LEFT
- Add new conversation button (+) on RIGHT
- Remove long-press gesture (no longer needed)
- Remove swipe-up gesture from ChatScreen

### 2. New: NavigationDrawer.kt

New component that combines session history and settings access:

```kotlin
@Composable
fun NavigationDrawer(
    sessions: List<SessionInfo>,
    currentSessionId: String?,
    currentModel: String,
    appVersion: String,
    onSessionSelect: (SessionInfo) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (SessionInfo) -> Unit,
    onSettingsClick: () -> Unit,
    onClose: () -> Unit,
)
```

### 3. ChatScreen.kt

**Changes:**
- Remove swipe-up gesture detection
- Integrate with `ModalNavigationDrawer`
- Pass drawer state to header

```kotlin
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            NavigationDrawer(
                sessions = viewModel.sessions,
                onSettingsClick = onOpenSettings,
                // ...
            )
        }
    ) {
        Scaffold(
            topBar = {
                ChatHeader(
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onNewConversationClick = viewModel::startNewSession,
                )
            },
            // ...
        )
    }
}
```

### 4. SettingsSheet.kt

No major changes needed. Settings sheet remains a bottom sheet, accessed via the drawer.

### 5. SessionListSheet.kt

**Deprecate** or repurpose. Session list is now part of the navigation drawer.

---

## Visual Specifications

### Header Icons

| Icon | Material Icon | Size | Color |
|------|--------------|------|-------|
| Menu | `Icons.Rounded.Menu` | 24dp | `onSurfaceVariant` |
| New Chat | `Icons.Rounded.Add` (or `Edit`) | 24dp | `onSurfaceVariant` |

### Navigation Drawer

| Property | Value |
|----------|-------|
| Width | 85% of screen width, max 320dp |
| Background | `surface` |
| Scrim | 32% black overlay |
| Animation | Material 3 drawer animation |
| Corner Radius | 0dp (left) / 16dp (right corners) |

### Settings Entry (Bottom of Drawer)

| Property | Value |
|----------|-------|
| Height | 72dp |
| Background | `surfaceVariant` |
| Icon | `Icons.Outlined.Settings` |
| Border Top | 1dp `outlineVariant` |

---

## Interaction Flows

### Flow 1: Access Settings

```
[Old Flow - Not Discoverable]
1. Long-press header title → Settings sheet opens
   OR
1. Swipe up from bottom → Settings sheet opens

[New Flow - Discoverable]
1. Tap menu button (≡) → Drawer opens
2. Tap "Settings" at bottom → Settings sheet opens
```

### Flow 2: View Session History

```
[Old Flow]
1. Tap history icon (top right) → Bottom sheet opens

[New Flow]
1. Tap menu button (≡) → Drawer opens (history visible immediately)
2. Tap any session → Session loads, drawer closes
```

### Flow 3: Start New Conversation

```
[Old Flow]
1. Tap history icon → Bottom sheet opens
2. Tap "Start New Session" button

[New Flow - Quick Access]
1. Tap (+) button in header → New session starts
   OR
1. Tap menu button (≡) → Drawer opens
2. Tap "Start New Session" → New session starts, drawer closes
```

### Flow 4: Delete Session

```
[Same as before, but in drawer]
1. Open drawer
2. Swipe session left → Delete button appears
3. Tap delete → Confirmation dialog → Session deleted
```

---

## Implementation Plan

### Phase 1: Header Redesign
1. Update `ChatHeader.kt` with new layout
2. Add hamburger menu icon (left)
3. Add new conversation button (right)
4. Remove long-press gesture

### Phase 2: Navigation Drawer
1. Create `NavigationDrawer.kt` component
2. Implement session list inside drawer
3. Add settings entry at bottom
4. Integrate with `ChatScreen.kt`

### Phase 3: Cleanup
1. Remove swipe-up gesture from `ChatScreen.kt`
2. Deprecate `SessionListSheet.kt` (or keep for tablet/large screen)
3. Update `ChatViewModel` if needed
4. Update documentation

### Phase 4: Polish
1. Add drawer open/close animations
2. Add haptic feedback
3. Test edge cases (empty state, long session list)
4. Accessibility testing

---

## Migration Notes

### Backward Compatibility
- Settings remain functional (just different access path)
- Session history functionality unchanged
- No data migration needed

### User Education
- Consider one-time tooltip: "Tap here for menu"
- Or let the pattern be self-discoverable (common enough)

---

## Alternatives Considered

### Option A: Bottom Navigation Bar
- **Pros**: Very discoverable, iOS-like
- **Cons**: Takes permanent screen space, not common for chat apps

### Option B: Keep Bottom Sheet for History
- **Pros**: Less code change
- **Cons**: Settings still needs discovery solution

### Option C: Tab Bar (Chat / History / Settings)
- **Pros**: All features visible
- **Cons**: Overkill for simple app, takes space

**Decision:** Navigation drawer chosen because:
1. Most similar to ChatGPT/Claude pattern
2. Combines history + settings access
3. Doesn't take permanent screen space
4. Familiar to users

---

## Open Questions

1. **Search in drawer?** Add session search for users with many sessions?
2. **Session grouping?** Group by day/week or flat list?
3. **Quick settings in drawer?** Show model selector directly in drawer?
4. **Gestures?** Keep swipe-from-left-edge to open drawer?

---

## Success Criteria

- [ ] New users can find settings within 10 seconds
- [ ] History access is obvious without tutorial
- [ ] New conversation can be started with 1 tap
- [ ] Settings access requires max 2 taps
- [ ] No hidden gestures required for core features

---

## References

- [Material 3 Navigation Drawer](https://m3.material.io/components/navigation-drawer)
- ChatGPT Mobile App
- Claude Mobile App
- Current UI docs: `doc/main/ui_stack.md`
