# Review: Claude Initial Design for ClosePaw General Agent

## Plan Summary

Both designs aim to add a backend-neutral workspace execution layer to ClosePaw while keeping Android UI automation and the existing restricted `shell` tool intact. The target runtime is Termux first, remote second, native fallback last. Success means the agent can safely combine phone UI tools with file, shell, search, git, and process capabilities in one session.

Claude's design optimizes for radical minimalism: five tools and a three-method backend. Codex's design optimizes for explicit state, policy, and bridge contracts. The aligned draft should keep Claude's pressure against tool sprawl, but it needs Codex's stricter policy/lifecycle model as the base.

## What Already Exists

- `ToolSpec.kt` already provides the declarative tool/invocation abstraction.
- `SessionToolingBootstrapper.kt` is the right registration gate for built-in and conditional tools.
- `ToolRouter.kt` already owns validation, approval, execution state, cancellation, and observations.
- `PolicyEngine.kt` currently gates by foreground app tier and auto-allows non-screen-changing tools.
- `ToolName.isScreenChanging` is used by both policy and turn arbitration, so adding non-UI tools as "not screen-changing" without a separate policy path is risky.
- `SessionConfig.kt` is immutable per session and is the right place for a coding/workspace config.
- `StandaloneAgentDef.kt` and the planner/executor role defs are static today; conditional coding tools require either runtime prompt/tool extension or role-definition refactoring.

## Findings

### High: Both designs overstate workspace enforcement for `shell_exec`

File endpoints can enforce canonical path boundaries. Arbitrary shell commands cannot, unless the backend runs them inside a real sandbox. Setting `cwd` to the workspace does not prevent `cat /etc/hosts`, `python -c 'open(...)'`, symlink tricks created during command execution, package scripts, or tools that read/write absolute paths.

The aligned draft must state this clearly: workspace boundaries are hard for file tools and bridge-managed paths; `shell_exec` is controlled by policy, command classification, cwd, timeout, output caps, and user approval, not by syscall-level filesystem confinement. If a hard shell sandbox is required later, it is a separate backend capability.

### High: Claude's `NativeBackend` contradicts the safety boundary

Claude keeps `ShellTool` unchanged but adds a separate native Android backend with expanded `Runtime.exec`, pipes, redirects, 120-second timeout, and broader command behavior. That recreates the exact shell expansion the framing said not to do, just under a different name.

Native fallback should not be a relaxed Android shell in v1. It should be limited status and app-accessible test/file operations, or disabled for `shell_exec` with a clear `unsupported` result. Codex handles this better.

### High: Claude's command classifier defaults are unsafe

Claude classifies unknown commands as `COMMAND_MUTATE` and allows them in SMART mode. It also treats broad first tokens like `python`, `node`, `npm`, `pip`, and `make` as safe build commands even though they can install packages, run postinstall scripts, upload data, delete files, or execute arbitrary project code.

First-token matching is acceptable as a first classifier only if uncertain commands ask for approval. Default-allow is the wrong failure mode for an agent shell. Codex's policy table is safer because package install, network, publishing, destructive commands, and device-control commands are explicit ask/deny categories.

### High: Claude's workspace canonicalization is incorrect for Termux/remote paths

`File(path).canonicalPath.startsWith(root)` has two bugs:

- Prefix bug: `/work2` starts with `/work`.
- Wrong machine bug: Android app-side `File` cannot canonicalize Termux-home or remote filesystem paths correctly.

Path containment must be enforced inside the backend that owns the filesystem, using path-aware prefix checks after resolving symlinks. ClosePaw can pre-classify obvious risk, but the bridge is the authority for Termux/remote paths.

### High: Claude's outside-workspace behavior is internally inconsistent

Claude says outside-workspace file access should ask the user, while the bridge examples return `403` for outside-workspace paths. Those are different products. For v1, deny outside-workspace file tools and make the user change/select the workspace root instead. Asking only works if the bridge supports an explicit, audited allow-list expansion.

### Medium: Claude's minimal tool surface is too minimal in the wrong places

The five-tool design is clean, but removing `workspace_status`, `list_files`, structured `git`, and process tools pushes too much behavior through raw shell. That hurts policy, status reporting, output shaping, and long-running process control.

The better compromise for v1 is a small but semantic surface: `workspace_status`, `list_files`, `search_files`, `file_read`, `file_write`, `apply_patch`, and `shell_exec`. Add structured `git` if policy quality matters early; defer process tools until dev-server workflows are required.

### Medium: Claude's status model is too static

Claude injects backend capabilities once in the system prompt and expects the model to use shell for dynamic state. That misses bridge death, backend fallback, workspace changes, capability drift, and setup failures. Codex's `workspace_status` tool plus backend state machine is a stronger base.

### Medium: Claude's policy integration is simpler but fragile

Routing by `toolName in CODING_TOOL_NAMES` is easy to implement, but the current codebase already spreads `ToolName.isScreenChanging` across policy and turn arbitration. Adding more hard-coded name sets will create drift.

Codex's invocation metadata is heavier, but it matches the real problem: the same tool can be read-only, mutating, networked, destructive, or secret-sensitive depending on arguments.

### Medium: Claude's bootstrap details are useful but need hardening

Claude is stronger than Codex on setup mechanics: `RUN_COMMAND` only for bootstrap, PendingIntent result, raw-resource bridge deployment, version check, and idle watchdog are all good. But the aligned draft should also keep Codex's corrections: Termux version gating, Android package visibility, `allow-external-apps`, loopback-not-private token auth, and fallback script for unsupported setup.

The setup script must not rely on parsing a noisy `pkg install` stdout for the token. Emit machine-readable setup results or separate setup logs from the token result.

### Medium: Both designs need to stop trusting request-supplied workspace roots

Bridge requests can include a workspace id/root, but the bridge must validate it against server-side configured allow-lists. A bearer token plus request-provided `workspace_root` is not enough. The bridge should reject unknown roots even if ClosePaw sends them.

### Low: Claude's estimates are optimistic

The phase estimates do not reflect the policy/UI/test blast radius: approval details need new fields, settings need setup-state modeling, sub-agent role scoping must be preserved, trace redaction needs tests, and cancellation has to propagate to bridge processes.

### Trade-Off: `file_edit` vs `apply_patch`

Claude's old-string `file_edit` is easy for targeted replacements and fails safely on ambiguity. Codex's `apply_patch` is better for multi-file edits and matches how coding agents usually express changes. The aligned draft should choose one primary edit path for v1, not both. I would keep `apply_patch` primary and defer old-string replacement until evals show it improves reliability.

## Test Plan

- Coding disabled: existing tool schemas, prompts, policy decisions, and UI automation behavior are unchanged.
- Tool registration: coding tools appear only when coding mode is enabled and the active role is allowed to use them; UI executor never sees coding tools.
- Policy split: UI tools still follow `AppTier`; workspace reads/writes/shell commands follow workspace policy.
- Unknown/uncertain command classification: defaults to ask or deny, not auto-allow.
- Risk classifier cases: `git status`, `git diff`, build/test commands, `npm install`, `pip install`, `curl`, `git commit`, `git push`, `rm -rf`, `git reset --hard`, `am`, `pm`, `settings`, `input`, `.env`, `*.pem`.
- Path boundary tests inside the bridge: normal relative paths, `..`, prefix sibling paths, symlink escape, missing parent, new-file parent canonicalization.
- Shell boundary tests: verify the product response when a command tries obvious outside-workspace absolute paths; do not claim hard sandboxing.
- Termux setup states: Termux missing, permission missing, `allow-external-apps` missing, unsupported Termux version, bridge missing, bridge outdated, bridge stopped, token mismatch.
- Bridge auth: missing token and wrong token return stable unauthorized errors.
- Bridge execution: timeout kills process group, cancellation works, stdout/stderr truncation is reported, large output gets an artifact/continuation path.
- Backend selection: pinned backend unhealthy fails clearly; auto mode chooses Termux, then remote, then limited native status.
- Mixed workflow QA: one pure UI task, one pure workspace task, and one UI-plus-workspace task complete without policy leakage across domains.

## Not In Scope

- Shizuku/root/chroot as a coding backend.
- Embedded Python/JS runtimes in the APK.
- A hard syscall-level sandbox for arbitrary Termux shell commands.
- Full process-management UX in v1, unless dev-server workflows are a launch requirement.
- Automatic package installation without explicit approval.
- Remote SSH bootstrap, unless HTTPS bridge deployment proves too clumsy.

## Next Step

Build the first aligned draft from Codex, then import these Claude pieces:

- Keep the v1 tool surface small and resist adding every specialized tool up front.
- Reuse Claude's concrete Termux setup/lifecycle details: PendingIntent result, raw-resource bridge deployment, version checks, idle watchdog, and bridge update flow.
- Consider old-string `file_edit` as an eval-driven follow-up, not the default v1 edit primitive.
- Keep the backend daemon simple: Python stdlib, no Flask, no extra dependencies.

Before implementation, the aligned draft must resolve three decisions:

- State the real shell boundary: policy-controlled, not hard-sandboxed.
- Pick the v1 edit primitive: `apply_patch` or `file_edit`.
- Decide whether structured `git` is v1 or phase 2.

Better base for the first aligned draft: CODEX.
