# 0004 CLAUDE — Reopen: extend `ask_user` instead of `show_canvas`

## Context

Previous rounds (0001–0003) converged on `show_canvas` as a new tool with `CanvasSpec` naming. Both sides approved.

After re-reading the codebase (`AskUserTool.kt`, `UserResponseChannel.kt`, `Op.kt`, `CapsuleMode.kt`), I'm reopening the tool question. The previous alignment was based on individual designs that hadn't fully traced the split-brain cost.

## What I Changed in `final/design.md`

### Core change: `show_canvas` → extend `ask_user`

The previous draft introduced `show_canvas` alongside `ask_user`. This creates:
- Two tools that both suspend on user interaction
- Two ops: `Op.UserResponse` (string) + `Op.CanvasResponse` (typed)
- Two event families: `AskUser*` + `Canvas*`
- Two capsule modes: `WaitingForInput/Action` + whatever `show_canvas` needs
- Ambiguity for the LLM: "use `ask_user` for text/action, `show_canvas` for choice/confirmation/summary"

Since `show_canvas` covers `TextInput` and `ActionRequired` (the same two things `ask_user` does today), keeping both tools means the old path is dead code. We'd be maintaining `ask_user` for nothing, or we'd need to document "never use `ask_user` anymore, use `show_canvas`" — which is just renaming with extra steps and leftover code.

Extending `ask_user` directly:
- One tool name. `AskUserType` enum deleted, replaced by `InteractionSpec` sealed hierarchy.
- `Op.UserResponse` carries `InteractionResponse` instead of `String`. No new Op.
- `UserResponseChannel` becomes `CompletableDeferred<InteractionResponse>`. No new channel.
- One event pair: `InteractionRequested`/`InteractionResolved`. `AskUser` event deleted.
- One capsule mode: `WaitingForInteraction(spec, canRenderInline)`. Old modes deleted.

The product is pre-release. There is zero backward compatibility cost.

### Naming: `CanvasSpec` → `InteractionSpec`

"Canvas" implies a rendering surface. The actual abstraction is a typed interaction between agent and user. `InteractionSpec`/`InteractionResponse`/`InteractionCardData`/`InteractionState` are more precise.

### Added sections

- **Capsule mode changes**: explicit `WaitingForInteraction` with `canRenderInline` flag
- **Component change list**: concrete file-level deletes/creates/modifies
- **Tool output examples**: each `kind` → exact output string
- **"Why One Tool" section**: explicit reasoning

### What's unchanged

- Display + blocking in Step 1
- Capsule host policy (TextInput/ActionRequired inline, rest redirect)
- 5-minute timeout
- Persistence path
- Non-goals
- Open questions (multi-select, card update)

## Questions for Codex

1. **Tool name**: `ask_user` is slightly misleading for `Summary` (display-only). Acceptable? Or rename to `interact`/`show_user`?

2. **`displayText` vs `responseSummary`**: `InteractionResolved` has `displayText` (for user bubble in transcript). `InteractionCardData` has `responseSummary` (for resolved card). Are these always the same string? Should we collapse to one field?

## Vote

**CHANGES** — substantive rewrite of tool approach, naming, and component list.
