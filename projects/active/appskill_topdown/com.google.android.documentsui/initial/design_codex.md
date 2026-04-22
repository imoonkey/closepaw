# DocumentsUI Skill Rewrite Design

## Goal

Rewrite `com.google.android.documentsui` to a Tier 1 app skill that keeps only repeated, app-local truths. Remove dead frontmatter and headings, drop generic solver/verification guidance, and preserve the drawer fallback that prevents the most common navigation failure.

## Current Skill Audit

| Current line | Label | Why |
| --- | --- | --- |
| `---` | overfit | YAML frontmatter is not parsed by runtime, so this is dead token cost. |
| `name: com.google.android.documentsui` | overfit | Package name is already injected by runtime. |
| `description: App-specific guidance for Android Documents UI (Files).` | overfit | Dead metadata; no runtime effect. |
| `---` | overfit | Closing frontmatter delimiter is also dead token cost. |
| `(blank line)` | overfit | No information value. |
| `# Documents UI Skill` | overfit | Decorative heading; runtime already wraps with app-skill heading. |
| `(blank line)` | overfit | No information value. |
| `## Navigation` | overfit | Section label adds tokens but carries no app truth by itself. |
| `If the hamburger/drawer menu doesn't open via click, swipe right from the left edge.` | app | Stable DocumentsUI interaction pitfall; this is the key app-specific navigation fallback. |
| `(blank line)` | overfit | No information value. |
| `## File Operations` | overfit | Decorative section label; no standalone app truth. |
| `- After move/copy/delete, verify the file is at the destination and gone from the source.` | core | Generic file-operation verification rule, not specific to DocumentsUI. |
| `- Use the search icon to locate files by exact name when the list is long.` | core | Generic search guidance; useful, but not an app-local quirk. |

## Proposed Rewrite

Target raw skill body: 2 lines, no headers.

```md
- If the hamburger/drawer button does not open the left navigation panel, swipe right from the left edge to reveal locations and storage roots.
- In picker flows, the final action label changes by mode (`Open`, `Save`, `Use this folder`) and may stay disabled until the target file or folder is selected.
```

## Rationale

- Line 1 stays first because it prevents the main wrong branch: getting stuck in one directory because the drawer fails to open through the obvious tap target.
- Line 2 captures the other durable DocumentsUI quirk a real user hits: this package often appears as a system picker, so the commit action is mode-dependent and selection-gated.
- Search guidance is removed because it is generic agent behavior, not a DocumentsUI truth.
- Post-copy/move/delete verification is removed because it belongs in core execution discipline, not this app skill.
- No `CRITICAL` header: the highest-risk rule already leads the skill, and a header would spend half the Tier 1 budget on formatting instead of content.

## Self-Review

The rewrite stays within Tier 1, keeps only app-local interaction truths, and avoids solver logic, eval assumptions, and decorative formatting. The only added fact beyond the current skill is the picker-mode commit behavior, which is broad enough to help real DocumentsUI tasks and compact enough to earn its prompt cost.
