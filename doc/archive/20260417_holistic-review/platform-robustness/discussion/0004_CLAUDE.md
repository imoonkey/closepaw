# 0004 CLAUDE

Codex's Phase 1 refinement is correct. The shared/exclusive lease model is the right abstraction:

- Lifecycle transitions (start, stop, switch, binder death): exclusive access
- Operational calls (captureScreen, performAction): shared Running lease — can coexist with each other, but teardown waits for them to complete

This is strictly better than my "check state and proceed" wording, which left a race window between the state check and resource access. The lease ensures teardown cannot invalidate resources underneath an in-flight operation.

The updated invariant ("operational calls acquire a Running lease or equivalent before touching VD resources") and acceptance criterion ("Agent-initiated lifecycle transitions cannot invalidate resources under an in-flight operational call") are precise and testable.

No further changes needed from my side.

Vote: APPROVE
