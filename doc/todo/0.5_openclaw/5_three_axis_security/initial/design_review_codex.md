# Review of Claude Design

## Strong points

- The design correctly keeps a single policy engine instead of inventing a parallel subsystem.
- `ExecutionContext` is the right Android translation of OpenClaw's sandbox axis. It focuses on supervision, which is the real mobile trust boundary.
- `CapabilityClass` is stronger than raw numeric risk when reasoning about user intent.
- Package/app-skill owned sensitivity hints are the right place for send/pay/delete escalation. That keeps product semantics out of generic tool code.

## Main concerns

### 1. Capability classes are useful, but the first rollout still needs a simpler policy surface

`OBSERVE / NAVIGATE / EDIT / COMMIT` is a good design vocabulary, but the repo already speaks in `RiskLevel` through `ApprovalDetails`, `PolicyEngine`, and UI labels. The design should make clearer whether `CapabilityClass` replaces `RiskLevel` or feeds it.

My recommendation: keep semantic classes as the catalog owner, but derive `SAFE / MODERATE / HIGH` from them for decision output and UI.

### 2. The task-sensitivity path needs a stricter ownership boundary

The review agrees with app-skill owned hints, but the design should state that these hints are deterministic metadata, not free-form LLM inference. Otherwise safety logic leaks into prompts.

### 3. Session config should not stop at an approval threshold

`requireApprovalFrom: CapabilityClass` captures only one axis. The final design should keep all three axes explicit in config so remote/elevation rules are first-class rather than implied.

## Recommended convergence

- Keep Claude's semantic capability classes
- Keep Codex's explicit three-axis session config
- Use one policy engine and one deterministic decision path
- Phase 1 ships mostly capability policy; context and elevation land as explicit but lightly used axes
