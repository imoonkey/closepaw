# Agent Cognition Tracking & Visualization Design

> Date: 2026-02-04
> Status: Draft
> Author: Gemini

## 1. Problem Statement

To enable rapid research and iteration on the "Agent Cognition Core", we need a high-fidelity, low-friction debugging loop. The current system lacks:
1.  **Unified Visibility**: Screen state and "Brain" state (LLM inputs/outputs) are not visualized side-by-side.
2.  **Sub-agent Tracking**: No clear linkage between main agent and sub-agents (e.g. Planner -> Executor).
3.  **Visual Replay**: Hard to walk through a session step-by-step to pinpoint where logic diverged.

## 2. Goals

1.  **Global Logging**: A "zero-config" logging system that works for any agent run (Main, Sub, Test).
2.  **Full Context Capture**:
    *   **Screen**: Screenshot + A11y Tree (Raw & Sanitized).
    *   **Brain**: System Prompt, Context, User Input, LLM Response, Tool Calls.
3.  **Visualizer 2.0**: A web-based tool to replay traces with:
    *   Session Tree (Main -> Subs).
    *   Split View (Visual vs Cognition).
    *   Step-by-step navigation.

## 3. Logging System Design

### 3.1 Data Model Extensions

We will build upon the existing `AgentTrace` classes but enhance them for linkage and structure.

#### `AgentConfig` Updates
Add parent tracking to support the session tree.

```kotlin
data class AgentConfig(
    // ... existing fields
    val sessionId: SessionId,
    val parentSessionId: SessionId? = null, // [NEW] Link to parent
    val agentRole: AgentRole = AgentRole.PLANNER // [NEW] Metadata for UI
)
```

#### Event Schema Updates
Enhance `session_started` to include hierarchy info.

```json
// Event: session_started
{
  "type": "session_started",
  "sessionId": "session-123",
  "data": {
    "parent_session_id": "session-root-000", // [NEW]
    "agent_role": "EXECUTOR", // [NEW]
    "goal": "...",
    // ...
  }
}
```

### 3.2 Storage Strategy
We will continue using the **Flat File + Artifacts** approach. It is robust and simple.

*   **Structure**:
    ```
    /trace_output_dir/
      trace.jsonl         // All events from ALL sessions (multiplexed)
      artifacts/
        // Artifacts can be namespaced by session_id to avoid collision
        session-123/
          turn_1_screenshot.jpg
          turn_1_full_prompt.txt
    ```
*   **Multiplexing**: Since sub-agents typically run in the same app process, they can write to the same `trace.jsonl` safely (using synchronized writes or a single Looper/Actor). The `sessionId` field in every event differentiates them.

## 4. Visualizer Design (Inspection Tool 2.0)

A standalone Single Page Application (SPA). We can use pre-bundled React or just cleaner Vanilla JS/ES6 modules if we want to avoid a build step. Given the complexity, a **lightweight React setup** (via separate build or CDN) is recommended, but for now, we can stick to **no-build ES6 Modules** for zero-friction dev.

### 4.1 UI Layout

```text
+----------------+-------------------------------+--------------------------------+
|  Session Tree  |  Timeline (Steps)             |  Detail View                   |
|                |                               |                                |
|  [P] Main      |  1. PERCEPTION                |  +--------------------------+  |
|    |           |  2. COGNITION (Think)         |  | Screen (Left) | Brain (R)|  |
|    +-[E] Sub A |  3. ACTION (Tool Call)        |  |               |          |  |
|    +-[E] Sub B |  4. RESULT                    |  | [Screenshot]  | [Prompt] |  |
|                |                               |  | [A11y Overly] | [Rsning] |  |
|                |                               |  +--------------------------+  |
+----------------+-------------------------------+--------------------------------+
```

### 4.2 Key Components

1.  **Session Tree sidebar**:
    *   Parses all `session_started` events.
    *   Reconstructs hierarchy using `parent_session_id`.
    *   Shows pass/fail status of each session.

2.  **Timeline**:
    *   Lists "Turns" rather than raw events.
    *   Aggregates `turn_started` -> `screen_captured` -> `llm_request` -> `tool_call` -> `turn_completed`.

3.  **State Inspector (The "Detail View")**:
    *   **Left Panel (World)**:
        *   Image Viewer for `screenshot`.
        *   A11y Tree Overlay: Render bounding boxes from `sanitized_a11y_tree` json. Hover to see Node ID/Text.
    *   **Right Panel (Mind)**:
        *   **Tab 1: Prompt**: Read `turn_{n}_full_prompt.txt`. Syntax highlight.
        *   **Tab 2: Reasoning**: Show `llm_response_text` (CoT).
        *   **Tab 3: Tool Call**: Show JSON inputs.
        *   **Tab 4: Result**: Show Tool Output/Observation.

### 4.3 Implementation Tech
*   **Framework**: Vanilla ES6 + Lit-html (or just DOM API if lazy) to keep it strictly "no compilation needed".
*   **Styling**: CSS Grid/Flexbox. Dark mode default.

## 5. Implementation Plan

### Phase 1: Core Logging Enhancements (Android)
*   **Goal**: Ensure `trace.jsonl` contains all necessary links and data.
*   **Task 1**: Add `parentSessionId` and `agentRole` to `AgentConfig`.
*   **Task 2**: Pass these values in `AgentRuntime` and `ExecutorAgent`.
*   **Task 3**: Update `AgentTrace.sessionStarted` to emit them.
*   **Task 4**: Ensure `artifacts/` uses subfolders `artifacts/{sessionId}/` to prevent collisions if IDs reset.

### Phase 2: Visualizer Skeleton
*   **Goal**: A new `inspection_tool/v2/index.html` that loads `trace.jsonl` and builds the Session Tree.
*   **Task 1**: JS Logic to group events by `sessionId`.
*   **Task 2**: JS Logic to build the tree.
*   **Task 3**: Render the sidebar.

### Phase 3: Detail Views
*   **Goal**: Render the split screen.
*   **Task 1**: Implement Screenshot viewer with bounding box overlay (canvas).
*   **Task 2**: Implement Markdown viewer for Prompts/Reasoning.

### Phase 4: Polish
*   **Goal**: Hotkeys (Left/Right arrow to step), Filtering.

## 6. Action Items (Immediate)
1.  Modify `AgentConfig.kt`.
2.  Update `AgentRuntime` instantiation in `AgentFactory` or `ExecutorAgent`.
3.  Create `inspection_tool/v2` scaffolding.
