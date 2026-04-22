# Track B — UI Architecture Refactor + Semantic Naming (Claude initial draft)

**Author:** Claude (independent draft, pre-cross-review).
**Status:** Initial. Pairs with `codex_design.md` for cross-review and alignment.
**Scope:** `app/src/main/kotlin/ai/closepaw/ui/capsule/**`, `app/src/main/kotlin/ai/closepaw/ui/chat/**`.
**KISS rule:** the smallest decomposition that (a) replaces every `row1/row2/row3` name with a semantic one and (b) un-tangles the obvious knots in `SmartCapsuleSurface`. No new abstractions, no new state machines, no theming overhauls.

---

## 1. Audit — what's actually wrong

### 1.1 Capsule

| Sin | Where | Why it hurts |
|---|---|---|
| Positional names `Row1 / Row2 / Row3` are baked into composables, the spec, parameter names, comments, and a test | `SmartCapsuleSurfaceParts.kt:59,174,175,211,254`; `SmartCapsuleSurface.kt:65,121,135,150,162,236`; `CapsuleRenderSpec.kt:20,40,66,77,91,102,158,187,188,191,198,201`; `CapsuleRenderSpecTest.kt:42,51` | Names encode visual layout, not role. Reader can't predict what `Row2` means without reading the file; future appearance changes (e.g. moving the input above the action buttons) would invalidate the names without a real refactor. Item 2 of the parent note explicitly calls this out. |
| `SmartCapsuleSurface` is a god-composable | `SmartCapsuleSurface.kt:46–186` | One function: derives `renderSpec`, derives `navSpec`, owns the `inputText` state, computes `inputEnabled`, gates the dividers, applies the startup-error banner, *and* assembles the column. 5 axes of change. |
| `CapsuleRow2` is two responsibilities glued together | `SmartCapsuleSurfaceParts.kt:59–171` | Same composable contains the action-button cluster (Takeover/Resume/Done/Approve/Stop/Deny — left side) **and** the navigation cluster (Minimize/OpenApp/OpenViewer — right side). They are driven by separate specs (`ButtonsSpec` vs `NavSpec`) and respond to different events. |
| Mode-aware dispatch in the view layer | `SmartCapsuleSurfaceParts.kt:79–88, 116–125` | `primary.onClick` decides — based on `mode is CapsuleMode.Running / Takeover / WaitingForAction / WaitingForApproval` — which callback to invoke. The `CapsuleRenderSpec.ButtonSpec` exposes only label + enabled, so the renderer reaches back into `mode` to figure out semantics. |
| `SmartCapsuleSurfaceParts` is a dumping ground | filename | A "parts" file is a code smell — it means we knew the surface was too big but parked the overflow somewhere generic. |
| Docstring/comment leakage of positional names | `CapsuleRenderSpec.kt:28,31,39,187`; `SmartCapsuleSurface.kt:169` ("§1.4: VD viewer reachable via Row1 nav / island, not idle Row3") | Comments still teach future readers the bad vocabulary. |
| `CapsuleHostLayout.kt` (12 lines) holds `smartCapsuleHostPadding()` Modifier helper | `SmartCapsuleHostLayout.kt:1–12` | Fine in isolation but worth folding into `SmartCapsuleCompose.kt` to reduce file count. |

### 1.2 Chat

The chat side is in **much** better shape than the capsule side:
- `ChatEventReducer.kt` is well-factored, names are semantic, state machine matches the doc.
- `ChatScreen.kt` cleanly separates layout (drawer + scaffold), bottom-bar plumbing, and the message list.
- `MessageBubble.kt` already iterates `contentBlocks` chronologically — exactly the shape Track A's aligned spec asks for (`Trace*` + Final).

The only chat-side work for Track B:
- Confirm `ChatEventReducer` is ready to receive a future `ContentBlock.Thought` variant without restructuring (it is — see §4.3).
- A small naming/comment cleanup in `MessageBubble.kt:171–175` (a dangling "Could add a mini '...' indicator" TODO) and `ChatMessage.kt:36–50` (`content` and `actions` "backward compatibility" convenience getters that nothing in production reads — only tests; remove them).

That's it. Track B does **not** restructure chat. Track A's implement phase will add `ContentBlock.Thought` and the `ThoughtUpdate` reducer branch on top of the cleaned-up base.

---

## 2. Target module boundaries (semantic names)

The capsule visual is, top to bottom, four logical slots:

```
┌─ SmartCapsuleSurface (orchestrator) ──────────────────────────┐
│  StatusLine     ← dot + thought (formerly Row 1)              │
│  DetailBody     ← optional question / instruction / approval  │
│  ActionBar      ← agent-control + nav buttons (formerly Row 2)│
│  InputBar       ← text field + send button (formerly Row 3)   │
└────────────────────────────────────────────────────────────────┘
```

`ActionBar` internally has two clusters — **AgentControls** (left: Takeover/Resume/Done/Approve/Deny/Stop) and **NavControls** (right: Minimize/OpenApp/OpenViewer) — but they share a row layout, so they live in one composable that delegates to two sub-composables for the clusters. (See §3 for why this is one file, not two.)

### File layout (after refactor)

```
ui/capsule/
    SmartCapsuleCompose.kt          (main-app entry point + smartCapsuleHostPadding helper)
    NavAction.kt                    (the existing NavAction enum, hoisted from SmartCapsuleCompose.kt for cleanliness)
    surface/
        SmartCapsuleSurface.kt      (orchestrator only — no rendering details)
        CapsuleStatusLine.kt        (dot + thought)
        CapsuleDetailBody.kt        (expanded question/instruction/approval body)
        CapsuleActionBar.kt         (agent controls + nav controls in one row)
        CapsuleInputBar.kt          (TextField + send + autofocus + clear-on-mode-change)
        StartupErrorBanner.kt       (the in-line error banner)
```

`SmartCapsuleSurfaceParts.kt` is **deleted** — every composable inside it moves to a dedicated file with a name that says what it is.

### Spec rename

`CapsuleRenderSpec` becomes (field/type-name only — semantics preserved):

```kotlin
data class CapsuleRenderSpec(
    val dot: DotSpec?,                  // unchanged
    val thought: ThoughtSpec,           // unchanged (already semantic)
    val detail: String?,                // was: expandedBody
    val actions: ActionsSpec,           // was: buttons (ButtonsSpec)
    val input: InputSpec?,              // was: row3 (Row3Spec)
) {
    data class DotSpec(...)             // unchanged
    data class ThoughtSpec(...)         // unchanged
    data class ButtonSpec(...)          // unchanged (it's already semantic — a single button)
    data class ActionsSpec(             // was: ButtonsSpec — renamed to match field
        val primary: ButtonSpec?,
        val secondary: ButtonSpec? = null,
        val tertiary: ButtonSpec? = null,
        val stop: ButtonSpec?,
    )
    data class InputSpec(               // was: Row3Spec
        val hint: String,
        val buttonText: String,
        val clearInput: Boolean = false,
    )
}
```

Decisions and rejected alternatives:
- **Keep `ButtonSpec` as-is** — `Button` is already semantic (it's a button). No need for `AgentControlSpec` or similar.
- **Keep `primary / secondary / tertiary / stop` slot names.** They map to mode-aware dispatch (see §3.2). A name like `takeoverOrResume` lies for half the modes.
- **Reject `CapsuleRenderSpec.NavSpec` field**: nav visibility depends on context + platform + mode (NavSpec.from), not on mode alone, so it stays a separate spec. This was already correct; we keep it, just rename the internal `row2Hidden` → `actionsHidden`.

### Internal NavSpec rename

```kotlin
data class NavSpec(
    val showMinimize: Boolean,
    val showApp: Boolean,
    val showWatch: Boolean,
)
```
No rename needed at the type level — already semantic. Internally rename the local var `row2Hidden` → `actionsHidden` and update the comment.

---

## 3. The orchestrator after the cut

```kotlin
@Composable
fun SmartCapsuleSurface(... same params ...) {
    val renderSpec = remember(...) { CapsuleRenderSpec.from(mode, previousMode, isStopPending)
        .let { if (transientThought.isNullOrBlank()) it else it.copy(thought = ThoughtSpec(transientThought)) }
    }
    val navSpec = remember(...) { NavSpec.from(context, platformMode, hasIsland, mode) }
    val inputState = rememberCapsuleInputState(renderSpec.input, pendingInputText, onPendingInputConsumed)

    Surface(...) {
        Column(modifier = orchestratorPadding) {
            if (mode !is CapsuleMode.Hidden) {
                CapsuleStatusLine(renderSpec.dot, renderSpec.thought, onClick = onRow1Click)
                if (mode !is CapsuleMode.Done) {
                    renderSpec.detail?.let {
                        CapsuleDivider(); CapsuleDetailBody(it)
                    }
                    CapsuleDivider()
                    CapsuleActionBar(
                        actions = renderSpec.actions,
                        nav = navSpec,
                        mode = mode,
                        onTakeover, onResume, onStop, onDone, onApprovalResponse, onDismissError, onNavigate,
                    )
                }
            }
            renderSpec.input?.let { spec ->
                if (mode !is CapsuleMode.Hidden && mode !is CapsuleMode.Done) CapsuleDivider()
                if (startupError != null) StartupErrorBanner(startupError, onDismissStartupError, onStartupErrorClick)
                CapsuleInputBar(spec, inputState, mode, platformMode, context, onSend, onSupplement, onUserResponse, onInputFocusChanged, onInputSubmitted, autoFocusInput)
            }
        }
    }
}
```

### 3.1 What moved out

- `inputText` state, `clearInput` effect, `inputEnabled` computation, `pendingInputText` consumption → all into `rememberCapsuleInputState` + `CapsuleInputBar`. The orchestrator no longer touches input strings.
- `CapsuleDivider` stays in `SmartCapsuleSurface.kt` as a `private fun` — single use site per call, not worth its own file.
- The `onRow1Click` parameter is renamed to `onStatusLineClick` everywhere (including `CapsuleOverlayHost.kt:145`).

### 3.2 Why `CapsuleActionBar` is one composable, not two

Splitting AgentControls and NavControls into two separate composables that callers compose themselves looks tidier in the diagram but it's worse for KISS:
- They share one `Row(SpaceBetween)` layout — splitting forces duplicating that structural decision in the orchestrator.
- They share the "hide-when-Done" gate — already enforced by NavSpec.from but the orchestrator should not have to know that.
- A single `CapsuleActionBar(actions, nav, mode, ...)` keeps every callback in one place where the mode-aware dispatch lives.

Internally `CapsuleActionBar` calls two private composables, `AgentControls(actions, mode, ...)` and `NavControls(nav, onNavigate)`, in the same file. This file is the sole place where button click → callback dispatch happens.

### 3.3 Mode-aware dispatch — leave it in the view layer

The `primary` button does Takeover / Resume / Done / Approve depending on mode. Today this `when (mode)` lives in `CapsuleRow2`. Two options:
1. Move it into `CapsuleRenderSpec.ButtonSpec` as `onClick: () -> Unit`. Spec stops being pure data; cannot be tested without mock callbacks.
2. Keep it in the view layer.

KISS says **keep it in the view layer**. The spec must remain pure data (it's tested as such; the doc says "No business logic. No callbacks.") The dispatch lives in `AgentControls`, in one `when (mode)` block, with one comment explaining it.

---

## 4. Migration plan

Each step compiles and `./gradlew test` stays green.

**Step 1 — Spec rename + test update** (touches 3 files)
- `CapsuleRenderSpec`: rename `expandedBody → detail`, `buttons → actions` (type `ButtonsSpec → ActionsSpec`), `row3 → input` (type `Row3Spec → InputSpec`). Internal `row2Hidden → actionsHidden`, comment update.
- `SmartCapsuleSurface.kt` + `SmartCapsuleSurfaceParts.kt`: update field accessors only. Keep file/composable names for now.
- `CapsuleRenderSpecTest.kt`: `spec.row3?.clearInput` → `spec.input?.clearInput`.
- ✅ Build + test.

**Step 2 — Composable rename**
- `CapsuleRow1 → CapsuleStatusLine` (private function rename in `SmartCapsuleSurface.kt`).
- `CapsuleRow2 → CapsuleActionBar` (`SmartCapsuleSurfaceParts.kt`).
- `CapsuleRow3 → CapsuleInputBar` (`SmartCapsuleSurfaceParts.kt`).
- Parameter `onRow1Click → onStatusLineClick` (here and at `CapsuleOverlayHost.kt:145`).
- Parameter `row3Spec → inputSpec` inside `CapsuleInputBar`.
- Comment cleanup: search-and-rewrite all `Row 1 / Row 2 / Row 3` references.
- ✅ Build + test.

**Step 3 — Move composables to dedicated files**
- Extract `CapsuleStatusLine`, `CapsuleActionBar`, `CapsuleInputBar`, `StartupErrorBanner`, `CapsuleDetailBody` into one file each under `ui/capsule/surface/`.
- Inside `CapsuleActionBar.kt`, factor the right-side nav cluster into a private `NavControls` and the left-side action cluster into a private `AgentControls`.
- Delete `SmartCapsuleSurfaceParts.kt`.
- ✅ Build + test.

**Step 4 — Hoist input state**
- Add a `rememberCapsuleInputState(input: InputSpec?, pendingInputText: String, onConsumed: () -> Unit)` helper inside `CapsuleInputBar.kt`.
- Move `inputText` `mutableStateOf`, the `pendingInputText` `LaunchedEffect`, and the `clearInput` `LaunchedEffect` into it.
- `SmartCapsuleSurface` becomes the slim orchestrator from §3.
- ✅ Build + test (UI behavior identical — same effects, same triggers, just relocated).

**Step 5 — Fold `SmartCapsuleHostLayout.kt` into `SmartCapsuleCompose.kt`**
- 12-line file → top-level helper alongside `SmartCapsuleCompose`. Update the one importer (`ChatScreen.kt:45`).
- ✅ Build + test.

**Step 6 — Chat-side mini-cleanup** (defer to Track A implement if preferred — list anyway)
- Remove `Agent.content` and `Agent.actions` "backward compatibility" getters in `ChatMessage.kt:36–50` if no production reader uses them (verify by grep).
- Delete the dangling `// Could add a mini "..." indicator` comment in `MessageBubble.kt:171–175`.
- ✅ Build + test.

**No-op steps deliberately not in this plan:**
- Splitting `SmartCapsuleSurface` into MVI components.
- Introducing a `CapsuleRenderState` wrapper around `(mode, renderSpec, navSpec)`.
- Lifting `inputText` to a ViewModel.
- Renaming `CapsuleMode` states.
- Restructuring `ChatViewModel` / `ChatEventReducer`.

---

## 5. Track A accommodation

Track A's aligned spec specifies:
> Add `ContentBlock.Thought(text: String)` and route `ThoughtUpdate` into it via a new branch in `ChatEventReducer.handle`.

After the Track B refactor, the chat-side surfaces look like:
- `ChatMessage.Agent.contentBlocks: List<ContentBlock>` — already chronological, append-only.
- `ChatEventReducer.handle(...)` — single switch, easy to add a `ThoughtUpdate -> appendThoughtBlock()` branch.
- `MessageBubble.kt` `when (block)` — easy to add `is ContentBlock.Thought -> ThoughtTraceItem(...)`.

**Position:** Track B does **not** add `ContentBlock.Thought` itself. Track A's implement phase owns that change. Reasoning: keeping the two tracks' commit/test boundaries distinct is clearer than coupling them. Track B leaves the door visible (clean reducer, clean MessageBubble); Track A walks through it.

The four row states from Track A §5 (`Live / Waiting / Complete / Error`) are also Track A's implement responsibility.

---

## 6. Invariants from Track C — must not regress

From `doc/main/state_machines/ui_capsule.md`:
- `CapsuleMode` → `CapsuleRenderSpec` mapping is pure (preserved — only field names change).
- All events are total (preserved — orchestrator doesn't touch events).
- `previousMode` drives `clearInput` for `WaitingForInput` (preserved — moved to `rememberCapsuleInputState`).
- Auto-hide coroutine, `isStopPending` lifecycle, `onStopRequested` idempotency live in `CapsuleStateHolder` and are not touched.
- NavSpec rules (Done hides nav; minimize hides during Waiting/Error; etc.) preserved.
- Input routing per mode (Hidden → onSend, WaitingForInput → onUserResponse, else → onSupplement) — preserved verbatim in `CapsuleInputBar`.

From `doc/main/state_machines/ui_chat.md`:
- All chat invariants are about the reducer, which Track B does not change.

Test coverage that protects this:
- `CapsuleRenderSpecTest.kt` — exercises every spec branch; updated for the field rename in Step 1.
- `CapsuleStateHolderTest.kt`, `CapsuleApprovalTransitionTest.kt`, `NavSpecTest.kt`, `CapsuleModeTest.kt` — test the holder + nav spec, untouched by the refactor.
- `ChatEventReducerTest.kt` + 5 chat tests — chat side untouched.

`./gradlew test` is the green-bar gate after every step.

---

## 7. Out of scope (explicit)

- Theming / token redesign — visual styling preserved exactly.
- New states or mode renames — `CapsuleMode` untouched.
- ViewModel restructuring — input state stays composable-local.
- Adding `ContentBlock.Thought` — that's Track A.
- Lifting `CapsuleRenderSpec` into MVI / Redux pattern.
- Lifting button click dispatch into the spec (would force callbacks into pure data).
- Splitting the overlay host (`CapsuleOverlayHost.kt`) — only the renamed parameter call site touches it.
- Migrating to Material3's `BottomAppBar` or other built-in surfaces.

---

## 8. Acceptance check

- ✅ Every `Row1/Row2/Row3` and `row3/buttons/expandedBody` field/identifier replaced with semantic names (StatusLine, DetailBody, ActionBar, InputBar, detail, actions, input).
- ✅ `SmartCapsuleSurface` becomes a thin orchestrator (§3); rendering details live in named files.
- ✅ `SmartCapsuleSurfaceParts.kt` deleted.
- ✅ Track A's `ContentBlock.Thought` insertion remains a one-branch reducer change + one-branch MessageBubble change.
- ✅ Every test in `app/src/test/kotlin/ai/closepaw/ui/` still passes.
- ✅ No new state machines, no new abstractions.
