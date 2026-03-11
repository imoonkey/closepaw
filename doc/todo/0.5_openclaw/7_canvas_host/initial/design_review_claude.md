# Review of Codex Design — Canvas Host (7)

Reviewer: Claude
Reviewed: `design_codex.md`

---

## Summary

The Codex design proposes extending `ask_user` into a typed interaction protocol (`InteractionSpec` / `InteractionResponse`) that replaces the current `QUESTION`/`ACTION` binary model. The same spec renders in whichever host is active — chat in main app, capsule in overlay — and all interactions are blocking. Non-blocking display cards are deferred to Step 2.

This is a clean, unified design. The review below identifies what works, what has gaps, and where the two designs should converge before alignment.

---

## What Works Well

### 1. Typed spec/response pair is the right abstraction
The `InteractionSpec` ↔ `InteractionResponse` pairing is well-shaped. Each variant has a matching response type. This is cleaner than the current stringly-typed `AskUser(message: String)` path and eliminates the need to parse free-form responses.

### 2. Single tool, single channel
Keeping one tool (`ask_user`) with one pending-interaction slot is simpler than the Claude design's two-channel approach (`ask_user` + `show_canvas`). There is real value in having exactly one answer to "how does the agent ask the user something?"

### 3. Persistence is explicitly scoped
The Codex design correctly identifies that both runtime history (`ResponseItem`) and persisted chat (`SessionRecordingService`, `ContentBlockRecord`) must be updated. The persistence section is concrete.

### 4. `displayText` on resolution is a good detail
Having `UserInteractionResolved` carry a human-readable `displayText` that becomes a user bubble in the transcript is elegant. It keeps the transcript readable without a second user-message type.

### 5. Alternatives section is honest
The three rejected alternatives (WebView first, more enum values, chat-only) are genuine and correctly dismissed.

---

## Issues

### Issue 1 (High): Non-blocking display cards are a day-one need, not Step 2

The source brief's three core use cases are: choice, confirmation, and **structured display** (summaries, search results, key-value data). The Codex design defers display-only cards to Step 2. This means the most common, lowest-risk card type — a summary that doesn't block the agent — ships later than the harder blocking interaction types.

Display-only cards are simpler than blocking ones (no suspension, no response channel, no timeout). They should be in Step 1. The `InteractionSpec` sealed hierarchy can include a `Summary` variant that returns immediately, exactly as the Claude design's `mode=display` does.

**Recommendation**: Add `Summary` to `InteractionSpec` in Step 1. The tool returns immediately for this variant. No response needed.

### Issue 2 (High): Capsule as full interactive host is risky

The design says overlay contexts use the capsule as "the primary interactive host" and renders the full `InteractionSpec` there. This means the capsule must render:
- A choice list with N items (potentially many)
- Confirmation dialogs with detail rows
- Text input fields

The capsule is an overlay with tight size constraints. A 6-option choice list in a floating overlay is a poor UX compared to a full-screen card in the app. The Claude design's position — capsule shows "Agent needs your choice in the app" with an open-app affordance — is more realistic for Step 1.

**Recommendation**: Step 1 capsule should render only `ActionRequired` (the existing pattern) and `TextInput` (already supported as `WaitingForInput`). For `SingleChoice` and `Confirmation`, the capsule should show a compact "respond in app" banner. Full overlay rendering can come in Step 2 after validating card sizes in practice.

### Issue 3 (Medium): Replacing `Op.UserResponse` payload type is a breaking change

The design changes `Op.UserResponse` from carrying a `String` to carrying `InteractionResponse`. This touches every call site that submits or handles `Op.UserResponse` today — the capsule, the session dispatcher, and the `UserResponseChannel`.

This is doable but underestimated in the design. The migration path is not described. Since `ask_user` today returns a raw string to the LLM, changing the response type also changes what the LLM sees in tool output for the existing `QUESTION` and `ACTION` flows (now `TextInput` and `ActionRequired`). Backward compatibility of tool output format matters because existing prompt tuning and app skills reference the current response shape.

**Recommendation**: Either (a) keep `Op.UserResponse(String)` and add `Op.UserResponse(InteractionResponse)` as a second overload during migration, or (b) document the migration explicitly: every capsule submission site, every test, and the LLM tool-output format.

### Issue 4 (Medium): One pending interaction per session may be too restrictive

The design states "exactly one pending request per session, same as today." This is inherited from the current `ask_user` limitation, but it means the agent cannot show a summary card (non-blocking) and then immediately ask a choice question. The second call would need to wait for the first to resolve.

With display-only cards in scope (Issue 1), this constraint becomes problematic. A display card should not occupy the single interaction slot.

**Recommendation**: Distinguish blocking interactions (occupy the slot) from display-only cards (do not). This is naturally what the Claude design's `mode=display` vs `mode=request` achieves.

### Issue 5 (Medium): `WaitingForInteraction` capsule mode loses type information

Replacing `WaitingForInput` and `WaitingForAction` with a single `WaitingForInteraction(PendingInteraction)` sounds simpler, but the capsule rendering code still needs to branch on the spec type to decide what UI to show. The simplification is cosmetic — the complexity moves from the state enum to the renderer.

More importantly, if the capsule should NOT render full choice/confirmation in overlay (per Issue 2), then the capsule state machine needs to distinguish "I can handle this inline" from "redirect to app." A single mode doesn't express that.

**Recommendation**: Keep the capsule state machine aware of which interaction types it can host inline vs. which need app redirection.

### Issue 6 (Low): No timeout specification

The design mentions timeout in passing ("tool blocks until a typed response arrives or timeout occurs") but doesn't specify a default, what happens on timeout, or how the LLM is informed. The Claude design specifies 120s default with `TimedOut` state.

**Recommendation**: Specify default timeout (e.g., 120s), the terminal `TimedOut` state, and the tool output format on timeout so the LLM can handle it.

### Issue 7 (Low): No `title` field on `TextInput`

The `InteractionSpec.TextInput` has `prompt`, `placeholder`, and `submitLabel` but no `title` field, unlike the other spec variants. Minor visual inconsistency when rendering cards.

**Recommendation**: Add `title: String` to `TextInput` for consistency.

---

## Comparison: Key Decision Points

| Decision | Codex | Claude | Verdict |
|----------|-------|--------|---------|
| Tool name | Extend `ask_user` | New `show_canvas` | Codex is simpler for blocking; Claude handles display-only better. Needs alignment. |
| Response channel | Widen `Op.UserResponse` | New `Op.CanvasResponse` | Codex is cleaner long-term but riskier migration. |
| Non-blocking cards | Step 2 | Step 1 | Claude is right — Summary is a day-one need. |
| Capsule role | Full interactive host | Compact redirect banner | Claude is more realistic for Step 1. |
| Event model | Replace `AskUser` events | Parallel canvas events | Codex is bolder; Claude is safer. Depends on appetite for migration. |
| Persistence | Same approach | Same approach | Aligned. |
| Process-death recovery | Explicitly out of scope | Not mentioned | Codex is more honest here. |

---

## Recommendations Before Alignment

1. **Merge the scope**: Codex's typed spec model + Claude's display-only cards in Step 1. The result is `InteractionSpec` with a `Summary` variant that returns immediately.

2. **Decide the tool question**: One tool (`ask_user` extended) vs. two tools (`ask_user` + `show_canvas`). The Codex approach is simpler if we accept the migration cost. The Claude approach avoids migration risk. This is the central alignment decision.

3. **Agree on capsule scope**: Both designs should converge on "capsule handles text input and action-required inline; choice and confirmation redirect to app" for Step 1.

4. **Specify timeout behavior** in whichever design wins.

5. **Document the migration path** for `Op.UserResponse` if the Codex approach is chosen.
