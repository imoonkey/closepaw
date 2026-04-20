# Track A — Chat Row Info Architecture (Aligned Final UX Spec)

**Authors:** Claude + Codex (cross-reviewed and aligned)
**Date:** 2026-04-20
**Status:** Final (design-only). Hand-off: Track B (architecture refactor) and Track C (state-machine doc + tests).
**Scope:** UX spec for the chat row. No code changes this round.

> **KISS rule applied throughout:** the aligned spec is the simplest thing that solves item 1 of `doc/todo/frontend-ui-review/eng-design/note.md` — *the chat must surface the agent's thoughts in addition to its actions* — and nothing more. Both reviewers explicitly rejected: per-step state machines, density modes, multi-finger gestures, persistence schemes, recursive sub-agent UI, and `MessageDelta` semantic routing.

---

## 1. Problem (one paragraph)

Today's chat surface drops `ThoughtUpdate` on the floor: `ChatEventReducer.handle(...)` has no branch for it. The Smart Capsule shows the agent's reasoning live, but once the capsule moves on, that reasoning is gone — the chat preserves only what the agent did to the phone, not what it was thinking. This makes the chronicle less informative than the live ambient view, which is backwards. The fix: surface `ThoughtUpdate` in the chat row alongside actions, in the order they occurred, without inventing new state machines or new control surfaces.

---

## 2. Mental model — one row per turn, chronological inside

A chat **turn** is what happens between two user messages. We keep today's container shape:

```
[User bubble]
[Agent row]    ← one row per turn (this is what we're enriching)
```

Inside the agent row is a **chronological trace** of what the agent did, plus a final answer when one exists:

```
Agent row  =  Trace*  +  (optional) Final
Trace item ::=  Thought  |  Action
Final      =   the closing assistant prose
```

That's the whole model. Two abstractions: **Trace items** (in event order) and an optional **Final** block.

Why chronological and not categorical (Thought / Actions / Result)? Because the agent's reasoning evolves *between* actions ("open Settings → tap Accessibility → realise wrong path → backtrack"), and a categorical layout with a single live "latest thought" erases that evolution. The trace preserves it for free using the same `contentBlocks` shape `ChatEventReducer` already maintains — we just add one new variant: `ContentBlock.Thought`.

---

## 3. Event taxonomy — what surfaces in chat, what doesn't

| Event | Effect on the agent row | Notes |
|---|---|---|
| `TaskStarted` | Closes prior turn, inserts `User` bubble, opens new agent row in `Live` state. | Existing behavior. |
| `ThoughtUpdate` | Appends a new **Thought** trace item to the open row. **(today: dropped — this is the fix)** | The agent emits this each time its plan changes. Each one becomes its own trace item. |
| `MessageDelta` | Streams into the **Final** block (creating it on first delta). | We do **not** try to route deltas into a "thought-vs-final" split based on position; the protocol does not encode the distinction. Keep deltas in one place. |
| `ActionProposed` | Appends an **Action** trace item with status `proposed`. | If the same `actionId` later receives `ActionExecuted`, the trace item resolves in place. |
| `ActionExecuted` | Resolves the matching Action trace item with `success / failed / skipped` and a one-line result summary. If no matching `ActionProposed` exists, append a resolved Action trace item directly. | Existing behavior, slightly tightened. |
| `TaskCompleted` | Row → `Complete`. If `Final` is empty and `result` is non-empty, `result` becomes Final. Outcome footer rendered. | |
| `SessionError` | Append an inline error block to the trace; row → `Error`. | Row is locked open. |
| `SupplementReceived` | **Hard turn split**: seal current row, insert User bubble, open new agent row. | Existing behavior. |
| `AskUser*` | Append an inline **prompt** block at the tail of the trace; row → `Waiting`. | Same affordance as Approval. |
| `Approval*` | Same as AskUser. Resolved approval/denial becomes a normal Action trace item (`✓ Approved Settings access`). | One representation; no inner "block reduces to status" state. |
| `SubAgent*` | Single one-line trace item: `↳ <subagent name>: <latest activity / final summary>`. Latest activity wins while live; final summary on completion. | No nested UI. Internal sub-agent activity stays in the capsule debug surfaces. |
| `TurnStarted` / `TurnCompleted` | No standalone UI. | Internal boundary only. |
| `TurnPhaseChanged` | No standalone UI. | The trace already tells the story; phase labels duplicate it. (Capsule still uses this event.) |
| `StatusUpdate` | No chat surface. | Too noisy; lives in capsule/status surfaces. |
| Perception / screen-capture events | No chat surface. | Belong in viewer/debug surfaces. |

---

## 4. Row anatomy

Three visible regions, top to bottom. Trace is the only mandatory region; Final and Footer appear when applicable.

```
┌─ Agent row ─────────────────────────────────────────────┐
│  ✱  I should open Settings first                         │ ← Trace item (Thought)
│  →  open_app(com.android.settings)              ✓        │ ← Trace item (Action)
│  ✱  Now find Accessibility under System                  │ ← Trace item (Thought)
│  →  scroll(down, ...)                            ✓        │
│  ✱  Tap the Accessibility entry                          │
│  →  tap_at(540, 1280)                            ✓        │
│  ─────────────────────────────────────────────────────── │ ← hairline (only if Final exists)
│  Settings is open at the Accessibility menu.             │ ← Final
│                                                          │
│  ✓  3 actions · 4.8s                                     │ ← Outcome footer
└──────────────────────────────────────────────────────────┘
```

### 4.1 Trace item — Thought

- Glyph: `✱` (paw-toe asterisk).
- Text: italic body — visually marks "inner voice."
- One trace item per `ThoughtUpdate`. Multiple `ThoughtUpdate`s become multiple Thought items in chronological order. **Do not** mutate the previous Thought in place.

### 4.2 Trace item — Action

- Glyph: `→` plus the tool icon.
- Text: monospaced `tool_name(args)` (one line; args truncated as needed).
- Right-aligned status glyph: `⏳ proposed/executing` · `✓ success` · `✕ failed` · `⊘ skipped`.
- Result rendering is governed by the row's disclosure state (§5), not by a per-action toggle:
  - Row **collapsed**: result not shown (only the row headline is visible).
  - Row **expanded**: full result body shown inline (multiline, monospace if needed), height-capped to viewport with internal scroll for very long output.
- No per-action expand control. One disclosure axis only — the row.
- (No copy button in v1; users can long-press the text.)

### 4.3 Trace item — inline prompt (AskUser / Approval)

- Visually heavier than Action items because the user must respond.
- Renders the prompt text + the required affordance (reply field for AskUser; Allow/Deny chips with optional scope for Approval).
- Row is locked in `Waiting` state until reply.
- On reply, the prompt block is replaced by a normal Action trace item summarizing the resolution (`✓ Approved Settings access` or `↩ Reply: "Saturday afternoon"`).

### 4.4 Final block

- Renders the closing assistant prose (everything `MessageDelta` streamed in).
- Separated from the trace by a hairline rule (`ink @ 8%`).
- Streaming cursor while in flight; cursor removed on `TaskCompleted`.
- If no `MessageDelta` ever arrived but `TaskCompleted.result` is non-empty, that string becomes the Final.
- If neither exists, the Final block is omitted.

### 4.5 Outcome footer

- Single line at the bottom of the row when the row is `Complete` or `Error`.
- Format: `✓ <N> actions · <elapsed>` (success), `⚠ <error summary>` (error). Elapsed is rounded to 0.1s under 10s, whole seconds otherwise.
- Stable, terse. No live updates while running.

### 4.6 Row chrome

- No surrounding bubble or card. The trace items sit on the page background, separated by 6dp vertical spacing.
- Visual style (palette, typography) inherits from `frontend-ui-review/` direction; this spec does not introduce new tokens.

---

## 5. State machine — four row states, one user control

```
TaskStarted        → Live
AskUser/Approval   → Waiting
reply received     → Live
TaskCompleted      → Complete
SessionError       → Error
SupplementReceived → seal current row; new turn begins
```

Presentation defaults per state:

| State | Collapsible? | Default disclosure |
|---|---|---|
| `Live` | No (auto-tracking, see §5.1) | All trace items + Final + footer visible. |
| `Waiting` | No | All trace items + prompt block visible; required affordance focused. |
| `Complete` | Yes | **Collapsed** by default — see §5.2. |
| `Error` | Yes | **Expanded** by default; user may collapse after acknowledging. |

**One user control: tap the row header to toggle collapsed ↔ expanded.** No other gestures, no per-item toggles, no persistence.

### 5.1 Live auto-scroll

While the row is `Live`, the conversation auto-scrolls to keep the latest trace item in view. If the user manually scrolls up, auto-scroll pauses and a `↓ live` pill appears at the bottom-right; tapping it resumes auto-scroll. (Standard chat pattern, called out so we don't lose the live experience.)

### 5.2 Collapsed presentation

A collapsed `Complete` row shows a single-line summary:

```
✓  Open Settings and check Accessibility · 3 actions · 4.8s     ▸
```

Headline source ladder (first non-empty wins):
1. The user message that opened the turn (truncated to ~6 words).
2. The first `ThoughtUpdate` text.
3. The first action description.
4. `"(no activity)"` as a last resort.

Rationale: the user's prompt is the most stable, descriptive headline for a completed turn. The thought ladder kicks in only when the user's prompt isn't available (e.g. the turn was triggered by an event other than `TaskStarted`).

---

## 6. Edge cases

| Case | Behavior |
|---|---|
| No `ThoughtUpdate` ever arrives | Trace renders Action items only. No placeholder thought. The first action's description carries enough information. |
| `ThoughtUpdate` updates without an action between | Each update is its own Thought trace item. No deduplication; the chronological story is the value. |
| `MessageDelta` arrives before any action | Final block opens immediately and streams. If an action subsequently fires, Final remains where it is at the bottom of the trace; new Action items append after it. (Visual: rare, but acceptable — the trace is chronological.) |
| Action with no preceding thought | Render Action item alone. No fabricated thought. |
| Long shell/tool result | Expanded result body is height-capped to viewport with internal scroll. |
| Streaming Final | Cursor on Final until `TaskCompleted`. |
| Error mid-row | Inline error block appended; row → `Error`, locked open until user acknowledges. |
| Pending prompt (AskUser/Approval) | Prompt block appended; row → `Waiting`, locked open. |
| Sub-agent | One-line trace item; updates in place during sub-agent execution; finalized on completion. |
| Supplement mid-task | Hard split: seal row, new User bubble, new agent row. |
| Empty turn (immediate `TaskCompleted` with no events) | Render only the outcome footer. No empty trace rendering. |

---

## 7. Accessibility

- The collapsed row header is one control with the announced state (`expanded`/`collapsed`), the headline summary, and the outcome.
- Trace items are labelled by section type (`Thought`, `Action`, `Result`, `Prompt`).
- Status conveyed via glyph + text label, never color alone.
- Streaming Final is a polite live region; trace items mutating live (action status changing) are not announced individually, only on resolution.
- In `Waiting`, accessibility focus moves to the first required affordance.
- Minimum 48dp tap target on the row header and on each Action item.

---

## 8. Motion

- Trace item appearance: `slideInVertically(8dp) + fadeIn`, 240ms `EaseOutCubic`.
- Row collapse/expand: `expandVertically + fadeIn`, 240ms.
- Action status flip (`⏳` → `✓`): glyph cross-fade 120ms.
- Live thought update: cross-fade 120ms (no slide; same item position).
- No springs, no bounces. Reduced-motion: replace slide-in with instant + 120ms fade; collapse/expand becomes instant.

---

## 9. Out of scope (explicit)

These were proposed in the initial drafts and explicitly rejected during alignment:

- Per-step / per-action collapse axis (only the row itself collapses).
- Multiple density modes (only Collapsed / Expanded).
- Multi-finger or long-press gestures (single tap is the only control).
- Persistence of collapse state across app restarts (session-only).
- Recursive sub-agent UI (one-line entry only).
- Mid-turn supplement nesting (supplement = new turn).
- Routing `MessageDelta` between Thought and Final based on position (protocol doesn't encode the distinction; deltas always go to Final).
- `TurnPhaseChanged` chat surface (the trace already tells the story).
- Per-trace-item timestamps in the default view (footer carries elapsed).
- Auto-derived placeholder thought when `ThoughtUpdate` is absent.
- Any new design tokens — visual styling inherits from `frontend-ui-review/`.

If a real user complaint emerges later that warrants any of these, add them then.

---

## 10. Acceptance check

- ✅ Defines what events from the agent pipeline are surfaced in the chat row (§3 table).
- ✅ Defines composition of thought + action + result (§4: chronological trace + Final + footer).
- ✅ Defines collapse/expand interaction (§5: four row states, one tap control).
- ✅ Handles canonical edge cases (§6).
- ✅ Stays design-only.
- ✅ KISS: one new event handler, one new `ContentBlock` variant, one collapse axis, four row states, no new visual tokens.

---

## 11. Hand-off

**Track B (UI architecture refactor)** consumes:
- The chronological trace shape — already represented by `contentBlocks: List<ContentBlock>` in `ChatViewModel`. The change is to add `ContentBlock.Thought(text: String)` and route `ThoughtUpdate` into it via a new branch in `ChatEventReducer.handle`. Track B can decide whether to formalize a `ChatTurnRenderSpec` analogous to `CapsuleRenderSpec`, or keep the existing reducer-driven shape — that is an engineering decision, not a UX one.
- The four row states from §5 should be lifted from inferred-from-message into an explicit row-level enum once Track B touches the model.

**Track C (state-machine doc + tests)** documents:
- The four row states (`Live / Waiting / Complete / Error`) and their event-driven transitions (§5).
- The locked-open invariants for `Error` and `Waiting`.
- The chronological-trace ordering invariant: the trace is exactly the event arrival order; no reordering, no deduplication.

---

## Open questions (none blocking)

There are no unresolved open questions blocking implementation. Items deliberately deferred:

- Whether the collapsed-row headline ladder should ever surface a *thought* in completed state (this spec says no — user prompt only). Revisit after first user feedback.
- Whether to render Markdown / lists / code in the Final block (this spec assumes existing Markdown support continues). Confirm during Track B implementation.
