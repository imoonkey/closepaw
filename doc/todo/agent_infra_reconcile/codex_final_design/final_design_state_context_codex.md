# Final Design (KISS) - State and Context

Date: 2026-02-04
Goal: Reduce context noise and avoid shared mutable state

## 1) State Sharing Strategy

- No shared AgentState object between parent and executor.
- Parent owns all long-term planning state (subgoals, scratchpad, errors).
- Executor receives only what it needs via structured inputs.
- Executor returns a structured report; parent merges it into its own state.

This preserves isolation while keeping logical continuity.

## 2) Context Passing (Minimum Fields)

When parent delegates to executor, pass:
- goal (string)
- current_subgoal (string)
- important_notes (short list)
- screen_summary (short text)
- latest_screen_snapshot (a11y tree + screenshot) if required by executor

Do NOT pass:
- full history
- prior screenshots or prior a11y trees

## 3) History Hygiene (Mobile-Specific)

Problem:
- Old screenshots/a11y trees quickly become irrelevant and flood context.

Design:
- Only the latest screen state is injected into the LLM prompt.
- History is text-only: tool results, short summaries, decisions.
- Store a compact screen summary per step (1-3 lines). Use it for memory, not raw trees.

Implementation idea (minimal):
- Modify prompt builder to take current screen state separately from chat history.
- Ensure HistoryManager does not store screenshots or a11y trees.
- Add a simple screen_summary string to the parent agent memory (not the child).

## 4) Make Planning State Tool-Backed

To reduce prompt size and keep code simple:
- Implement TODO and Scratchpad as tools with internal storage scoped to the session.
- The tool returns the current list on demand.
- Parent prompt instructs the agent to call these tools instead of carrying large plans in memory.

Minimal data model for TODO tool:
- List of items: {id, text, status}
- Operations: add, update_status, list, clear

Minimal data model for Scratchpad:
- Map of key -> value
- Operations: put, get, list_keys, clear

KISS outcome:
- No new shared state types.
- The tool itself holds the state.
- The LLM only sees what it asks for.

