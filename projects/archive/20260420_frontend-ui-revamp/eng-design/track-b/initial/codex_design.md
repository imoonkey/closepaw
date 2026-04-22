# Track B — UI Architecture Refactor + Semantic Naming (Codex initial design)

**Author:** Codex (independent draft, no Claude track-b read)
**Date:** 2026-04-20
**Scope:** rename positional `Row1/Row2/Row3` naming and de-spaghetti the capsule + chat surfaces. Tests stay green at every step.

---

## 1. Audit — concrete sins

### Capsule surface

- `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpec.kt:20` — `val row3: Row3Spec?`. Positional name leaks into every consumer; the field actually models the **input bar**.
- Same file, `:40` — nested type `Row3Spec(hint, buttonText, clearInput)`. `buttonText` is also positional ("the text on the row's send button"); should be `submitLabel`.
- Same file, `:188` — `val row2Hidden = mode is CapsuleMode.Done` inside `NavSpec.from`. The variable is a private local but it encodes the same positional concept and propagates through three guards.
- `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/SmartCapsuleSurface.kt:65` — public composable parameter `onRow1Click: (() -> Unit)?`. Callers (`CapsuleOverlayHost.kt:145`) bind it to "open app from a non-accessibility platform"; nothing to do with row indices.
- `SmartCapsuleSurface.kt:95` — `LaunchedEffect(renderSpec.row3?.clearInput)` couples a cross-cutting "reset draft" concern to a positional field name.
- `SmartCapsuleSurface.kt:108-186` — single 80-line composable owns: surface chrome, status line, optional expanded body inline `Text`, divider logic, button row, startup-error banner, input row composition, submit routing (`Hidden→onSend`, `WaitingForInput→onUserResponse`, else→`onSupplement`). Three responsibilities tangled: layout, transient input state, and submit-intent routing.
- `SmartCapsuleSurface.kt:121` `CapsuleRow1`, `:135` `CapsuleRow2`, `:162` `CapsuleRow3` — the three private composables in this file plus their twins in `SmartCapsuleSurfaceParts.kt:59,174` are named purely by row index. Their actual semantics are: status line, action/nav bar, input bar.
- `SmartCapsuleSurfaceParts.kt:59-171` — `CapsuleRow2` mixes left-cluster action buttons (`Takeover/Resume/Done/Allow/Session/Always/Stop/Deny/Close`) with right-cluster nav icons (`Minimize/OpenApp/OpenViewer`). Two distinct concerns (action vs. nav) sharing a single composable purely because they sit on the same row.
- `SmartCapsuleSurfaceParts.kt:174` — `CapsuleRow3` parameter `row3Spec: CapsuleRenderSpec.Row3Spec` propagates the positional name into a leaf widget.
- `SmartCapsuleCompose.kt` — pure pass-through wrapper (60 lines, every parameter forwarded one-for-one to `SmartCapsuleSurface`). Indirection earns nothing and obscures the call graph; it exists only because the composable used to be the outer entry point. Candidate for deletion.
- `SmartCapsuleHostLayout.kt` — exists solely to host one extension function `Modifier.smartCapsuleHostPadding()`. Not spaghetti per se, but does not need its own file.

### Chat surface

- `ChatScreen.kt:84-90` — six-line `stateHolder?.X ?: fallbackX` chain to reach `CapsuleStateHolder` via the `AgentService` singleton. Coupling the chat composable to `AgentService.instance` is a god-singleton smell, but out of scope for Track B (touch only if the rename forces it).
- `ChatScreen.kt:243-262` — bespoke `scrollKey` derivation. Works, but encodes a lot of business logic in a UI-side hash. Leave it for now; not a naming sin.
- `MessageBubble.kt:99-190` — `AgentBubble` knows about `AgentMessageState`, `ContentBlock.Text` vs `Action`, streaming-cursor placement, and timestamp visibility — all in one body. Will need a third branch (`Thought`) when Track A lands. The current shape is fine; the refactor is small.
- `ChatEventReducer.kt` — clean. No rename needed.

---

## 2. Target module boundaries (semantic names)

Goal: **strictly fewer composables than today**. After the refactor: 4 visible composables in the capsule package.

```
SmartCapsuleSurface (entry, was the same)
 ├── CapsuleStatusLine     (was CapsuleRow1)        — dot + thought
 ├── CapsuleControlBar     (was CapsuleRow2)        — primary/secondary/tertiary/stop + nav cluster
 │     ├── (private) ActionButtonCluster            — left side, mode-driven semantics
 │     └── (private) NavButtonCluster               — right side, NavSpec-driven
 └── CapsuleInputBar       (was CapsuleRow3)        — text field + submit
```

**Key decisions:**

- Drop `SmartCapsuleCompose.kt` entirely. Callers (`ChatScreen.kt:146`) call `SmartCapsuleSurface` directly. The wrapper buys nothing; the rename is the right time to remove it.
- Keep `CapsuleControlBar` as one composable. The left/right split is an internal layout detail; both clusters are mode/nav-driven and rendered together. Splitting would force callers to pass two sibling slots and would violate KISS. Two `private` helpers inside the same file is enough.
- Keep `Modifier.smartCapsuleHostPadding()` but inline it into a top-level file under `ui/capsule/` — delete `SmartCapsuleHostLayout.kt` as a standalone file (optional).
- Rename `onRow1Click` → `onStatusClick` on `SmartCapsuleSurface`.
- `transientThought` parameter — already semantic, leave alone.

### Chat side

The Track A handoff is "add `ContentBlock.Thought`, route `ThoughtUpdate` into it." That is **two diffs**, not a refactor. No new module boundary. The existing `ContentBlock` sealed interface is the right seam; do not introduce a `ChatTurnRenderSpec`.

---

## 3. CapsuleRenderSpec field rename

```kotlin
data class CapsuleRenderSpec(
    val dot: DotSpec?,
    val thought: ThoughtSpec,        // unchanged
    val expandedBody: String?,       // unchanged (already semantic)
    val buttons: ButtonsSpec,        // unchanged
    val input: InputSpec?,           // was: row3: Row3Spec?
) {
    data class InputSpec(            // was: Row3Spec
        val hint: String,
        val submitLabel: String,     // was: buttonText
        val clearDraft: Boolean = false,  // was: clearInput
    )
}
```

**Why these names:**
- `input` over `inputBar` — the spec is data, not a widget. Bar lives in the composable name.
- `submitLabel` over `buttonText` — `Label` is the standard a11y term for a button caption.
- `clearDraft` over `clearInput` — the field models "wipe the user's in-progress draft," not "clear the input widget."

Local rename inside `NavSpec.from`: `row2Hidden` → `controlBarHidden`.

**Test impact:** `CapsuleRenderSpecTest.kt:42,51` reference `spec.row3?.clearInput`. They become `spec.input?.clearDraft`.

---

## 4. Migration plan (each step compiles + tests green)

**Step 1** — Rename `CapsuleRenderSpec.Row3Spec → InputSpec`, field `row3 → input`, fields `buttonText → submitLabel`, `clearInput → clearDraft`. Updates `CapsuleRenderSpec.kt`, `SmartCapsuleSurface.kt`, `SmartCapsuleSurfaceParts.kt`, `CapsuleRenderSpecTest.kt`.

**Step 2** — Rename `NavSpec.from` local `row2Hidden → controlBarHidden`. One file.

**Step 3** — Rename composables: `CapsuleRow1 → CapsuleStatusLine`, `CapsuleRow2 → CapsuleControlBar`, `CapsuleRow3 → CapsuleInputBar`. Update `androidTest/CapsuleRenderingTest.kt` comments.

**Step 4** — Rename `onRow1Click → onStatusClick` parameter. Three sites.

**Step 5** — Inside `CapsuleControlBar`, extract two private composables `ActionButtonCluster` and `NavButtonCluster` from the existing two `Row(...)` blocks. No behavior change.

**Step 6** — Delete `SmartCapsuleCompose.kt`; `ChatScreen.kt` calls `SmartCapsuleSurface` directly.

**Step 7 (optional)** — Inline `smartCapsuleHostPadding` into `SmartCapsuleSurface.kt`; delete `SmartCapsuleHostLayout.kt`.

After step 6: `./gradlew test` and `./gradlew lint` clean. Each step green independently.

---

## 5. Track A accommodation

**Position: defer `ContentBlock.Thought` to Track A's implement phase.** Reasons:

- Adding the variant now without the renderer branch produces dead code; adding the renderer without the producer event leaves the branch untested.
- The Track A spec explicitly defers this decision to Track B — I am taking the position: **keep the existing reducer-driven shape**. The capsule's `RenderSpec` exists because two renderers (overlay + in-app) share it. The chat has one renderer. Adding a parallel `RenderSpec` would be premature abstraction.
- Track A only needs `MessageBubble` to grow one branch.

What Track B **does** do: the rename + extraction in steps 1-6 leave the chat-screen wiring untouched, so Track A's diff lands cleanly on top with zero merge conflict.

---

## 6. Risks + invariants to preserve

From `doc/main/state_machines/ui_capsule.md`:

1. **`CapsuleMode` drives every pixel.** Renames must not introduce a new state field. `transientThought` stays a `SmartCapsuleSurface` parameter.
2. **`CapsuleRenderSpec.from(...)` stays a pure function.** Step 1 only changes member names.
3. **`InputSpec.clearDraft = previousMode != null && previousMode !is WaitingForInput`** — preserved verbatim. Prevents stale draft leaking into Q&A response.
4. **Submit routing** — `Hidden → onSend`, `WaitingForInput → onUserResponse(callId)`, else → `onSupplement`. Stays in `SmartCapsuleSurface`. Do **not** push into `CapsuleInputBar`. The bar stays a dumb widget that calls back `onSubmit(text: String)`.
5. **Input enabled-ness** — `MAIN_APP || (not ACCESSIBILITY in Running/TakeoverPending)`. Same rule, same place.
6. **NavSpec hides the entire control bar in `Done` mode.** Predicate stays the same; just renamed local.

From `doc/main/state_machines/ui_chat.md`:

7. **Append-only timeline; at-most-one open agent message.** Track A's `Thought` block must append, never mutate prior.
8. **All reducer mutations under `stateLock`.** Track A's `handleThoughtUpdate` will inherit this for free.

### Risks

- **Step 6 risk:** other callers of `SmartCapsuleCompose` may exist. Audit before deletion.
- **Step 5 risk:** Compose inline-vs-extracted can change recomposition scope and animation keys. Run `androidTest/CapsuleRenderingTest.kt` after step 5.

---

## 7. Out of scope (KISS)

- No `ChatTurnRenderSpec`. Chat has one renderer; reducer is the seam.
- No new state machine.
- No theming overhaul.
- No MVI/redux wrapper around `CapsuleStateHolder`.
- No splitting `CapsuleControlBar` into two public composables.
- No removal of the `AgentService.instance` singleton coupling — separate concern.
- No rename of `transientThought`, `pendingInputText`, `startupError` — already semantic.

---

## Summary of key decisions

1. `row3 → input`, `Row3Spec → InputSpec`, `buttonText → submitLabel`, `clearInput → clearDraft` on `CapsuleRenderSpec`. Local `row2Hidden → controlBarHidden` in `NavSpec`.
2. Composables: `CapsuleRow1/2/3 → CapsuleStatusLine / CapsuleControlBar / CapsuleInputBar`. Public param `onRow1Click → onStatusClick`.
3. `CapsuleControlBar` stays one composable; left action cluster + right nav cluster become two `private` helpers — do not over-split.
4. Delete `SmartCapsuleCompose.kt` pass-through wrapper; `ChatScreen` calls `SmartCapsuleSurface` directly.
5. Defer Track A's `ContentBlock.Thought` to Track A implement phase. No `ChatTurnRenderSpec`.
6. Submit-intent routing stays in `SmartCapsuleSurface`, not pushed into `CapsuleInputBar`.
7. Migration is 6 ordered steps, each compilable + tests green.
