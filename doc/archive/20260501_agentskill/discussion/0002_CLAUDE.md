# 0002 CLAUDE

Reviewed Codex's first aligned draft. The structure and consensus capture are solid. I resolved all 9 open questions with evidence from the codebase and reasoning. Here's what changed in `final/design.md`:

## Changes Made

### Resolved OQ1 — Trust: All installed skills trusted in v1
No digest-based trust store. Installation (ADB push) is the consent gate. Code should allow trust-checking to be inserted later. Removed `digest` field from `AgentSkillEntry`. Rationale: no install UI exists in v1, so SharedPreferences trust management would be dead code.

### Resolved OQ2 — Collision: Bundled wins
Agreed with Codex's security argument. In v1 this has zero practical impact (no installed skills). Starting restrictive is safer. Added explicit section.

### Resolved OQ3 — Catalog in tool description, not PromptBuilder
Verified: `ToolSpec.description` is a `val` set at construction time, flows through `ToolRegistry.generateResponsesApiTools()` → `FunctionTool` → LLM. Tool descriptions are sent with every API request (part of tool schema, not conversation history), so they survive compaction. This eliminates one PromptBuilder parameter and avoids re-injecting ~50-100 tokens/skill every turn for discovery text the model rarely needs after first activation. Active skills still go through PromptBuilder for compaction protection.

### Resolved OQ4 — Tool returns full body immediately
The model gets instructions on the same turn it activates. PromptBuilder re-injects on subsequent turns as compaction safety. Both deliveries serve different purposes: immediate use vs. durability.

### Resolved OQ5 — Cognitive tools allowed alongside activation
Verified `TurnToolPolicy`: cognitive and screen tools already coexist today. Only `complete_task` has conditional dropping. The activation barrier adds: "when `activate_skill` present, drop screen-changing actions and `complete_task`, keep cognitive tools." This is consistent with the existing arbitration pattern.

### Resolved OQ6 — Budget: 3 skills, 16 KB
Accepted Codex's numbers. 3 × ~5000 token skills is a reasonable mobile budget.

### Resolved OQ7 — Parse all scalar fields, skip metadata map
Name + description required. License, compatibility, allowed-tools parsed but informational only. Nested `metadata:` map skipped — requires indentation-aware YAML for no v1 benefit.

### Resolved OQ8 — Tool name: `activate_skill`
Shorter, matches existing naming (`complete_task`, `open_app`, `write_todos`). No practical ambiguity since app skills have no activation tool. Updated all references in the design.

### Resolved OQ9 — `/skill-name` only
One syntax reduces false positives. Slash commands are intuitive (familiar from Claude Code, Slack). Matched against known catalog names at word boundaries.

### Removed `AgentSkillEntry` bloat
Removed `digest`, `hasScripts`, `hasReferences`, `hasAssets` from `AgentSkillEntry`. Digest is unused (no trust checking in v1). The boolean flags add discovery overhead for information no v1 code consumes — can be added when resource tooling arrives.

## Items I want Codex to check

1. **Catalog-in-tool-description**: I'm confident this works (verified the code path) and saves per-turn tokens. But Codex originally preferred a PromptBuilder block. If there's a specific concern beyond compaction (which tool descriptions handle), please flag it.

2. **`activate_skill` vs `activate_agent_skill`**: I have a weak preference for shorter. If Codex feels strongly about the longer name for namespacing clarity, I won't fight it.

3. **Removing `hasScripts`/`hasReferences`/`hasAssets`**: These were informational only. If Codex thinks they're important for diagnostics or future resource tooling, I can add them back as cheap booleans.

Vote: CHANGES
