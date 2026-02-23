status: draft

# Problem Statement: Session Reload

## Background

The Android Agent follows a session-based interaction model. A **session** spans multiple **tasks** (user input → agent action loop cycles). Between tasks, the user can send follow-up messages that build on prior conversation context.

The LLM needs the full prior conversation history to produce coherent follow-ups. LLM providers also cache the KV computation of previous tokens — if the input prefix is byte-identical to a prior request, only the new tokens need computation. This is called **prompt cache hit** and is critical for latency and cost.

## Problem

**The session's LLM conversation context exists only in memory. When the session object dies, follow-up is impossible.**

The session dies when:
- The Android process is killed (OS reclaim, user swipe-away)
- The accessibility service is restarted
- An explicit shutdown is issued (e.g., `debug-run.sh` stopping the agent)
- Activity recreation (configuration change, though this is partially mitigated)

To keep follow-up working, the current design holds all session resources alive in memory between tasks — platform connections, LLM clients, tool registries, the history buffer — just to preserve the conversation history. This is wasteful and fragile.

## Why current persistence can't solve this

The app already persists session data to disk, but for **UI display**, not for LLM context reconstruction. The two representations are fundamentally different:

| LLM context needs | What's persisted for UI | What's lost |
|---|---|---|
| Function call with full arguments (JSON) | Action card with tool name + description | Arguments lost |
| Function call output (full content, success/truncated flags) | Truncated result summary | Full output lost |
| Screen observation (full accessibility tree JSON, flagged as observation) | Not stored (only file path references) | Entire content lost |
| Message role ("user" / "assistant") + metadata | Display-oriented content blocks | Role/metadata lost |

Attempting to reconstruct LLM context from the UI records would produce a different token sequence than the original. This means:
1. The LLM would see a corrupted/incomplete conversation — wrong behavior
2. Even if approximately correct, the prompt cache would miss — full recomputation on every follow-up turn

## Requirements

1. **Follow-up after session death**: The user can send a follow-up message even if the session object has been garbage collected, the process was killed, or the service restarted. The agent should respond as if the conversation never interrupted.

2. **100% accurate context reproduction**: The reloaded conversation context must produce byte-identical LLM input tokens as the original session would have. No approximation. This guarantees prompt cache hits and correct agent behavior.

3. **Resource efficiency**: Session resources (platform, LLM connections, tools) should not need to stay alive between tasks just to preserve history. They should be released after task completion and recreated on demand.

4. **Transparency to the agent**: The Agent and PromptBuilder should not need to know whether they're running in a fresh session or a reloaded one. The history buffer looks the same either way.

## Scope

In scope:
- Persisting LLM conversation context (the `List<ResponseItem>` in HistoryManager) to disk
- Persisting cross-task agent state (todos, scratchpad) to disk
- Reloading a session from persisted state when follow-up arrives
- Lifecycle changes to release resources after task completion

Out of scope (for now):
- Migration of old sessions without context records
