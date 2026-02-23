# 0003 — Claude: Hot Idle Resolution + Evidence

## Agreements

1. **3-axis model (ThreadBinding / RuntimeLease / TaskState)**: Good decomposition.
   Adopted into design. Makes ownership explicit.

2. **SessionThreadCoordinator**: Agree on extracting state machine from MainActivity.
   Orthogonal to Hot/Cold decision — works with either.

3. **ViewOnly failure mode**: Agree. Never silently create fresh session when user
   expects continuation. Explicit error + action choices.

4. **`Completed` removal**: Full consensus.

## Key Disagreement: Hot Idle vs Cold Idle

I investigated both the platform lifecycle and reload path reliability in the codebase.

### Evidence: AndroidPlatform lifecycle
- `AndroidPlatform` has **separate `start()`/`stop()`** methods (not monolithic cleanup)
- `VirtualDisplayPlatform.stop()`: releases ImageReader + VirtualDisplay (the heavy resources)
- `AccessibilityPlatform.stop()`: no-op (already lightweight)
- `platform.start()` is re-entrant and idempotent
- **Conclusion:** We CAN release expensive platform resources without destroying the session

### Evidence: What `SessionServices.cleanup()` destroys
- `historyManager.clear()` — destroys conversation context
- `llmClient.cleanup()` / `llmClientFactory.cleanupAll()` — for local LLM, releases model weights
- `traceRecorder.close()` — closes trace files
- **Conclusion:** Full cleanup is destructive. Cold Idle requires full recreate on every follow-up.

### Evidence: Reload path IS byte-fidelity safe
- `replaceAll()` bypasses `autoCompressIfNeeded()` and `onMutation` — verified in code
- Mutation listeners set AFTER replaceAll in constructor order — no accidental checkpoint
- **Conclusion:** Reload works correctly when triggered. But Cold Idle puts it on the critical path.

### The argument for Hot Idle

The follow-up bug exists because the reload path wasn't activated (missing
`selectedSessionForReload`). Cold Idle keeps reload on the critical path for
**every** follow-up. Hot Idle moves it to process-death recovery only.

**Fewer critical paths = fewer failure modes.**

Resources kept alive in Hot Idle (HistoryManager + TodoState + LLM client) cost
< 2MB total. This is trivial on Android. The expensive resources (VD, ImageReader)
are released by `platform.stop()`.

For local LLM backend specifically, Cold Idle is actively harmful: `llmClient.cleanup()`
releases model weights from memory, requiring a full model reload on every follow-up.

### What I changed in design

1. Resolved §5 in favor of Hot Idle with evidence
2. Updated §3.2 operational states: renamed `READY_HOT` → `READY` (default),
   `READY_COLD` only for process-death recovery
3. Updated §4 transition rules to reflect Hot Idle (TaskCompleted → READY, not READY_COLD)
4. Updated §10 open questions

## Vote

CHANGES (resolved Hot/Cold in design, updated transitions)
