---
description: Sync documentation with code changes before commits.
---

# Update Docs

Keep documentation in sync with code changes.

## When to Activate

- Before commits with architecture/workflow changes
- After modifying public APIs
- After changing build/setup process

## Doc Priority

| Folder | Priority | Update When |
|--------|----------|-------------|
| `doc/main/` | Critical | Architecture changes |
| `doc/dev/` | Critical | Workflow/build changes |
| `doc/todo/active_proj/` | High | Project status changes |
| `doc/archive/` | Low | OK if outdated |

## Workflow

### 1. Analyze Changes

```bash
git diff --name-only HEAD~1
```

### 2. Map to Docs

| Code Change | Doc to Update |
|-------------|---------------|
| `agent/`, `session/`, `protocol/` | `doc/main/agent_infra.md` |
| `tool/` | `doc/main/agent_protocol.md` |
| `ui/` | `doc/main/ui/` (user_interaction.md, tech_design.md, style.md) |
| Build/gradle | `doc/dev/development.md` |

### 3. Update Principles

- Keep same detail level as existing content
- Don't over-document minor changes
- Link rather than duplicate
- Update timestamps

### 4. Verify

- All doc links still work
- Code examples still valid
- No stale references to removed code

## Output Format

```
DOC UPDATE: [DONE/NEEDED]

Changes analyzed:
- [file] → affects [doc]

Updates made:
- [doc]: [section updated]

Verification: [OK/ISSUES]
```

## Principles

- `doc/main`, `doc/dev`: Must stay current, best onboarding resources
- `doc/todo/active_proj`: Reflect latest status
- `doc/archive`: OK if outdated
- Keep discussion at appropriate detail level
