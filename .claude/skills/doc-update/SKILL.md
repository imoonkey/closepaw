# Doc Update

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

## Update Checklist

1. **Analyze Changes**
   ```bash
   git diff --name-only HEAD~1
   ```

2. **Match to Docs**
   - `agent/`, `session/`, `protocol/` → `doc/main/agent_infra.md`
   - `tool/` → `doc/main/agent_protocol.md`
   - `ui/` → `doc/main/ui_stack.md`
   - Build/gradle changes → `doc/dev/development.md`

3. **Update Principles**
   - Keep same detail level as existing content
   - Don't over-document minor changes
   - Link rather than duplicate
   - Update timestamps

4. **Verify**
   - All doc links still work
   - Code examples still valid
   - No stale references to removed code

## Output

```
DOC UPDATE SUMMARY

Changed files that need doc updates:
- [file] → affects [doc]

Docs updated:
- doc/main/agent_infra.md (architecture section)

Verification:
- Links: OK
- Examples: OK
```
