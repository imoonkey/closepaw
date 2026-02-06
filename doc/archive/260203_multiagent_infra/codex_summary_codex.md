# Codex Multi-Agent Architecture Summary (Codex CLI)

> Source focus: `.reference/code_agent/codex/*`

## High-Level Approach
Codex CLI implements **hierarchical sub-agents** by spawning full Codex threads and wiring them into the parent session. Sub-agents are first-class Codex instances with their own event streams, while approvals and lifecycle control are coordinated by the parent.

## Core Mechanisms

### 1) Control Plane: `AgentControl`
- **Purpose**: Spawn and manage sub-agents from any session.
- **Key design**: `AgentControl` holds a **Weak** handle to `ThreadManagerState` to avoid cycles and lingering threads.
- **Main ops**:
  - `spawn_agent(config, prompt, headless)`
  - `send_prompt(agent_id, prompt)`
  - `get_status(agent_id)`

**Relevant files**
- `.reference/code_agent/codex/codex-rs/core/src/agent/control.rs`
- `.reference/code_agent/codex/codex-rs/core/src/thread_manager.rs`

### 2) Sub-Agent Delegation: `codex_delegate.rs`
- **Interactive mode**: `run_codex_thread_interactive()` returns a sub-agent event stream and an op sender for continued interaction.
- **One-shot mode**: `run_codex_thread_one_shot()` immediately sends initial input and auto-shuts down after `TurnComplete`/`TurnAborted`.
- **Event forwarding**: Non-approval events are forwarded to the consumer; approval requests are **intercepted** and routed to the parent.
- **Cancellation**: Parent cancellation cascades to child via `CancellationToken`.

**Relevant file**
- `.reference/code_agent/codex/codex-rs/core/src/codex_delegate.rs`

### 3) Approval Routing (Parent-Handled)
- Child emits approval events (`ExecApprovalRequest`, `ApplyPatchApprovalRequest`).
- Parent session requests approval and then sends `Op::ExecApproval` / `Op::ApplyPatchApproval` back to child.
- Result: **single approval UX**, even with sub-agents.

**Relevant file**
- `.reference/code_agent/codex/codex-rs/core/src/codex_delegate.rs`

### 4) Collab Tools (Spawn/Send/Wait/Close)
- Codex exposes multi-agent ops as tools: `spawn_agent`, `send_input`, `wait`, `close_agent`.
- Tool specs registered conditionally (feature flag `collab_tools`).
- `wait` and `close_agent` are currently stubbed for future lifecycle tracking.

**Relevant files**
- `.reference/code_agent/codex/codex-rs/core/src/tools/spec.rs`
- `.reference/code_agent/codex/codex-rs/core/src/tools/handlers/collab.rs`

### 5) Headless Drain to Prevent Event Buildup
- Headless sub-agents without a UI can produce unbounded events.
- Codex spawns a drain task to consume and discard events until shutdown.

**Relevant file**
- `.reference/code_agent/codex/codex-rs/core/src/agent/control.rs`

## Design Implications
- **Full agent reuse**: Sub-agents run the exact same agent loop as the parent.
- **Approval bubbling**: Centralizes security/UX.
- **Cancellation hygiene**: Child lifecycle strictly tied to parent.
- **Event filtering**: Reduces noise; only meaningful events flow upward.

## Key Takeaways to Borrow
1. **Dedicated control plane** (`AgentControl`) for spawning and messaging agents.
2. **Event bridge** that filters noise and routes approvals to parent.
3. **One-shot sub-agent pattern** for simple delegation flows.
4. **Headless drain** to avoid event queue growth when no UI consumer.
