# Track B — UI Architecture Refactor + Semantic Naming (Aligned Final Eng Spec)

**Authors:** Claude + Codex (cross-reviewed and aligned)
**Date:** 2026-04-20
**Status:** Final. Hand-off: implementation in this same Track B PR; Track A consumes the cleaned chat surface in its own implement phase.
**Scope:** `app/src/main/kotlin/ai/closepaw/ui/capsule/**`, `app/src/main/kotlin/ai/closepaw/ui/chat/**`.

> **KISS rule applied throughout:** the aligned spec is the simplest decomposition that (a) replaces every `Row1/Row2/Row3` positional name with a semantic one and (b) un-tangles the obvious knot inside `SmartCapsuleSurface`. Nothing more. Both reviewers explicitly rejected: `ChatTurnRenderSpec`, MVI/redux wrappers, theming overhauls, public action/nav split, lifting submit routing into `CapsuleInputBar`, eager addition of `ContentBlock.Thought`, and any state-machine churn.

---

## 1. Problem (one paragraph)

Items 2 and 3 of `doc/todo/frontend-ui-review/eng-design/note.md`. The capsule UI uses positional names — `Row1`, `Row2`, `Row3`, `row3Spec`, `row2Hidden`, `onRow1Click`, "Row2-R nav buttons" — across the spec, composables, parameter names, comments, and one test. Names that encode *where* a thing sits, not *what* it is. On top of that, `SmartCapsuleSurface` is a god-composable that owns layout, transient input state, divider gating, the startup-error banner, and submit-intent routing all at once. The fix: rename the positional terms with semantic counterparts, lightly extract the input-state ownership, and delete the pass-through `SmartCapsuleCompose.kt` wrapper. No new abstractions; no behavior change; every Track C state-machine test stays green.

---

## 2. Audit — concrete sins (file:line)

### 2.1 Positional naming (the stated sin)

| Sin | Site |
|---|---|
| `val row3: Row3Spec?` field on the spec | `CapsuleRenderSpec.kt:20` |
| `data class Row3Spec(hint, buttonText, clearInput)` nested type | `CapsuleRenderSpec.kt:40` |
| `val row2Hidden = mode is CapsuleMode.Done` local in `NavSpec.from` | `CapsuleRenderSpec.kt:188` |
| `onRow1Click: (() -> Unit)?` public composable parameter | `SmartCapsuleSurface.kt:65` |
| `LaunchedEffect(renderSpec.row3?.clearInput) { ... }` | `SmartCapsuleSurface.kt:95–99` |
| `CapsuleRow1`, `CapsuleRow2`, `CapsuleRow3` private composables | `SmartCapsuleSurface.kt:121,135,162,236`; `SmartCapsuleSurfaceParts.kt:59,174` |
| `row3Spec: CapsuleRenderSpec.Row3Spec` parameter | `SmartCapsuleSurfaceParts.kt:175` |
| Tests that probe the positional field | `CapsuleRenderSpecTest.kt:42,51` |
| Comments still teaching the bad vocabulary | `CapsuleRenderSpec.kt:28,31,39,187`; `SmartCapsuleSurface.kt:169` |
| `androidTest` comment refs to "Row1/Row2/Row3" as visual regions | `CapsuleRenderingTest.kt:19–43` |

### 2.2 Spaghetti (the unstated sin worth fixing in the same pass)

- **`SmartCapsuleSurface.kt:46–186`** — one composable owns: derived `renderSpec`, derived `navSpec`, `inputText` state, three `LaunchedEffect`s for input lifecycle, `inputEnabled` predicate, divider gating, startup-error banner placement, and submit-intent routing. Five axes of change in one body.
- **`CapsuleRow2` (`SmartCapsuleSurfaceParts.kt:59–171`)** — left-cluster action buttons (Takeover/Resume/Done/Allow/Session/Always/Stop/Deny/Close) and right-cluster nav icons (Minimize/OpenApp/OpenViewer) live in one body. They are driven by separate specs (`ButtonsSpec` vs `NavSpec`) and respond to different events.
- **`SmartCapsuleCompose.kt`** — pure pass-through wrapper, every parameter forwarded one-for-one to `SmartCapsuleSurface`. Indirection earns nothing.
- **`SmartCapsuleSurfaceParts.kt`** — a "parts" filename is itself a smell: an admission that the surface was too big and the overflow was parked somewhere generic.

### 2.3 Chat side (verdict: don't touch)

The chat surface is in good shape: the reducer is well-factored, names are semantic, the state machine matches the doc, `MessageBubble` already iterates `contentBlocks` chronologically (which is exactly what Track A's aligned spec asks for). Track B's chat-side change set is **empty** — Track A will own the `ContentBlock.Thought` addition in its own implement phase, on top of the cleaned capsule surface.

---

## 3. Target module boundaries (semantic names)

The capsule renders, top to bottom, as four logical slots. After the refactor:

```
┌─ SmartCapsuleSurface (orchestrator)─────────────────────────┐
│  CapsuleStatusLine    ← dot + thought                       │
│  (DetailBody)         ← optional question/instruction text  │
│  CapsuleControlBar    ← action cluster + nav cluster        │
│  CapsuleInputBar      ← text field + send                   │
└──────────────────────────────────────────────────────────────┘
```

### 3.1 Composables

| Old | New | Lives in |
|---|---|---|
| `CapsuleRow1` (private fn) | `CapsuleStatusLine` | `surface/SmartCapsuleSurface.kt` (private — it's tiny) |
| `CapsuleRow2` | `CapsuleControlBar` | `surface/CapsuleControlBar.kt` (renamed from `SmartCapsuleSurfaceParts.kt`) |
| `CapsuleRow3` | `CapsuleInputBar` | `surface/CapsuleInputBar.kt` (new file; pulls input-state ownership out of the orchestrator) |
| (none — was inline `Text`) | `CapsuleDetailBody` (private) | `surface/SmartCapsuleSurface.kt` |
| `StartupErrorBanner` (private fn) | unchanged | `surface/SmartCapsuleSurface.kt` |

Inside `CapsuleControlBar.kt`, the left/right clusters become two **private** helpers, not public composables:

```kotlin
@Composable
internal fun CapsuleControlBar(
    actions: CapsuleRenderSpec.ButtonsSpec,
    nav: NavSpec,
    mode: CapsuleMode,
    /* same callbacks as today */
) {
    Row(Arrangement.SpaceBetween, ...) {
        ActionButtonCluster(actions, mode, ...)
        NavButtonCluster(nav, onNavigate)
    }
}
```

**Why one composable, not two public ones.** They share one `Row(SpaceBetween)` layout, both hide together when mode is `Done`, and the orchestrator doesn't need to know they're two clusters. Splitting would force the orchestrator to compose two siblings inside its own `Row`, leaking layout. The split as private helpers gives the readability win without the structural cost.

### 3.2 Files

After the refactor:

```
ui/capsule/
    SmartCapsuleCompose.kt          [DELETED]
    surface/
        SmartCapsuleSurface.kt      (slim orchestrator + private CapsuleStatusLine, CapsuleDetailBody, StartupErrorBanner, CapsuleDivider)
        CapsuleControlBar.kt        (renamed from SmartCapsuleSurfaceParts.kt; contains CapsuleControlBar + private ActionButtonCluster + NavButtonCluster + ApprovalScopeButton + NavIconButton + icon helpers; also hosts the NavAction enum)
        CapsuleInputBar.kt          (new; contains CapsuleInputBar + rememberCapsuleInputState)
        SmartCapsuleHostLayout.kt   (unchanged — 12 lines; moving it would touch imports for no real benefit)
```

`SmartCapsuleCompose.kt` is deleted; `ChatScreen.kt:146` calls `SmartCapsuleSurface` directly. The `NavAction` enum that lived in that file moves to the top of `CapsuleControlBar.kt` (where it is consumed as a parameter type).

### 3.3 `CapsuleRenderSpec` rename

```kotlin
data class CapsuleRenderSpec(
    val dot: DotSpec?,                 // unchanged
    val thought: ThoughtSpec,          // unchanged (already semantic)
    val expandedBody: String?,         // unchanged (already semantic)
    val buttons: ButtonsSpec,          // unchanged (a single button spec is already semantic)
    val input: InputSpec?,             // was: row3: Row3Spec?
) {
    data class DotSpec(...)            // unchanged
    data class ThoughtSpec(...)        // unchanged
    data class ButtonSpec(...)         // unchanged
    data class ButtonsSpec(...)        // unchanged
    data class InputSpec(              // was: Row3Spec
        val hint: String,
        val submitLabel: String,       // was: buttonText
        val clearDraft: Boolean = false,// was: clearInput
    )
}
```

**Renames we adopt** (Codex was right, KISS):
- `row3 → input`, `Row3Spec → InputSpec` — the field models the input bar.
- `buttonText → submitLabel` — `Label` is the standard a11y term for a button caption.
- `clearInput → clearDraft` — the field models "wipe the user's in-progress draft," not "clear the input widget."
- Local `row2Hidden → controlBarHidden` inside `NavSpec.from`.

**Renames we explicitly reject** (Claude proposed, Codex pushed back, alignment kept Codex's call):
- `expandedBody → detail` — `expandedBody` is already semantic; the rename is churn.
- `buttons → actions` (`ButtonsSpec → ActionsSpec`) — `buttons` is unambiguous and accurate; `actions` would also be fine but the rename is cosmetic, not corrective.
- `primary/secondary/tertiary/stop` slot names — they map to mode-aware dispatch (a name like `takeover` would lie for half the modes). Keep.

### 3.4 Public parameter rename

`SmartCapsuleSurface.onRow1Click` → `onStatusClick`. Touches `CapsuleOverlayHost.kt:145` and (until step 6) `SmartCapsuleCompose.kt`.

---

## 4. Where the spaghetti gets fixed (orchestrator after the cut)

```kotlin
@Composable
fun SmartCapsuleSurface(... same params, with onRow1Click → onStatusClick ...) {
    val renderSpec = remember(mode, isStopPending, previousMode, transientThought) {
        val base = CapsuleRenderSpec.from(mode, previousMode, isStopPending)
        if (transientThought.isNullOrBlank()) base else base.copy(thought = ThoughtSpec(transientThought))
    }
    val navSpec = remember(context, platformMode, mode, hasIsland) {
        NavSpec.from(context, platformMode, hasIsland = hasIsland, mode = mode)
    }

    Surface(...) {
        Column(orchestratorPadding) {
            if (mode !is CapsuleMode.Hidden) {
                CapsuleStatusLine(renderSpec.dot, renderSpec.thought, onClick = onStatusClick)
                if (mode !is CapsuleMode.Done) {
                    renderSpec.expandedBody?.let { CapsuleDivider(); CapsuleDetailBody(it) }
                    CapsuleDivider()
                    CapsuleControlBar(
                        actions = renderSpec.buttons,
                        nav = navSpec,
                        mode = mode,
                        onTakeover, onResume, onStop,
                        onDone = { callId -> onUserResponse(callId, "done") },
                        onApprovalResponse, onDismissError, onNavigate,
                    )
                }
            }
            renderSpec.input?.let { input ->
                if (mode !is CapsuleMode.Hidden && mode !is CapsuleMode.Done) CapsuleDivider()
                if (startupError != null) StartupErrorBanner(startupError, onDismissStartupError, onStartupErrorClick)
                CapsuleInputBar(
                    spec = input,
                    mode = mode,
                    platformMode = platformMode,
                    context = context,
                    pendingInputText = pendingInputText,
                    onPendingInputConsumed = onPendingInputConsumed,
                    autoFocusInput = autoFocusInput,
                    onInputFocusChanged = onInputFocusChanged,
                    onSubmit = { text ->
                        when (mode) {
                            is CapsuleMode.Hidden -> onSend(text)
                            is CapsuleMode.WaitingForInput -> onUserResponse(mode.callId, text)
                            else -> onSupplement(text)
                        }
                        onInputSubmitted()
                    },
                )
            }
        }
    }
}
```

### 4.1 What moved out of the orchestrator

- `inputText` state, the `pendingInputText` `LaunchedEffect`, the `clearInput`/`clearDraft` `LaunchedEffect`, and the `inputEnabled` predicate all move into `CapsuleInputBar.kt`, owned by a `rememberCapsuleInputState(spec, mode, platformMode, context, pendingInputText, onPendingInputConsumed)` helper. The orchestrator no longer touches input strings.

### 4.2 What stays in the orchestrator (deliberate)

- **Submit-intent routing** (`Hidden → onSend`, `WaitingForInput → onUserResponse(callId)`, else → `onSupplement`) stays at the surface level. The bar gets a single `onSubmit(text: String)` callback. Reason: routing depends on `mode`, which the bar should not need to know about.
- **Mode-aware button dispatch** inside `CapsuleControlBar` (the `when (mode)` deciding what `primary` actually does) stays in the view layer. Pulling it into `ButtonSpec.onClick` would force callbacks into pure data and break `CapsuleRenderSpecTest`.
- `transientThought` stays a `SmartCapsuleSurface` parameter (it's overlay-only ambient text).

### 4.3 Why the rename of files matters

`SmartCapsuleSurfaceParts.kt` is renamed to `CapsuleControlBar.kt` because, after the input bar moves out and after `CapsuleStatusLine` stays inline in the surface, the file's *only* exported composable is `CapsuleControlBar`. The "parts" name no longer has anything to be parts of.

---

## 5. Migration plan

Each step compiles. `./gradlew test` stays green at every step. One commit per step (`refactor(capsule): <step>`) so a bisect can isolate any visual regression.

**Step 1 — Spec rename.** `CapsuleRenderSpec`: `row3 → input`, `Row3Spec → InputSpec`, `buttonText → submitLabel`, `clearInput → clearDraft`. Local `row2Hidden → controlBarHidden` inside `NavSpec.from`. Update field access in `SmartCapsuleSurface.kt` and `SmartCapsuleSurfaceParts.kt`. Update `CapsuleRenderSpecTest.kt:42,51` (`spec.row3?.clearInput` → `spec.input?.clearDraft`). Update doc comments in `CapsuleRenderSpec.kt:28,31,39,187`. ✅ Build + test.

**Step 2 — Composable rename.** `CapsuleRow1 → CapsuleStatusLine`, `CapsuleRow2 → CapsuleControlBar`, `CapsuleRow3 → CapsuleInputBar` (private function renames in their existing files). Parameter `row3Spec → inputSpec` inside the new `CapsuleInputBar`. Public parameter `onRow1Click → onStatusClick` (touches `SmartCapsuleSurface.kt:65`, the inner pass-through `SmartCapsuleCompose.kt`, and `CapsuleOverlayHost.kt:145`). Comment cleanup: search-and-rewrite all `Row 1 / Row 2 / Row 3` references. Update `androidTest/CapsuleRenderingTest.kt` comments. ✅ Build + test.

**Step 3 — Hoist input state into `CapsuleInputBar.kt`.** Create `surface/CapsuleInputBar.kt`. Move `CapsuleInputBar` (formerly `CapsuleRow3`) plus a new `rememberCapsuleInputState(spec, mode, platformMode, context, pendingInputText, onPendingInputConsumed)` helper into it. The helper owns: `inputText` `mutableStateOf`, the `pendingInputText` `LaunchedEffect`, the `clearDraft` `LaunchedEffect`, and the `inputEnabled` predicate. The orchestrator passes only `onSubmit(text: String)` plus the original parameters; routing logic stays at the surface level. ✅ Build + test.

**Step 4 — File rename + reorganise.** Rename `surface/SmartCapsuleSurfaceParts.kt → surface/CapsuleControlBar.kt`. Inside, extract two private helpers `ActionButtonCluster(actions, mode, ...)` and `NavButtonCluster(nav, onNavigate)` from the existing two `Row(...)` blocks. Move the `NavAction` enum here from `SmartCapsuleCompose.kt` (it's used as a parameter type by `NavButtonCluster.onNavigate`). ✅ Build + test.

**Step 5 — Delete the pass-through wrapper.** Audit `grep SmartCapsuleCompose app/src` to confirm `ChatScreen.kt:146` is the only caller. Update `ChatScreen.kt` to call `SmartCapsuleSurface` directly (one import change, identical parameter list). Delete `ui/capsule/SmartCapsuleCompose.kt`. ✅ Build + test + lint.

**Step 6 — Final scrub.** Final `grep -r "row1\|row2\|row3\|Row1\|Row2\|Row3" app/src/main/kotlin/ai/closepaw/ui/` returns zero hits. Doc updates per §7. ✅ Build + test + lint.

**Steps deliberately NOT in this plan:**
- Splitting `CapsuleRenderSpec` into MVI components.
- Lifting `inputText` into a ViewModel.
- Renaming `expandedBody`, `buttons`, `ButtonsSpec`, `primary/secondary/tertiary/stop`, `transientThought`, `pendingInputText`, `startupError`, or any `CapsuleMode` state.
- Adding `ContentBlock.Thought` or any chat-side change.
- Removing the `AgentService.instance` singleton coupling in `ChatScreen.kt`.
- Inlining `SmartCapsuleHostLayout.kt`.
- Removing the chat `Agent.content` / `Agent.actions` "convenience getters" in `ChatMessage.kt`.

---

## 6. Track A accommodation

Track A's aligned spec lands as **two diffs**, not a refactor:
1. `ChatMessage.kt`: add `data class Thought(val text: String) : ContentBlock`.
2. `ChatEventReducer.handle`: add `is ThoughtUpdate -> handleThoughtUpdate(event)` plus a small `appendThoughtBlock` helper analogous to `handleActionProposed`.
3. `MessageBubble.AgentBubble` `when (block)`: add `is ContentBlock.Thought -> ThoughtTraceItem(...)`.

**Position:** Track B does **not** ship those changes. Reasons:
- Adding the variant without the producer event leaves the renderer branch untested; adding the producer without the renderer drops it on the floor.
- Track A explicitly defers the `ChatTurnRenderSpec` decision to Track B. **We reject it.** The capsule's `CapsuleRenderSpec` exists because two renderers (overlay + in-app) share it. The chat has one renderer; the reducer is the right seam. Adding a parallel `RenderSpec` would be premature abstraction.
- Track B keeps the chat-screen wiring untouched, so Track A's diff lands cleanly with zero merge conflict against Track B.

The four row states from Track A §5 (`Live / Waiting / Complete / Error`) are similarly Track A's implement responsibility (they collapse into the existing `AgentMessageState` shape, with a small extension for `Waiting`).

---

## 7. Invariants from Track C — must not regress

From `doc/main/state_machines/ui_capsule.md`:

1. **`CapsuleMode` drives every pixel.** No new state field. `transientThought` stays a `SmartCapsuleSurface` parameter — do not bake it into `InputSpec`.
2. **`CapsuleRenderSpec.from(mode, previousMode, isStopPending)` is pure.** Step 1 only changes member names; signature and truth-table preserved.
3. **`InputSpec.clearDraft = previousMode != null && previousMode !is WaitingForInput`** — preserved verbatim from today's `clearInput`. This is the only guard preventing a stale draft leaking into a Q&A response.
4. **Submit routing** — `Hidden → onSend`, `WaitingForInput → onUserResponse(callId)`, else → `onSupplement`. Stays in `SmartCapsuleSurface`. The bar stays a dumb widget that calls back `onSubmit(text: String)`.
5. **Input enabled-ness** — `MAIN_APP || (not ACCESSIBILITY in Running/TakeoverPending)`. Same rule, moved into `rememberCapsuleInputState`.
6. **NavSpec hides the entire control bar in `Done` mode.** Predicate stays the same; just the local rename.
7. **All events are total** — orchestrator doesn't touch events.
8. **Auto-hide coroutine, `isStopPending` lifecycle, `onStopRequested` idempotency** — all live in `CapsuleStateHolder`, untouched.

From `doc/main/state_machines/ui_chat.md`:

9. **Append-only timeline; at-most-one open agent message.** No structural change here. Track A's `Thought` block must append, never mutate prior — flagged in Track A handoff.
10. **All reducer mutations under `stateLock`.** Track A's `handleThoughtUpdate` will inherit this for free via the existing `synchronized(stateLock)` wrapper.

### Test coverage that protects this

- `CapsuleRenderSpecTest` — exercises every spec branch; updated in Step 1.
- `CapsuleStateHolderTest`, `CapsuleApprovalTransitionTest`, `NavSpecTest`, `CapsuleModeTest` — test the holder + nav spec, untouched by this refactor.
- `ChatEventReducerTest`, `ChatSupplementAndActionTransitionTest`, `ChatActionExecutionMappingTest`, `ChatCompletionMessageTest`, `ChatCompletionSummaryTest`, `ChatViewModelTest`, `ChatRebindEventFilterTest`, `ChatStartupFailureTest`, `ChatSessionHistoryControllerTest` — chat side untouched.
- `androidTest/CapsuleRenderingTest.kt` — comment refresh in Step 2 (no assertion change). This is the catch-net for accidental visual regression from the Step 3 input-state extraction (Compose recomposition-scope drift).

`./gradlew test` is the green-bar gate after every step. `./gradlew lint` runs after Step 5 and 6.

---

## 8. Risks

- **Step 3 — Compose recomposition scope drift.** Moving `inputText` from the surface body into `rememberCapsuleInputState` changes the recomposition scope for the input subtree. Mitigation: keep state declarations identical (`mutableStateOf("")`, identical `LaunchedEffect` keys), run `androidTest/CapsuleRenderingTest.kt` after the step.
- **Step 5 — Hidden caller of `SmartCapsuleCompose`.** Audit `grep SmartCapsuleCompose app/src` first; redirect each hit before deletion.
- **Step 1 — Test method visibility.** `CapsuleRenderSpecTest` is the only test that probes the renamed fields. Two assertion lines change.

---

## 9. Out of scope (explicit, KISS)

- No `ChatTurnRenderSpec`. Chat has one renderer; the reducer is the seam.
- No new state machine. Track C's machines are preserved bit-for-bit.
- No theming overhaul, no design tokens introduced.
- No MVI / Redux wrapper around `CapsuleStateHolder`.
- No public split of `CapsuleControlBar` into `CapsuleActionBar` + `CapsuleNavBar`. Two private helpers inside one file.
- No removal of the `AgentService.instance` singleton coupling in `ChatScreen.kt:84` — separate concern.
- No rename of `expandedBody`, `buttons`, `ButtonsSpec`, `primary/secondary/tertiary/stop`, `transientThought`, `pendingInputText`, `startupError` — already semantic.
- No `ContentBlock.Thought` in this PR.
- No chat-side cleanup of `Agent.content` / `Agent.actions` convenience getters.
- No inlining of `SmartCapsuleHostLayout.kt`.

---

## 10. Acceptance check

- ✅ Every `Row1/Row2/Row3` and `row3` / `buttonText` / `clearInput` / `row2Hidden` identifier replaced with semantic names.
- ✅ `SmartCapsuleSurface` becomes a thin orchestrator (§4); rendering details and input-state ownership live in named files.
- ✅ `SmartCapsuleSurfaceParts.kt` renamed to `CapsuleControlBar.kt` (no more "parts" file).
- ✅ `SmartCapsuleCompose.kt` deleted; `ChatScreen` calls `SmartCapsuleSurface` directly.
- ✅ Track A's `ContentBlock.Thought` insertion remains a one-branch reducer change + one-branch `MessageBubble` change, with no merge conflict against Track B.
- ✅ Every test in `app/src/test/kotlin/ai/closepaw/ui/` still passes; one rename in `CapsuleRenderSpecTest`.
- ✅ No new state machines, no new abstractions, no theming churn.

---

## 11. Hand-off

**Implementation:** proceeds in this same Track B PR per the 6-step plan in §5.

**Track A implement** consumes:
- A clean reducer with no positional baggage — `handle(ThoughtUpdate)` slots in next to `handleActionProposed`.
- A clean `MessageBubble` whose `when (block)` is ready to grow a `Thought` arm.
- An invariant: `ContentBlock.Thought` must append to `contentBlocks`, not mutate prior — Track C `ChatEventReducerTest` expects append-only.

**Doc sync** (per `/update-doc` after implementation):
- `doc/main/state_machines/ui_capsule.md`: §"Render derivation" mentions `row3` field — update to `input`. §"Input behavior" still describes the "Row 3 input"; switch to "input bar". §"Owner code" references `CapsuleRenderSpec` types — no change needed.
- `doc/todo/frontend-ui-review/eng-design/note.md` Track B status block: mark complete with link to this file.
