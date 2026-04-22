# Round6 Followup Final Design (P1 Fixes)

Date: 2026-02-14
Scope: fix all P1 issues in `qi_note.md` under `round6/followup`.

---

## 0. Source Alignment (Codex + Claude)

Both reviews converge on:
- `V1` is implementation-level and should be enforced by tests.
- `V2/V3/V4` are tightly coupled with Activity/task/session continuity.
- `A3` and `V5` require design-level policy updates (not just bug patching).

This final design therefore includes **two deliberate policy changes**:
1. A11y allows collapse to island while task active.
2. VD allows edge glow while task active.

---

## 1. P1 Issues and Final Decisions

## A3 (A11y missing collapse button)

Decision: **Fix as design+implementation update**.

New rules:
- In A11y overlay context (`OTHER_APP + active`), show `⊖` button.
- `⊖` toggles `ShowPreference=ISLAND`, island becomes visible, capsule hidden.
- Tapping island restores capsule (`ShowPreference=CAPSULE`).
- `WaitingForInput/WaitingForAction/Error` force `CAPSULE` invariant still applies.

Rationale:
- Satisfies user request while preserving safety by keeping `📱/👁` disabled in A11y.

## V1 (Done shows 👁)

Decision: keep current fix and strengthen regression checks.

Rule:
- `mode=Done` => Row2 hidden => all Row2-R buttons hidden (`⊖/📱/👁`).

## V2 (viewer 📱 can’t return chat)

Decision: **task-stack/navigation fix**.

Rule:
- `onOpenApp` from overlay must bring existing `MainActivity` to front and clear viewer-above-main stack.
- Use `NEW_TASK + CLEAR_TOP + SINGLE_TOP`.

Expected:
- On viewer tap `📱`, user returns to chat screen directly.

## V3 (home -> relaunch app opens new session)

Decision: **session continuity fix**.

Rules:
- AgentService remains session owner while task running.
- MainActivity on foreground (`onStart`) attempts rebind to active service session.
- Rebind must restore chat transcript from active recording snapshot.

Expected:
- Returning from Home/recents shows ongoing session, not blank/new chat.

## V4 (missing complete_task in chat history)

Decision: **make completion append robust even under rebind/race**.

Rules:
- Keep existing completion fallback text behavior.
- If `TaskCompleted` arrives and no existing agent bubble exists, create one and append completion text.
- Combined with V3 rebind, completion should be visible after app return.

## V5 (no edge glow in VD)

Decision: **design+implementation update**.

New rule:
- In VD mode, when task active and user not in MAIN_APP, show edge glow.
- Glow coexists with capsule/island visibility policy.

---

## 2. Implementation Plan

## Phase A: Visibility/Nav Policy updates
- Update `OverlayLocationPolicy.deriveOverlayVisibility()`:
  - A11y branch respects `ShowPreference` and allows island.
  - VD branch enables glow when task active off-main.
  - Keep force-capsule invariant for `WI/WA/Error`.
- Update `NavSpec.from()`:
  - Allow `showMinimize` in A11y (screen-viewing + active rows), still keep `📱/👁` disabled for A11y.
- Update `ServiceOverlayController.onIslandTapped()`:
  - A11y tap on island restores capsule.

## Phase B: Navigation/session continuity
- Update `AgentService.onOpenApp` intent flags (`CLEAR_TOP` path).
- Expose active session getter in `AgentService`.
- In `MainActivity.onStart`, rebind to service active session when needed:
  - set `currentSession`
  - restart event collection
  - restore messages from recording snapshot.

## Phase C: Completion robustness
- In `ChatViewModel.handleTaskCompleted`, if no agent message exists, append a terminal agent message with completion text.

---

## 3. Test Gate

Must pass:
- `OverlayLocationPolicyTest`
  - A11y minimize/island path.
  - VD glow-on-active path.
  - mutual exclusion + force-capsule invariants.
- `NavSpecTest`
  - A11y `⊖` visible in running context.
  - A11y `📱/👁` still hidden.
  - Done hides all row2-r.
- `ChatCompletionSummaryTest` + completion append fallback tests.
- `assembleDebug`.

Given current `debug-run.sh` hang bug, visual-debug loop is deferred by user instruction.
