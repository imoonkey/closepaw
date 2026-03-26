# Review: `design_claude.md`

## Summary

The Claude draft has a strong simplifying idea: app sensitivity should modify capability risk, not create a second policy subsystem. That part is clean.

The main problem is that it optimizes the execution-time policy path and largely ignores the earlier privacy leak path that already exists in this repo. As written, it would improve approval behavior for some apps, but it would still allow sensitive screen contents to reach the LLM, history, and traces before tool policy runs. It also leaves high-consequence actions in ordinary apps under-classified and has a real correctness bug in the override-floor pseudocode.

## Critical

1. Privacy is modeled at the wrong choke point. `design_claude.md:28` says reading a bank balance is harmless, and `design_claude.md:322-328` says privacy is already handled because `OBSERVE` is safe. That does not match the current architecture. The repo captures the screen before planning in [AgentTurnRunner.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt#L136), sends the current observation into the prompt in [PromptBuilder.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptBuilder.kt#L130), and records screen observation into history in [TurnPlanningPhaseRunner.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt#L181). `PolicyEngine` only runs later during tool execution in [PolicyEngine.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt#L57). So a "read-only in bank apps is fine" policy is not actually read-only here; it still exposes confidential content to the model and traces. This is a must-fix design gap, not a detail.

## High

1. The `open_app` design cannot work with the proposed data flow. `design_claude.md:265-271` says opening Chase should be classified as opening a CRITICAL app and require approval. But `design_claude.md:296-311` and `design_claude.md:342` say `AppContext` is resolved from the current foreground package before tool execution. That gives the source app, not the target app. In the current code, the target package is only resolved inside [OpenAppTool.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt#L188) after policy has already run. Without a target-package policy request for `open_app`, the design cannot enforce its own rule.

2. Unknown apps fail open. `design_claude.md:313-320` sends unclassified apps to `STANDARD` with no escalation. That is too optimistic for the actual problem. Finance, enterprise, government, and regional-service apps have a long tail of opaque package names; many will miss both exact lists and pattern heuristics. Under this design, those apps get normal navigation and edit behavior until a `COMMIT` threshold is hit. That is not a safe default for a system whose agent can autonomously operate arbitrary apps.

3. The design does not solve action-level risk within otherwise normal apps. `design_claude.md:24-39` argues that the right abstraction is app-level capability escalation. That is incomplete. The prompt asked specifically about things like send money, delete account, or post publicly. Under this design, a generic `click` on "Delete account", "Post", or "Grant" inside a `STANDARD` app remains a moderate navigation action. The policy therefore misses the most important semantic escalation case: ordinary tools can become high-risk based on the specific target on screen.

4. The user-override safety floor pseudocode is incorrect as written. `design_claude.md:46-50` defines enum order as `CRITICAL, SENSITIVE, STANDARD`, then `design_claude.md:87-94` uses `<` and `maxOf()` as if lower ordinal meant lower sensitivity. It does not. With this enum order, a downgrade from `CRITICAL` to `STANDARD` will not be clamped the way the text claims. Even if treated as pseudocode, this is not a cosmetic issue; it shows the design has not pinned down the semantics of relax/tighten correctly.

## Medium

1. The draft understates the amount of plumbing needed to use app-skill metadata. `design_claude.md:97-108` and `design_claude.md:344-345` treat `security:` frontmatter in app `SKILL.md` files as a natural extension. In the current code, [AppSkillRepository.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/AppSkillRepository.kt#L26) strips frontmatter and only returns the body. That is fixable, but it is not just "one new parameter through the existing pipeline."

2. "One new enum" is cleaner on paper, but it collapses distinct policy problems into one sensitivity tier. Messaging, health, password managers, admin consoles, and finance apps do not only differ by how often they ask for approval; they also differ in whether the agent should be allowed to observe content at all. The three-tier model is elegant, but it is too small to express privacy boundaries without adding a second hidden concept later.

3. The package heuristic examples are weaker than they look. `design_claude.md:114-178` relies heavily on package-name exact matches and substring patterns. Some listed exact package constants are mixed-case while the classifier lowercases the runtime package string first. That is easy to fix in implementation, but it is another sign that the catalog approach needs tighter normalization rules in the design itself.

## Trade-off Notes

1. The Claude draft wins on simplicity. A small escalation table is easy to reason about, and it preserves the existing approval flow with minimal conceptual churn.

2. That simplicity is bought by pushing aside the hardest part of the problem: observation/privacy policy before cognition. For this repo, that is not optional because screen capture is part of planning, not just execution.

3. The "do not hard-block CRITICAL apps" trade-off is reasonable if the system first has a real privacy gate and deterministic target-level escalation. Without those, it is too permissive.

## Recommendation

`CHANGES_REQUESTED`

The Claude draft has a useful core idea for app-level escalation, but it is not correct enough to use as the primary base. The better base for the first aligned draft is `CODEX`, because it covers the pre-cognition privacy boundary, handles unclassified apps more safely, and includes deterministic action-level escalation rather than relying only on app tier.

**Better base for first aligned draft: CODEX**
