---
name: update-docs
description: Sync documentation with code changes before commits. Triggered by /update-doc or /update-docs after architecture/workflow changes.
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
| `doc/main/` | Critical | Architecture and runtime behavior changes |
| `doc/dev/` | Critical | Workflow/build changes |
| `.ai-dev/ & AIDEV.md` | Critical | AI Dev agent workflow changes |
| `doc/todo/` | High | Active project status |
| `doc/archive/` | Low | OK if outdated |

## Canonical Doc Map

- Use `doc/main/README.md` as the source of truth for documentation structure and navigation.
- Do not duplicate the full `doc/main` tree inside this skill file.

## Workflow

### 1. Analyze Changes

Docs under doc/main has its last update commit hash, use it for the diff
```bash
git diff --name-only <doc_last_update_commit>..HEAD
# or, if a commit baseline is provided:
git diff --name-only <base_commit>..HEAD
```

### 2. Map to Docs

| Code Change | Doc to Update |
|-------------|---------------|
| `agent/Agent.kt`, `agent/AgentTurnRunner.kt`, `agent/Turn.kt`, `agent/AgentRuntimeTypes.kt` | `doc/main/agent/loop.md` |
| `agent/definition/` | `doc/main/agent/overview.md`, `doc/main/agent/multiagent.md`, `doc/main/infra/session.md` |
| `agent/subagent/` | `doc/main/agent/multiagent.md` |
| `agent/cognition/`, `session/TodoState.kt`, `session/ScratchpadState.kt`, `perception/ScreenSummary.kt` | `doc/main/agent/planning.md` |
| `session/AgentSession.kt`, `session/SessionAgentRunner.kt`, `session/SessionServices.kt` | `doc/main/infra/session.md` |
| `tool/` | `doc/main/infra/tools.md` |
| `platform/`, `perception/` | `doc/main/infra/platform.md` |
| `protocol/` | `doc/main/protocol/protocol.md` |
| `history/` | `doc/main/app/history.md` |
| `llm/` | `doc/main/infra/llm.md` |
| `ui/settings/`, `app/AppSettings*` | `doc/main/app/settings.md` |
| `ui/theme/` | `doc/main/ui/style.md` |
| `ui/chat/`, `ui/navigation/`, `ui/settings/` | `doc/main/ui/tech_design.md`, `doc/main/ui/user_interaction.md` |
| `ui/overlay/` | `doc/main/ui/overlay.md` |
| `trace/` | `doc/main/agent/turn_prompt_anatomy.md` and related sections in `doc/main/agent/loop.md` |
| Build/gradle/scripts | `doc/dev/development.md` |

### 3. Update Principles

- Keep the same detail level as nearby content
- Do not over-document tiny internal renames
- Use `→ See:` pointers to source files instead of large code blocks
- Prefer linking over duplicating explanations
- Update timestamps (`Last updated`) on touched docs

### 4. Verify

- Search for removed/renamed symbols in docs (`rg` against `doc/main`)
- Verify referenced files/classes still exist
- Ensure links still work

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

- `doc/main` and `doc/dev` are primary onboarding docs and must stay current
- `doc/todo` should reflect active status
- `doc/archive` can lag behind
- Minimize code blocks; prefer file pointers (`→ See: path/to/file.kt`)
- Keep explanation depth proportional to change impact
