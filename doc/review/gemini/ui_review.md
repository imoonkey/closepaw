# UI and Android Services Review

## Summary
The UI is built with Jetpack Compose (Material 3) and follows a modern, clean design. The `AgentService` acts as the bridge between Android system events (Accessibility) and the Agent Session.

## High-Risk Issues (Must-Fix)

### 1. OverlayManager Emoji Hack
**Location**: `service/OverlayManager.kt` line 171
**Issue**: The overlay uses text emojis ("⏸", "▶", "⏹") for buttons instead of proper vector icons.
**Impact**: While "elegant" in theory, emoji rendering varies wildly across Android versions and OEMs. On some devices, these might look like blobs or be invisible.
**Fix**: Use `VectorDrawable` or bitmap resources for the overlay icons to ensure consistent, professional appearance.

## Medium Issues (Should-Fix)

### 2. Status Line Unbounded Growth
**Location**: `MainActivity.kt` line 56
**Code**: `statusLines = (statusLines + status).takeLast(MAX_STATUS_LINES)`
**Issue**: While capped at 100, creating a new List implementation on every status update triggers UI recomposition of the entire list.
**Fix**: Use `mutableStateListOf` or a more efficient collection for Compose to minimize recomposition churn.

### 3. Service Connection Reliability
**Location**: `AgentService.kt`
**Issue**: The service relies on `onServiceConnected` to initialize the overlay. If the service crashes and restarts, or if the system kills it, the overlay might get out of sync with the session state.
**Fix**: Ensure robust state restoration or synchronization when the service reconnects.

## Low-Risk Suggestions (Nice-to-Have)

### 4. StatusUtils Duplication
**Location**: `util/StatusUtils.kt`
**Observation**: Good job extracting this. It ensures consistent behavior between Activity and Overlay.

### 5. Theme Hardcoding
**Location**: `ui/theme/Color.kt`
**Suggestion**: The colors are hardcoded values. Consider supporting proper Dark Mode (system follows) by defining both light and dark schemes in `AgentTheme`.
