# Review: 3_session_model Claude Design

## Summary

The design is pointed in the right direction. It correctly identifies the core problem in the current codebase: session identity is split across `AgentSession`, `SessionRecord`, `SessionRuntimeSnapshot`, and UI-side glue. The proposed session directory layout, cold-vs-hot distinction, and single-lane execution model are all sound starting points.

The main issues are not with the direction, but with underspecified boundaries. In particular, the design still mixes UI selection into canonical session state, does not fully model the existing non-input operations that already shape session behavior, and does not define how `session.json`, `events.jsonl`, and `checkpoint.json` stay consistent under crash or partial writes.

## High

1. UI selection is being promoted into repository truth, which recreates the same ownership leak in a new place. In [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L181), [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L191), and [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L193), `SessionRegistry` is proposed to "publish active session selection as canonical state." That conflates a UI concern with durable session truth. The current code already distinguishes between the selected chat thread in `MainActivity` and the runtime session observed by `AgentService`; future headless or multi-entry flows make that separation more important, not less. Canonical repository state should answer "what sessions exist and what state are they in," not "which session is this UI currently looking at." The fix is to keep session selection in UI/controller state and reserve repository state for durable facts plus, if needed, runtime ownership metadata.

2. The design introduces `PAUSED` and `RECOVERING` work states but never specifies the operation matrix that gets the system into or out of them. `TaskState` includes `PAUSED` and `RECOVERING` in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L65), [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L70), and the state machine references `PAUSED` in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L221), but the entry-point flow in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L241) only models new input. The repository today already depends on `Op.Takeover`, `Op.Resume`, `Op.Supplement`, `Op.UserResponse`, `Op.Approve`, and `Op.Interrupt`. Without explicit rules for those operations in hot, cold, queued, and paused states, the design is incomplete in a correctness-sensitive area. This should be fixed by adding an operation/state table, not by leaving it to implementation.

3. The storage model lacks a consistency contract between `session.json`, `events.jsonl`, and `checkpoint.json`. The three-file split is introduced in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L107), [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L119), [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L137), and [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L155), but there is no ordering rule for writes or recovery. That is a real gap because the current code explicitly uses atomic snapshot writes and reloadable checkpoint states. If `events.jsonl` is ahead of `checkpoint.json`, or `session.json` says a session is updated but the checkpoint is stale, browsing and recovery can disagree. The design needs an explicit source-of-truth rule and write protocol, for example event append first, checkpoint flush second, manifest rewrite last with a `lastAppliedOffset` or generation number.

## Medium

1. `EXPLICIT/<sessionId>` is doing identity work while pretending to be routing. The key examples in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L96) through [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L105) mix stable entry-point keys like `MAIN/main` with `EXPLICIT/<sessionId>`, which is just a direct identity reference. That weakens the invariant that keys are reusable routing handles. Loading an existing session by id should stay a separate path; keys should represent things like `main`, direct chats, or group channels.

2. Persisting only finalized semantic events makes the event log cleaner, but it weakens crash-time observability compared with the current recorder. The design says not to persist token deltas and to keep finalized events only in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L137) through [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L153). That is reasonable for text deltas, but the current `SessionRecordingService` also persists partial agent message/action state on debounce, which is useful after mid-task crashes. If the new design intentionally gives that up, it should say so. If not, it needs an interim durable snapshot event or a stronger checkpoint story for running tasks.

3. The migration section understates the impedance mismatch between old transcript files and the new event log. Import is proposed in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L269) through [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/3_session_model/initial/design_claude.md#L280), but existing `session-*.json` files are message snapshots, not a full semantic event timeline. Reconstructing `events.jsonl` exactly is impossible from old data. The design should either define a lossy import explicitly or treat imported legacy sessions as transcript-backed historical sessions rather than pretending they came from the new event model.

## Strengths

- The design correctly recenters the system around durable session identity instead of runtime pointers.
- The cold/hot residency split is the right fix for the current misuse of `Shutdown`.
- A per-session directory is a cleaner storage boundary than the current paired filename scheme.
- Staying with one device-wide lane is the right trade-off for the current Android automation model.

## Open Questions

1. What transitions a session from `OPEN` to `ARCHIVED`, and is that user-driven, retention-driven, or automatic?
2. If the UI opens a cold session for browsing only, does that stay browse-only until new input, or does selection hydrate a runtime?
3. Where should runtime ownership live if "selected session" is removed from canonical state but the UI still needs a stable active badge?

## Recommendation

CHANGES_REQUESTED
