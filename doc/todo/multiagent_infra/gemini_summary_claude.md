# Gemini CLI Multi-Agent Architecture Summary

> Analysis of Google Gemini CLI's approach to delegated sub-agents.

## Overview

Gemini CLI uses a **registry-based delegation** pattern where sub-agents are defined declaratively and invoked via a unified `delegate_to_agent` tool. Agents can be **local** (same process) or **remote** (A2A protocol).

## Key Components

### 1. Agent Types

```typescript
type AgentDefinition<TOutput> = LocalAgentDefinition<TOutput> | RemoteAgentDefinition<TOutput>;

interface LocalAgentDefinition<TOutput> {
  kind: 'local';
  name: string;
  description: string;
  inputConfig: InputConfig;
  outputConfig?: OutputConfig<TOutput>;
  promptConfig: PromptConfig;
  modelConfig: ModelConfig;
  runConfig: RunConfig;
  toolConfig?: ToolConfig;
}

interface RemoteAgentDefinition<TOutput> {
  kind: 'remote';
  name: string;
  description: string;
  inputConfig: InputConfig;
  agentCardUrl: string;  // A2A agent card endpoint
}
```

---

### 2. AgentRegistry - Discovery & Management

```typescript
class AgentRegistry {
  private readonly agents = new Map<string, AgentDefinition>();
  
  async initialize(): Promise<void> {
    this.loadBuiltInAgents();
    
    // Load from ~/.gemini/agents/
    const userAgents = await loadAgentsFromDirectory(userAgentsDir);
    
    // Load from .gemini/agents/ (project-level)
    if (isTrustedFolder) {
      const projectAgents = await loadAgentsFromDirectory(projectAgentsDir);
    }
  }
  
  getToolDescription(): string {
    // Generate tool description with all available agents
  }
  
  getDirectoryContext(): string {
    // Generate "phone book" for system prompt
  }
}
```

**Loading Sources:**
1. Built-in agents (e.g., `CodebaseInvestigatorAgent`, `CliHelpAgent`)
2. User-level: `~/.gemini/agents/*.toml`
3. Project-level: `.gemini/agents/*.toml` (if trusted)

---

### 3. DelegateToAgentTool - Unified Delegation

```typescript
class DelegateToAgentTool extends BaseDeclarativeTool<DelegateParams, ToolResult> {
  constructor(registry: AgentRegistry, config: Config, messageBus: MessageBus) {
    // Build discriminated union schema from all registered agents
    const agentSchemas = definitions.map((def) => z.object({
      agent_name: z.literal(def.name).describe(def.description),
      // ... agent-specific inputs
    }));
    
    const schema = z.discriminatedUnion('agent_name', agentSchemas);
  }
}
```

**Schema Generation:**
- Creates a discriminated union based on `agent_name`
- Each agent has its own typed input schema
- Prevents invalid agent references at schema level

---

### 4. SubagentToolWrapper - Agent as Tool

```typescript
class SubagentToolWrapper extends BaseDeclarativeTool<AgentInputs, ToolResult> {
  constructor(definition: AgentDefinition, config: Config, messageBus: MessageBus) {
    super(
      definition.name,
      definition.displayName ?? definition.name,
      definition.description,
      Kind.Think,
      convertInputConfigToJsonSchema(definition.inputConfig),
      messageBus,
    );
  }
  
  createInvocation(params: AgentInputs): ToolInvocation<AgentInputs, ToolResult> {
    if (definition.kind === 'remote') {
      return new RemoteAgentInvocation(definition, params, messageBus);
    }
    return new LocalSubagentInvocation(definition, config, params, messageBus);
  }
}
```

---

### 5. LocalAgentExecutor - Execution Loop

```typescript
class LocalAgentExecutor<TOutput> {
  async run(inputs: AgentInputs, signal: AbortSignal): Promise<OutputObject> {
    const chat = await this.createChatObject(augmentedInputs, tools);
    
    while (true) {
      const reason = this.checkTermination(startTime, turnCounter);
      if (reason) break;
      
      const turnResult = await this.executeTurn(
        chat, currentMessage, turnCounter++, combinedSignal, timeoutController.signal
      );
      
      if (turnResult.status === 'stop') break;
      currentMessage = turnResult.nextMessage;
    }
    
    // Recovery attempt if not GOAL or ABORTED
    if (needsRecovery) {
      const recoveryResult = await this.executeFinalWarningTurn(...);
    }
    
    return { result: finalResult, terminate_reason: terminateReason };
  }
}
```

**Termination Modes:**
- `GOAL` - Agent called `complete_task`
- `TIMEOUT` - Time limit exceeded
- `MAX_TURNS` - Turn limit reached
- `ABORTED` - External cancellation
- `ERROR_NO_COMPLETE_TASK_CALL` - Protocol violation

**Recovery Mechanism:**
- Grace period (60s) for agent to call `complete_task`
- Only attempted for `TIMEOUT`, `MAX_TURNS`, `ERROR_NO_COMPLETE_TASK_CALL`

---

### 6. Activity Streaming

```typescript
interface SubagentActivityEvent {
  isSubagentActivityEvent: true;
  agentName: string;
  type: 'TOOL_CALL_START' | 'TOOL_CALL_END' | 'THOUGHT_CHUNK' | 'ERROR';
  data: Record<string, unknown>;
}

// In LocalSubagentInvocation
const onActivity = (activity: SubagentActivityEvent): void => {
  if (activity.type === 'THOUGHT_CHUNK') {
    updateOutput(`🤖💭 ${activity.data['text']}`);
  }
};
```

---

### 7. Input/Output Configuration

```typescript
interface InputConfig {
  inputs: Record<string, {
    description: string;
    type: 'string' | 'number' | 'boolean' | 'integer' | 'string[]' | 'number[]';
    required: boolean;
  }>;
}

interface OutputConfig<T extends z.ZodTypeAny> {
  outputName: string;
  description: string;
  schema: T;  // Zod schema for validation
}
```

---

### 8. Remote Agent Integration (A2A)

```typescript
class RemoteAgentInvocation extends BaseToolInvocation<AgentInputs, ToolResult> {
  async execute(signal: AbortSignal, updateOutput?): Promise<ToolResult> {
    const client = await A2AClientManager.getInstance().getClient(
      definition.name,
      definition.agentCardUrl,
      authHandler
    );
    
    // Send task via A2A protocol
    const response = await client.sendTask({
      query: params.query,
      // ... other params
    }, signal);
    
    return { llmContent: response.result, returnDisplay: response.result };
  }
}
```

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        Parent Agent                              │
│                                                                  │
│   ┌──────────────────┐                                          │
│   │  Tool Registry   │                                          │
│   │  (includes       │                                          │
│   │  delegate_to_    │                                          │
│   │  agent tool)     │                                          │
│   └────────┬─────────┘                                          │
│            │                                                     │
│            ▼                                                     │
│   ┌──────────────────┐        ┌────────────────────────────┐   │
│   │DelegateToAgentTool│────────│      AgentRegistry         │   │
│   └────────┬─────────┘        │  - getAllDefinitions()     │   │
│            │                  │  - getDefinition(name)     │   │
│            │                  │  - getToolDescription()    │   │
│            ▼                  └────────────────────────────┘   │
│   ┌──────────────────┐                                          │
│   │SubagentToolWrapper│                                         │
│   │  (per agent)     │                                          │
│   └────────┬─────────┘                                          │
│            │                                                     │
│            ├────────────────────────────────────────┐           │
│            ▼                                        ▼           │
│   ┌──────────────────┐                    ┌──────────────────┐ │
│   │LocalSubagent     │                    │RemoteAgent       │ │
│   │Invocation        │                    │Invocation        │ │
│   │                  │                    │                  │ │
│   │ LocalAgentExecutor│                   │  A2A Client      │ │
│   │ - run()          │                    │  - sendTask()    │ │
│   │ - executeTurn()  │                    │                  │ │
│   └──────────────────┘                    └──────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## Design Principles

1. **Declarative Definition** - Agents defined via TOML configs or code
2. **Strong Typing** - Zod schemas for input/output validation
3. **Local/Remote Abstraction** - Same interface for both execution modes
4. **Isolated Tool Registry** - Each sub-agent gets its own tool set
5. **Streaming Activity** - Real-time thought/action updates to parent
6. **Graceful Recovery** - Final warning turn before hard termination

---

## Applicability to Android Agent

| Pattern | Applicability | Notes |
|---------|--------------|-------|
| Registry-based delegation | ✅ High | Clean tool-based dispatch |
| Declarative agent definition | ✅ High | Enables config-driven agents |
| Input/Output schemas | ✅ High | Type safety for agent calls |
| Activity streaming | ✅ High | Maps to our MessageDelta events |
| Local/Remote split | ⚠️ Medium | Remote less relevant initially |
| `complete_task` tool | ✅ High | Similar to our pattern |
| Grace period recovery | ✅ High | Improves success rate |

---

## References

- `packages/core/src/agents/delegate-to-agent-tool.ts` - Delegation tool
- `packages/core/src/agents/subagent-tool-wrapper.ts` - Agent-as-tool wrapper
- `packages/core/src/agents/local-executor.ts` - Local execution loop
- `packages/core/src/agents/registry.ts` - Agent registration
- `packages/core/src/agents/types.ts` - Type definitions
