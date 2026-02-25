# Round 0001 (CLAUDE baseline import)

Source: `/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.02_history_compress/design_claude.md`

Imported as the starting proposal from Claude side.

Key points in Claude draft:
- Root bottleneck is screen observations consuming token budget.
- Move screen compression responsibility into `HistoryManager`.
- Add proactive screen downgrade and recent-window protection.
- Keep user intent messages and pair function call/output removals.
- Prefer structural compression; avoid LLM summarization for now.

Vote: `CHANGES`.
