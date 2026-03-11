# Review of Codex Design

## Strong points

- The design is grounded in the exact repo seam that already exists: `SessionConfig` -> `SessionToolingBootstrapper` -> `PolicyEngine` -> `ToolRouter`.
- Replacing `approvalMode` with a structured session security object is cleaner than stretching one enum further.
- Separating elevation from normal high-risk approval is correct. "Needs confirmation" and "requests extra power" are not the same thing.
- The rollout is pragmatic: capability policy first, sensitive-flow escalation second, remote entry third.

## Main concerns

### 1. The capability catalog should speak in user-meaningful terms, not only risk bands

`SAFE / MODERATE / HIGH` is fine for decisions, but it is weak as the design's primary vocabulary. The final design should preserve a semantic grouping layer so the policy does not collapse into a bag of numeric risk overrides.

### 2. `targetHints` needs a clearer source

The request shape is right, but the design should say who owns `targetHints`. If that stays vague, future implementations will smuggle heuristics into unrelated layers.

### 3. Surface should be framed as supervision, not just origin

The proposal is basically correct, but the final wording should emphasize "can the user supervise this action right now" because that is the mobile safety property that matters.

## Recommended convergence

- Keep Codex's three explicit axes and session model
- Add Claude's semantic capability layer above risk output
- Make sensitive escalation deterministic and app-skill owned
- Keep the first implementation minimal and centered on the existing policy engine
