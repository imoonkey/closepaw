# Folder Structure Redesign Proposal

> Date: 2026-01-20  
> Status: Implemented

## Overview

This document proposes a reorganization of the Kotlin source files in `app/src/main/kotlin/com/moonkey/androidagent/`. The primary goal is to consolidate tool-related code and improve overall package cohesion.

---

## Current Structure Issues

### 1. Tool System Fragmentation

Tool-related code is scattered across 4 different locations:

| Location | Contents |
|----------|----------|
| `infra/tools/` | ToolRouter, ToolSpec, ToolCallState, ToolCallResult |
| `infra/registry/` | ToolRegistry |
| `infra/policy/` | PolicyEngine (tool approval logic) |
| `tools/` | BaseTool and concrete implementations |

### 2. `infra/` Package is Overloaded

Contains unrelated subsystems grouped only by being "infrastructure":
- `infra/history/` - Conversation history
- `infra/policy/` - Tool approval
- `infra/registry/` - Tool discovery
- `infra/tools/` - Tool execution

### 3. `data/` Package is Misleading

Named "data" but contains external service integrations:
- `data/llm/LLMClient.kt` - LLM API client
- `data/perception/Perceptor.kt` - Screen perception engine

### 4. Entry Points at Root

`AgentService.kt` and `MainActivity.kt` sit at the package root without a containing package.

### 5. Redundant Nesting

`domain/models/Models.kt` has unnecessary depth for a single file.

---

## Proposed Structure

```
com.moonkey.androidagent/
│
├── app/                          # Application entry points
│   ├── MainActivity.kt           # UI entry point
│   └── AgentService.kt           # AccessibilityService entry point
│
├── agent/                        # Core agent logic (UNCHANGED)
│   ├── Agent.kt                  # ReAct loop
│   ├── AgentConfig.kt            # Configuration
│   └── Turn.kt                   # Single LLM turn
│
├── session/                      # Session management (UNCHANGED)
│   ├── AgentSession.kt           # Lifecycle manager
│   └── SessionServices.kt        # DI container
│
├── tool/                         # *** CONSOLIDATED TOOL SYSTEM ***
│   │
│   │  # Core abstractions
│   ├── ToolSpec.kt               # Interface + types
│   ├── ToolCallState.kt          # State definitions
│   ├── ToolCallResult.kt         # Result types
│   │
│   │  # Infrastructure
│   ├── ToolRegistry.kt           # Discovery/registration
│   ├── ToolRouter.kt             # Execution state machine
│   ├── PolicyEngine.kt           # Approval logic
│   │
│   │  # Implementations
│   ├── BaseTool.kt               # Abstract base class
│   └── impl/                     # Concrete tools
│       ├── ClickTool.kt
│       ├── TypeTool.kt
│       ├── ScrollTool.kt
│       ├── SwipeTool.kt
│       ├── NavigationTools.kt    # BackTool + HomeTool
│       ├── WaitTool.kt
│       └── CompleteTaskTool.kt
│
├── protocol/                     # Communication contracts (UNCHANGED)
│   ├── Op.kt                     # Operations (UI → Agent)
│   ├── AgentEvent.kt             # Events (Agent → UI)
│   ├── SessionState.kt           # State machine
│   ├── SessionId.kt              # ID value class
│   ├── AgentError.kt             # Error types
│   └── ApprovalTypes.kt          # Approval enums
│
├── platform/                     # Android platform abstraction (UNCHANGED)
│   ├── AndroidPlatform.kt        # Interface
│   ├── AccessibilityPlatform.kt  # Implementation
│   ├── UIAction.kt               # Action types
│   └── ActionResult.kt           # Result types
│
├── perception/                   # Screen perception (was data/perception)
│   └── Perceptor.kt              # Accessibility tree → ScreenSnapshot
│
├── llm/                          # LLM integration (was data/llm)
│   └── LLMClient.kt              # OpenAI Responses API
│
├── history/                      # Conversation history (was infra/history)
│   └── HistoryManager.kt         # Token management, truncation
│
├── model/                        # Domain models (was domain/models)
│   └── Models.kt                 # ScreenSnapshot, PerceptionElement, etc.
│
├── ui/                           # UI layer
│   ├── screen/
│   │   └── AgentScreen.kt
│   ├── theme/
│   │   ├── Color.kt
│   │   ├── Theme.kt
│   │   └── Type.kt
│   └── overlay/                  # (was service/OverlayManager)
│       └── OverlayManager.kt
│
└── util/
    └── StatusUtils.kt
```

---

## Change Details

### 1. Tool System Consolidation (`tool/`)

All tool-related code moves to a single `tool/` package:

| Before | After |
|--------|-------|
| `infra/tools/ToolSpec.kt` | `tool/ToolSpec.kt` |
| `infra/tools/ToolCallState.kt` | `tool/ToolCallState.kt` |
| `infra/tools/ToolCallResult.kt` | `tool/ToolCallResult.kt` |
| `infra/tools/ToolRouter.kt` | `tool/ToolRouter.kt` |
| `infra/registry/ToolRegistry.kt` | `tool/ToolRegistry.kt` |
| `infra/policy/PolicyEngine.kt` | `tool/PolicyEngine.kt` |
| `tools/base/BaseTool.kt` | `tool/BaseTool.kt` |
| `tools/impl/*` | `tool/impl/*` |

**Rationale**: PolicyEngine is specifically for tool approval decisions—it's tightly coupled to tool execution and belongs with the tool system.

### 2. Entry Points (`app/`)

| Before | After |
|--------|-------|
| `AgentService.kt` (root) | `app/AgentService.kt` |
| `MainActivity.kt` (root) | `app/MainActivity.kt` |

**Rationale**: These are Android framework entry points, not core logic. Grouping them in `app/` follows common conventions.

### 3. Flatten Infrastructure

| Before | After |
|--------|-------|
| `data/perception/Perceptor.kt` | `perception/Perceptor.kt` |
| `data/llm/LLMClient.kt` | `llm/LLMClient.kt` |
| `infra/history/HistoryManager.kt` | `history/HistoryManager.kt` |

**Rationale**: Each is a standalone, cohesive subsystem. The `data/` and `infra/` groupings added no semantic value.

### 4. Simplify Domain Models

| Before | After |
|--------|-------|
| `domain/models/Models.kt` | `model/Models.kt` |

**Rationale**: The `domain/` layer adds no value for a single-file package.

### 5. Relocate Overlay

| Before | After |
|--------|-------|
| `service/OverlayManager.kt` | `ui/overlay/OverlayManager.kt` |

**Rationale**: The `service/` package only contained UI overlay code. It belongs in the UI layer.

---

## Packages Unchanged

| Package | Reason |
|---------|--------|
| `agent/` | Clean, cohesive—contains only core agent logic |
| `session/` | Clean, cohesive—session lifecycle |
| `protocol/` | Clean, cohesive—Op/Event contracts |
| `platform/` | Clean, cohesive—Android abstraction |
| `ui/screen/`, `ui/theme/` | Already well-organized |
| `util/` | Already minimal |

---

## Visual Comparison

```
BEFORE                              AFTER
──────────────────────────────      ──────────────────────────────
AgentService.kt                     app/
MainActivity.kt                       AgentService.kt
                                      MainActivity.kt

agent/                              agent/
  Agent.kt                            (unchanged)
  AgentConfig.kt
  Turn.kt

session/                            session/
  AgentSession.kt                     (unchanged)
  SessionServices.kt

infra/                              tool/                    ← CONSOLIDATED
  history/                            ToolSpec.kt
    HistoryManager.kt                 ToolCallState.kt
  policy/                             ToolCallResult.kt
    PolicyEngine.kt                   ToolRegistry.kt
  registry/                           ToolRouter.kt
    ToolRegistry.kt                   PolicyEngine.kt
  tools/                              BaseTool.kt
    ToolCallResult.kt                 impl/
    ToolCallState.kt                    ClickTool.kt
    ToolRouter.kt                       TypeTool.kt
    ToolSpec.kt                         ScrollTool.kt
                                        SwipeTool.kt
tools/                                  NavigationTools.kt
  base/                                 WaitTool.kt
    BaseTool.kt                         CompleteTaskTool.kt
  impl/
    ClickTool.kt                    perception/              ← FLATTENED
    TypeTool.kt                       Perceptor.kt
    ScrollTool.kt
    SwipeTool.kt                    llm/                     ← FLATTENED
    BackTool.kt                       LLMClient.kt
    WaitTool.kt
    CompleteTaskTool.kt             history/                 ← FLATTENED
                                      HistoryManager.kt
data/
  llm/                              model/                   ← SIMPLIFIED
    LLMClient.kt                      Models.kt
  perception/
    Perceptor.kt                    protocol/
                                      (unchanged)
domain/
  models/                           platform/
    Models.kt                         (unchanged)

protocol/                           ui/
  (6 files)                           overlay/               ← RELOCATED
                                        OverlayManager.kt
platform/                             screen/
  (4 files)                             AgentScreen.kt
                                      theme/
service/                                Color.kt
  OverlayManager.kt                     Theme.kt
                                        Type.kt
ui/
  screen/                           util/
    AgentScreen.kt                    StatusUtils.kt
  theme/
    Color.kt
    Theme.kt
    Type.kt

util/
  StatusUtils.kt
```

---

## Migration Plan

### Phase 1: Tool Consolidation

1. Create `tool/` package
2. Move files from `infra/tools/`, `infra/registry/`, `infra/policy/`
3. Move `tools/base/BaseTool.kt` to `tool/BaseTool.kt`
4. Move `tools/impl/` to `tool/impl/`
5. Rename `BackTool.kt` to `NavigationTools.kt` (already contains both Back and Home)
6. Delete empty `infra/`, `tools/` directories
7. Update all imports

### Phase 2: Entry Points

1. Create `app/` package
2. Move `AgentService.kt` and `MainActivity.kt`
3. Update AndroidManifest.xml if needed
4. Update imports

### Phase 3: Flatten Packages

1. Move `data/perception/Perceptor.kt` → `perception/Perceptor.kt`
2. Move `data/llm/LLMClient.kt` → `llm/LLMClient.kt`
3. Move `infra/history/HistoryManager.kt` → `history/HistoryManager.kt`
4. Move `domain/models/Models.kt` → `model/Models.kt`
5. Delete empty directories
6. Update imports

### Phase 4: UI Overlay

1. Create `ui/overlay/`
2. Move `service/OverlayManager.kt` → `ui/overlay/OverlayManager.kt`
3. Delete empty `service/` directory
4. Update imports

---

## Impact Assessment

| Metric | Value |
|--------|-------|
| Files moved | ~22 |
| New packages | 5 (`app/`, `tool/`, `perception/`, `llm/`, `history/`, `model/`, `ui/overlay/`) |
| Deleted packages | 6 (`infra/*`, `data/*`, `domain/*`, `tools/*`, `service/`) |
| Risk | Low—mostly package declaration and import changes |

### Files Requiring Import Updates

All files that reference moved packages will need import updates. Key files:
- `SessionServices.kt` - Creates most services
- `Agent.kt` - Uses ToolRouter, HistoryManager
- `Turn.kt` - Uses ToolRegistry, LLMClient
- `AgentSession.kt` - Creates SessionServices

---

## Documentation Updates Required

After migration, update:
- `doc/main/agent_infra.md` - Package structure section
- `doc/per_file_summary.md` - File locations

---

## Decision Log

| Decision | Rationale |
|----------|-----------|
| Include PolicyEngine in `tool/` | PolicyEngine is specifically about tool approval—it's tightly coupled to tool execution |
| Keep `tool/impl/` subpackage | Separates interface from implementation, keeps `tool/` root manageable |
| Flatten BaseTool to `tool/` | Only one file; `base/` subpackage was unnecessary |
| Use `model/` not `domain/` | Simpler; `domain/` adds no value for one file |
| Use `app/` for entry points | Common convention; clearly separates framework integration from core logic |
