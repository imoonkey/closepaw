# Overall Code Review

## Summary

The core architecture is coherent (single ReAct agent + session services + tool router), but several correctness, security, and reliability gaps remain. The most critical risks are around Android permissions/API key handling, memory retention of accessibility nodes, and approval flows that can block indefinitely. There are also protocol mismatches (unused Op config, missing UserInput handling) and performance inefficiencies (duplicate screen capture).

## High-level problems

### 1) Security + platform compatibility
- API keys are loaded from external storage and require deprecated permissions that won’t work on targetSdk 35. This is both insecure and likely broken.
- Recommendation: move to app-private encrypted storage, remove external storage permissions, and update UI to store keys securely.

### 2) Resource leakage and stability
- Accessibility nodes are retained without recycling, which can leak memory and keep stale UI references alive.
- Recommendation: store only stable selectors or use `AccessibilityNodeInfo.obtain()` + `recycle()`; avoid keeping raw nodes in `ScreenSnapshot`.

### 3) Approval flow can deadlock the agent
- Tool execution waits indefinitely for user approval with no timeout or auto-deny path.
- Recommendation: add timeouts and emit an approval-timeout event; treat as cancellation or denial.

### 4) Turn execution safety
- If the LLM emits multiple tool calls, they run against a stale snapshot, increasing the risk of unintended actions.
- Recommendation: enforce one tool per response, or refresh the snapshot before each tool call.

### 5) Protocol and config drift
- `Op.Start.config` and `SessionConfig.model` are ignored, and `Op.UserInput` is not implemented.
- Recommendation: wire config into `SessionServices`/LLM, or remove unused protocol fields; implement UserInput handling or document it as unsupported.

## Medium-priority issues

- Tool observation is captured in tools but not surfaced; the agent captures a second observation instead.
- Session event flow does not close on normal completion, risking leaked collectors.
- Approval resolution events (`ApprovalResolved`) are not emitted.

## Fixes applied during this review

- Corrected turn phase event emission (`TurnStarted`, `TurnPhaseChanged`, `TurnCompleted`).
- Aligned `ActionExecuted` IDs with ToolRouter `callId` for approval correlation.
- Added missing `swipe` tool instructions in the LLM prompt.
- Corrected cancellation stop reason when the cancellation signal completes.

## Open questions

- Should `AgentService` allow multiple sessions concurrently, or enforce a single active session?
- Do we want to keep `activity_main.xml` as a fallback, or remove it after the Compose migration?
