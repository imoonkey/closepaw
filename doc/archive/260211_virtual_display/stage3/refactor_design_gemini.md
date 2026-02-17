# Refactoring Design: Platform Convergence (Gemini)

> **Author**: Gemini (Linus Mode)
> **Date**: 2026-02-11
> **Status**: Draft

## 1. Philosophy

We are not building a cathedral. We are building a workshop.

The current state of `AccessibilityPlatform` and `VirtualDisplayPlatform` is "serviceable but messy". `VirtualDisplayPlatform` creates a nice separation of concerns (WindowAccessor, NodeActionPerformer, InputInjector), while `AccessibilityPlatform` is a monolithic God Class containing perception, actions, screenshots, and app queries.

We will not create a complex inheritance hierarchy. We will not over-engineer "universal" abstractions. We will simply **decompose** `AccessibilityPlatform` to match the clean structure of `VirtualDisplayPlatform`, and **deduplicate** the obvious identical logic.

**Core Tenets:**
- **Split Responsibilities**: Platforms are orchestrators, not workers.
- **Align Implementations**: If one platform uses an `Injector` class, the other should too.
- **Fix Leaks**: The current `AccessibilityPlatform` leaks `AccessibilityNodeInfo` (root). This must stop.
- **Readability**: A human should be able to read `AccessibilityPlatform.kt` and see *what* it does, not *how* it does every detail.

---

## 2. Architecture

We will align `AccessibilityPlatform` to the component-based architecture of `VirtualDisplayPlatform`.

### 2.1 The Components

| Responsibility | Virtual Display (Current) | Accessibility (Target) | Shared |
|---|---|---|---|
| **Orchestration** | `VirtualDisplayPlatform` | `AccessibilityPlatform` | `AndroidPlatform` (Interface) |
| **Window/Root Access** | `VirtualDisplayWindowAccessor` | **`AccessibilityWindowAccessor`** (New) | - |
| **Node Actions** | `VirtualDisplayNodeActionPerformer` | **`AccessibilityNodeActionPerformer`** (New) | - |
| **Input Injection** | `VirtualDisplayInputInjector` | **`AccessibilityInputInjector`** (New) | - |
| **App Queries** | *Inline (Duplicated)* | *Inline (Duplicated)* | **`AppQueryUtils`** (New) |
| **Bitmap Logic** | `BitmapUtils` | *Inline (Duplicated)* | **`BitmapUtils`** |

### 2.2 Component Details

#### `AccessibilityWindowAccessor` (New)
*   **Purpose**: Safe access to the active window root.
*   **Why**: Currently, `AccessibilityPlatform` calls `service.rootInActiveWindow` in multiple places. Some recycle it, some don't (**Leak!**).
*   **Contract**: `getRoot(): AccessibilityNodeInfo?`.
*   **Behavior**: Wraps `service.rootInActiveWindow`.

#### `AccessibilityNodeActionPerformer` (New)
*   **Purpose**: Execute `ACTION_CLICK`, `ACTION_SET_TEXT`, etc.
*   **Why**: Move ~300 lines of low-level node manipulation out of the platform class.
*   **Behavior**: Takes `AccessibilityWindowAccessor`. Fetches root, finds node (using `AccessibilityNodeFinder`), performs action, **ensure-recycles root**.

#### `AccessibilityInputInjector` (New)
*   **Purpose**: Execute gestures (`dispatchGesture`).
*   **Why**: Move gesture construction and callback logic out of the platform class.
*   **Behavior**: Takes `AccessibilityService`. Implements `injectTap`, `injectSwipe`, `injectSystemButton` (via `performGlobalAction`).

#### `AppQueryUtils` (New)
*   **Purpose**: Query installed apps.
*   **Why**: Identical 30-line block in both platforms.
*   **Behavior**: `fun getInstalledApps(pm: PackageManager): List<AppInfo>`.

---

## 3. Detailed Changes

### 3.1 `platform/AppQueryUtils.kt` (New)
Extract the `getInstalledApps` logic here.
```kotlin
object AppQueryUtils {
    suspend fun getInstalledApps(pm: PackageManager): List<AppInfo> { ... }
}
```

### 3.2 `platform/virtualdisplay/VirtualDisplayPlatform.kt` (Refactor)
1.  **Remove** `getInstalledApps` implementation. Delegate to `AppQueryUtils`.
2.  **Keep** everything else (it is the model implementation).

### 3.3 `platform/AccessibilityWindowAccessor.kt` (New)
```kotlin
class AccessibilityWindowAccessor(private val service: AccessibilityService) {
    fun getRoot(): AccessibilityNodeInfo? {
        return service.rootInActiveWindow
    }
}
```
*Note*: Unlike VD, we don't need to filter windows manually, but having this class creates a symmetric injection point for the NodePerformer.

### 3.4 `platform/AccessibilityNodeActionPerformer.kt` (New)
Move the following methods from `AccessibilityPlatform`:
- `performNodeClickAt`
- `performNodeLongClickAt`
- `performSetTextOnNodeAt`
- `performSetTextOnFocused`
- `performEnterKey` (Logic specific to A11y service IME/action)
- `setTextOnNode` (Private helper)

**Crucial Change**: Wrap *every* public method's logic in:
```kotlin
val root = windowAccessor.getRoot() ?: return Failure
try {
   // logic
} finally {
   root.recycle() // FIX THE LEAK
}
```

### 3.5 `platform/AccessibilityInputInjector.kt` (New)
Move the following methods from `AccessibilityPlatform`:
- `performTap` -> `injectTap`
- `performSwipe` -> `injectSwipe`
- `performLongPressGesture` -> `injectLongPress`
- `performSystemButton` -> `injectSystemButton`
- `dispatchGesture` (Private helper)

### 3.6 `platform/AccessibilityPlatform.kt` (Refactor)
1.  **Properties**: Add `windowAccessor`, `nodePerformer`, `inputInjector`.
2.  **Start/Stop**: No change (no-op).
3.  **App Management**: Delegate to `AppQueryUtils`.
4.  **Capture**:
    - `captureScreenshotIfEnabled`: Use `BitmapUtils` directly. Remove private `scaleBitmapIfNeeded` and `compressJpeg`.
5.  **Actions**:
    - `performAction`: Simply route to `nodePerformer` or `inputInjector`.

---

## 4. Implementation Steps

1.  **Extract Shared Utils**: Create `AppQueryUtils`.
2.  **Clean VD**: Switch `VirtualDisplayPlatform` to use `AppQueryUtils`.
3.  **Deconstruct A11y**:
    - Create `AccessibilityWindowAccessor`.
    - Create `AccessibilityInputInjector` (Move gesture code).
    - Create `AccessibilityNodeActionPerformer` (Move node code + **Fix Leaks**).
4.  **Reassemble A11y**: Rewrite `AccessibilityPlatform` to use the new components.

## 5. Benefits

- **Parity**: Both platforms now look the same.
- **Leak Fix**: Root node leaking in A11y actions is structurally impossible in the new Performer.
- **Readability**: `AccessibilityPlatform.kt` drops from ~800 lines to ~200 lines.
- **Maintenance**: Gestures, Node Actions, and App Queries are now separate concerns.

---
"Keep it simple, stupid."
