# History Compression Alignment (Codex)

Aligned design draft is now tracked in:
- `/Users/moonkey/workspace/android-agent-workspace/androidagent/align/design/design.md`

Current alignment status:
- `/Users/moonkey/workspace/android-agent-workspace/androidagent/align/discussion/status.txt`

Round notes:
- `/Users/moonkey/workspace/android-agent-workspace/androidagent/align/discussion/0001_CLAUDE.md`
- `/Users/moonkey/workspace/android-agent-workspace/androidagent/align/discussion/0002_CODEX.md`

This alignment version resolves both proposals into one design:
- Explicit intent/screen semantics (`MessageKind`)
- Screen-first compression with proactive downgrade
- Turn-aware deterministic digest replacement
- Recent-window protection
- Single compression owner in `HistoryManager`
- Explicit `BudgetUnreachable` when impossible to fit
