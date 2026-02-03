# Codex Multi-Agent Architecture Summary

> Analysis of OpenAI Codex CLI's approach to hierarchical agents.

## Overview

Codex takes a **parent-child session spawning** approach where sub-agents are full Codex instances with event forwarding and approval routing back to the parent session.

## Key Components

### 1. Hierarchical AGENTS.md System

Codex uses a file-based instruction system via `AGENTS.md` files that provide context-specific guidance:

```
/AGENTS.md              ← Root instructions
/project/AGENTS.md      ← Project-specific overrides
/project/src/AGENTS.md  ← Module-specific overrides
```

**Resolution Rules:**
- Deeper files override higher-level files
- User prompts override all AGENTS.md content
- Files govern their directory and all children

**Purpose:** Pass human guidance to agents (coding standards, build steps, project layout).

---

### 2. codex_delegate.rs - Sub-Agent Spawning

The core multi-agent mechanism is in `codex_delegate.rs`:

```rust
pub async fn run_codex_thread_interactive(
    config: Config,
    auth_manager: Arc<AuthManager>,
    models_manager: Arc<ModelsManager>,
    parent_session: Arc<Session>,
    parent_ctx: Arc<TurnContext>,
    cancel_token: CancellationToken,
    initial_history: Option<InitialHistory>,
) -> Result<Codex, CodexErr>
```

**Key Features:**

| Feature | Description |
|---------|-------------|
| **Full Codex Instance** | Sub-agents are complete Codex instances, not simplified |
| **Event Forwarding** | Non-approval events flow to consumer, approvals route to parent |
| **Approval Routing** | `ExecApprovalRequest` and `PatchApprovalRequest` handled by parent |
| **Signal Propagation** | Parent cancellation cascades to child via `CancellationToken` |
| **Session Source** | Sub-agents tagged with `SessionSource::SubAgent(SubAgentSource::Review)` |

---

### 3. One-Shot vs Interactive Modes

**Interactive Mode:**
```rust
run_codex_thread_interactive(...) -> Codex
// Returns IO channels for ongoing interaction
// Caller can submit additional Ops
```

**One-Shot Mode:**
```rust
run_codex_thread_one_shot(...) -> Codex
// Immediately submits initial input
// Auto-shuts down on TurnComplete/TurnAborted
// Returns closed tx_sub (no further ops allowed)
```

---

### 4. Event Flow Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Parent Session                            │
│                                                                  │
│   ┌────────────────┐         ApprovalRequest         ┌────────┐ │
│   │                │◄──────────────────────────────── │        │ │
│   │  Parent Turn   │                                  │  Sub   │ │
│   │    Context     │ ─────────────────────────────────│ Agent  │ │
│   │                │         ApprovalDecision         │ Codex  │ │
│   └────────────────┘                                  └────────┘ │
│         │                                                  │     │
│         │                                                  │     │
│         ▼                                                  ▼     │
│   ┌────────────────┐                                ┌──────────┐ │
│   │  Other Events  │◄───────────────────────────────│  Events  │ │
│   │   (forwarded)  │         (filtered)             │  Output  │ │
│   └────────────────┘                                └──────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

**Filtered Events (not forwarded):**
- `AgentMessageDelta`, `AgentReasoningDelta` (legacy deltas)
- `TokenCount`
- `SessionConfigured`

**Routed to Parent:**
- `ExecApprovalRequest` → `handle_exec_approval()`
- `ApplyPatchApprovalRequest` → `handle_patch_approval()`

---

### 5. Approval Handling

```rust
async fn handle_exec_approval(
    codex: &Codex,
    id: String,
    parent_session: &Session,
    parent_ctx: &TurnContext,
    event: ExecApprovalRequestEvent,
    cancel_token: &CancellationToken,
) {
    // Request approval from parent session
    let decision = parent_session.request_command_approval(
        parent_ctx,
        parent_ctx.sub_id.clone(),
        event.command,
        event.cwd,
        event.reason,
        event.proposed_execpolicy_amendment,
    ).await;
    
    // Forward decision back to sub-agent
    codex.submit(Op::ExecApproval { id, decision }).await;
}
```

---

### 6. Graceful Shutdown

```rust
async fn shutdown_delegate(codex: &Codex) {
    codex.submit(Op::Interrupt).await;
    codex.submit(Op::Shutdown {}).await;
    
    // Drain events with 500ms timeout
    timeout(Duration::from_millis(500), async {
        while let Ok(event) = codex.next_event().await {
            if matches!(event.msg, TurnAborted(_) | TurnComplete(_)) {
                break;
            }
        }
    }).await;
}
```

---

## Design Principles

1. **Full Agent Composition** - Sub-agents are complete Codex instances, enabling recursive delegation
2. **Approval Bubbling** - Security-sensitive operations bubble to parent for unified approval UX
3. **Signal Propagation** - Cancellation cleanly propagates through hierarchy via `CancellationToken`
4. **Event Filtering** - Parent controls which events surface, filtering noise like deltas
5. **File-Based Context** - AGENTS.md provides directory-scoped human guidance without code changes

---

## Applicability to Android Agent

| Pattern | Applicability | Notes |
|---------|--------------|-------|
| Parent-child session spawning | ✅ High | Enables concurrent tool exploration |
| Approval routing | ✅ High | Critical for Android permissions |
| CancellationToken propagation | ✅ High | Maps to Kotlin coroutine cancellation |
| AGENTS.md system | ⚠️ Medium | Less relevant for mobile context |
| Event filtering | ✅ High | Reduce UI noise from sub-agents |

---

## References

- `codex-rs/core/src/codex_delegate.rs` - Main sub-agent spawning logic
- `codex-rs/core/hierarchical_agents_message.md` - AGENTS.md system documentation
- `AGENTS.md` - Root project instructions
