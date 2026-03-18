# Documents UI App Skill Rewrite — Design

## Goal

Rewrite the Documents UI skill from 13 raw lines (including frontmatter, headings, and generic rules) to a Tier 1 skill (2-5 lines) containing only genuine app truths.

## Current Skill Audit

| Line | Content | Classification | Rationale |
|---|---|---|---|
| 1-4 | YAML frontmatter | **remove** | Runtime doesn't parse it; pure token waste |
| 6 | `# Documents UI Skill` | **remove** | Runtime wraps with `## App Skill` + package name |
| 8 | `## Navigation` | **remove** | Header for a single-line section |
| 9 | Hamburger/drawer swipe fallback | **app** | Genuine interaction pitfall — accessibility click on hamburger button often fails in this app, swipe-from-edge is the reliable alternative |
| 11 | `## File Operations` | **remove** | Header adds nothing |
| 12 | Verify file at destination after move/copy/delete | **core** | Generic file-operation verification; framework explicitly lists this as core-prompt content |
| 13 | Use search icon for exact name in long lists | **core** | Generic search behavior, not Documents UI-specific |

**Surviving app content: 1 line** (the drawer swipe fallback).

## Additional App Truths Considered

Beyond the existing content, what genuine quirks does Documents UI have?

| Candidate | Verdict | Reasoning |
|---|---|---|
| Navigation drawer holds storage roots (Internal, Downloads, SD card, Drive) | **add** | The drawer is not just a menu — it's the primary mechanism for switching storage providers. Without this, an agent asked to browse Internal Storage has no guidance on where to start. |
| Default view is "Recent" (cross-provider, no folder structure) | **add** | The landing screen shows recent files from all providers. An agent looking for files in a specific folder needs to know to open a storage root first, not browse the Recent view. |
| Breadcrumb bar for folder navigation | **skip** | Visible in the UI and works as expected — not a pitfall. |
| Grid vs List toggle | **skip** | Both work; visible in toolbar; no hidden trap. |
| Sort options in overflow menu | **skip** | Standard pattern, no Documents UI-specific gotcha. |
| Long-press for selection mode | **skip** | Standard Android pattern, not app-specific. |
| Copy/Move destination picker has "Copy here"/"Move here" at bottom | **skip** | Borderline, but visible in UI when the picker opens. Not a hidden trap. |

## Approach

Combine the surviving line (drawer swipe fallback) with the drawer's storage-root role and the Recent-view gotcha into 2 compact lines. No headers needed. Order by failure cost: the drawer being unreachable (blocks all navigation) is higher cost than being confused about the default view.

## Proposed Skill

```
- Navigation drawer lists storage roots (Internal storage, Downloads, etc.). If the hamburger button doesn't respond to click, swipe right from the left edge.
- Default screen shows recent files across all providers — open a specific root from the drawer to browse by folder.
```

**Line count: 2**

## Review Checklist

- **Would this help with a different real-user task?** Yes — any task requiring file browsing, moving, or finding files in a specific location benefits from knowing how navigation works and that the default view is cross-provider.
- **Does any line describe how to solve a benchmark?** No — both lines describe how the app works.
- **Could this line be said without mentioning the app?** No — the drawer-as-storage-root-selector and the Recent cross-provider view are specific to Documents UI's architecture.
- **Is the first line the most important app truth?** Yes — if the drawer can't be opened, the agent is stuck on the Recent view with no way to navigate.
- **Is the skill as short as it can be without losing a real app constraint?** Yes — each line carries a distinct app fact (interaction pitfall + data model truth).

## Trade-Offs

- **Lost generic reminders** (verify after file ops, use search): These belong in the core prompt. If the core prompt doesn't already cover them, that's a separate promotion issue, not a reason to keep them in this skill.
- **No CRITICAL block**: The drawer swipe fallback is important but doesn't meet the full CRITICAL protocol — the agent can usually recover by trying the swipe after a failed click. It's an interaction pitfall, not a silent wrong-answer trap.
- **Minimal line count (2 vs 2-5 target)**: This reflects that Documents UI is genuinely simple. Adding more lines would require inventing problems that don't exist.
