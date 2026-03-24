# Session History Persistence

> Session recording, storage, runtime prompt history, compression pipeline, and resume.
> Last updated: 2026-03-05 (commit: 0b5b379)

## Overview

The session history system has three layers:

1. **Persistence layer** — automatic recording of chat sessions to disk for browsing and resuming past conversations
2. **Runtime layer** — in-memory conversation history management for LLM context with token budgeting, multi-phase compression, and proactive screen downgrade
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
| [runtime.md](runtime.md) | HistoryManager, compression pipeline, token budgeting |
| [models.md](models.md) | Data models: SessionRecord, MessageRecord, ScreenStateRecord |

## File Structure

```
history/
├── HistoryManager.kt              # Runtime prompt history + compression
├── HistoryConfig.kt               # Token budgets, compression params
├── ResponseItem.kt                # Conversation items (MessageKind)
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
