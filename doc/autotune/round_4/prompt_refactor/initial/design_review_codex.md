# Review: Claude Prompt Refactor Design

## Summary

Claude's design is strong on diagnosis. It correctly identifies the main failure mode in the current prompt: unclear rule ownership, duplicated tool guidance, and unbounded growth of app-specific patches. On direction, it is largely aligned with my design in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_codex.md#L406): smaller core prompt, richer tool-local descriptions, and moving app tips out of the static base prompt.

The main problems are in the proposed implementation details, not the diagnosis. In particular, the proposed `{{app_tips}}` mechanism assumes a prompt lifecycle the current system does not have, and the rule-allocation policy slides back toward putting cross-tool cognitive policy into tool descriptions. I would treat the design as directionally correct but not yet ready to implement as written.

## High

1. **The proposed app-tip injection model is not compatible with the current prompt lifecycle.** Claude recommends injecting `{{app_tips}}` based on the current foreground app at runtime in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L363) and [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L380). But the current system prompt is resolved once when the session starts in [SessionAgentRunner.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt#L64) and only supports fixed string replacements in [SessionAgentRunner.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt#L104). As written, the design would either freeze app tips to the launch-time app or require a larger architectural change than the doc acknowledges. The fix is to explicitly choose one of two designs:
   - make the system prompt per-turn dynamic, or
   - inject hints into per-turn user context via `PromptBuilder` instead of the static system prompt.

2. **The system-prompt vs tool-description split is internally inconsistent.** Claude states that tool descriptions are API references and the system prompt is the cognitive playbook in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L317), then proposes moving cross-turn/cognitive rules into tool descriptions:
   - semantic selector preference into `MobileActionTool` in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L340)
   - shell retry/pivot policy into `ShellTool` in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L345)
   - pre-completion checklist into `CompleteTaskTool` in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L350)

   Those are not all tool-local API rules. They include global action policy, recovery policy, and task verification policy. Moving them into tool descriptions recreates the ownership blur the design is trying to remove. My design explicitly separates these layers in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_codex.md#L596). The fix is to keep tool descriptions self-contained for tool use, but keep retry, QA, and completion strategy in the core prompt.

3. **A single `app_tips` bucket is too weak a model and will turn into a new dumping ground.** Claude moves multiple non-app-scoped rules into `App Context`, including always-injected date-label and `Nh` time-format rules in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L382). That is already broader than “app tips”. It leaves no clean owner for:
   - task-format rules like blank-line semantics
   - filename matching rules
   - environment rules like ignoring the capsule UI
   - app-independent date-label mapping

   My design uses separate `AppHint`, `TaskHint`, and `EnvironmentHint` buckets plus a quality bar in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_codex.md#L558). Without that separation, the app-tip registry will accumulate the same mixed-content problem in a new file.

## Medium

1. **Unsupported-task policy should stay in the core prompt, not be deleted or buried in `CompleteTaskTool`.** Claude suggests moving the image-reading limitation into `CompleteTaskTool.description` or deleting it in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L301). I disagree. Unsupported-capability handling is task-mode policy, not completion-tool API. If the agent only sees that guidance when thinking about `complete_task`, it may still waste turns before deciding to stop. My design keeps “Unsupported mode” in the core prompt in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_codex.md#L474).

2. **The migration plan enriches tool descriptions before doing a formal ownership audit.** Claude’s rollout starts with additive tool-description enrichment in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L424). That is safe operationally, but it also risks cementing questionable heuristics into tool descriptions before deciding whether they should survive at all. My design starts with explicit rule tagging in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_codex.md#L698). I would add that ownership-audit step ahead of any content migration.

3. **Scratchpad and todo overlap is not fully resolved.** Claude says `ScratchpadTool` and `WriteTodosTool` need no changes in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L355), but the design still leaves an unclear split between:
   - tool-local semantics: full-list replacement, truncation/read behavior
   - cross-tool behavior: when to plan, when to batch facts, when to stop re-surveying

   This is not fatal, but it means the post-refactor system may still retain a smaller version of the current duplication unless that split is tightened.

4. **One overfit heuristic is retained in the core design without challenge.** Claude keeps “turn-budget awareness, partial > nothing” inside the Information Gathering strategy in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L272). I flagged that class of rule as likely evaluator-shaped and needing re-justification in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_codex.md#L77). I would not carry that over automatically.

## Agreement

1. **We agree on the root problem.** Both designs identify rule ownership, not raw length, as the real failure mode. Claude makes this clear in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L53); I make the same argument in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_codex.md#L112).

2. **We agree that app-specific guidance should leave the static base prompt.** Claude proposes dynamic injection in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L246) and [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L363). I recommend the same direction in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_codex.md#L558).

3. **We agree that the core prompt should shrink and stop containing per-tool subsections.** Claude’s target hierarchy does this in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L250). My design does the same in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_codex.md#L416).

4. **We agree that autodevice is the anti-pattern to avoid.** Claude calls it out directly in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L183); I make the same point in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_codex.md#L323).

5. **We agree the refactor should stay within the single-agent architecture for now.** Claude makes that scoping decision in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L463). I also kept the design scoped to the current architecture.

## What Claude Caught That I Missed

1. **Claude did a better job calling out internal redundancy in the current prompt itself.** The repeated retry/pivot rules are explicitly identified in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L40). I discussed duplication mostly as prompt-vs-tool overlap, not prompt-internal repetition.

2. **“Critical Rules First” is a useful structural idea.** Claude pulls this pattern from minitap in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L238). I did not emphasize ordering within the core prompt as strongly.

3. **The “What Moves Where” table is stronger than my first-pass migration map.** Claude’s explicit line-by-line relocation table in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L288) is very implementation-friendly.

4. **The phased rollout is operationally safer in one respect.** Claude’s “enrich tool descriptions first, then remove duplicated prompt content” plan in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_claude.md#L424) is a good anti-regression tactic, even though I still think it should be preceded by ownership tagging.

## What I Think Claude Still Missed

1. **The runtime architecture constraint on system-prompt dynamism.** This is the biggest gap. The design needs to acknowledge that current prompt resolution is session-scoped, not turn-scoped.

2. **A proper hint taxonomy and quality bar.** Without separate app/task/environment hint classes, the new registry risks becoming a second unstructured dumping ground.

3. **QA and unsupported behavior should remain explicit task modes.** Claude keeps QA as strategy guidance, which is fine, but then weakens unsupported-task handling by pushing it toward `CompleteTaskTool`.

4. **Ownership checks after the refactor.** My design adds explicit prompt-ownership checks and snapshot tests in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_4/prompt_refactor/initial/design_codex.md#L766). Claude has eval validation, but not structural regression guards.

## Overall Comparison With My Design

Claude’s design is stronger on prompt shape and implementation concreteness:

- clearer callout of “Critical Rules First”
- more explicit relocation table
- more operationally staged rollout

My design is stronger on rule ownership and long-term maintainability:

- sharper separation between core prompt, tool descriptions, and dynamic hints
- explicit task-hint/environment-hint distinction
- clearer argument that QA/unsupported behavior is task-mode policy
- stronger anti-regression guardrails

If I were merging the two designs, I would take:

1. Claude’s relocation table and phased rollout.
2. My ownership model, hint taxonomy, and task-mode framing.
3. A revised injection mechanism that works with the actual prompt lifecycle.

## Recommendation

**CHANGES_REQUESTED**

Claude’s design is good enough to use as input to the final refactor plan, but not good enough to implement verbatim. Before implementation, I would revise it to:

1. Replace the current `{{app_tips}}` proposal with a mechanism compatible with the real prompt lifecycle.
2. Tighten the split so tool descriptions own tool-local semantics only, not global retry/completion policy.
3. Introduce separate `AppHint`, `TaskHint`, and `EnvironmentHint` buckets plus a hint quality bar.
4. Keep unsupported-task behavior in the core prompt.
5. Add an explicit ownership-audit step and post-refactor structural checks.
