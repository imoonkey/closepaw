# AccessibilityPlatform Refactoring — Stage 3

Date: 2026-02-11
Status: Design proposal (updated after cross-review with Gemini design)

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

### 5. Root node leak in AccessibilityPlatform

`AccessibilityPlatform.performNodeClickAt()` (line 405) calls
`service.rootInActiveWindow` to get root but **never recycles it**.
Same for `performNodeLongClickAt`. The VD side always recycles root
because `VirtualDisplayNodeActionPerformer` does it in finally blocks.
This is a bug worth fixing structurally, not just patching.

### 6. `performWait()` — Trivial but duplicated

Both do `delay(action.durationMs)` and return `ActionResult.Success`.

---

## What's NOT Duplicated (Don't Unify)

- Screenshot capture: fundamentally different (a11y screenshot API vs ImageReader)
- Gesture dispatch: different transport (dispatchGesture vs Shizuku injection)
- System buttons: different APIs (globalAction vs KeyEvent injection)
- Lifecycle: different (no-op vs create/release display)
- Display info: different sources (DisplayMetrics vs VirtualDisplayConfig)
- A11y tree capture: similar but AccessibilityPlatform has trace recording

Don't try to unify these behind a shared abstraction. They're different
for hardware reasons. But gesture dispatch code should still be **extracted
out** of AccessibilityPlatform into its own class for structural parity
with VD's `VirtualDisplayInputInjector` (see Change 4 below).

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

### Change 4: `AccessibilityGestureInjector` — Extract gesture dispatch

> Borrowed from Gemini design. This is the right call.

AccessibilityPlatform has ~120 lines of gesture construction and dispatch:
`performTap`, `performSwipeGesture`, `performLongPressGesture`,
`dispatchGesture` (with timeout + callback), and `performSystemButton`
(globalAction). Extract these into `AccessibilityGestureInjector`.

This creates true structural parity with VD's `VirtualDisplayInputInjector`.
Both platforms now have a dedicated injector — they just use different
transports (dispatchGesture vs Shizuku injectInputEvent).

```kotlin
// platform/AccessibilityGestureInjector.kt
class AccessibilityGestureInjector(
    private val service: AccessibilityService,
    private val visualizer: ActionVisualizerManager? = null
) {
    companion object {
        private const val DEFAULT_GESTURE_DURATION_MS = 100L
        private const val GESTURE_TIMEOUT_MS = 5000L
    }

    suspend fun injectTap(x: Float, y: Float): ActionResult { ... }
    suspend fun injectSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): ActionResult { ... }
    suspend fun injectLongPress(x: Float, y: Float, durationMs: Long): ActionResult { ... }
    suspend fun injectSystemButton(button: SystemButtonType): ActionResult { ... }

    // Gesture dispatch with timeout (moved from AccessibilityPlatform)
    private suspend fun dispatchGesture(gesture: GestureDescription): ActionResult { ... }
}
```

Naming uses `inject*` to match VD's `VirtualDisplayInputInjector` API
names, even though the transport is dispatchGesture. The caller doesn't
care about the transport.

### Change 5: Delete `VirtualDisplayNodeActionPerformer`

Replaced by the shared `NodeActionPerformer`. The VD-specific
`clearInputFocusAfterSetText()` moves into the shared class (because the
behavior is correct for both platforms — fixes IME popup bug everywhere).

### Change 6: AccessibilityPlatform becomes a thin orchestrator

After extracting node actions, gesture dispatch, bitmap utils, and app
management, AccessibilityPlatform's remaining responsibilities:

```
AccessibilityPlatform (orchestrator, ~250 lines)
├── captureScreen()            — a11y tree + screenshot with trace recording
├── performAction()            — one-liner dispatch to nodeActionPerformer / gestureInjector
├── captureAccessibilityTree() — a11y tree + trace artifacts
├── captureScreenshotIfEnabled() — screenshot via a11y API + BitmapUtils
├── hasRequiredPermissions(), getCurrentPackageName(), getDisplayInfo()
├── getInstalledApps()         — delegates to AppManager
└── launchApp()                — intent-based launch
```

~250 lines is a proper orchestrator. You can read it in one sitting and
understand what the platform does without drowning in implementation detail.

---

## File Changes Summary

| File | Action | Lines (est.) |
|---|---|---|
| `platform/NodeActionPerformer.kt` | **NEW** | ~120 |
| `platform/AppManager.kt` | **NEW** | ~30 |
| `platform/AccessibilityGestureInjector.kt` | **NEW** | ~120 |
| `platform/AccessibilityPlatform.kt` | **MODIFY** — thin orchestrator | ~250 (from 831) |
| `platform/virtualdisplay/VirtualDisplayPlatform.kt` | **MODIFY** — use shared `NodeActionPerformer` + `AppManager` | ~310 (from 338) |
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

1. **AccessibilityPlatform drops from 831 to ~250 lines** — proper
   orchestrator size, readable in one sitting.

2. **Root node leak fixed structurally** — `NodeActionPerformer.withRoot`
   always recycles. No more ad-hoc leaks in individual action methods.

3. **Node action logic lives in one place** — `NodeActionPerformer`.
   Bug fixes apply to both platforms automatically. The IME focus-clear
   fix currently only in VD gets applied to accessibility mode too.

4. **App management logic lives in one place** — `AppManager`. Zero
   behavior change, just no more copy-paste.

5. **Both platforms now have symmetric structure:**
   ```
   AccessibilityPlatform (orchestrator, ~250 lines)
     ├── NodeActionPerformer           (shared)
     ├── AccessibilityGestureInjector  (a11y-specific)
     ├── AppManager                    (shared)
     ├── BitmapUtils                   (shared)
     └── AccessibilityNodeFinder       (shared, already was)

   VirtualDisplayPlatform (orchestrator, ~310 lines)
     ├── NodeActionPerformer           (shared)
     ├── VirtualDisplayInputInjector   (VD-specific)
     ├── AppManager                    (shared)
     ├── BitmapUtils                   (shared)
     ├── AccessibilityNodeFinder       (shared, already was)
     └── VirtualDisplayWindowAccessor  (VD-specific)
   ```

   The structural symmetry is now obvious: both have a shared
   `NodeActionPerformer` + a platform-specific injector + shared utilities.

6. **No abstraction overhead** — `NodeActionPerformer` is a plain class
   with a lambda constructor. `AppManager` is a stateless object.
   `AccessibilityGestureInjector` is a plain class. No interfaces, no
   inheritance, no dependency injection framework.

---

## What This Doesn't Do (Non-goals)

- **No `AbstractPlatform` base class.** That would just move code around
  without reducing complexity. The two platforms have different lifecycles,
  different capture mechanisms, and different gesture systems. A base class
  would either be too thin to be useful or too thick to be clean.

- **No shared screenshot pipeline.** The capture mechanisms are fundamentally
  different (a11y API callback vs ImageReader buffer copy). Both already
  converge on `BitmapUtils` for the final scale+compress step.

- **No shared gesture/injector interface.** `dispatchGesture` (a11y) and
  `injectInputEvent` (Shizuku) have different APIs, different error modes,
  and different threading models. A common interface would add indirection
  without value. They're both extracted into their own classes for
  readability, not for polymorphism.

- **No `AccessibilityWindowAccessor`.** Gemini's design proposes a
  5-line class wrapping `service.rootInActiveWindow`. That's a file for
  a one-liner. The lambda `rootProvider` in `NodeActionPerformer`
  handles this without the extra indirection.

- **No per-platform `NodeActionPerformer` copies.** Gemini creates
  separate `AccessibilityNodeActionPerformer` and keeps
  `VirtualDisplayNodeActionPerformer`. This re-duplicates the logic we're
  trying to deduplicate. One shared class with a lambda is simpler.

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

### Commit 1: Extract shared utilities

- Create `NodeActionPerformer.kt` — shared node action logic with
  `rootProvider` lambda, `withRoot` helper, and `clearInputFocusAfterSetText`.
  Include `performEnterKey` (moved from AccessibilityPlatform).
- Create `AppManager.kt` — extract identical `getInstalledApps` logic.
- Create `AccessibilityGestureInjector.kt` — move gesture construction,
  dispatch, timeout, visualizer calls, and systemButton (globalAction)
  out of AccessibilityPlatform.

### Commit 2: Rewire both platforms

- `AccessibilityPlatform`: construct `NodeActionPerformer` with
  `rootProvider = { service.rootInActiveWindow }`, construct
  `AccessibilityGestureInjector` with service + visualizer. Delete all
  private node action methods, all gesture methods. Delete private
  `scaleBitmapIfNeeded`/`compressJpeg`, use `BitmapUtils`. Delegate
  `getInstalledApps` to `AppManager`.

- `VirtualDisplayPlatform`: construct `NodeActionPerformer` with
  `rootProvider = { windowAccessor.getRootOnDisplay() }`. Update
  `performAction()` to use it. Delegate `getInstalledApps` to `AppManager`.

### Commit 3: Delete + clean up

- Delete `VirtualDisplayNodeActionPerformer.kt`
- Remove dead imports from both platforms
- Build, verify no regressions

---

## Verification

- `./gradlew clean assembleDebug` — must compile
- `./gradlew test` — existing tests must pass
- `./gradlew lint` — no new lint errors
- Manual: `./scripts/debug-run.sh "Open Settings"` on both ACCESSIBILITY
  and VIRTUAL_DISPLAY modes — same behavior as before

---

## Cross-review: Gemini Design

After reading `refactor_design_gemini.md`, incorporated and rejected:

| Gemini Idea | Verdict | Reason |
|---|---|---|
| Extract `AccessibilityInputInjector` | **Adopted** (as `AccessibilityGestureInjector`) | Creates true structural parity. Drops AP from ~450 to ~250 lines. Good call. |
| Root leak fix as first-class concern | **Adopted** | Valid bug. `withRoot` pattern fixes it structurally. |
| Separate `AccessibilityNodeActionPerformer` | **Rejected** | Re-duplicates `setTextOnNode` and all node action logic. Shared class with lambda is simpler. |
| `AccessibilityWindowAccessor` (5-line wrapper) | **Rejected** | Over-engineering. A lambda does the job without a new file. |
| `AppQueryUtils` name | **Rejected** (naming only) | `AppManager` is clearer. Same concept. |

---

## One-liner

Extract shared node-action logic, app management, and gesture dispatch into
focused utility classes. Both platforms use the shared ones, each has a
platform-specific injector. Delete the VD-specific copy. Finish the
BitmapUtils migration. Fix root node leaks structurally.
AccessibilityPlatform goes from 831 to ~250 lines.
No new abstractions, no inheritance, no framework — just less copy-paste
and symmetric structure.
