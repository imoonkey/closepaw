# Prompt Design Review & Comparison: Claude vs. Codex

## 1. Executive Summary

I have reviewed the design proposals from both Claude (3 documents) and Codex (1 document).

**Recommendation:** **Adopt Claude's "Sequential Narrative" Design.**

While both designs identify the same core problems (redundant screen summaries, lack of visual history, need for better prompts), Claude's solution is architecturally superior because it aligns with the LLM's natural cognitive model ("I saw X, I did Y") rather than treating visual history as a disjointed "context window" lookup.

## 2. Approach Comparison

| Feature | Claude's Proposal | Codex's Proposal | Winner |
| :--- | :--- | :--- | :--- |
| **History Logic** | **Interleaved**: `User(Screen) → Asst(Action) → User(Screen)` | **Appended**: `History(Text) + Window(Last 3 Screens)` | **Claude** (Better grounding) |
| **Old Screen State** | Stored in generic `HistoryManager` as `UserMessage` with formatting flag | Stored in specialized `ScreenWindowState` ring buffer | **Claude** (Simpler, uses existing constructs) |
| **Memory** | Single block before current observation | Part of current turn input | **Claude** (Cleaner separation) |
| **Tool Outputs** | Meta-only (remove screen summary) | Meta-only (remove screen summary) | **Tie** (Both agree) |
| **Architecture** | `PromptBuilder` (Single Entry Point) | `TurnPromptAssembler` (Single Entry Point) | **Tie** (Both agree) |
| **Reminders** | Minimal (Loop + Budget + Final) | Critical Only (Loop + Final) | **Tie** (Both agree) |

## 3. Detailed Critique

### 3.1 Claude's Design ("The Narrative")

**Strengths:**
*   **Cognitive Alignment:** The LLM reads a story. "I saw screen A, I clicked button B. Then I saw screen C." This is how autoregressive models work best.
*   **Architecture "Surgery":** The plan to modify `HistoryManager` with a metadata flag (`isScreenObservation`) rather than building a parallel state system (`ScreenWindowState`) is elegant. It keeps the "source of truth" in one place (the history).
*   **Flexibility:** Because screen states are just messages, we can change compression strategies (e.g., "keep first and last", "keep key turns") without changing the underlying state structure.
*   **Compression Strategy:** Compressing old observations into one-line summaries in the prompt builder is a robust way to manage tokens without losing the "this happened then" timeline.

**Weaknesses:**
*   **Token Inflation:** Storing full JSON in history (even if compressed at render time) might bloat memory usage if the session is very long (hundreds of turns), though `HistoryManager` already handles dropping old items.

### 3.2 Codex's Design ("The Context Window")

**Strengths:**
*   **Clean History:** Keeping the history strictly text-only (Goal -> Action -> Result) is conceptually very clean.
*   **Explicit Budgeting:** The "Ring Buffer" approach makes it very easy to hard-limit visual context to exactly N frames.

**Weaknesses:**
*   **Cognitive Disconnect:** Appending "Last 3 Screens" at the end of the prompt forces the LLM to perform a mental lookup: "Wait, `screen_window[1]` corresponds to the action I took 2 turns ago?" This breaks the causal link. Visual context should be *at the point in time* it occurred.
*   **New State Complexity:** Introducing `ScreenWindowState` adds another stateful component to sync with `AgentTurnRunner` and `HistoryManager`.

## 4. Synthesis & Recommendations

I recommend proceeding with **Claude's Implementation Plan**, with two specific refinements adopted from Codex's analysis:

1.  **Adopt Claude's Interleaved History:** It is the correct way to model agent-environment interaction.
2.  **Adopt Codex's "Critical Only" Reminders:** Claude proposed keeping "Turn Budget" warnings. Codex suggests removing them until they are critical (e.g., "Final Turn"). I agree with Codex here—less noise is better.
3.  **Refinement on Implementation:**
    *   Claude's plan `3.3 A` (adding `isScreenObservation` to `ResponseItem.Message`) is the correct architectural path.
    *   Ensure the `PromptBuilder` completely replaces the fragmented logic in `PromptUtils` and `AgentTurnRunner`, as both proposals demand.

**Verdict:** The "Sequential Narrative" (Claude) is the stronger design for an autonomous agent.
