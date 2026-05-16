# Session History Persistence

> Session recording, storage, runtime prompt history, context-window compaction, and resume.
> Last updated: 2026-05-16

## Overview

The session history system has three layers:

1. **Persistence layer** — automatic recording of chat sessions to disk for browsing and resuming past conversations
2. **Runtime layer** — in-memory conversation history (`HistoryManager`) with proactive screen downgrade plus context-window-driven auto-compaction (`Compactor`)
3. **Checkpoint layer** — session state snapshots for process-death recovery (history + todos + scratchpad)

## Architecture

```
┌──────────────────┐       ┌──────────────────────────────┐
│   MainActivity   │──────►│   SessionHistoryManager      │
│  (UI entry)      │       │   (High-level API)           │
└────────┬─────────┘       └──────────────┬───────────────┘
         │                                │ coordinates
         ▼                                ▼
┌──────────────────┐       ┌──────────────────────────────┐
│  ChatViewModel   │◄─────►│  SessionRecordingService     │
│  (State mgmt)    │       │  (Real-time event bridge)    │
└────────┬─────────┘       └──────────────┬───────────────┘
         │ events                         │ debounced writes
         ▼                                ▼
┌──────────────────┐       ┌──────────────────────────────┐
│  AgentSession    │──────►│     SessionStorage           │
│  (Events)        │       │     (File I/O)               │
└──────────────────┘       └──────────────┬───────────────┘
                                          ▼
                           ┌──────────────────────────────┐
                           │  /files/sessions/*.json      │
                           └──────────────────────────────┘
```

## Recording Flow

```
AgentEvent                     SessionRecordingService              File
    │                                    │                            │
    │ TaskStarted                        │                            │
    │───────────────────────────────────►│ startAgentMessage()        │
    │                                    │                            │
    │ MessageDelta("I'll...")            │                            │
    │───────────────────────────────────►│ appendTextDelta()          │
    │                                    │ (buffer, no save)          │
    │                                    │                            │
    │ ActionExecuted(click)              │                            │
    │───────────────────────────────────►│ recordAction()             │
    │                                    │───────────────────────────►│
    │                                    │ (debounced save, 500ms)    │
    │                                    │                            │
    │ TaskCompleted                      │                            │
    │───────────────────────────────────►│ completeAgentMessage()     │
    │                                    │───────────────────────────►│
    │                                    │ (immediate save)           │
    │                                    │                            │
    │ SessionCompleted (shutdown only)   │                            │
    │───────────────────────────────────►│ completeSession()          │
    │                                    │───────────────────────────►│
    │                                    │ (immediate save)           │
```

## Subpages

| Page | Focus |
|------|-------|
| [persistence.md](persistence.md) | SessionHistoryManager, SessionRecordingService, SessionStorage |
| [runtime.md](runtime.md) | HistoryManager (revision + CAS), Compactor, screen downgrade |
| [models.md](models.md) | Data models: SessionRecord, MessageRecord, ScreenStateRecord |

## File Structure

```
history/
├── HistoryManager.kt              # Runtime prompt history + screen downgrade + revision/CAS
├── HistoryConfig.kt               # Screen-retention config
├── Compactor.kt                   # Context-window-triggered LLM summarization
├── ResponseItem.kt                # Conversation items (MessageKind incl. COMPACTION_SUMMARY)
├── SessionHistoryManager.kt       # High-level session management
├── SessionRecordingService.kt     # Real-time event recording
├── AgentMessageBuffer.kt          # Streaming agent message buffer
├── SessionRecordMessageMerger.kt  # Merge agent snapshots into SessionRecord
├── model/
│   ├── SessionRecord.kt           # Complete session data + metadata
│   ├── SessionRuntimeSnapshot.kt  # Checkpoint snapshot
│   ├── HistoryItemConverter.kt    # ResponseItem ↔ PersistedHistoryItem
│   ├── MessageRecord.kt           # Message types + content blocks
│   ├── SessionInfo.kt             # Lightweight session summary
│   ├── ScreenStateRecord.kt       # Screen state reference
│   └── MessageConverter.kt        # ChatMessage ↔ MessageRecord
└── storage/
    └── SessionStorage.kt          # File I/O operations
```

## Related Docs

- [UI User Interaction](../../ui/user_interaction.md) - Session history UI
- [Session State Machine](../../ui/session/state_machine.md) - Checkpoint coordination
- [Protocol](../../protocol/overview.md) - Events that trigger recording
- [Session](../../infra/session.md) - AgentSession lifecycle
