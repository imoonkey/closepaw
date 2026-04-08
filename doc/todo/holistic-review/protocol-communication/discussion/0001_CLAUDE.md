# Round 0001 (CLAUDE — merged proposal)

Source: Codex design (base) + Claude tactical items

Both cross-reviews independently chose CODEX as the better base. This round proposes the merged final plan.

## Base: Codex's Structure (5 phases)

Adopted as-is:

1. **Phase 1: Prune Event Surface** — collapse markers, remove dead/redundant events, remove duplicate approval identity
2. **Phase 2: Repair Lifecycle Semantics** — split `CompletionReason` into `TaskOutcome` + `SessionEndReason`, decide on idle-transition event
3. **Phase 3: Unify Interaction and Approval Naming** — canonical `callId`, rename `Op.Approve` → `ResolveApproval`, immutable approval payloads
4. **Phase 4: Reshape `SessionConfig`** — split by ownership (Execution, Model, Observability, Eval), make invalid LLM states unrepresentable, fix persistence gap
5. **Phase 5: Clarify `protocol/` Boundary** — Option B (domain contract, move display events out)

## Additions from Claude

Merged into the appropriate phases:

- **Into Phase 1:** Remove `StatusUpdate.emoji` dead field (Claude P1-D)
- **Into Phase 1:** Use Claude's concrete deletion steps with exact file/line references for `AgentError.kt`, `SessionError`, and `AgentEventDomains.kt`
- **Into Phase 3:** Flag `ApprovalDetails.args: JSONObject` for replacement with immutable type (Claude P3-B, Codex Phase 3.3)
- **Into Phase 1:** Move `sanitizeThought()` out of `protocol/` (Claude P3-A, Codex Phase 5.B)

## Execution Order

Hybrid of both approaches:

1. **Quick wins first** (Claude's zero-risk deletions): Delete `AgentError.kt`, `SessionError`, `AgentEventDomains.kt`, `StatusUpdate.emoji`, unused event emissions. This can land as a single commit.
2. **Semantic fixes** (Codex phases 2-3): `CompletionReason` split, approval ID unification. These require careful migration.
3. **Structural reshape** (Codex phase 4): `SessionConfig` split. Depends on stable lifecycle contract from step 2.
4. **Boundary clarification** (Codex phase 5): Move display events out of `protocol/`. Last because it depends on all prior cleanup.

## Scope Clarification

From Codex's non-goals (adopted):
- No richer event inheritance tree
- No generic message envelope
- No backward-compat hacks

Vote: `APPROVE` — I believe this is ready for finalization. The cross-reviews already resolved the main disagreements.
