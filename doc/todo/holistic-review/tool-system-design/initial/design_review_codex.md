# Cross-Review Of Claude vs Codex Tool-System Reviews

## Scope

Compared these four files:

- `doc/todo/tool-system-design/initial/design_claude.md`
- `doc/todo/tool-system-design/initial/improvement_plan_claude.md`
- `doc/todo/tool-system-design/initial/design_codex.md`
- `doc/todo/tool-system-design/initial/improvement_plan_codex.md`

This is a review of the reviews and plans, not a fresh third design audit.

## Executive Judgment

**Better base for the improvement plan: `CODEX`.**

Reason:

- Codex identified the highest-severity system-design problem: the blocked-app boundary is not enforced end to end.
- Codex also framed the deeper architectural issue correctly: tool capabilities and policy metadata do not belong in a parallel `ToolName` heuristic layer.
- Claude’s review is useful, but it is weighted too heavily toward cleanup and local simplification. Its improvement plan does not address the biggest correctness/security issue first.

Best outcome:

- Use **CODEX** as the base plan.
- Merge in a focused subset of **CLAUDE** findings as secondary cleanup items after the structural fixes.

## What Both Reviews Independently Found

These are the strongest convergent findings because both reviews discovered them independently.

### 1. `ToolName` is incomplete and misclassifies real tools

Both reviews found that `ask_user` and `shell` are not represented correctly in `ToolName`, which causes wrong policy/arbitration behavior.

Shared value:

- this is real
- it affects runtime behavior
- it should be fixed

Difference:

- Claude treats this mostly as an enum-completeness bug
- Codex treats it as a symptom of a deeper metadata-design problem

### 2. `shell` is problematic

Both reviews found that `shell` is risky and weakly constrained.

Shared value:

- the current implementation is not robustly enforced by the tool framework
- the command restriction model is weak

Difference:

- Claude sees this mainly as a blocklist-hardening issue
- Codex sees it as a model violation: an opaque shell string bypasses the declarative tool system

### 3. There is stale/dead framework surface

Both reviews noticed unused or stale pieces:

- `DataQueryInvocation`
- schema/export helpers that appear unused in production
- leftover compatibility structure around old action naming

Shared value:

- there is cleanup debt
- not all abstractions in `tool/` are carrying their weight

## What Claude Found That Codex Missed

Claude is stronger on local cleanup, dead-code spotting, and small implementation inconsistencies. These are the main useful additions from Claude.

### 1. Dead scroll-boundary code in `UIActionInvocation`

Claude correctly noticed that `UIActionInvocation` is only used by `SystemButtonTool` and `WaitTool`, so its `uiAction is UIAction.Swipe` branch is dead. This is worth keeping because it is concrete, low-cost cleanup.

### 2. `MobileActionName` has vestigial members / dead escape path

Claude correctly called out that `PolicyEngine.isEscape()` still contains legacy `mobile_action(action=back/home)` logic even though `MobileActionTool` no longer validates those actions. This is a good cleanup finding.

### 3. `Scheduled` state is probably unnecessary

Claude’s point that `Scheduled` is effectively ephemeral is fair. This is not a correctness issue, but it is a valid KISS observation.

### 4. Duplicate constants and small dead branches

Useful low-cost items:

- duplicate constants in `OpenAppTool`
- unreachable fallback branch in `SystemButtonTool`
- duplicate scroll-boundary logic

These are worth keeping as cleanup tasks, but not as plan drivers.

### 5. Silent shell truncation

Claude caught that shell output truncation is silent. This is a good product-quality finding and should be added if `shell` remains at all.

### 6. Small output/ergonomics findings

Claude also caught several low-priority polish issues:

- `CompleteTaskTool` output wrapper is noisy
- executor objects are reallocated per call
- `WriteTodosTool` double-parses

These are real, but secondary.

## What Codex Found That Claude Missed

These are the highest-value misses in the Claude review.

### 1. End-to-end blocked-app boundary failure

Codex found the most important issue in the module:

- policy checks happen before execution using the current foreground app
- `open_app` resolves its destination later, inside execution
- multiple tool-layer paths capture raw screen data directly
- masking is not applied consistently at all capture boundaries

Claude did not identify this as a critical system flaw. Instead, Claude concluded:

- “No critical bugs found”
- “No security bypasses in the policy engine”

That misses the main tool-system-design problem. The policy tree itself is not the problem; the end-to-end boundary is.

### 2. `open_app` needs destination-aware policy, not just enum cleanup

Claude’s plan says to add `ask_user` and `shell` to `ToolName` first. Codex correctly identified that the bigger gap is `open_app`:

- policy should consider the destination app tier
- otherwise the agent can launch into a blocked app after being approved/allowed on a safe one

This is a much more important plan item than enum completion.

### 3. Explicit-target scroll changes meaning silently

Codex identified a concrete semantic bug:

- targeted `scroll` silently degrades to whole-screen scroll when target resolution fails

Claude did not call this out. This matters because it changes the meaning of an explicit tool call at runtime.

### 4. Cancellation semantics are inconsistent end to end

Codex found that:

- `swipe` converts cancellation into failure
- `type` collapses cancellation into failure trails
- router cancellation APIs do not truly own execution cancellation

Claude did not surface this as a design issue.

### 5. Tool metadata belongs on `ToolSpec`

Codex’s deeper architectural point is correct:

- the real issue is not just missing enum entries
- capability metadata is in the wrong place entirely

Claude’s review stayed at the local-fix level here.

### 6. Point-action retargeting is hidden policy

Codex surfaced that click/long-press can retarget from the explicitly selected element to a container or nearby child. Claude described this sophistication positively, but did not challenge whether this behavior should be explicit policy instead of executor magic.

For a tool-system-design review, Codex’s framing is better.

## Where I Disagree With Claude

### 1. “No critical bugs found”

I disagree. The blocked-app observation boundary issue is critical from the tool-system-design angle.

Why:

- it undermines the stated security model
- it is cross-cutting
- it affects policy, execution, and observation

Claude’s review underweighted this because it evaluated the policy engine too locally.

### 2. `shell` is not just a hardening problem

Claude treats `shell` as “strong sandboxing” plus a bypassable blocklist. I disagree with that framing.

From the tool-system-design angle, the bigger issue is structural:

- `shell` turns a typed tool system back into a stringly-typed escape hatch
- policy cannot reason about intent
- the framework cannot express capability boundaries precisely

Claude’s hardening suggestions are useful only if the product chooses to keep `shell`.

### 3. Enum completion is not the right primary fix

Claude’s P0 is “add AskUser and Shell to ToolName.”

I disagree with that as the base plan. It is a patch on drift, not a fix for drift.

Better:

- add temporary enum entries if needed for immediate behavior correction
- but the real plan should move capability metadata onto the tool definitions

### 4. Router purity vs capture centralization

Claude downranks the router’s direct screen recapture as a purity concern and recommends no action.

I disagree. The issue is not aesthetic purity. It is that screen capture and masking are not centralized, which is exactly why the blocked-app boundary is inconsistent.

### 5. Point-action complexity is not automatically justified

Claude describes `PointActionExecutorCore` as sophisticated but justified. I only partly agree.

It is justified as an implementation tactic, but from the design angle it still deserves scrutiny because it can change the target semantics invisibly. That should be explicit and observable.

## Where I Disagree With Codex

Codex is the better base, but a few parts should be revised.

### 1. Do not force an immediate full metadata framework expansion

The direction is right, but the example `ToolMetadata` shape in the Codex plan is larger than necessary for the first step.

Revise:

- start with a minimal capability object such as:
  - `screenChanging`
  - `capturesScreen`
  - `mayLaunchApp`
  - maybe `displayName`
- only add richer categories if a real caller needs them

### 2. Be careful with immediate `shell` removal

The Codex plan says to remove `shell` from default registration unless a constrained replacement exists. The direction is defensible, but the rollout should be staged.

Revise:

- first decide whether current agent definitions and workflows still rely on `shell`
- if yes, feature-gate or replace it before removal

### 3. Cleanup items should not be undersold

Codex is correct to prioritize architecture first, but it underemphasizes some very cheap cleanup wins Claude found.

Revise:

- explicitly include the dead `UIActionInvocation` swipe branch
- explicitly include the dead `mobile_action` escape path
- explicitly include shell truncation indication if shell survives

## Which Review Is The Better Base

Recommendation: Use `CODEX` as the base.

### Why `CODEX` is the better base

1. It found the highest-severity issue.
2. It framed the main metadata problem at the right layer.
3. Its plan is ordered by system risk, not by ease of cleanup.
4. It addresses tool-system design, not just local code hygiene.

### Why `CLAUDE` is not the better base

1. It misses the blocked-app boundary flaw.
2. It concludes there are no critical bugs.
3. Its plan starts with local enum and dead-code cleanup instead of the security/correctness boundary.
4. It treats shell mostly as a hardening matter instead of a framework-boundary problem.

## What To Keep, Revise, Or Drop From Each

## Keep From CLAUDE

- dead `UIActionInvocation` swipe/scroll-boundary cleanup
- dead legacy `mobile_action(back/home)` escape path cleanup
- duplicate constant cleanup in `OpenAppTool`
- shell truncation indicator if `shell` remains
- small clarity fix in `SystemButtonTool`
- note that `Scheduled` may be removable

## Revise From CLAUDE

- “Add AskUser and Shell to ToolName”:
  keep only as a short-term patch, not the long-term design answer
- shell hardening:
  keep as interim mitigation only if shell is retained
- “No action” on router snapshot recapture:
  revise to “centralize capture and masking”

## Drop From CLAUDE

- the “no critical bugs found” conclusion
- the claim that there are no security bypasses relevant to the tool-system design
- using Claude’s plan ordering as the main implementation sequence

## Keep From CODEX

- Phase 0: secure observation boundary
- destination-aware policy for `open_app`
- Phase 1 direction: capability metadata should move onto tool definitions
- Phase 2: unify cancellation/observation semantics across executors
- explicit-target scroll should fail explicitly
- router cancellation contract tightening

## Revise From CODEX

- make the first metadata step smaller and more concrete
- stage `shell` removal/replacement instead of assuming immediate removal
- append Claude’s cheap cleanup items as a later cleanup batch

## Drop From CODEX

- do not prioritize broad cleanup of unused schema/export helpers until after the security and metadata fixes

## Final Merged Plan Shape

If I were rewriting the plan now, I would use this order:

1. Centralize capture + masking for every tool-layer observation path.
2. Make `open_app` policy destination-aware.
3. Add a temporary stopgap for `ask_user` and `shell` misclassification if needed.
4. Move capability metadata onto the tool definitions with a minimal first version.
5. Normalize cancellation and explicit-target semantics in the action layer.
6. Decide whether `shell` is removed, gated, or replaced.
7. Apply Claude’s cleanup items.

That preserves the right priorities from Codex while still harvesting Claude’s useful cleanup work.
