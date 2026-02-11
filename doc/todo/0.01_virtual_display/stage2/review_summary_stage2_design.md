# Virtual Display Review Summary

Date: 2026-02-11
Scope:
- `final_design.md`
- `review_codex_impl_20260211.md`
- `review_cursor_20260211.md`
- `review_gemini.md`
- Current implementation in `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/`

## Goal

Converge all review feedback into one decision document:
- Judge each issue: valid / partially valid / outdated / rejected
- Keep only high-value fixes
- Build a phased implementation plan with KISS and readability first

## Execution Status (2026-02-11)

- Phase 1 (runtime correctness and lifecycle): **Done**
- Phase 2 (platform reorg and simplification): **Done**
- Phase 3 (visual debug verification): **Done**
- Phase 4 (cleanup/docs pass for this implementation cycle): **Done**

## Final Issue Matrix

### P0 (Do now)

1) Lifecycle ownership is ambiguous; resources can leak after task completion
- Source: codex, cursor
- Verdict: **Valid**
- Why:
  - `MainActivity` clears `currentSession` on task completion.
  - `AgentSession` enters `Idle` on `TaskCompleted` and does not auto-cleanup.
  - `platform.stop()` only runs during shutdown cleanup.
- Fix:
  - Define explicit one-shot lifecycle for current product stage.
  - On task completion, request `Op.Shutdown`, then clear session reference.
  - Keep shutdown idempotent.

2) `VirtualDisplayPlatform.getCurrentPackageName()` leaks root node
- Source: cursor
- Verdict: **Valid**
- Why:
  - `AccessibilityWindowInfo.root` result must be recycled.
- Fix:
  - Wrap root usage with `try/finally { root.recycle() }`.

3) Keyboard popup on main display during VD typing (cross-display IME side effect)
- Source: codex, cursor, gemini
- Verdict: **Valid**
- Why:
  - Focusing editable fields on VD can trigger IME lifecycle on default display.
- Fix:
  - Use pragmatic mitigation first:
    - clear focus after `ACTION_SET_TEXT`
    - add optional keyboard-dismiss path in VD typing flow when needed
  - Keep change local and reversible.

4) Shell launch path reflection is fragile
- Source: codex, cursor
- Verdict: **Valid**
- Why:
  - Runtime can miss reflected `Shizuku.newProcess(...)` signature.
- Fix:
  - Prefer direct API call path.
  - Keep logging explicit on failure.

5) `debug-run.sh` success path does not send stop broadcast
- Source: codex
- Verdict: **Valid**
- Why:
  - Script breaks monitor loop but may leave session alive.
- Fix:
  - Send `STOP_AGENT` on success path (best effort, non-fatal).

### P1 (Should fix in same refactor window)

6) `VirtualDisplayPlatform` too large and multi-responsibility
- Source: codex, cursor, gemini
- Verdict: **Valid**
- Why:
  - Current class mixes lifecycle, capture, node actions, injection, launching, and window handling.
- Fix:
  - Split by responsibility into focused components.
  - Keep one orchestration entry class.

7) Hot-path window logging too verbose
- Source: codex, cursor
- Verdict: **Valid**
- Fix:
  - Gate detailed logs with `Log.isLoggable(...)` or debug guard.

8) Dead code in `ShizukuClient` (`createNullCallbackProxy`)
- Source: codex, cursor
- Verdict: **Valid**
- Fix:
  - Remove dead helper and related imports.

9) Binder proxy creation repeated on each injection
- Source: cursor
- Verdict: **Valid**
- Fix:
  - Cache binder proxies inside `ShizukuClient`.

### P2 (Nice to have / follow-up)

10) `captureA11yTree()` dispatcher consistency
- Source: cursor
- Verdict: **Partially valid**
- Why:
  - Some devices are strict about a11y calls on main thread.
- Fix:
  - Align with main-thread access pattern where practical.

11) `AccessibilityWindowInfo` recycling
- Source: cursor
- Verdict: **Partially valid**
- Why:
  - Lifecycle/recycling semantics differ by framework object ownership path.
- Fix:
  - Handle safely in extracted window helper, avoid double recycle.

12) Screenshot mismatch between debug artifact and VD content
- Source: gemini
- Verdict: **Valid (tooling issue)**
- Fix:
  - Clarify or improve debug screenshot source in follow-up tooling changes.

### Rejected / Outdated

- "No AIDL should exist" as a hard rule
  - Verdict: **Rejected**
  - Reason: current `IVirtualDisplayCallback.aidl` path is pragmatic and stable enough now.

- "Type must always be key-event injection, replace ACTION_SET_TEXT now"
  - Verdict: **Deferred**
  - Reason: larger behavioral change; current phase prioritizes deterministic lifecycle and stability first.

## Design Decisions

1) Current product stage uses **one-shot task session semantics**.
- No backward compatibility requirement.
- Simpler lifecycle, clearer ownership, fewer hidden resources.

2) Keep platform abstraction stable, but extract internals.
- Prefer composition over deep inheritance.
- Avoid new complex generic hierarchies.

3) Prioritize correctness and observability over clever fallback.
- Explicit logs for chosen path.
- Fail early on hard platform failures.

## Phased Plan

### Phase 1 — Runtime correctness and lifecycle
- Session completion -> deterministic shutdown
- Root recycle leak fix
- Shell command execution path hardening
- IME side-effect mitigation for VD text path
- Debug script stop-on-success

### Phase 2 — Reorg + simplification
- Split `VirtualDisplayPlatform` into cohesive components
- Remove dead code and reduce hot-path logging noise
- Add small focused tests for new pure components where feasible

### Phase 3 — Verify and polish
- End-to-end `debug-run` validation on VD
- Run independent code review
- Remove redundant legacy pieces
- Update docs

## Acceptance Criteria

- No leaked VD after task completion in normal run
- `platform.stop()` path is observed in logs
- Typing on VD no longer leaves persistent keyboard artifact on main display in common flow
- `VirtualDisplayPlatform` shrinks and responsibilities become clear
- Build/tests used in touched scope pass
