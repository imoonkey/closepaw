1.  **Adopt "Critical Only" Reminders:** Claude proposed keeping "Turn Budget" warnings. I suggest removing them until they are critical (e.g., "Final Turn"). less noise is better.
2.  **Refinement on Implementation:**
    *   Claude's plan `3.3 A` (adding `isScreenObservation` to `ResponseItem.Message`) is the correct architectural path.
    *   Ensure the `PromptBuilder` completely replaces the fragmented logic in `PromptUtils` and `AgentTurnRunner`, as both proposals demand.