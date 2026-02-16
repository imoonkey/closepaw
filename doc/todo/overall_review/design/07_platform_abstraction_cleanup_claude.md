# Design: Platform Abstraction Cleanup

**Priority**: P1 — Architectural
**Files affected**: `platform/virtualdisplay/VirtualDisplayPlatform.kt`, `platform/virtualdisplay/ShizukuClient.kt`, `platform/AccessibilityNodeFinder.kt`, `platform/NodeActionPerformer.kt`

---

## Problem

### VirtualDisplayPlatform scope creep (645 lines)

`VirtualDisplayPlatform` mixes 4 responsibilities:
1. **Display lifecycle** — create/destroy virtual display via ShizukuClient
2. **Screen capture** — ImageReader-based screenshot + a11y tree from display
3. **Live preview** — switching between ImageReader and SurfaceView output
4. **Viewer touch forwarding** — translating viewer touch coords to VD input injection

Responsibilities 3-4 are UI/viewer concerns that leak into the platform layer. The platform should only handle display lifecycle + capture.

### ShizukuClient duplication (544 lines)

Display creation has 3 callback registration sites with nearly identical error handling. Reflection-heavy IPC code could be simplified with helper functions.

### AccessibilityNodeFinder duplication (210 lines)

`findClickable()` and `findLongClickable()` are 95% identical — same tree traversal, same coordinate matching, just different accessibility action check:

```kotlin
// findClickable
if (node.isClickable) { ... }

// findLongClickable
if (node.isLongClickable) { ... }
```

### NodeActionPerformer duplication (163 lines)

`performNodeClickAt()` and `performNodeLongClickAt()` are nearly identical — same node finding, same coordinate calculation, just different `AccessibilityNodeInfo.ACTION_*` constant.

## Solution

### 1. Extract viewer concerns from VirtualDisplayPlatform

```kotlin
// platform/virtualdisplay/VirtualDisplayPlatform.kt — display + capture only (~300 lines)
// platform/virtualdisplay/VirtualDisplayViewer.kt — live preview + touch forwarding (~200 lines)

class VirtualDisplayViewer(
    private val platform: VirtualDisplayPlatform
) {
    fun switchToLivePreview(surfaceView: SurfaceView) { ... }
    fun switchToImageReader() { ... }
    fun onViewerTouch(action: Int, x: Float, y: Float, ...): Boolean { ... }
}
```

`AgentService` creates the viewer and passes it to viewer-related callers. Platform stays clean.

### 2. Parameterize node finders

```kotlin
// Before: two nearly identical functions
fun findClickable(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo?
fun findLongClickable(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo?

// After: one function with action parameter
fun findActionableNode(
    root: AccessibilityNodeInfo,
    x: Int, y: Int,
    actionCheck: (AccessibilityNodeInfo) -> Boolean
): AccessibilityNodeInfo?

// Usage:
findActionableNode(root, x, y) { it.isClickable }
findActionableNode(root, x, y) { it.isLongClickable }
```

### 3. Parameterize node action performers

```kotlin
// Before:
fun performNodeClickAt(root, x, y): Boolean
fun performNodeLongClickAt(root, x, y): Boolean

// After:
fun performNodeActionAt(
    root: AccessibilityNodeInfo,
    x: Int, y: Int,
    action: Int,  // AccessibilityNodeInfo.ACTION_CLICK or ACTION_LONG_CLICK
    actionCheck: (AccessibilityNodeInfo) -> Boolean
): Boolean
```

### 4. ShizukuClient helper extraction

Extract a `ShizukuCallbackHelper` to reduce callback registration boilerplate:

```kotlin
private inline fun <T> withShizukuCallback(
    crossinline register: (IShizukuCallback) -> Unit,
    crossinline action: () -> T,
    crossinline onResult: (T) -> Unit
) { ... }
```

## Steps

1. Create `VirtualDisplayViewer.kt` — move `switchToLivePreview()`, `switchToImageReader()`, `onViewerTouch()` from `VirtualDisplayPlatform`
2. Update `AgentService` to create and manage `VirtualDisplayViewer` separately
3. Parameterize `AccessibilityNodeFinder` — merge `findClickable`/`findLongClickable` into `findActionableNode`
4. Parameterize `NodeActionPerformer` — merge `performNodeClickAt`/`performNodeLongClickAt`
5. Extract `ShizukuCallbackHelper` from `ShizukuClient`

## Risks

- **Medium**: `VirtualDisplayViewer` extraction touches the viewer ↔ service interface. Test touch forwarding carefully.
- **Low**: Node finder/performer parameterization is straightforward.
