# Track A — Chat Row Info Architecture (Claude Independent UX Spec, KISS pass)

**Author:** Claude, independent draft
**Date:** 2026-04-20
**Scope:** UX spec for a single chat turn. Design-only.
**Out of scope:** engineering refactor (Track B), state-machine doc (Track C), broader visual revamp (covered by `frontend-ui-review/`).

> **KISS rule:** the spec ships in a week. Reject any state, mode, or control that doesn't pay rent. The full record this design produces should still feel like a chat, not a debug console.

---

## 1. Problem (one paragraph)

Today the chat surface drops `ThoughtUpdate` on the floor (`ChatEventReducer.handle` doesn't handle it). The capsule shows what the agent is thinking; the chat only shows what it tapped. So the live ambient view is more informative than the permanent record. **Fix: chat must surface thoughts alongside actions, in the same chronological flow, without becoming a wall of text.**

---

## 2. The mental model: a turn is a list of **Steps**

A turn is what runs between two user messages.

```
Turn  = User message + Step* + (optional) Final answer
Step  = (optional) Thought + (optional) Action + (optional) Result
```

Constraints:
- A Step has at least one of {Thought, Action}. Empty Steps are not rendered.
- A Result only exists if the Step had an Action.
- The **Final** is just the agent's closing prose after no more actions are needed. Visually distinct from a Step (no thought glyph, full-width).

That's the whole model. Three nouns: **Turn**, **Step**, **Final**.

---

## 3. Event mapping (the only events that surface)

| Event | Where it lands |
|---|---|
| `TaskStarted` | New Turn (existing behavior). |
| `ThoughtUpdate` | Thought of the open Step. **(today: dropped — this is the fix)** |
| `MessageDelta` | If no Action has fired in this turn yet → into open Step's thought; if at least one Action has fired and the agent is wrapping up → into the Final. |
| `ActionProposed` | Action of the open Step. If the Step already has an Action, **opens a new Step**. |
| `ActionExecuted` | Resolves Action status; sets Result. |
| `TaskCompleted` | Closes the Turn. If no Final exists, `result` becomes Final. |
| `SessionError` | Renders an inline error block in the open Step. Step stays expanded. |
| `SupplementReceived` | Existing behavior: closes Turn, inserts User bubble, opens new Turn. (No mid-turn split — KISS: a supplement is a new turn.) |
| `AskUser*` / `Approval*` | Inline prompt card pinned at the tail of the open Step. Same affordance both events. |

Everything else (TurnStarted, TurnPhaseChanged, StatusUpdate, sub-agent events, perception events) is **not surfaced in chat** for v1. The capsule is the live HUD; the chat is the chronicle. Sub-agents render as a one-liner `↳ sub-agent: <summary>` inside the parent Step's Result area — no nested UI.

---

## 4. Row anatomy

```
┌────────────────────────────────────────────────────────┐
│  [User message bubble — existing right-aligned]        │
└────────────────────────────────────────────────────────┘
   ✱  Looking for the Messages app                ⏳     ← Step (expanded)
   →  open_app(com.google.android.apps.messaging) ✓
   ↳  Messages opened

   ✱  Searching contacts for "Mom"                       ← Step (collapsed)

   ✱  Tapping the compose button                         ← Step (collapsed)

   ─────────────────────────────────────────────────────
   Sent. Want me to add a photo?                         ← Final
```

Each Step has up to three rows. All optional, in this fixed order:

1. **Thought row** — `✱` glyph + thought text in **italic body**. Live thought animates cross-fade (180ms) when revised.
2. **Action row** — `→` glyph + monospaced `tool_name(args)` + status glyph (`⏳ ✓ ✕ ⊘`).
3. **Result row** — `↳` glyph + 1-line summary. Truncated at ~80 chars when collapsed.

**Final** is rendered without a glyph: full-width prose, separated from steps by a hairline rule. The current chat's "agent message" treatment applies here.

No surrounding card. Steps sit on the page background, separated from each other by 8dp vertical spacing. A turn ends with the Final (or an outcome footer if there's no Final): `✓ 12s · 4 steps`.

---

## 5. Collapse / expand — exactly two states, one control

**State per Step: Collapsed or Expanded.** That's it.

- **Collapsed**: Thought row only. Action and Result hidden.
- **Expanded**: All three rows visible; Result body expanded to multi-line.

**Defaults:**

| Turn | Step state |
|---|---|
| Active turn | Last Step Expanded; earlier Steps Collapsed. |
| Completed turn (latest) | All Steps Collapsed; Final visible. |
| Older turns | Same as latest completed. |
| Step contains an error / open prompt | Forced Expanded. User cannot collapse it. |

**Control: tap a Step to toggle Collapsed ↔ Expanded.** That is the entire interaction surface.

Not in v1: turn-level collapse, "expand all", multi-finger gestures, persistence across sessions, debug densities. If we need them later, add them later.

---

## 6. Edge cases (the short list)

| Case | Behavior |
|---|---|
| Step with no Thought ever | Thought row shows muted placeholder `Thinking…`. Never render an empty Step. |
| Step with no Action (just thinking aloud) | Render Thought row only. Glyph stays `✱`. Acceptable terminal step. |
| Long Result body (shell output) | Expanded Result is height-capped to viewport with internal scroll + Copy button. Prevents runaway Steps. |
| Streaming Final | Cursor (existing `StreamingText`) shows on Final until `TaskCompleted`. |
| Error in any Step | Step is Expanded and locked open. Turn footer shows `⚠`. Turn does not collapse on completion. |
| AskUser / Approval pending | Inline card pinned in the Step. Step locked Expanded until reply. |

---

## 7. Visual / motion (reuses existing tokens — no new primitives)

- Glyphs: `✱ → ↳` for Step rows; `⏳ ✓ ✕ ⊘ ⚠` for status. All have text labels for screen readers.
- Type: existing body for Thought (italicized), existing monospace for Action row, existing body for Result/Final.
- Color: status conveyed by glyph + label, not color alone. Color reuses whatever palette `frontend-ui-review/` settles on (claw-red for active, ink for settled, rust for error, moss for success).
- Motion: 240ms ease-out for collapse/expand; 120ms cross-fade for status flips. No springs. No bounces.
- Hairline `ink @ 8%` rule above Final. No other dividers.

---

## 8. Accessibility

- Each Step announces as `"Step <n>: <thought>. <action>. <result>. <state: collapsed/expanded>."`
- Collapse toggle is a button with `expanded`/`collapsed` state.
- 48dp minimum tap target on the Step row.
- Reduced-motion: instant transitions + 120ms fade.

---

## 9. What this spec deliberately does NOT include

Listed so reviewers can see what we said no to and why:

- **Turn-level collapse.** Adds a second toggle scale. Not needed if Step defaults are sane.
- **Per-step elapsed timestamps.** Useful for debugging, noise for users.
- **Sub-agent recursive trace UI.** Renders as one summary line; full trace lives in capsule debug surfaces.
- **Mid-turn supplement nesting.** Supplement = new Turn. Easier to read.
- **Auto-derived thought from action name.** If Thought is missing, show placeholder; don't fabricate.
- **Persistence of user collapse state.** Session-only.
- **Multiple density modes.** Two states (collapsed/expanded) only.

If any of these turn out to be wrong, add them once a real user complaint exists.

---

## 10. Acceptance check

- ✅ Surfaces thought + action + result in chat row (§3, §4).
- ✅ Defines collapse/expand interaction (§5: two states, one control).
- ✅ Handles canonical edge cases (§6).
- ✅ Stays design-only.
- ✅ KISS: one control, two states, three nouns, no v2 hooks.

Hand-off:
- **Track B** consumes §2's data shape (`Turn → Step* → Final?`). A `ChatTurnRenderSpec` analogous to `CapsuleRenderSpec` is the natural target.
- **Track C** documents the per-Step state transitions (Thinking → Acting → Resolved) and the locked-open invariants for error/prompt Steps.
