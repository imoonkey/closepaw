# Design Review: Multi-Round Chat MVP

I have reviewed the three design proposals (`claude.md`, `codex.md`, `gemini.md`) and compared them against the reference implementation in `.reference/labmat`.

## Evaluation Criteria

1.  **Alignment with LabMat**: How well does it adapt the "simplified but good enough" logic from the reference?
2.  **MVP Suitability**: Is the scope appropriate for a first version? (Not over-engineered, but functional).
3.  **Architecture**: Is the proposed structure clean and maintainable?
4.  **Completeness**: Does it cover Protocol, Session, Agent, and UI layers?

## 1. Claude's Design (`claude.md`)

**Rating: 9/10**

*   **Summary**: Proposes a dedicated `ChatSession` and `StreamingTurn` that wraps the existing logic. It introduces a comprehensive `StreamEvent` hierarchy.
*   **Pros**:
    *   **Strong LabMat Alignment**: Explicitly maps to `labmat`'s `ChatSession` and `Turn` structure.
    *   **Clear Separation**: Separates the "Goal Mode" (Agent) from "Chat Mode" (ChatSession), which reduces risk of breaking existing functionality.
    *   **Detailed Protocol**: The `StreamEvent` sealed interface is well-defined and covers all necessary states (text, thinking, tool calls).
    *   **Streaming Logic**: The `StreamingTurn` implementation using Kotlin Flows is idiomatic and maps well to the `labmat` Python generator approach.
*   **Cons**:
    *   Creating a separate `ChatSession` might lead to some code duplication with `Agent` if not careful, though the design aims to wrap/reuse.

## 2. Codex's Design (`codex.md`)

**Rating: 8/10**

*   **Summary**: Focuses on integrating chat into the *existing* `AgentSession` by wiring up `Op.UserInput`. Emphasizes UI stability (newline-gating).
*   **Pros**:
    *   **Pragmatic**: Reusing `AgentSession` is a valid MVP approach to minimize boilerplate.
    *   **UI UX**: The "newline-gated" streaming idea (borrowed from `codex` reference) is a valuable insight for a better user experience.
    *   **Simple Protocol**: The `ChatStreamItem` -> `AgentEvent` mapping is simple and effective.
*   **Cons**:
    *   **Less Robust Backend**: The backend design is less detailed than Claude's. It assumes `AgentSession` can easily handle the dual nature (Goal vs Chat) without becoming messy.
    *   **Concurrency**: The "one in-flight response" rule is good for MVP but the implementation details on how to enforce it in the existing session are light.

## 3. Gemini's Design (`gemini.md`)

**Rating: 7/10**

*   **Summary**: Proposes refactoring the `Agent.run()` loop into a state machine (`IDLE`, `THINKING`, etc.).
*   **Pros**:
    *   **Correct Mental Model**: A state machine is ultimately the right way to model an agent.
    *   **Interleaved Tools**: Explicitly handles the "Tool Execution (Interleaved)" flow well.
*   **Cons**:
    *   **High Risk**: Refactoring the core `Agent.run()` loop into a state machine is a significant change that risks breaking the existing "Goal Mode". It feels like a "v2" refactor rather than an MVP addition.
    *   **Complexity**: The distinction between "Agent-driven" and "User-driven" auto-continue logic adds complexity that might not be needed for a simple chat MVP.

## Overall Recommendation

**Winner: Claude (`claude.md`)**

Claude's design offers the best balance of safety (separate `ChatSession`) and functionality. It most closely mirrors the successful `labmat` reference architecture.

**Suggestion**: Adopt Claude's backend architecture (`ChatSession`, `StreamingTurn`, `StreamEvent`) but incorporate Codex's UI insights (newline-gating) and Gemini's state-machine thinking for the internal `ChatCycle` logic.
