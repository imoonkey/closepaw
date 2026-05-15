# Implementation Summary: Unified Agent Mode

**Project**: `projects/active/unified_agent_mode/`
**Branch**: `task/unified-agent-mode` (in worktree `.worktrees/unified-agent-mode/`)
**Status**: Complete, verified, awaiting merge
**Design**: [`design_claude.md`](design_claude.md)
**Code review**: [`code_review_codex.md`](code_review_codex.md) — APPROVE-with-follow-ups
**QA report**: [`qa_report_codex.md`](qa_report_codex.md) — PASS-with-follow-ups

## What changed

Collapsed the BASIC/PRO `AgentMode` distinction into a single Default role with `delegate_task` as an opt-in capability — mirroring Claude Code's main-agent + Agent-tool pattern. Subagent reuses the same role definition with two policy-driven tool exclusions. Subagent actions now stream into the same chat row as the parent's actions (Design C-minimal).

## Code-level deltas

| Area | Before | After |
|---|---|---|
| Role definitions | `StandaloneRoleDef`, `PlannerRoleDef`, `ExecutorRoleDef` | Single `DefaultRoleDef` (delegatable, includes `delegate_task`) |
| `AgentMode` enum | `BASIC` / `PRO`, plumbed through `SessionConfig`, settings, intent, snapshot, LLM bootstrapper, onboarding, trace | Deleted entirely (no compat shim per CLAUDE.md) |
| `AgentExecutionRole` | `STANDALONE` / `PLANNER` / `EXECUTOR` | `MAIN` / `SUBAGENT` |
| Subagent tool exclusions | `{delegate_task}` | `{delegate_task, remember_experience}` (recursion guard + memory-write scope) |
| Model field | `executorModel` | `subagentModel` |
| Settings UI | Pro/Basic mode dropdown + PRO-gated conditionals | Removed; "Subagent Model" label always shown |
| Subagent action visibility | Wrapped as `SubAgentActivity` → no-op'd in handler → silent in chat | Forwarded unchanged through parent's `eventEmitter` (Design C-minimal) |
| Subagent allowlist filtering | Tool registry filtered, but `AgentExecutionConfig.allowedToolNames` unfiltered (gap caught in review) | Both registry and config use the same exclusion set |

## Three-layer history model — what flows where

| Layer | Storage | Subagent contribution |
|---|---|---|
| 1. LLM context (parent) | parent's `HistoryManager` → `context-*.json` | Only the `delegate_task` call + summarized result. Subagent has its own fresh `HistoryManager`; intermediate steps stay private. **Context firewall preserved.** |
| 2. Session record (chat UI) | `SessionRecordingService` → `session-*.json` | Subagent's `ActionProposed`/`ActionExecuted` flow into the parent's event stream and land as `ContentBlockRecord.Action` entries in the same agent_message row, interleaved with the parent's actions. UUID `id` field prevents collision. **Visible in chat history, persists across reload.** |
| 3. Runtime checkpoint | `SessionCheckpointCoordinator` → `SessionRuntimeSnapshot` | Untouched. `RUNNING_DIRTY` mid-delegation never marked reloadable, so a snapshot taken mid-flight is invalid. Reload only happens at `IDLE_READY` or `CLOSED`, where parent's HistoryManager has complete `Call`/`Result` pairs. **Reload safety guaranteed.** |

## Verified end-to-end

Captured during QA: a delegation goal ("compute 7×8 in calculator") produced a single agent_message row containing this content-block sequence:

```
thought                        (parent)
action: delegate_task          (parent, success, "Sub-agent 'default' completed: 56")
action: open_app               (subagent, failed — Calculator)
action: open_app               (subagent, success — 计算器)
action: mobile_action          (subagent, typed 7)
action: mobile_action          (subagent, typed ×)
action: wait                   (subagent)
action: mobile_action          (subagent, typed 8)
action: complete_task          (subagent)
thought                        (parent)
action: complete_task          (parent)
final_text                     (parent)
```

Each block has its own UUID `id`. Persisted at `files/sessions/session-<...>.json` on the device. Reading this file IS the reload path, so on-disk state IS post-reload state.

## Why this works without schema change

- `ChatEventReducer` already keys actions by UUID (`callId`).
- `AgentMessageBuffer` already keys actions by UUID.
- `ContentBlockRecord.Action` already stores UUID.
- `SessionRecordingService` already records by UUID.

Subagent actions are routed through the parent's `eventEmitter` exactly the way the parent's own actions are. Because the keys are unique UUIDs, there's no collision risk and no need for an `originAgent` discriminator. Total surface change: one method body in `SubAgentRunner.bridgeEvent`, plus plumbing the parent emitter through `SessionAgentRunner.ensureDelegationToolRegistered` → `IsolatedSubAgentRunner` constructor.

## Verification

- `./gradlew --no-configuration-cache clean assembleDebug lint test` — BUILD SUCCESSFUL
- 1711 unit tests pass
- Lint clean
- Codex independent code review — APPROVE-with-follow-ups (1 high fixed in-place, 1 medium fixed in-place)
- On-device QA — PASS-with-follow-ups
- Manual delegation flow on device with full evidence captured (32 sequential screenshots + uiautomator dumps + persisted session JSON inspection)

## Open follow-ups (out of scope)

1. **Gradle config-cache + `:app:licenseDebugReport`** — invokes `Task.project` at execution time, breaks `--configuration-cache`. Pre-existing build-infra issue, unrelated to this work.
2. **Runtime test of subagent `ask_user`** — code-traced as correct (shared `AskUserTool` instance through `toolRegistry.createFilteredCopy`, single `UserResponseChannel` lock prevents concurrent collision). No product flow naturally exercised this path during QA.

## Commit log

```
3986168e chore: mark unified-agent-mode tasks done
603efd91 docs: qa report for unified-agent-mode
05ec43d5 docs: codex code review for unified-agent-mode
34d06a9a docs: refresh unified agent mode references          (codex review fix — medium)
6f23899f fix(subagent): align child allowlist with exclusions (codex review fix — high)
c9e8a65d feat(subagent): forward subagent actions to parent event stream  (Design C)
c7defa36 docs: update for unified agent mode
9ba464ec test: drop AgentMode references from tests and scripts
a4a779a3 refactor(settings): remove agent-mode UI and PRO-gated conditionals
4f23302d refactor: rename AgentExecutionRole values to MAIN/SUBAGENT
988492fe refactor: rename executorModel to subagentModel
07fa2ab4 refactor: remove AgentMode enum and BASIC/PRO plumbing
09a88701 refactor: scope remember_experience to main agent only
8ecc9598 refactor: collapse role defs into single DefaultRoleDef
```

## Orchestration notes

This project was driven via `/orchestrate` + `/multmux`. 11 leaf tasks across 7 dispatch waves (uam-1 → [uam-2, uam-3] → uam-4 → uam-5 → uam-6 → uam-7 → [uam-8, uam-9] → codex-review → qa). All workers ran in the shared worktree on the same branch; sequential within a wave to avoid git-index races. Codex was used for the independent code review and initial QA pass; QA report synthesized from on-disk artifacts after the worker hit an LLM payload-too-large error mid-run.
