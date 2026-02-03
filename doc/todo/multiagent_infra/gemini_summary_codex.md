# Gemini CLI Multi-Agent Architecture Summary

> Source focus: `.reference/code_agent/gemini-cli/*`

## High-Level Approach
Gemini CLI implements **agent-as-tool delegation**. Sub-agents are declaratively defined and invoked through a single tool (`delegate_to_agent`) that validates inputs and routes execution to local or remote agents.

## Core Mechanisms

### 1) Agent Definitions (Local / Remote)
- **Local agents** define prompts, model settings, run constraints, and tool allowlists.
- **Remote agents** reference an **A2A agent card URL** and accept a single `query` input.
- Agents define inputs/outputs via schemas for structured validation.

**Relevant file**
- `.reference/code_agent/gemini-cli/packages/core/src/agents/types.ts`

### 2) Agent Registry + Discovery
- Built-in agents are registered programmatically.
- User-level agents: `~/.gemini/agents/*.toml`.
- Project-level agents: `.gemini/agents/*.toml` (requires trusted folder).
- Registry reloads on model changes and supports UI listing.

**Relevant files**
- `.reference/code_agent/gemini-cli/packages/core/src/agents/registry.ts`
- `.reference/code_agent/gemini-cli/packages/core/src/agents/toml-loader.ts`

### 3) Unified Delegation Tool
- `DelegateToAgentTool` builds a **discriminated union schema** keyed by `agent_name`.
- Each agent contributes its input schema, preventing invalid calls at the schema level.
- The tool wraps sub-agent execution into a standard tool invocation.

**Relevant file**
- `.reference/code_agent/gemini-cli/packages/core/src/agents/delegate-to-agent-tool.ts`

### 4) Subagent Tool Wrapper
- A single wrapper exposes each agent as a tool with its own parameter schema.
- Routes execution to either **LocalSubagentInvocation** or **RemoteAgentInvocation**.

**Relevant file**
- `.reference/code_agent/gemini-cli/packages/core/src/agents/subagent-tool-wrapper.ts`

### 5) Local Subagent Execution
- Uses `LocalAgentExecutor`, which:
  - Creates an isolated tool registry per sub-agent.
  - Runs a loop until `complete_task` is called.
  - Enforces time/turn limits and emits termination modes.
  - Provides a grace-period recovery turn if the agent fails to call `complete_task`.

**Relevant files**
- `.reference/code_agent/gemini-cli/packages/core/src/agents/local-executor.ts`
- `.reference/code_agent/gemini-cli/packages/core/src/agents/local-invocation.ts`

### 6) Remote Subagent Invocation (A2A)
- Remote agents are invoked via A2A client manager.
- Maintains `contextId`/`taskId` for ongoing conversations.
- Always requests confirmation for remote calls (policy safety).

**Relevant file**
- `.reference/code_agent/gemini-cli/packages/core/src/agents/remote-invocation.ts`

### 7) Guardrails
- TOML loader **disallows** sub-agents from including `delegate_to_agent` in their tool list (prevents recursion).
- Experimental flag `enableAgents` gates this feature.

**Relevant files**
- `.reference/code_agent/gemini-cli/packages/core/src/agents/toml-loader.ts`
- `.reference/code_agent/gemini-cli/schemas/settings.schema.json`

## Design Implications
- **Declarative agent registry** makes multi-agent extensible and user-configurable.
- **Schema-level validation** prevents wrong agent calls.
- **Isolated tool registries** allow per-agent capability scoping.
- **A2A support** enables remote agent ecosystems.

## Key Takeaways to Borrow
1. **Agent registry + declarative definitions** for local and remote agents.
2. **Single delegation tool** with typed schemas and validation.
3. **Per-agent tool isolation** to enforce capability boundaries.
4. **Recursion guardrails** and feature flags.
