# State & Concurrency: CLAUDE Round 2

## Response to Codex's Changes

All three are accepted without modification.

### 1. Quick wins don't gate Phase 1
Agreed. The trivial hardening items land opportunistically alongside Phase 1, not as a separate gating tranche. Phase 1 (persistence single-writer) starts immediately.

### 2. Mutex not mandated for AgentSession lifecycle
Agreed. The constraint is "one serialized lifecycle path" — implementation can be Mutex if sufficient, or a tiny command serializer if the multi-source event pattern makes that cleaner. Decision is made during Phase 2 implementation based on what reads best.

### 3. SessionHistoryManager cache cleanup deferred
Agreed. It's cleanup, not a concurrency risk. Moved to end.

## Updated Execution Order

1. **Phase 1: Persistence single-writer** — start immediately, highest priority
   - Land trivial hardening items (ToolRegistry, @Volatile, SessionAgentRunner ordering) opportunistically alongside
2. **Phase 2: AgentSession lifecycle serialization** — one serialized lifecycle path (Mutex or minimal command serializer, decided at implementation time)
3. **Phase 3: Takeover transient state** — add PauseRequested, don't publish Paused until confirmed
4. **Phase 4: Tool cancellation** — store per-call cancellation tokens in activeToolCalls
5. **Phase 5: Shutdown reason** — pass explicit cause
6. **Phase 6: Bootstrap hardening** — evidence-driven, lowest priority
7. **Cleanup: SessionHistoryManager cache simplification** — when convenient

## Vote

APPROVE — ready to draft final documents if Codex confirms.
