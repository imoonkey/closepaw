# Agent Tracking & Visualization System Design

> **Date**: 2026-02-04  
> **Status**: Draft  
> **Author**: Claude  
> **Companion**: Complements `logging_and_viz_design_gemini.md`

## 1. Executive Summary

This document proposes a **unified logging and visualization system** for rapid iteration on the Android Agent's cognition core. The design builds on the existing `AgentTrace` + `FileTraceRecorder` infrastructure while adding:

1. **Hierarchical Session Tracking** — Clear parent-child linking for main agent → sub-agent relationships
2. **Step-based Visualization** — Walk through each cognitive step with visual + mental state side by side
3. **Minimal Android Changes** — Most work is in the visualizer; logging enhancements are surgical

## 2. Current State Analysis

### 2.1 What Already Works Well ✅

| Component | Status | Notes |
|-----------|--------|-------|
| `FileTraceRecorder` | ✅ Solid | JSONL event stream + artifacts, async buffered writes |
| `AgentTrace` | ✅ Good | Comprehensive event coverage: session, turn, screen, LLM, tool |
| Artifact Storage | ✅ Good | Organized by kind: `screenshot/`, `llm_full_prompt/`, etc. |
| Redaction | ✅ Done | `CognitionTraceRedactor` handles sensitive data |
| Event Schema | ✅ Rich | Full LLM input/output + tool args/results captured |

### 2.2 What's Missing ❌

| Gap | Impact | Priority |
|-----|--------|----------|
| **Sub-agent session linking** | Can't trace Planner→Executor flow | 🔴 High |
| **Agent role identification** | No way to distinguish PLANNER vs EXECUTOR in trace | 🔴 High |
| **Visualizer is primitive** | 3-panel viewer lacks hierarchy, split view, step-by-step | 🔴 High |
| **Multiplexed session parsing** | Sub-agent events interleaved but not grouped | 🟡 Medium |

### 2.3 Existing Trace Structure (Good Foundation)

```
debug-output/run_YYYYMMDD_HHMMSS/
├── trace/
│   ├── meta.json                      # Run metadata
│   ├── trace.jsonl                    # Multiplexed event stream
│   └── artifacts/
│       ├── screenshot/                # Per-turn screenshots
│       ├── llm_full_prompt/           # Complete prompts
│       ├── llm_input_items/           # Structured input items
│       ├── llm_tool_calls/            # Tool call JSON
│       ├── raw_a11y_tree/             # Full accessibility XML
│       ├── sanitized_a11y_tree/       # Filtered elements
│       ├── tool_call_args/            # Tool invocation args
│       └── tool_result/               # Tool execution results
```

Sub-agent sessions already use composite sessionIds:
```
parent:       952fb0f6-f067-461b-9c20-4cf7a8c751c1
sub-agent:    952fb0f6-f067-461b-9c20-4cf7a8c751c1::sub-executor-1770234716326
```

**Key insight**: The `::` delimiter + hierarchical naming is already there! We just need to:
1. Add explicit `parent_session_id` and `agent_role` to events
2. Build a visualizer that parses this hierarchy

## 3. Design Principles

1. **Preserve Existing Infrastructure** — Don't rewrite what works; enhance surgically
2. **Separation of Concerns** — Logging (Android) vs Visualization (Web) are independent
3. **Offline-First** — Visualizer works with pulled trace folders, no live connection needed
4. **Step-Oriented UI** — The cognitive loop is: Perceive → Think → Act → Observe → Repeat
5. **Zero Build Step** — Visualizer uses vanilla ES6 modules for instant iteration

## 4. Logging System Enhancements (Android Side)

### 4.1 Data Model Changes

#### Add to `AgentConfig.kt`

```kotlin
data class AgentConfig(
    // ... existing fields ...
    val sessionId: SessionId,
    val parentSessionId: SessionId? = null,  // [NEW] Link to parent
    val agentRole: AgentRole = AgentRole.PLANNER,  // [NEW] Role metadata
    // ...
)

enum class AgentRole {
    PLANNER,    // Main orchestration agent
    EXECUTOR,   // Delegated sub-agent for UI actions
    STANDALONE  // Single-agent mode (no delegation)
}
```

#### Update `SubAgentRunner.kt`

```kotlin
val childAgent = Agent(
    config = AgentConfig(
        // ... existing ...
        parentSessionId = parentSessionId,  // [NEW] Pass parent reference
        agentRole = AgentRole.EXECUTOR      // [NEW] Mark as executor
    ),
    // ...
)
```

### 4.2 Event Schema Updates

#### `session_started` Event (Enhanced)

```json
{
  "type": "session_started",
  "sessionId": "parent::sub-executor-123",
  "data": {
    "parent_session_id": "parent",           // [NEW] Explicit parent link
    "agent_role": "EXECUTOR",                // [NEW] Role for UI display
    "goal": "Tap the search button",
    "task_id": "sub-executor-123",
    "max_turns": 5,
    "cognition_profile_id": "baseline"
  }
}
```

This is **backward compatible** — existing events without these fields still parse correctly.

### 4.3 Implementation Checklist

1. [ ] Add `parentSessionId: SessionId?` to `AgentConfig`
2. [ ] Add `agentRole: AgentRole` enum + field to `AgentConfig`
3. [ ] Update `IsolatedSubAgentRunner` to set `parentSessionId` and `agentRole = EXECUTOR`
4. [ ] Update `AgentTrace.sessionStarted()` to emit `parent_session_id` and `agent_role` in data payload
5. [ ] Update `AgentFactory` / main agent creation to set `agentRole = PLANNER` (or `STANDALONE`)

**Estimated effort**: ~30 min of surgical edits

## 5. Visualizer Design (Web Side)

### 5.1 Architecture Overview

```
inspection_tool/v2/
├── index.html           # Entry point, loads modules
├── styles.css           # Dark theme, 3-column layout
├── js/
│   ├── main.js          # App entry, orchestrates modules
│   ├── trace-loader.js  # Parse trace.jsonl + artifacts
│   ├── session-tree.js  # Build & render session hierarchy
│   ├── timeline.js      # Turn-by-turn navigation
│   ├── world-panel.js   # Screenshot + A11y overlay
│   ├── mind-panel.js    # Prompt, reasoning, tool calls
│   └── utils.js         # Helpers
└── lib/                 # Optional: lit-html for templating
```

### 5.2 UI Layout

```
┌────────────────────────────────────────────────────────────────────────────┐
│  📂 Load Trace    │  run_20260204_115128  │  ⏮ ◀ Step 5/23 ▶ ⏭  │  🔍 Filter │
├──────────────────┬──────────────────────────────────────────────────────────┤
│                  │                                                          │
│  SESSION TREE    │  STEP DETAIL VIEW                                        │
│  ────────────    │  ─────────────────                                       │
│                  │  ┌─────────────────────┬──────────────────────────────┐  │
│  ◉ [P] Main      │  │                     │                              │  │
│    │             │  │    WORLD            │     MIND                     │  │
│    ├─ [E] Sub 1  │  │   (Screenshot)      │    (LLM State)               │  │
│    │   └ ✅ done │  │                     │                              │  │
│    │             │  │  ┌───────────────┐  │  ┌──────────────────────────┐│  │
│    ├─ [E] Sub 2  │  │  │               │  │  │ SYSTEM PROMPT            ││  │
│    │   └ ✅ done │  │  │  [Screenshot] │  │  │ ────────────────         ││  │
│    │             │  │  │  + A11y boxes │  │  │ You are the MAIN...      ││  │
│    └─ [E] Sub 3  │  │  │               │  │  └──────────────────────────┘│  │
│        └ ⏳ runs │  │  └───────────────┘  │                              │  │
│                  │  │                     │  ┌──────────────────────────┐│  │
│  ────────────    │  │  Elements: 31       │  │ TOOL CALL                ││  │
│  Legend:         │  │  Package: youtube   │  │ ──────────               ││  │
│  [P] Planner     │  │                     │  │ delegate_task(           ││  │
│  [E] Executor    │  │                     │  │   agent_name="executor", ││  │
│  ✅ Success      │  │                     │  │   query="Tap search"     ││  │
│  ❌ Failed       │  │                     │  │ )                        ││  │
│  ⏳ Running      │  │                     │  └──────────────────────────┘│  │
│                  │  └─────────────────────┴──────────────────────────────┘  │
└──────────────────┴──────────────────────────────────────────────────────────┘
```

### 5.3 Core Components

#### A. Session Tree Panel (`session-tree.js`)

**Responsibilities**:
- Parse all `session_started` / `session_stopped` events
- Build tree using `parent_session_id` (or parse `sessionId` with `::` delimiter)
- Show pass/fail status based on final tool call (`complete_task` with status)
- Clicking a session filters timeline to that session's events

**Tree Building Algorithm**:
```javascript
function buildSessionTree(events) {
  const sessions = new Map(); // sessionId -> node
  
  for (const e of events.filter(e => e.type === 'session_started')) {
    const parentId = e.data?.parent_session_id || parseParentFromId(e.sessionId);
    sessions.set(e.sessionId, {
      id: e.sessionId,
      parentId,
      role: e.data?.agent_role || inferRole(e.sessionId),
      goal: e.data?.goal,
      children: [],
      status: 'running'
    });
  }
  
  // Link children to parents
  for (const [id, node] of sessions) {
    if (node.parentId && sessions.has(node.parentId)) {
      sessions.get(node.parentId).children.push(node);
    }
  }
  
  // Update status from session_stopped events
  for (const e of events.filter(e => e.type === 'session_stopped')) {
    if (sessions.has(e.sessionId)) {
      sessions.get(e.sessionId).status = e.data?.reason || 'done';
    }
  }
  
  return sessions;
}

function parseParentFromId(sessionId) {
  const parts = sessionId.split('::');
  return parts.length > 1 ? parts.slice(0, -1).join('::') : null;
}
```

#### B. Timeline Panel (`timeline.js`)

**Responsibilities**:
- Group events by turn: `turn_started` → `screen_captured` → `llm_request` → `llm_response` → `tool_call` → `tool_result` → `turn_completed`
- Display as a vertical step list with icons per phase
- Keyboard navigation: ←/→ to move between steps
- Clicking a step updates detail view

**Step Types**:
```javascript
const STEP_ICONS = {
  turn_started: '🔄',
  screen_captured: '📷',
  llm_request: '🧠',
  llm_response: '💭',
  tool_arbitration: '⚖️',
  tool_call: '🔧',
  tool_result: '📦',
  turn_completed: '✅',
  turn_error: '❌'
};
```

#### C. World Panel (`world-panel.js`)

**Responsibilities**:
- Display screenshot from `screenshot/` artifact
- Overlay bounding boxes from `sanitized_a11y_tree/` JSON
- Hover to highlight element, show index + text
- Click to select element (for debugging)

**Overlay Rendering**:
```javascript
async function renderWorldPanel(step, artifactLoader) {
  const screenshot = await artifactLoader.loadImage(step.artifacts.find(a => a.kind === 'screenshot'));
  const elements = await artifactLoader.loadJson(step.artifacts.find(a => a.kind === 'sanitized_a11y_tree'));
  
  // Render screenshot as background
  // For each element with bounds, render a colored box:
  //   - Green: clickable
  //   - Orange: editable
  //   - Purple: scrollable
  //   - Cyan: has text/desc
}
```

#### D. Mind Panel (`mind-panel.js`)

**Responsibilities**:
- Tabbed interface: Prompt | Reasoning | Tool Call | Result
- Load content from `llm_full_prompt/`, `llm_tool_calls/`, `tool_result/`
- Syntax highlighting for JSON and Kotlin-style DSL
- Collapsible sections for long content

**Tabs**:
| Tab | Source Artifact | Description |
|-----|-----------------|-------------|
| 📝 Prompt | `llm_full_prompt/turn_N_full_prompt.txt` | Complete system + user prompt |
| 💭 Reasoning | `llm_response.text` (if CoT) | Model's chain-of-thought (if any) |
| 🔧 Tool Call | `llm_tool_calls/turn_N_tool_calls.json` | Function name + arguments |
| 📦 Result | `tool_result/turn_N_*.txt` | Execution output + observation |

### 5.4 Key Interactions

#### 1. Session Tree ⟷ Timeline Linking

When clicking a session in the tree:
```javascript
function onSessionSelect(sessionId) {
  // Filter timeline to show only events from this session
  const sessionEvents = allEvents.filter(e => e.sessionId === sessionId);
  timeline.setEvents(sessionEvents);
  timeline.selectFirst();
}
```

#### 2. Step Navigation

```javascript
document.addEventListener('keydown', (e) => {
  if (e.key === 'ArrowRight') timeline.nextStep();
  if (e.key === 'ArrowLeft') timeline.prevStep();
});
```

#### 3. Artifact Lazy Loading

```javascript
class ArtifactLoader {
  constructor(fileMap) {
    this.fileMap = fileMap; // From folder input
    this.cache = new Map();
  }
  
  async loadText(ref) {
    if (!ref?.path) return null;
    if (this.cache.has(ref.path)) return this.cache.get(ref.path);
    const file = this.fileMap.get(ref.path);
    if (!file) return `[Missing: ${ref.path}]`;
    const text = await file.text();
    this.cache.set(ref.path, text);
    return text;
  }
  
  async loadJson(ref) {
    const text = await this.loadText(ref);
    return text ? JSON.parse(text) : null;
  }
  
  async loadImage(ref) {
    const file = this.fileMap.get(ref?.path);
    return file ? URL.createObjectURL(file) : null;
  }
}
```

### 5.5 Styling (Dark Theme)

Core CSS variables:
```css
:root {
  --bg-primary: #0d1117;
  --bg-secondary: #161b22;
  --bg-tertiary: #21262d;
  --border: #30363d;
  --text-primary: #e6edf3;
  --text-secondary: #8b949e;
  --accent-cyan: #58a6ff;
  --accent-green: #3fb950;
  --accent-purple: #a371f7;
  --accent-orange: #d29922;
  --accent-red: #f85149;
}
```

## 6. Implementation Plan

### Phase 1: Logging Enhancements (Android) — 1 hour

| Task | File | Effort |
|------|------|--------|
| Add `AgentRole` enum | `AgentConfig.kt` | 5 min |
| Add `parentSessionId` field | `AgentConfig.kt` | 5 min |
| Update `IsolatedSubAgentRunner` | `SubAgentRunner.kt` | 10 min |
| Update `AgentTrace.sessionStarted()` | `AgentTrace.kt` | 15 min |
| Update main agent creation | `SessionAgentRunner` or caller | 10 min |
| Test with `debug-run.sh` | — | 15 min |

### Phase 2: Visualizer v2 Skeleton — 2 hours

| Task | Effort |
|------|--------|
| Create `inspection_tool/v2/` directory structure | 10 min |
| Implement `trace-loader.js` (parse JSONL + build file map) | 30 min |
| Implement `session-tree.js` (build & render tree) | 30 min |
| Implement basic `timeline.js` (list turns) | 30 min |
| Wire up `index.html` with 3-column layout | 20 min |

### Phase 3: Detail Panels — 2 hours

| Task | Effort |
|------|--------|
| Implement `world-panel.js` (screenshot + overlay) | 45 min |
| Implement `mind-panel.js` (tabs + content) | 45 min |
| Add keyboard navigation | 15 min |
| Polish styling | 15 min |

### Phase 4: Polish & Testing — 1 hour

| Task | Effort |
|------|--------|
| Test with real trace from `debug-output/` | 20 min |
| Fix edge cases (missing artifacts, empty turns) | 20 min |
| Add filter input for event types | 10 min |
| Add export/copy functionality | 10 min |

**Total estimated effort: ~6 hours**

## 7. Migration & Compatibility

### Backward Compatibility

- New fields (`parent_session_id`, `agent_role`) are optional
- Visualizer falls back to parsing `sessionId` for hierarchy if explicit parent missing
- Old trace folders still viewable (degraded hierarchy but timeline works)

### Deprecation Path

1. Keep `inspection_tool/trace_viewer.html` for simple use cases
2. `inspection_tool/v2/` becomes the power-user tool
3. Eventually merge or retire v1 if v2 covers all use cases

## 8. Future Extensions

### 8.1 Live Trace Streaming (v3)

Instead of pulling trace folder post-run:
- WebSocket connection from device to visualizer
- Real-time event streaming
- Live step-through during execution

### 8.2 Trace Comparison

- Side-by-side comparison of two runs
- Diff view for prompts and tool calls
- Useful for A/B testing cognition profiles

### 8.3 Trace Annotations

- Mark turns as "good" or "bad" for training data
- Export annotated traces for fine-tuning

## 9. Action Items (Immediate Next Steps)

1. **Android**: Implement Phase 1 logging enhancements
2. **Web**: Create `inspection_tool/v2/` scaffolding
3. **Test**: Pull a trace with `debug-run.sh`, verify new fields appear
4. **Iterate**: Build visualizer incrementally, validate with real traces

---

## Appendix A: Event Type Reference

| Event Type | When Emitted | Key Data Fields |
|------------|--------------|-----------------|
| `session_started` | Agent starts | goal, task_id, parent_session_id, agent_role |
| `session_stopped` | Agent finishes | reason, turns_executed |
| `turn_started` | Turn begins | turnNumber |
| `screen_captured` | After perception | elements, package |
| `llm_request` | Before LLM call | history_items, input_items, model |
| `llm_response` | After LLM call | has_text, tool_calls |
| `tool_arbitration` | Policy decision | selected_tool, dropped_tools |
| `tool_call` | Before execution | tool name, id |
| `tool_result` | After execution | success, observation |
| `turn_completed` | Turn ends | — |
| `turn_error` | On error | error message |

## Appendix B: Artifact Kinds Reference

| Kind | Format | Contains |
|------|--------|----------|
| `screenshot` | JPEG | Screen capture at step |
| `raw_a11y_tree` | JSON | Full accessibility tree |
| `sanitized_a11y_tree` | JSON | Filtered elements list |
| `llm_system_prompt` | TXT | System instructions |
| `llm_user_context` | TXT | User context injection |
| `llm_full_prompt` | TXT | Complete concatenated prompt |
| `llm_input_items` | JSON | Structured input array |
| `llm_history` | JSON | Conversation history |
| `llm_tool_calls` | JSON | Model's tool call output |
| `tool_call_args` | JSON | Arguments for specific tool |
| `tool_result` | TXT | Tool execution output |
| `tool_observation_screen` | JSON | Post-action screen state |
