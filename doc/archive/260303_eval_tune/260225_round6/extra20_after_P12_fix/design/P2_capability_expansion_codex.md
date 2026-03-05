# P2 Capability Expansion Design (Codex)

## Scope
- Recommendation 7: screenshot perception strategy for canvas-style tasks
- Recommendation 8: file-content capability (shell/read-file path)

## Reference Findings (mobile agents only)

### Screenshot / vision pattern
- Agent-S exposes observation mode at runtime (`screenshot`, `a11y_tree`, `screenshot_a11y_tree`, `som`).
- DroidRun defaults to a11y-only and enables vision via config/flag (`vision=True`, CLI `--vision`).
- Common pattern is not "LLM calls screenshot tool each turn".
- Common pattern is observation-pipeline mode switch (a11y-only vs hybrid/vision).

### File/content access pattern
- DroidRun supports extension via custom tools.
- DroidRun also documents MCP server integration; config example includes filesystem MCP server with prefixed tools (`fs_read_file`, `fs_write_file`, etc.).
- This matches your preference for a flexible shell-like capability, but production setups usually sandbox it.

## Design 7: Screenshot Strategy (Mode-based, not tool-first)

### Why
- Current app already supports `PerceptionConfig` (`accessibility_only`, `screenshot_only`, `hybrid`).
- A separate "take_screenshot tool" would add turns and planning burden for deterministic eval.

### Proposed approach
1. Keep screenshot as perception mode, not a new explicit tool.
2. Add per-task perception policy in eval runner:
- default: `accessibility_only`
- visual-hard tasks (e.g., BrowserDraw, BrowserMaze): `hybrid`
3. Add optional auto-escalation rule:
- If goal has visual keywords (draw, color, maze, pixel, shape) and a11y info density is low, escalate `accessibility_only -> hybrid` once.

### Expected impact
- Unblocks canvas tasks currently impossible in a11y-only mode.
- Minimal runtime complexity because perception plumbing already exists.

## Design 8: Shell/File Tool (Flexible but Safe)

### Requirement direction
- Prefer shell flexibility for Markor-like cross-app content extraction.

### Proposed architecture
1. Introduce `shell` tool in app runtime, disabled by default, enabled only in eval/tool profile where needed.
2. Read-only allowlist policy initially:
- Allowed commands: `cat`, `ls`, `find`, `grep`, `sed` (read patterns only)
- Denied commands: `rm`, `mv`, `chmod`, `chown`, package/activity mutating commands
- Path allowlist: `/sdcard/`, `/storage/emulated/0/`
3. Output controls:
- timeout cap (e.g., 5s)
- output cap (e.g., 8KB)
- explicit error surface to model
4. Add `read_file` convenience wrapper as a safer primary path:
- internally uses shell policy but with stricter validation.

### Why this is clean
- Preserves your requested flexibility.
- Constrains risk via policy guardrails.
- Keeps future migration path to MCP-like external tools if needed.

## Implementation sketch
- New tool spec: `ShellTool` + `ShellPolicy`.
- Policy profile decides enablement (`EVAL_CLEAN` keeps disabled unless task family requires it).
- For Markor tasks, enable via task/profile config rather than globally.

## Validation Plan
1. BrowserDraw/BrowserMaze with hybrid perception:
- verify model receives screenshot context and no longer stalls on invisible canvas semantics.
2. Markor-style extraction:
- compare a11y navigation vs shell/read_file path on same task.
- measure turn reduction and success delta.
3. Safety checks:
- ensure blocked commands/path escape attempts fail deterministically.

## Risks and mitigations
- Risk: shell tool may encourage bypassing UI on tasks where UI interaction is expected.
  - Mitigation: profile/task-scoped enablement, default off.
- Risk: hybrid perception increases token cost.
  - Mitigation: enable only for targeted tasks or auto-escalation trigger.
