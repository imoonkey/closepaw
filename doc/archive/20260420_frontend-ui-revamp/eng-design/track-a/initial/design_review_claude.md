# Design Review — Claude reviewing Codex's Track A spec

**Reviewer:** Claude
**Reviewing:** `doc/todo/frontend-ui-review/eng-design/track-a/initial/design_codex.md`
**Lens:** KISS — does every state, surface, and control earn its keep?

---

## TL;DR

Codex's spec is **simpler than mine** and that is a virtue. It correctly resists my impulse to invent a "Step" abstraction. The four-state row machine (`Live / Waiting / Complete / Error`) is the right altitude. **One concern**: by keeping only the *latest* `ThoughtUpdate` and a flat `Actions` list, Codex's row erases the cognitive trail when an agent thinks→acts→re-thinks→acts. That's the exact information density the parent note (item 1) is asking us to add. A small tweak to Codex's spec recovers this without adding states.

**My recommendation: use Codex's spec as the base, with one targeted amendment (chronological inner trace).**

---

## What Codex got right

1. **One agent row per task / turn.** Reuses today's container shape. My "Turn → Step* → Final" model invents a sub-noun without justification — the protocol doesn't ship Step boundaries (no `StepStarted` event), and inferring them from `ActionProposed` cadence is structure-from-nothing.
2. **Four-state machine `Live / Waiting / Complete / Error`.** Maps cleanly to user-visible affordances (live = no collapse, complete = collapsible, waiting = locked open with prompt, error = locked open). My per-Step state machine multiplied this 1×N for no win.
3. **One disclosure level only.** I had two (Step density + Turn density). Codex's single Collapsed/Expanded toggle is enough.
4. **Sub-agents as one-line delegation in `Actions`.** Same call I made; we agree.
5. **Supplements as a hard turn split.** Same call. My earlier draft had a "mid-turn supplement nesting" mode — overengineered.
6. **Out-of-scope list is explicit and brave.** "no per-turn sub-rows, no density modes, no persistence, no debug surfaces" — exactly the kind of explicit no-list KISS demands.
7. **Fallback ladder for the collapsed primary line** (latest thought → first action description → first result line → "Thinking…") is genuinely thoughtful and handles the empty-thought case without an empty UI shell.

---

## Concerns

### C1. "Latest thought wins" silently drops the cognitive trail (the headline concern)

`ThoughtUpdate` rule in §"Event Taxonomy → UI Surface": *"Latest value wins. Do not build a thought history."*

If the agent does this realistic loop:

```
ThoughtUpdate("I should open Settings first")
ActionProposed(open_app, Settings)
ActionExecuted(success)
ThoughtUpdate("Now find Accessibility — I see it under System")
ActionProposed(scroll, ...)
ActionExecuted(success)
ThoughtUpdate("Tap the Accessibility entry")
ActionProposed(tap, ...)
ActionExecuted(success)
```

…then Codex's row shows:
- **Thought:** "Tap the Accessibility entry" (only the last)
- **Actions:** [open_app, scroll, tap]
- **Result:** (final prose)

The user **cannot reconstruct that the agent had to think between actions**. The actions list reads like a recipe; the deliberation is lost. That's exactly the gap item 1 of the parent note is calling out — *"chat ui对action只显示mobile action点了啥，不像capsule一样显示agent thought"* — and Codex's "latest wins" rule re-creates it.

**Suggested amendment (smallest possible change):** the row's interior is **chronological**, not categorical. Replace:

```
Thought | Actions | Result
```

with a single ordered timeline that interleaves thought updates and actions in the order they were emitted, then the result block at the end:

```
"I should open Settings first"
→ open_app(Settings) ✓
"Now find Accessibility…"
→ scroll(...)         ✓
"Tap the Accessibility entry"
→ tap(...)            ✓
─────────────
Settings is open at the Accessibility menu.
```

This is what `ChatViewModel` *already* does with `contentBlocks: List<ContentBlock>` (Text, Action). All we add is `ContentBlock.Thought` and route `ThoughtUpdate` into it. **No new states, no new collapse axis** — just one more `ContentBlock` variant. KISS-compatible.

The collapsed view still uses Codex's fallback ladder (latest thought as headline). Expanded view shows the full chronological trace.

### C2. The "Thought line is the new primary summary" framing fights the data

§Proposed Row Model says *"The thought line is the new primary summary. The rest of the row exists to back that summary up."*

Two issues:

- The agent's *final* `ThoughtUpdate` is often a low-information setup line ("Tap the button"), not a summary of the whole task. Using it as the row's headline produces misleading collapsed rows.
- The user's prompt is the better headline for a completed turn ("Send Mom a happy birthday text") — it's stable, descriptive, and already in the chat above.

**Suggested:** the collapsed-row primary line should default to a **terse outcome** ("Sent message to Mom · 4 actions · 12s"), not the latest thought. Use the thought ladder only when no outcome exists yet (Live state). This is also closer to how a normal chat reads.

### C3. `TurnPhaseChanged → "small live status label in header"` adds a surface for low value

This is an extra UI element that says "Thinking / Acting / Writing." The thought line *and* the action list already convey this. Phase labels duplicate what other surfaces show. **Drop.** Phase is implied by the latest content.

### C4. `Actions` as a separate categorical section locks out chronological reading

Subsumed by C1's amendment, but stated separately so the choice is explicit: a **categorical** layout (Thought / Actions / Result) reads like a structured report. A **chronological** layout reads like a transcript. The product is a *chat*. Chats are chronological. Choose chronological.

### C5. "Waiting block can collapse to a one-line resolved status" is a third row state

§Blocking block: *"After approval/denial, the block may reduce to a one-line resolved status and normal row updates continue."*

This implies a small inner state machine (live → resolved-summary) for the prompt block. Simpler: **once the user replies, the prompt block becomes an Action item in the chronological trace** (`✓ Approved Settings access`). One representation, no inner state.

### C6. Minor: missing `MessageDelta`-while-no-action-yet case

Codex's table sends `MessageDelta` to `Result`. But the agent often emits message tokens *before* any action fires (e.g. it thinks aloud as prose, then decides to call a tool). With the chronological-trace amendment, this is resolved naturally: deltas append to the current Text/Thought block until the next event opens a new one.

---

## What I'd amend in Codex's spec for the aligned draft

In priority order:

1. **(C1)** Replace categorical (Thought / Actions / Result) with a chronological inner trace; persist all `ThoughtUpdate` values as inline `Thought` blocks, not just the latest.
2. **(C2)** Collapsed-row headline = outcome summary (with thought-ladder fallback for Live state), not the latest thought.
3. **(C5)** Resolved prompts become a chronological Action item, not a "block that reduces to a status."
4. **(C3)** Drop `TurnPhaseChanged` surface in chat. (Capsule keeps it.)
5. Adopt Codex's four-state row machine, fallback ladder, sub-agent rule, supplement rule, accessibility, and motion verbatim.

That gives us:

> *"One row per turn, four-state machine, chronological inner trace, single collapse toggle, terse outcome headline, all the existing reducer plumbing. Adds exactly one new `ContentBlock.Thought` variant and one new event handler."*

That's the minimum viable Track A.

---

## What I'd drop from MY spec when we converge

- The "Step" abstraction (Turn → Step* → Final). Codex is right — there's no Step in the protocol. Drop.
- Per-Step density modes (Glance / Standard / Full). Drop — single Collapsed/Expanded.
- Long-press / multi-finger gestures. Drop — single tap.
- Sub-agent nested mini-trace UI. Drop — one-line delegation entry.
- Mid-turn supplement nesting. Drop — supplement = new turn.
- The seven open questions section. Most are over-thinking; the survivors fold into the aligned spec's Open Questions if they remain unresolved.

---

## Verdict

Codex's spec is the closer-to-shippable starting point. With the C1 amendment (chronological inner trace), it solves item 1 of the parent note without inventing infrastructure. My Step model was the more architecturally satisfying answer to a question we weren't asked.

**BETTER BASE FOR FIRST DRAFT: CODEX**
