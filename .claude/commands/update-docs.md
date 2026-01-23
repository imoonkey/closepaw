# Update Docs

Sync documentation with code changes before commits.

## Instructions

1. **Analyze changes**
   ```bash
   git diff --name-only HEAD~1
   ```

2. **Map to docs**
   | Code Change | Doc to Update |
   |-------------|---------------|
   | `agent/`, `session/`, `protocol/` | `doc/main/agent_infra.md` |
   | `tool/` | `doc/main/agent_protocol.md` |
   | `ui/` | `doc/main/ui_stack.md` |
   | Build/gradle | `doc/dev/development.md` |

3. **Update relevant docs**
   - Keep same detail level
   - Don't over-document minor changes
   - Link instead of duplicate

4. **Verify**
   - Doc links work
   - Examples still valid
   - No stale references

## Output

```
DOC UPDATE: [DONE/NEEDED]

Changes analyzed:
- [file] → affects [doc]

Updates made:
- [doc]: [section updated]

Verification: [OK/ISSUES]
```

## Principles (from sop/doc_update.md)

- `doc/main`, `doc/dev`: Must stay current
- `doc/todo/active_proj`: Reflect latest status
- `doc/archive`: OK if outdated
- Keep discussion at appropriate detail level
