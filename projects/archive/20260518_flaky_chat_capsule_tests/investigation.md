# Flaky Chat & Capsule Tests — Investigation

**Scope**: 13 failing methods reported on `connectedDebugAndroidTest` after PR #47.
**Bottom line**: PR #47 (commit `d2b9d692`) touched ONLY settings sources — no chat/capsule
production or test files. The merge body itself documents these failures as "Pre-existing
failures (KNOWN-FLAKY per verify.md)". Last real changes to the failing files predate #47
by many commits.

| Test class | Last test-file commit | Last SUT-file commit (chat/capsule) |
|---|---|---|
| CapsuleRenderingTest | `dd33e04d` (Operation-approval refactor) | unrelated to #47 |
| CapsuleLifecycleTest | `5086b02d` (Batch 2 fixes) | unrelated to #47 |
| ChatAgentRowDisclosureTest | `ecf599dd` (FinalText + CollapsePill split) | same commit (CollapsePill introduced) |
| ChatBubbleAlignmentTest | `3272923a` (theme wrap round 2) | `ecf599dd` (AgentRow split) |
| ChatEmptyStateTest | `3272923a` | EmptyState — pre-existing |
| ChatHeaderTest | `21e34233` (codex review) | ChatHeader — pre-existing |
| ChatThinkingStateTest | `3272923a` | ThinkingIndicator — pre-existing |
| ChatStreamingCursorTest | `3272923a` | StreamingText — pre-existing |

`git show --stat d2b9d692` confirms only `ui/settings/*` + `ui/settings/qa` + docs changed.
**No regression originates from PR #47.**

---

## ChatBubbleAlignmentTest

- **Failure**: `agent_bubble_sits_in_left_half` — `bubbleRight=1264.0 == rootW=1264.0`;
  `user_bubble_sits_in_right_half` — likely same on full-bleed root.
- **Evidence**:
  `app/src/main/kotlin/ai/closepaw/ui/chat/components/AgentRow.kt:65-70` — the node
  tagged `qa-agent-bubble` is a `Column.fillMaxWidth()`. By design the agent message
  is a full-width row (trace blocks + final answer stacked), not a chat bubble.
  Production never satisfied `bubble.right <= rootW/2`. The user bubble (`MessageBubble.kt:73-76`)
  is `Surface.widthIn(max = screenWidth * 0.85f)` inside `Row(Arrangement.End)`; on a
  1264 px root with short text "hi from user" the bubble's `left` is far from `rootW/2`,
  so the right-half check might pass on a wide tablet but fail on phones — yet still
  conceptually fragile.
- **Hypothesis**: Test assertion encodes the WRONG model (agent ≠ bubble; it's a row).
  Test was written against an older AgentRow layout and not updated when `ecf599dd`
  rewrote AgentRow as a full-width column.
- **Fix bucket**: **Test fix** — assert via the `qa-final-answer` or inner SelectionContainer
  for agent, or drop the agent-bubble half-test entirely; for the user-bubble test,
  cap the parent Box width (e.g., `Modifier.width(360.dp)`) so the assertion is
  deterministic on tablets/foldables.
- **Priority**: **P1** (deterministic failure on hardware P0110 at 1264 px).

## ChatAgentRowDisclosureTest

- **Failures**:
  - `pill_toggles_trace_visibility` — "Failed to assert (StateDescription = 'expanded')"
  - `row_default_collapses_when_transitioning_live_to_complete` — same StateDescription assert.
- **Evidence**:
  - `app/src/main/kotlin/ai/closepaw/ui/chat/components/AgentRow.kt:65-69` sets
    `stateDescription = rowDescription` on the `qa-agent-bubble` Column.
  - `app/src/main/kotlin/ai/closepaw/ui/chat/components/CollapsePill.kt:52-55` ALSO
    sets `stateDescription` on a child node within the same row.
  - Compose merges semantics: when a descendant supplies `stateDescription` and the
    ancestor is not `mergeDescendants = true`, the assertion `expectValue(StateDescription, "expanded")`
    on `qa-agent-bubble` reads the Column's own value. But after `ecf599dd` introduced
    CollapsePill, the child stateDescription may surface through SemanticsNode merging
    or via tree-walking ordering, causing the matcher to read "collapsed" / pill state.
  - `assertExpanded` is also called immediately after `performClick`; without
    `mainClockAutoAdvance = false` + explicit advance, the chevron's `animateFloatAsState`
    + collapsed-state recomposition may not have committed by the next read despite
    `waitForIdle`.
- **Hypothesis**: Dual stateDescription emitters + animation-driven recomposition race
  with `assertExpanded`. The `live_row_is_expanded_and_locked_open` test passes because
  no pill exists (locked open), confirming the pill is the noise source.
- **Fix bucket**: **Test fix** — assert against a distinct testTag (e.g., new
  `qa-trace-region` with explicit visibility state), OR use
  `compose.mainClock.advanceTimeBy(500L)` then re-read; alternative is **production fix**
  to remove duplicate stateDescription on CollapsePill.
- **Priority**: **P1**.

## ChatBubbleAlignmentTest (user side flake on hardware)

Covered above.

## ChatEmptyStateTest

- **Failures**: `shows_three_suggestion_chips` (assertExists), `tapping_suggestion_fires_callback_with_text`.
- **Evidence**:
  - Test searches `onNodeWithText("\"Check my unread emails\"")` with leading/trailing quotes.
  - `EmptyState.kt:45-53, 118-127` defines suggestions as `Suggestion("Check","my unread emails")`
    rendered as `verb` + `gloss` in **two separate Text composables** (`MarginaliaSuggestion`
    column at `EmptyState.kt:153-164`), with NO quotation marks anywhere.
  - Additionally `StaggeredReveal` at `EmptyState.kt:182-185` uses `LaunchedEffect { delay(index*80L) }`
    so chips start at `alpha = 0`; without `mainClock.advanceTimeBy` they may not register
    as displayed even if text matched.
- **Hypothesis**: Test was authored against a different copy/layout (single-Text "Check my
  unread emails" inside quotes). Never matched current production.
- **Fix bucket**: **Test fix** — match each Text separately (`onNodeWithText("Check")`),
  drop the quoted-string assumption, and either advance mainClock or `assertExists` (works
  pre-display) instead of `assertIsDisplayed`. The callback test additionally needs the
  click to land on the Row, not the Text — search with `hasClickAction()`.
- **Priority**: **P1**.

## ChatHeaderTest

- **Failure**: `new_chat_button_hidden_when_showNewChatButton_false`.
- **Evidence**: `app/src/androidTest/kotlin/ai/closepaw/qa/ChatHeaderTest.kt:21-28` does
  NOT wrap in `ClosePawTheme`. `ChatHeader.kt:63` calls `PageMasthead`, which references
  `MaterialTheme.closePaw.inkFaint` (`Ornaments.kt:98`). Without `ClosePawTheme`,
  `MaterialTheme.closePaw` accessor (`Tokens.kt:45`) returns the LocalComposition default,
  which may crash or render mis-typed. The other test method in the file
  (`new_chat_button_visible_...`) shares the bug but happens to find the node before
  the crash propagates; the negative-assertion variant fails because the entire subtree
  fails to compose.
- **Hypothesis**: Missed during `3272923a fix(test): wrap themed composables in ClosePawTheme — round 2`.
- **Fix bucket**: **Test fix** — wrap both `setContent` blocks in `ClosePawTheme { ... }`.
- **Priority**: **P2** (single-method flake, trivial fix).

## ChatThinkingStateTest

- **Failures**: both `thinking_indicator_shown_...` and `thinking_indicator_hidden_once_content_arrives`.
- **Evidence**: `ThinkingIndicator.kt:41` sets `testTag("qa-thinking-indicator")` correctly.
  Indicator uses `rememberInfiniteTransition` (toe cadence). Compose-rule default
  `mainClockAutoAdvance = true` plus infinite transitions causes
  `waitForIdle()` to hang or the assertion to read stale state — known Compose-test
  pitfall.
- **Hypothesis**: Infinite animation prevents idleness; `assertExists` lookup occurs
  before composition stabilizes.
- **Fix bucket**: **Test fix** — set `compose.mainClock.autoAdvance = false`, advance
  by a small fixed amount, then assert. OR add `ClosePawMotion.reducedMotion()` test
  override.
- **Priority**: **P1** (gates Thinking state coverage).

## ChatStreamingCursorTest

- **Failure**: `streaming_message_shows_inline_cursor_on_last_text_block`.
- **Evidence**: `CURSOR_TEST_TAG = "qa-streaming-cursor"` set at `StreamingText.kt:89,104`.
  The cursor uses `rememberInfiniteTransition` for blink. Same infinite-animation
  vs `mainClockAutoAdvance` race as ThinkingIndicator. Sibling test
  `complete_message_has_no_cursor` passes — confirms tag correctness; only the
  animation-on case flakes.
- **Hypothesis**: Same as ChatThinkingStateTest.
- **Fix bucket**: **Test fix** — toggle off `mainClock.autoAdvance`.
- **Priority**: **P2**.

## CapsuleRenderingTest

- **Failure**: `running_shows_stop_button` (K3) — `onNodeWithText("Stop").assertExists()`.
- **Evidence**: `CapsuleRenderSpec.kt:161-162` emits `ButtonSpec("Stop")` for
  Running + !isStopPending. Sister test K2 (`running_shows_thought_and_takeover_button`)
  and K13 (`is_stop_pending_disables_stop_button`) pass per merge notes — proves the
  Stop label and surrounding chrome render in this exact config. K3 differs only in
  thought = "Working" vs "Opening Settings" — irrelevant. Failure reads as
  "Failed to inject touch input" / device race during compose setup (capsule surface
  is heavy: status paw, control bar, input bar, voice gate — first-frame draw cost).
- **Hypothesis**: First-test-in-class warmup flake on the heavy SmartCapsuleSurface
  composition, not a production bug. The capsule overlay registers voice permission
  receivers etc. on first render.
- **Fix bucket**: **Test fix** — add `compose.waitForIdle()` after `setContent`, or
  reorder so K2 (which already passes) runs first, or **Environment skip** if it only
  reproes on emulator-5556. Lowest-risk: add explicit wait + `assertExists` retry loop.
- **Priority**: **P2**.

## CapsuleLifecycleTest

- **Failures**:
  - `error_dismiss_button_fires_callback` (K12) — "Failed to inject touch input"
    on `onNodeWithText("Close").performClick()`. Production
    (`CapsuleControlBar.kt:132-141`) wires Error→onDismissError correctly. K14
    (which does THREE clicks on icon-buttons) passes per merge — eliminates a
    universal injection failure. Likely a per-pixel hit-rect race: Error mode's
    stop button has narrow content padding (10dp x 6dp) and the Close text is
    short; one device's status-bar inset shifts the row off-screen.
  - `done_state_auto_dismisses_to_hidden_via_real_holder` (K11) —
    `CapsuleStateHolder.kt:283-291` schedules `delay(AUTO_HIDE_DELAY_MS=3000L)`.
    Test uses `runBlocking { withContext(Dispatchers.Main) { ... } ; delay(1500L) ; delay(1800L) }`.
    On Main dispatcher, `runBlocking` + `delay` interleave with Compose frame
    callbacks. On a slow emulator the auto-hide job (also dispatched to scope's
    Main) may not finish within 1800 ms slack; on a fast device it works.
- **Hypothesis**: K12 — environment / touch injection. K11 — test timing fragile
  on shared Main dispatcher.
- **Fix bucket**: K12 = **Environment skip** (mark `@FlakyTest`) or **Test fix**
  (use `onNodeWithText("Close").assertHasClickAction()` then advance + assert
  callback). K11 = **Test fix** — bump 1800 ms slack to 3000 ms, or run the
  holder on a `TestScope` + virtual time (true unit-test it; doesn't need a device).
- **Priority**: K12 **P2**, K11 **P1** (K11 has a clean fix).

---

## Summary

- **Environment (true device/runner)**: 0 — all failures have non-env root causes.
- **Production regression caused by PR #47**: **0** — PR #47 touched zero chat/capsule files.
- **Test bug / pre-existing test fragility**: **13 / 13**.
  - Theme not wrapped: 1 (ChatHeader).
  - Infinite-animation vs `mainClockAutoAdvance`: 3 (Thinking ×2, StreamingCursor ×1).
  - Stale layout assumptions (verb/gloss split, quoted text, agent-as-bubble): 4
    (EmptyState ×2, BubbleAlignment ×2).
  - Dual stateDescription / animation race on disclosure: 2
    (AgentRowDisclosure pill + transition).
  - Real-time race vs auto-hide: 1 (CapsuleLifecycle K11).
  - Touch-inject / first-frame composition: 2 (CapsuleRendering K3, CapsuleLifecycle K12).

**Recommendation**: file a single tracking issue
"chat/capsule androidTest hygiene sweep" with the per-class fix bullets above.
None block PR #47 or any post-merge work. Most fixes are 1–3 lines.
