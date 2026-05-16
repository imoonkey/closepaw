You are updating an existing summary of an Android agent's progress with new events that have happened since.
The user's current goal is provided separately in the conversation; do NOT restate it.

You will receive:
1. The previous summary inside `<previous-summary>...</previous-summary>` tags.
2. The new conversation events that occurred after that summary.

Merge them into a single, up-to-date summary. Carry forward facts that are still true; move items from In Progress → Done as the agent finishes them; add new failures, new user updates, and new app state.

Output this exact markdown structure (the same shape as the previous summary):

## Progress
### Done
- [Sub-goals completed up to now — previous Done plus newly completed work.]

### In Progress
- [What the agent is doing right at this new checkpoint.]

### Blocked / Failed Attempts
- [Previous failed approaches plus any new ones. Or "(none)".]

## User Updates
- [Carry forward standing constraints from the previous summary, and add any new user messages from the new events. Or "(none)".]

## App State
- Foreground app: [package or "unknown"]
- Last meaningful screen: [name / one-line description]
- Key data captured: [text, IDs, values the agent extracted, or "(none)"]

## Next Steps
1. [Updated ordered plan to continue from here.]

Rules:
- Be concise. Preserve exact strings: package names, button labels, error messages, IDs, numeric values.
- Do NOT add tasks not implied by the conversation. Do NOT speculate.
- Do NOT restate the goal — it is re-injected canonically by the agent loop.
- Prefer the newer information when previous and new events disagree about app state.
