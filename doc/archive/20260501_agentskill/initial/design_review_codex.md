# Codex Review of Claude Agent Skills Design

## Summary

Claude's design is clean and intentionally small: discover skills, put the catalog in the activation tool description, and return full instructions as a tool result. That is attractive from an implementation-size standpoint, but it has correctness gaps against the agentskills.io client guidance and the current ClosePaw runtime.

The biggest issue is that the design optimizes for "no prompt changes" even though ClosePaw's history compression and role tool filtering make that unsafe for skill instructions.

## Correctness Issues

### 1. Collision Precedence Is Reversed

Claude maps:

- project-level skills -> `assets/agent_skills`
- user-level skills -> `context.filesDir/agent_skills`

That mapping is reasonable, but then the design says installed skills override bundled skills and claims this matches the spec. It does not. The briefing says project-level overrides user-level. With Claude's mapping, bundled assets should override installed private-storage skills on name collision.

Installed-over-bundled also creates a security footgun: a lower-trust, user/imported skill can shadow a release-controlled skill by name.

### 2. Invalid Standard Skills Are Loaded Too Leniently

Claude says name/directory mismatch and overlong names should warn and still load. The briefing gives these as key constraints, including `name` matching the parent directory and lowercase-hyphen grammar. For ClosePaw, invalid skills should be skipped and logged. A malformed catalog entry should not become prompt-visible content.

### 3. Role Allowlists Are Missing

ClosePaw filters tools through `AgentRoleDef.allowedTools`. Registering `ActivateSkillTool` is not enough. Unless `activate_skill` is added to Standalone and Planner allowed tool sets, `Turn` will filter it out of the schema and drop returned calls.

Claude's draft does not mention updating role allowlists. It also does not mention updating `ToolName`, so the new tool would be treated as `Unknown`, which ClosePaw currently classifies as screen-changing.

### 4. Activation Can Be Batched With Actions

Current `TurnToolPolicy` keeps cognitive tools and screen-changing tools in the same turn. Claude's design has no activation barrier. If the model calls `activate_skill` and `mobile_action` together, the agent can act before the skill instructions are available to the model.

Activation should execute alone. The following turn should receive the active skill body before any skill-dependent action.

### 5. Tool Result Injection Does Not Protect Skills From Compaction

Claude returns the full skill body as a tool output and relies on conversation history. That fails the briefing's "protect skill content from context compaction" requirement.

ClosePaw's `HistoryManager` compresses and evicts history. A skill body stored only as a function output can drift far from the observation, be truncated, or be evicted. Active skills should be regenerated into prompt input every turn, like app skills and recalled memory, rather than relying on history retention.

### 6. Explicit User Activation Is Underspecified

The spec supports user-explicit activation. Claude's model-driven flow is clear, but it does not implement `/skill-name` or mention-style activation from the user's goal. That means user-explicit activation depends on the model noticing the catalog and choosing the activation tool, which is not the same feature.

### 7. Trust Model Is Too Weak

Claude treats installed `filesDir` skills as trusted because "user explicitly installed them", but v1 has no install UI and no trust record. A future import/download path would immediately inherit this weak assumption.

The safer minimal model is:

- bundled skills are trusted by release;
- installed skills require a `(name, digest)` trust record;
- changed installed skill content invalidates trust;
- untrusted installed descriptions are not shown to the model.

This is more code, but it prevents prompt injection from an imported skill before user consent.

### 8. Catalog In Tool Description Is Fragile

Putting the catalog in the activation tool description is small, and it does keep catalog text near activation. The downside is that the catalog only exists if the tool survives role filtering and tool schema handling. It also gives no place for explicit activation warnings, active skill state, or safety wrapping.

A dedicated prompt catalog block is more visible, easier to test in `PromptBuilderTest`, and closer to the spec's "catalog loaded at session start" tier. It can still be compact.

## Strong Parts Of Claude's Design

- It correctly keeps Agent Skills separate from existing package-scoped app skills.
- It avoids script execution, which is the right Android security call.
- It uses Android-safe roots: `assets/agent_skills` and `filesDir/agent_skills`.
- It keeps installation UI and remote registry out of v1.
- It recognizes that a full YAML dependency is unnecessary for the MVP parser.

## Trade-off Assessment

Claude's design is simpler in raw file count because it avoids `PromptBuilder` and `TurnPlanningPhaseRunner` changes. But that simplicity is bought by leaning on history/tool-output behavior that is not a stable place for active skill instructions in ClosePaw.

Codex's design is a little larger: repository, trust store, manager, activation tool, prompt wiring, role allowlist update, and activation barrier. Those pieces map directly to real requirements from the briefing:

- progressive disclosure;
- explicit activation;
- trust gating;
- compaction protection;
- role/tool-policy correctness;
- coexistence with app skills.

The extra code is not decorative. It closes concrete runtime holes.

## Recommended Alignment

Use Claude's design as a source of simplifying pressure:

- keep no script runner;
- keep no install UI in v1;
- keep discovery one-level deep;
- keep parser dependency-free;
- avoid changing existing app-skill repository behavior.

Use Codex's design as the implementation base:

- bundled-over-installed precedence;
- strict validation for standard constraints;
- digest-based trust for installed skills;
- active-skill manager;
- prompt-injected active skills every turn;
- explicit mention activation;
- role allowlist and `ToolName` updates;
- activation-only turn policy.

## Better Base

CODEX is the better base for the first aligned draft. Claude's version is smaller, but it misses several ClosePaw-specific correctness requirements that would likely show up as broken activation, unsafe installed-skill trust, or skill instructions disappearing under history compression.
