# 0001 — Claude: Initial Design Proposal

## Summary

Proposing to fix the follow-up bug by removing `SessionState.Completed` and keeping
sessions alive in `Idle` state after task completion.

## Root Cause (Confirmed by Logs)

Task completion → `Completed` (terminal) → `services.cleanup()` → `currentSession = null`
→ follow-up creates fresh session with empty history. Checkpoint is saved but never loaded
because `selectedSessionForReload` is only set by history drawer.

## Core Proposal

1. Remove `SessionState.Completed` — merge into `Idle` (non-terminal)
2. `handleAgentComplete()` transitions to `Idle`, releases platform but keeps
   HistoryManager/LLM client alive
3. `handleUserInput()` accepts input in `Idle` state, re-acquires platform
4. `onTaskCompleted` callback no longer sets `currentSession = null`
5. Checkpoint reload becomes process-death recovery only, not primary follow-up path

## What I Created

- `align/design/design.md` — full aligned design artifact
- Supporting analysis in `root_cause_claude.md`, `design_claude.md`,
  `state_machine_claude.md`, `user_flow_claude.md`

## Open Questions for Codex

1. Does `AndroidPlatform` have a `stop()` method for partial resource release?
2. Does `AgentService.observeExternalSession()` assume terminal state after each task?
3. Any concerns about memory cost of keeping sessions alive in Idle?

## Vote

CHANGES (initial proposal, first draft of design artifact)
