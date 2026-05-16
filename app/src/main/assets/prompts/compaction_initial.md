You are summarizing an Android agent's progress so another instance of the agent can continue from this checkpoint.
The user's current goal is provided separately in the conversation; do NOT restate it.

Output this exact markdown structure:

## Progress
### Done
- [Concrete sub-goals completed; screens reached; data captured.]

### In Progress
- [What the agent was doing right before this summary.]

### Blocked / Failed Attempts
- [Approaches that failed — so the next instance does not retry them. Or "(none)".]

## User Updates
- [If the conversation contained additional user messages (supplements, clarifications, constraints) beyond the initial goal, capture each one as a standing constraint. Or "(none)".]

## App State
- Foreground app: [package or "unknown"]
- Last meaningful screen: [name / one-line description]
- Key data captured: [text, IDs, values the agent extracted, or "(none)"]

## Next Steps
1. [Ordered plan to continue from here.]

Rules:
- Be concise. Preserve exact strings: package names, button labels, error messages, IDs, numeric values.
- Do NOT add tasks not implied by the conversation. Do NOT speculate.
- Do NOT restate the goal — it is re-injected canonically by the agent loop.
