# AccessibilityPlatform Refactoring — Stage 3

Date: 2026-02-11
Status: Design proposal

---

## The Problem

`AccessibilityPlatform.kt` is 831 lines. It does everything: screen capture,
screenshot compression, bitmap scaling, a11y tree capture, trace recording,
gesture dispatch, node actions, text entry, app management, display info.
It's a god-class.

Meanwhile, the VD side was refactored into clean components:

```
VirtualDisplayPlatform          (338 lines — orchestrator)
VirtualDisplayWindowAccessor    ( 79 lines — window/root access)
VirtualDisplayNodeActionPerformer (176 lines — node actions)
VirtualDisplayInputInjector     (212 lines — input injection)
```

These two sides share significant duplicated logic but have completely different
internal structures. They need to be aligned.

---

## What's Duplicated

I read every line. Here's the real duplication, not the imagined kind:

### 1. `setTextOnNode()` — Copy-pasted verbatim

`AccessibilityPlatform.setTextOnNode()` (lines 715-743) and
`VirtualDisplayNodeActionPerformer.setTextOnNode()` (lines 135-162).

Same Bundle construction, same clear-then-set logic. The only difference:
VD version calls `clearInputFocusAfterSetText()` on success. This is a
behavior difference that should be unified (both should clear focus, or
neither should — it was added to VD to fix the IME popup bug, and
AccessibilityPlatform should have it too).

### 2. `getInstalledApps()` — Identical

Both implementations query PackageManager with ACTION_MAIN/CATEGORY_LAUNCHER,
map to `AppInfo`, `distinctBy`, `sortedBy`. Line-for-line the same.

### 3. `scaleBitmapIfNeeded()` / `compressJpeg()` — Triple duplication

These exist in THREE places:
- `AccessibilityPlatform` (private methods, lines 311-329)
- `BitmapUtils` (shared object, already extracted)
- `VirtualDisplayPlatform` uses `BitmapUtils`

AccessibilityPlatform still has its own private copies and doesn't use
`BitmapUtils`. This was supposed to be cleaned up when `BitmapUtils` was
extracted but never was.

### 4. Node action pattern — Structural duplication

Both platforms follow the same pattern for every node action:
```
get root → find node → try { act } finally { recycle } → wrap in ActionResult
```

The AccessibilityPlatform versions are inlined as private functions.
The VD versions live in `VirtualDisplayNodeActionPerformer`.
The logic is the same; only the "get root" step differs:
- Accessibility: `service.rootInActiveWindow`
- VD: `windowAccessor.getRootOnDisplay()`

### 5. `performWait()` — Trivial but duplicated

Both do `delay(action.durationMs)` and return `ActionResult.Success`.

---

## What's NOT Duplicated (Don't Touch It)

- Screenshot capture: fundamentally different (a11y screenshot API vs ImageReader)
- Gesture dispatch: fundamentally different (dispatchGesture vs Shizuku injection)
- System buttons: different APIs (globalAction vs KeyEvent injection)
- Lifecycle: different (no-op vs create/release display)
- Display info: different sources (DisplayMetrics vs VirtualDisplayConfig)
- A11y tree capture: similar but AccessibilityPlatform has trace recording

Don't try to unify these. They're different for hardware reasons. Forcing
them into a shared abstraction would be architecture astronauting.

---

## The Design

### Principle: Extract shared logic into plain utility classes. No inheritance. No generics. No "framework".

### Change 1: `NodeActionPerformer` — Shared node action logic

Extract a simple class that encapsulates the "find node → act → recycle"
pattern. Both platforms delegate to it.

```kotlin
// platform/NodeActionPerformer.kt
class NodeActionPerformer(
    private val rootProvider: () -> AccessibilityNodeInfo?
) {
    fun performNodeClickAt(x: Int, y: Int): ActionResult {
        return withRoot { root ->
            val node = AccessibilityNodeFinder.findClickableNodeAtLocation(root, x, y)
                ?: return@withRoot ActionResult.Failure("No clickable node at ($x,$y)")
            try {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) ActionResult.Success("ACTION_CLICK at ($x,$y)")
                else ActionResult.Failure("ACTION_CLICK returned false at ($x,$y)")
            } finally {
                if (node !== root) node.recycle()
            }
        }
    }

    fun performNodeLongClickAt(x: Int, y: Int): ActionResult { ... }
    fun performSetTextOnNodeAt(x: Int, y: Int, text: String, clear: Boolean): ActionResult { ... }
    fun performSetTextOnFocused(text: String, clear: Boolean): ActionResult { ... }
    fun performEnterKey(): ActionResult { ... }

    // Shared text-setting: clear if requested, then set, then clear focus.
    private fun setTextOnNode(node: AccessibilityNodeInfo, text: String, clear: Boolean): ActionResult {
        if (clear) {
            val clearArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            clearInputFocusAfterSetText(node)  // Always do this. Fixes IME popup on both paths.
        }
        return if (ok) ActionResult.Success("Text entered: $text")
               else ActionResult.Failure("ACTION_SET_TEXT failed")
    }

    private fun clearInputFocusAfterSetText(node: AccessibilityNodeInfo) {
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) }
    }

    // Root-scoped helper: get root, run block, recycle root.
    private inline fun withRoot(block: (AccessibilityNodeInfo) -> ActionResult): ActionResult {
        val root = rootProvider()
            ?: return ActionResult.Failure("No a11y root available")
        return try {
            block(root)
        } finally {
            root.recycle()
        }
    }
}
```

**Key decision**: The `rootProvider` lambda is the only thing that differs
between platforms. AccessibilityPlatform passes `{ service.rootInActiveWindow }`.
VirtualDisplayPlatform passes `{ windowAccessor.getRootOnDisplay() }`. Done.

**Important note on `withRoot`**: For `AccessibilityPlatform`, root obtained
from `service.rootInActiveWindow` is system-owned and recycling it is optional
but harmless on modern APIs (API 31+, our minSdk). The VD side already
recycles root. Unifying this to always recycle is correct.

**ENTER key handling**: Currently `performEnterKey()` exists only in
`AccessibilityPlatform`. It uses `ACTION_IME_ENTER` with fallback to
`ACTION_CLICK`. This should work on VD too (it uses the same a11y node
APIs, not gesture dispatch). Move it into `NodeActionPerformer` so both
platforms get it for free.

### Change 2: `AppManager` — Shared app management

Extract the identical `getInstalledApps()` into a simple utility:

```kotlin
// platform/AppManager.kt
object AppManager {

    fun getInstalledApps(pm: PackageManager): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val ai = info.activityInfo ?: return@mapNotNull null
                AppInfo(
                    packageName = ai.packageName,
                    label = info.loadLabel(pm).toString().ifBlank { ai.packageName },
                    isSystemApp = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
```

Both platforms: `override suspend fun getInstalledApps() = withContext(Dispatchers.IO) { AppManager.getInstalledApps(service.packageManager) }`.

`launchApp()` stays separate — the implementations are genuinely different
(intent-only vs shell-then-intent). Don't try to unify them.

### Change 3: AccessibilityPlatform uses `BitmapUtils`

Delete the private `scaleBitmapIfNeeded()` and `compressJpeg()` from
`AccessibilityPlatform`. Use `BitmapUtils.scaleBitmapIfNeeded()` and
`BitmapUtils.compressJpeg()` instead. This was the original intent when
`BitmapUtils` was created — just finish the job.

### Change 4: Delete `VirtualDisplayNodeActionPerformer`

It gets replaced by the shared `NodeActionPerformer`. The VD-specific
`clearInputFocusAfterSetText()` moves into the shared class (because the
behavior is correct for both platforms).

### Change 5: AccessibilityPlatform internal cleanup

After extracting node actions and bitmap utils, AccessibilityPlatform
will naturally shrink. The remaining responsibilities:

```
AccessibilityPlatform (orchestrator, ~400 lines)
├── captureScreen()           — a11y tree + screenshot with trace recording
├── performAction()           — dispatch to nodeActionPerformer / gesture helpers
├── captureAccessibilityTree() — a11y tree + trace artifacts
├── captureScreenshotIfEnabled() — screenshot via a11y API
├── Gesture helpers           — tap, swipe, long press, dispatchGesture
├── hasRequiredPermissions(), getCurrentPackageName(), getDisplayInfo()
├── getInstalledApps()        — delegates to AppManager
└── launchApp()               — intent-based launch
```

The gesture helpers (performTap, performSwipeGesture, dispatchGesture,
performLongPressGesture) stay in AccessibilityPlatform. They use
`service.dispatchGesture()` which is specific to the a11y service
running on the default display. Don't extract them.

---

## File Changes Summary

| File | Action | Lines (est.) |
|---|---|---|
| `platform/NodeActionPerformer.kt` | **NEW** | ~120 |
| `platform/AppManager.kt` | **NEW** | ~30 |
| `platform/AccessibilityPlatform.kt` | **MODIFY** — remove node action methods, remove bitmap utils, delegate to shared classes | ~450 (from 831) |
| `platform/virtualdisplay/VirtualDisplayPlatform.kt` | **MODIFY** — use `NodeActionPerformer` instead of `VirtualDisplayNodeActionPerformer`, use `AppManager` | ~310 (from 338) |
| `platform/virtualdisplay/VirtualDisplayNodeActionPerformer.kt` | **DELETE** | — |
| `platform/BitmapUtils.kt` | No change | 43 |

### What Stays Untouched

- `AndroidPlatform.kt` — interface doesn't change
- `UIAction.kt` — no change
- `ActionResult.kt` — no change
- `AccessibilityNodeFinder.kt` — no change (already shared)
- `PlatformFactory.kt` — no change
- `VirtualDisplayWindowAccessor.kt` — no change
- `VirtualDisplayInputInjector.kt` — no change
- `ShizukuClient.kt` — no change
- `VirtualDisplayConfig.kt` — no change
- Everything above platform layer — no change

---

## What This Achieves

1. **AccessibilityPlatform drops from 831 to ~450 lines** — approaching
   reasonable orchestrator size.

2. **Node action logic lives in one place** — `NodeActionPerformer`.
   Bug fixes apply to both platforms automatically. The IME focus-clear
   fix currently only in VD gets applied to accessibility mode too.

3. **App management logic lives in one place** — `AppManager`. Zero
   behavior change, just no more copy-paste.

4. **Both platforms now have aligned structure:**
   ```
   AccessibilityPlatform (orchestrator)
     ├── NodeActionPerformer      (shared)
     ├── AppManager               (shared)
     ├── BitmapUtils              (shared)
     ├── AccessibilityNodeFinder  (shared, already was)
     └── gesture helpers          (a11y-specific, stays inline)

   VirtualDisplayPlatform (orchestrator)
     ├── NodeActionPerformer      (shared)
     ├── AppManager               (shared)
     ├── BitmapUtils              (shared)
     ├── AccessibilityNodeFinder  (shared, already was)
     ├── VirtualDisplayWindowAccessor    (VD-specific)
     └── VirtualDisplayInputInjector     (VD-specific)
   ```

5. **No abstraction overhead** — `NodeActionPerformer` is a plain class
   with a lambda constructor. `AppManager` is a stateless object. No
   interfaces, no inheritance, no dependency injection framework.

---

## What This Doesn't Do (Non-goals)

- **No `AbstractPlatform` base class.** That would just move code around
  without reducing complexity. The two platforms have different lifecycles,
  different capture mechanisms, and different gesture systems. A base class
  would either be too thin to be useful or too thick to be clean.

- **No shared screenshot pipeline.** The capture mechanisms are fundamentally
  different (a11y API callback vs ImageReader buffer copy). Both already
  converge on `BitmapUtils` for the final scale+compress step.

- **No gesture abstraction layer.** `dispatchGesture` (a11y) and
  `injectInputEvent` (Shizuku) are different APIs for different contexts.
  Wrapping them in a common interface would add indirection without value.

- **No changes to `AndroidPlatform` contract.** It's fine as is.

---

## Threading Model (Preserved)

- `NodeActionPerformer` methods run on whatever thread they're called from.
  Both callers already wrap in `withContext(Dispatchers.Main)` for the
  a11y API calls that require it. AccessibilityPlatform does this in its
  `performAction` dispatch. VirtualDisplayPlatform's node action calls
  were already on Main in the old `VirtualDisplayNodeActionPerformer`.
  
  Wait — actually, looking at the code more carefully:
  
  - `AccessibilityPlatform.performNodeClickAt()` wraps with `withContext(Dispatchers.Main)`
  - `VirtualDisplayNodeActionPerformer.performNodeClickAt()` wraps with `withContext(Dispatchers.Main)`
  
  So the `withContext(Main)` should be inside `NodeActionPerformer` methods,
  not in the callers. This keeps the threading contract self-contained.

  ```kotlin
  // NodeActionPerformer
  suspend fun performNodeClickAt(x: Int, y: Int): ActionResult {
      return withContext(Dispatchers.Main) {
          withRoot { root -> ... }
      }
  }
  ```

---

## Implementation Steps

Do this in three small commits, not one giant one:

### Commit 1: Extract `NodeActionPerformer` + `AppManager`

Create the two new files. Write them from scratch based on the shared
logic. Include `clearInputFocusAfterSetText` in the shared `setTextOnNode`.
Include `performEnterKey` (moved from AccessibilityPlatform).

### Commit 2: Rewire both platforms

- `AccessibilityPlatform`: construct `NodeActionPerformer` with
  `rootProvider = { service.rootInActiveWindow }`. Delete all private
  node action methods. Delete private `scaleBitmapIfNeeded`/`compressJpeg`,
  use `BitmapUtils` instead. Delegate `getInstalledApps` to `AppManager`.

- `VirtualDisplayPlatform`: construct `NodeActionPerformer` with
  `rootProvider = { windowAccessor.getRootOnDisplay() }`. Update
  `performAction()` to use it. Delegate `getInstalledApps` to `AppManager`.

### Commit 3: Delete + clean up

- Delete `VirtualDisplayNodeActionPerformer.kt`
- Remove any dead imports
- Build, verify no regressions

---

## Verification

- `./gradlew clean assembleDebug` — must compile
- `./gradlew test` — existing tests must pass
- `./gradlew lint` — no new lint errors
- Manual: `./scripts/debug-run.sh "Open Settings"` on both ACCESSIBILITY
  and VIRTUAL_DISPLAY modes — same behavior as before

---

## One-liner

Extract shared node-action logic and app-management into two small utility
classes. Both platforms use them. Delete the VD-specific copy. Finish the
BitmapUtils migration. AccessibilityPlatform goes from 831 to ~450 lines.
No new abstractions, no inheritance, no framework — just less copy-paste.
