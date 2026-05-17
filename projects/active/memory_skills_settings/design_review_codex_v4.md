# KISS Design Review v4: Memory & Skills in Settings

> Review target: `projects/active/memory_skills_settings/design_claude.md`
> Reviewer: Codex
> Date: 2026-05-17

## Verdict

**LGTM, only nits remaining.**

v5 closes the design issues from the last pass. The single-writer model is now explicit enough to replace `MemoryStoreHolder`, revision hashes, mtime guards, and merge UI. The important part is the conservative lock: `Created / Running / Idle / TakeoverPending / Paused` all lock; only `null` or `Shutdown` unlock. That is the right KISS boundary.

## Confirmation

### MemoryEditGate

This design closes the concurrency problem, with one implementation detail that must be honored: `currentSessionState` needs to flip to `Created` as soon as session creation/reload begins, before the `create()` lambda can suspend on `AgentSession.create`. If implementation only emits after `currentSession = session`, then the “creation in progress” window is not actually locked.

That is a small implementation nit, not a design blocker. Add it to the `memory_edit_gate` acceptance criteria as: “`createAndSubmit` emits locked/Created before invoking the suspending create block; aborted creation emits null.”

The action-time re-check on save/delete/+Memory is the correct second layer. UI disabled state alone was not enough; this fixes the TOCTOU class we cared about.

### Append Rules

Append rules are now complete enough:

- sanitation order is correct;
- heading missing / duplicate heading / new file behavior is deterministic;
- per-entry cap and per-file cap are separated;
- 8 KB is defined as UTF-8 bytes;
- returning Boolean keeps existing callers simple.

Boolean on over-cap is acceptable for KISS. Tests must assert the log/false path so it does not collapse into silent failure.

### Previous Findings

All prior High/Medium findings are either addressed or explicitly deferred with a sane trade-off:

- live skill filtering → next-session semantics;
- disabled skill visibility → `allDiscovered()`;
- `MemoryStoreHolder` / session tracker extraction → replaced by edit gate;
- mtime/hash conflict → replaced by edit gate;
- vague append behavior → explicit insertion rules;
- long inline editor → bounded + full editor escape;
- per-row IO → `AppAccessContentIndex`;
- invisible expansion → explicit chevron / `+ Memory`;
- blank/over-cap recall → filtered/skipped;
- ToolSettingsCard click ambiguity → info icon slot;
- double-buffer race → dirty bounded editor cannot open full editor.

### Task Split

Nine tasks is reasonable. `memory_edit_gate` deserves its own task; the rest are scoped cleanly. Do not merge it back into `memory_file_editor` because it is the correctness boundary for the whole single-writer simplification.

## Nits

1. In `memory_edit_gate`, explicitly test “creation suspends while no `AgentSession` exists yet, gate remains locked.”
2. In `agent_skill_toggle_ui`, run `BundledAgentSkillInstaller` on `Dispatchers.IO`; Settings should not scan/copy bundled skill files on main.
3. The abort toast says “Save aborted” but the same gate applies to Delete and `+ Memory`; either use operation-specific text or generic “Memory edit aborted — a session just started.”

## Bottom Line

Ship the design. The remaining work is implementation discipline, not another design round.
