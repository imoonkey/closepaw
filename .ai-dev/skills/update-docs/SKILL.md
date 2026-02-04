---
name: update-docs
description: Sync documentation with code changes before commits. Use after architecture or workflow changes.
---

# Update Docs

Keep documentation in sync with code changes.

## When to Activate

- Before commits with architecture/workflow changes
- After modifying public APIs
- After changing build/setup process

## Doc Priority

| Folder | Priority | Update When |
|--------|----------|-------------|
| `doc/main/` | Critical | Architecture changes |
| `doc/dev/` | Critical | Workflow/build changes |
| `doc/todo/` | High | Active project status |
| `doc/archive/` | Low | OK if outdated |

## Doc Structure

```
doc/main/
├── README.md        # Entry point, navigation guide, code structure
│
├── agent/           # Core agent intelligence
│   ├── overview.md  # Design principles, architecture, package structure
│   ├── loop.md      # ReAct loop, Turn, streaming execution
│   ├── multiagent.md # Sub-agent system, delegation, registry
│   └── planning.md  # TodoState, ScratchpadState, context hygiene
│
├── infra/           # Agent infrastructure
│   ├── session.md   # AgentSession, SessionServices, lifecycle
│   ├── tools.md     # Tool system, ToolRouter, ToolRegistry
│   ├── platform.md  # AndroidPlatform, Perceptor, perception
│   └── llm.md       # LLM clients, backends, API configuration
│
├── protocol/        # Communication contracts
│   └── protocol.md  # Op/Event, state machine, errors, config
│
├── app/             # Application layer (non-agentic)
│   ├── history.md   # Session history persistence
│   └── settings.md  # User settings, preferences persistence
│
└── ui/              # User interface
    ├── style.md     # Design system, colors, typography
    ├── tech_design.md # Technical implementation
    ├── user_interaction.md # Pages, user behaviors
    └── overlay.md   # Smart Capsule, Edge Glow, Visualizer
```

## Workflow

### 1. Analyze Changes

```bash
git diff --name-only main...HEAD
```

### 2. Map to Docs

| Code Change | Doc to Update |
|-------------|---------------|
| `agent/AgentRuntime.kt`, `agent/Turn.kt` | `doc/main/agent/loop.md` |
| `agent/subagent/` | `doc/main/agent/multiagent.md` |
| `session/TodoState.kt`, `session/ScratchpadState.kt` | `doc/main/agent/planning.md` |
| `session/AgentSession.kt`, `session/SessionServices.kt` | `doc/main/infra/session.md` |
| `tool/` | `doc/main/infra/tools.md` |
| `platform/`, `perception/` | `doc/main/infra/platform.md` |
| `protocol/` | `doc/main/protocol/protocol.md` |
| `history/` | `doc/main/app/history.md` |
| `llm/` | `doc/main/infra/llm.md` |
| `ui/settings/` | `doc/main/app/settings.md` |
| `ui/theme/` | `doc/main/ui/style.md` |
| `ui/chat/`, `ui/navigation/`, `ui/settings/` | `doc/main/ui/tech_design.md`, `doc/main/ui/user_interaction.md` |
| `ui/overlay/` | `doc/main/ui/overlay.md` |
| Build/gradle | `doc/dev/development.md` |

### 3. Update Principles

- Keep same detail level as existing content
- Don't over-document minor changes
- Use `→ See:` pointers to source files instead of long code blocks
- Link rather than duplicate
- Update timestamps

### 4. Verify

- All doc links still work
- Code examples still valid
- No stale references to removed code

## Output Format

```
DOC UPDATE: [DONE/NEEDED]

Changes analyzed:
- [file] → affects [doc]

Updates made:
- [doc]: [section updated]

Verification: [OK/ISSUES]
```

## Principles

- `doc/main`, `doc/dev`: Must stay current, best onboarding resources
- `doc/todo/`: Active projects, reflect latest status
- `doc/archive`: OK if outdated
- Minimize code blocks; use file pointers (`→ See: path/to/file.kt`)
- Keep discussion at appropriate detail level
