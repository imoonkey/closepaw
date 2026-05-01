# Cross-Review: Claude Reviews Codex Agent Skills Design

## Summary

Both designs arrive at similar foundations: separate Agent Skills from app skills, two Android-safe storage roots, lenient frontmatter parser, dedicated activation tool, no script execution. The Codex design is more thorough and catches several correctness issues that the Claude design misses. The Claude design is simpler but has gaps that would surface during implementation or at runtime.

## What Codex Gets Right That Claude Misses

### 1. Compaction Protection — Critical

Codex re-injects both the catalog and active skill instructions as PromptBuilder messages every turn. Claude puts skill content in tool call results, which live in conversation history — and ClosePaw compresses history. The agentskills.io client guide explicitly says: "exempt skill content from pruning." Codex's approach handles this by design; Claude's doesn't handle it at all.

**Verdict:** Codex is correct. This is the single biggest gap in the Claude design.

### 2. Activation Budget

Codex caps active skills at 3 with a 16 KB text budget. Claude has no limit. On mobile with smaller context windows, an uncapped model could activate many skills and blow the context. This is a real risk.

**Verdict:** Codex is correct. A budget is necessary for mobile.

### 3. Role Restrictions

Codex restricts the activation tool to Standalone and Planner roles — Executors can consume active skills but can't activate new ones. Claude registers the tool globally. An Executor activating skills during a delegated atomic task is wrong — it should follow the Planner's instructions, not browse capabilities.

**Verdict:** Codex is correct.

### 4. ToolName Registration

Codex explicitly adds `activate_agent_skill` to `ToolName` as non-screen-changing. Without this, `ToolName.from()` returns `Unknown`, which defaults to screen-changing — meaning the tool would incorrectly trigger app-tier policy checks and potentially require user approval.

**Verdict:** Codex is correct. Claude misses this implementation detail.

### 5. Sub-agent Inheritance

Codex propagates active skills to sub-agents via `SessionServices.copy()`. Claude doesn't address this. If a Planner activates a skill and delegates to an Executor, the Executor should have that skill context.

**Verdict:** Codex is correct.

### 6. User-Explicit Activation

Codex supports `/skill-name` and `@skill-name` in goal text, parsed before the first prompt. Claude defers this entirely. The spec calls for both model-driven and user-explicit activation. Supporting mention syntax is minimal code (regex against known catalog names) and a real usability win.

**Verdict:** Codex is correct. This is cheap to implement and part of the spec.

### 7. Activation Barrier in TurnToolPolicy

Codex isolates activation calls: if the model calls `activate_agent_skill`, screen actions and `complete_task` are dropped for that turn. This ensures the model sees the skill instructions (next turn) before acting. Without this, the model could activate a skill and simultaneously take an action that doesn't benefit from the skill's guidance.

**Verdict:** The idea is right, but the rule may be too aggressive — non-screen-changing tools like `write_todos` or `scratchpad` should probably still execute alongside activation. The barrier should be: drop *screen actions* and `complete_task`, not drop everything.

## Where Codex Over-Engineers

### 1. Trust Store with Digest Checking

Codex requires installed skills to have their `(name, digest)` in a SharedPreferences trust store, with trust revoked when the digest changes. This is security-forward but premature: v1 has no installation UI, no download mechanism — installed skills can only arrive via ADB push during development. Adding SharedPreferences management for a scenario that doesn't exist yet is complexity without value.

**Simpler alternative:** In v1, trust all installed skills (installation is the trust gate). Add digest-based trust when an installation flow exists. The code should be structured so trust checking can be added later, but v1 doesn't need the implementation.

### 2. Three Repository Implementations

Codex proposes `AssetAgentSkillRepository`, `FileAgentSkillRepository`, and `CompositeAgentSkillRepository`. A single `AgentSkillCatalog` class that reads from both sources (AssetManager + File) is simpler and adequate. The repository interface abstraction adds a test seam, but `AgentSkillCatalog.EMPTY` or constructor injection achieves the same thing.

### 3. AgentSkillId Wrapper Type

`data class AgentSkillId(val name: String)` adds a type for a plain string. The catalog map can be `Map<String, AgentSkillEntry>` without losing clarity. This is a wrapper that doesn't earn its keep.

### 4. `metadata:` Map Parsing in Frontmatter

Codex's parser handles `metadata:` as an indented string map. This is the most complex parsing case and we won't use it in v1 — `name` and `description` are the only required fields. Parse optional scalar fields (`license`, `compatibility`) but skip nested maps until needed.

### 5. Collision Precedence Direction

Codex maps bundled=project, installed=user, and follows the spec's "project overrides user" rule. The security argument is valid ("lower-trust installed skill shouldn't shadow release-controlled bundled skill"). But this prevents users from customizing bundled skills, which is the common use case for user-level skills in every desktop implementation.

The cleaner resolution: if trust checking is added (v2+), a trusted installed skill can override a bundled one. For v1 where all installed skills are trusted anyway, installed-overrides-bundled is more user-friendly. The precedence direction is a policy knob, not an architecture decision — the design should note both options and pick one.

## Correctness Issues in Codex Design

### 1. "Instructions Will Be Available Next Turn" Return Text

The `ActivateAgentSkillTool` says "report that instructions will be available next turn." This is misleading to the model — the model may think activation failed or is delayed. Better: return the instructions directly as the tool result (for immediate model use), AND have PromptBuilder re-inject them next turn (for compaction protection). This gives the model the instructions immediately while also protecting against compaction.

Actually, thinking more carefully: if the tool returns the full body AND PromptBuilder re-injects it next turn, there's duplication in context. But this is the approach Codex takes — the tool result is in history AND active skills are in PromptBuilder. This is intentional: the tool result may be compressed away, but the PromptBuilder injection survives.

So: the tool SHOULD return the instructions (so the model has them immediately), AND PromptBuilder should re-inject them (compaction protection). The "available next turn" phrasing should be removed — the instructions are available now.

### 2. `activateExplicitMentions` Regex Ambiguity

`/skill-name` in a goal could conflict with Unix paths or URLs. `@skill-name` could conflict with email-like patterns. The regex should match only at word boundaries and only against known catalog names.

### 3. Safety Disclaimer Verbosity

The active skills prompt includes: "They do not grant new tools or permission to bypass safety/policy." This is important context, but the phrasing is 15 tokens per activation block. Keep it to one line.

## Minor Notes

- Codex uses package `ai.closepaw.agent.cognition.skills` — consistent with existing `cognition.prompt` package. Claude uses `ai.closepaw.agent.skill`. Codex's is more consistent.
- Codex's task breakdown is more granular (5 tasks with clearer scope). Claude's is similar but groups tests separately rather than per-component.
- Both designs correctly ignore `allowed-tools` in v1.

## Score Card

| Aspect | Claude | Codex |
|--------|--------|-------|
| Compaction protection | Missing | Correct |
| Activation budget | Missing | Correct |
| Role restrictions | Missing | Correct |
| ToolName registration | Missing | Correct |
| Sub-agent inheritance | Missing | Correct |
| User-explicit activation | Deferred | Correct |
| Activation barrier | Missing | Mostly correct |
| Trust model | Simpler (OK for v1) | Over-designed for v1 |
| Type system | KISS | Over-typed |
| Repository layering | Single class | Too many classes |
| Frontmatter parser | Right-sized | Over-scoped |
| Collision precedence | Debatable | Debatable |
| Prompt changes | Zero (tool desc only) | Moderate (PromptBuilder) |
| Overall complexity | Low | Moderate |

## Recommendation

**CODEX is the better base for the first aligned draft.**

Codex catches five correctness issues that Claude misses entirely (compaction, budget, roles, ToolName, sub-agents). These aren't nice-to-haves — compaction protection alone is a must-have for correct behavior, and the activation budget is essential on mobile.

However, the aligned draft should simplify Codex's design by:
1. Dropping the digest-based trust store (trust all installed skills in v1)
2. Merging three repositories into one catalog class
3. Dropping AgentSkillId wrapper
4. Simplifying frontmatter parser (skip `metadata:` map parsing)
5. Having the activation tool return instructions immediately (not "next turn")
6. Making the activation barrier less aggressive (allow non-screen-changing tools)

The result should be Codex's correctness with Claude's simplicity.
