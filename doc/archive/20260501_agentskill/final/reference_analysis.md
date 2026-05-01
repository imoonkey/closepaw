# Reference Implementation Analysis: Agent Skills

How Claude Code, Codex, and pi handle Agent Skills.

## Comparison Table

| Aspect | Claude Code | Codex | pi |
|--------|-------------|-------|----|
| **Catalog location** | `<system-reminder>` message (turn 0) | Developer role message with `<skills>` XML | System prompt section with `<available_skills>` XML |
| **Catalog budget** | 1% of context, 250 chars/desc | 8000 chars or 2% of context | No explicit budget |
| **Activation mechanism** | `SkillTool` (model calls a tool) | `$skill-name` mention parsing in user text | `/skill:name` slash command expansion by harness |
| **Injection format** | User message, inline | User message with `<skill>` XML markers | User message with `<skill>` XML block |
| **Re-inject every turn?** | No | Per-turn if re-mentioned, not persistent | No |
| **Compaction protection** | `invoked_skills` attachment reconstructs post-compact | Fragment markers (`<skill>` start/end) registered | None — subject to normal compaction |
| **Catalog in tool description?** | No | No | No |
| **Frontmatter parser** | Custom regex + custom YAML with special-char quoting | `serde_yaml` (standard Rust YAML) | `yaml` npm package (standard YAML) |
| **Scripts handling** | Extract bundled files to temp dir, model reads on demand | Implicit activation when user runs a script from `scripts/` | Model resolves relative paths via `baseDir`, reads on demand |

## Key Takeaways for ClosePaw Design

### 1. Nobody puts the catalog in a tool description

All three put the catalog in system prompt or system-level messages. None embed it in a tool description. This challenges our current design decision (Resolved Decision #3).

**Why they avoid tool description:**
- System prompt is more prominent to the model
- Catalog is a discovery mechanism, logically part of session setup
- Tool descriptions are for explaining what a specific tool does, not for listing available options

**Implication:** We should reconsider. Putting the catalog in the system prompt (or as a system-level message in PromptBuilder) is the consensus pattern.

### 2. Nobody re-injects active skill content every turn

None of the three implementations re-inject active skill bodies via PromptBuilder on every turn. Instead:

- **Claude Code**: Skill content lives in conversation history. After compaction, `invoked_skills` attachment reconstructs it. Most sophisticated approach.
- **Codex**: Skill content lives in conversation history. `<skill>` fragment markers protect it from compaction truncation.
- **pi**: No special protection. Skill catalog is rebuilt in system prompt, but skill bodies are just in history.

**Why they don't re-inject:**
- Re-injecting 5000+ tokens every turn is expensive, especially with multiple active skills
- The tool result / user message in history already contains the content
- Compaction can be taught to preserve specific content (markers, attachments)

**Implication:** Per-turn re-injection is over-engineered. The right approach is compaction-aware protection of skill content in history, not duplication every turn. For ClosePaw, this means: either teach `HistoryManager` to preserve `<skill>` blocks, or reconstruct after compaction (like Claude Code).

### 3. Activation: tool call vs mention parsing

- **Claude Code** uses a tool (`SkillTool`) — closest to our `activate_skill` design.
- **Codex** uses `$skill-name` mention parsing — no tool needed.
- **pi** uses `/skill:name` command expansion — harness intercepts before model sees it.

For ClosePaw, a tool makes sense because our model doesn't have file-read capability (unlike Codex/pi where the model can `cat SKILL.md` directly). The tool is our only delivery mechanism.

### 4. YAML parsing: standard libraries are fine

2 of 3 implementations use standard YAML libraries (serde_yaml, yaml npm). Only Claude Code uses a fully custom parser with special-char quoting. The lenient parsing concern from the spec is real, but standard YAML + fallback handling is the norm, not custom parsers.

### 5. Catalog format: XML with name + description + location

All three use structured XML for the catalog:
```xml
<available_skills>
  <skill>
    <name>skill-name</name>
    <description>What it does</description>
    <location>/path/to/SKILL.md</location>
  </skill>
</available_skills>
```

For ClosePaw, `location` is less meaningful (skills aren't at filesystem paths the model can access). We can use a simpler format or omit location.

### 6. Scripts: model reads on demand, no special executor

All three let the model read script files on demand (via file-read tools or shell). None provide a special script executor. The scripts serve as reference material or are run by the model using existing tools.

## Design Revision Recommendations

Based on this analysis, the aligned design should change:

1. **Move catalog from tool description to system prompt / PromptBuilder** — consistent with all three reference implementations.
2. **Drop per-turn re-injection of active skills** — instead, protect skill content in history from compaction (teach HistoryManager to preserve `<skill>` blocks, or reconstruct post-compaction).
3. **Keep the `activate_skill` tool** — consistent with Claude Code's SkillTool approach. But the catalog is NOT in the tool description.
4. **Consider standard YAML parser** — 2/3 use standard libraries. Add fallback for malformed YAML if needed.
5. **Skill content format**: Use `<skill name="..." source="bundled">` XML wrapper around injected content, consistent with Codex and pi.
