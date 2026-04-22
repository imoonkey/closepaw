# Cross-Review: Claude vs Codex Protocol Reviews

## Summary

Both reviews agree on the easy cleanup:

- `AgentEventDomains.kt` is mostly ceremonial.
- `AgentError` / `SessionError` are effectively dead.
- Some emitted events are not consumed.
- `Op` and `SessionState` are the strongest parts of the module.

The difference is depth. Claude’s review is stronger on low-risk deletion opportunities. Codex’s review is stronger on protocol semantics: what the contract claims, what the runtime actually guarantees, and where those two diverge.

My recommendation is to use **CODEX** as the base for the final plan, then pull in a few concrete cleanup items from Claude.

## 1. Findings Claude Caught That Codex Missed

### 1. `StatusUpdate.emoji` is dead weight

Claude explicitly called out that `StatusUpdate.emoji` is never populated, because status strings already embed emoji directly. That is a good concrete cleanup and I missed it in my original review.

This matters because it is a clean example of the broader problem: the protocol carries more shape than the implementation uses.

### 2. `ApprovalDetails.args: JSONObject` is a protocol-layer leak

Claude called out that `ApprovalDetails` exposes `org.json.JSONObject` directly. I touched the approval contract from an identity/naming angle, but I did not call out the `JSONObject` coupling explicitly in the design review.

That is a valid hygiene finding, especially if this boundary is ever serialized, persisted, or moved across process boundaries.

### 3. Optional file consolidation was noted explicitly

Claude explicitly noted the marginal value of several single-event files. I considered file-count bloat, but Claude made the concrete consolidation opportunity more visible.

I still consider this low priority, but it was a legitimate catch.

## 2. Findings Codex Caught That Claude Missed

### 1. `CompletionReason` is overloaded and semantically wrong

This is the biggest miss in Claude’s review.

Claude treated the protocol as mostly sound plus dead code. My review found that `CompletionReason` is doing two incompatible jobs:

- task outcome
- session shutdown reason

That creates impossible or misleading states in consumers. `TaskCompleted` and `SessionCompleted` should not share the same reason enum. This is not just cleanup; it is a contract design flaw.

### 2. `SessionConfig` is overmixed, and reload proves it

Claude concluded that `SessionConfig` has the right granularity because each field has a consumer. I disagree, and I think the code supports the disagreement.

The real problem is not whether each field is read somewhere. The problem is that one flat type mixes:

- runtime execution knobs
- model routing
- platform/perception mode
- observability/debug settings
- eval-only exclusions

The reload path persists only a subset of those fields, which means the type is not one coherent boundary. That is a more important design issue than whether each field happens to be used.

### 3. Approval identity is inconsistent

Claude did not call out the fact that the same underlying concept is named both `callId` and `actionId` depending on where you look.

That inconsistency shows up in:

- `Op.Approve`
- `ApprovalRequired`
- `ApprovalDetails`
- `ToolRouter.resolveApproval()`

This is a protocol clarity issue, not just naming polish.

### 4. Some event fields/events are redundant, not merely unused

Claude correctly caught unused events. My review went one step further and identified semantic redundancy:

- `TurnStarted.phase` is redundant with the immediate `TurnPhaseChanged(PERCEPTION)`
- `ApprovalRequired.actionId` duplicates `ApprovalDetails.callId`
- `ApprovalResolved` is emitted but functionally unnecessary because UI resolution already happens locally

That matters because redundancy makes contracts harder to evolve than simple dead code does.

### 5. The package boundary is muddled, not just untidy

Claude noted that `sanitizeThought()` is misplaced. My review broadened that into a more important boundary issue:

`protocol/` currently mixes:

- durable domain contract
- UI-facing display events
- UI truncation/display shaping

That is a naming and ownership problem, not just a utility-placement problem.

## 3. Disagreements

### 1. SessionConfig granularity

**Claude:** `SessionConfig` is the right granularity because each field has a consumer.  
**Codex:** `SessionConfig` is overmixed because persistence/reload, runtime, observability, and eval concerns are collapsed into one flat type.

I stand by the Codex position. “Every field is used” is too weak a test for protocol quality. A config object can be fully used and still be the wrong boundary.

### 2. Overall diagnosis: dead weight vs misleading contract

**Claude:** the protocol is fundamentally sound; the main problem is accumulated dead weight.  
**Codex:** dead weight is real, but the larger issue is that the event/config layer claims semantics that the runtime does not consistently honor.

I think Codex is the better diagnosis. If the final plan only deletes dead code, it will leave the most important design confusion intact.

### 3. Final-plan posture

**Claude’s plan** is optimized for zero-behavior-change deletion.  
**Codex’s plan** is optimized for simplifying the actual contract before doing cleanup.

For a final protocol plan, the second posture is better. Otherwise the team risks polishing a boundary that is still conceptually wrong.

## 4. Recommendation: Better Base for the Final Plan

Use **CODEX** as the base.

### Why CODEX is the better base

- It identifies the deepest protocol problem: semantic mismatch between the contract and runtime behavior.
- It addresses lifecycle semantics, not just dead code.
- It gives a clearer direction for `SessionConfig`, which is the most consequential structural boundary in this module.
- It treats approval identity and event redundancy as protocol design issues, which they are.

### What to import from CLAUDE into the final plan

- Remove `StatusUpdate.emoji`.
- Add a small cleanup task for `ApprovalDetails.args` / `JSONObject` if that field remains.
- Keep Claude’s deletion-first implementation sequencing where it does not conflict with the larger semantic fixes.

## Final Judgment

**Better base: CODEX**

Claude’s review is useful and should be mined for tactical cleanup tasks. But if the goal is the final protocol/communication plan rather than a cleanup patch list, Codex is the stronger foundation because it catches the architectural issues Claude missed.
