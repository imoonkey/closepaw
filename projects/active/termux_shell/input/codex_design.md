# ClosePaw Coding Agent - Codex Design

## Goal

Make ClosePaw a dual-mode agent:

- Android mode: operate phone apps through the existing Accessibility/Virtual Display tools.
- Coding mode: inspect repositories, edit files, run commands, use git, install packages, run tests, and manage dev processes.

The hard part is not spawning a process. Android can do that. The hard part is giving the agent a real developer userland under Android constraints, then exposing it without weakening the current UI safety model.

Success:

- The LLM sees a small backend-neutral coding tool surface.
- Full coding works through Termux or a remote backend.
- The current restricted `shell` tool remains an Android inspection tool.
- File/shell policy is separate from foreground app tier policy.
- Mixed UI + coding tasks can compose safely.

## Current Codebase Fit

The repo already has the right extension points:

- `ToolSpec.kt`: declarative tool contract.
- `SessionToolingBootstrapper.kt`: built-in tool registration.
- `AgentRoleDef`: role-scoped tool allow-lists and prompts.
- `ToolRouter.kt`: validation, approval, execution, cancellation, observations.
- `PolicyEngine.kt`: current foreground-app approval logic.

The mismatch is policy. Today, `PolicyEngine` auto-allows non-screen-changing tools. That is right for `scratchpad`, but wrong for `file_write`, `git commit`, package install, or arbitrary shell. Coding needs its own policy axis.

Do not relax `ShellTool.kt`. It blocks metacharacters, destructive commands, long output, and long runtime for a reason. A real coding agent should add a separate execution plane.

## First-Principles Primitives

Claude Code-like behavior reduces to:

| Primitive | Needed for |
| --- | --- |
| List/search files | Find relevant code cheaply. |
| Read bounded file slices | Build context without flooding the model. |
| Apply patches | Precise multi-file edits. |
| Write files | New scripts, configs, generated docs/assets. |
| Execute commands | Build, test, inspect, run project tools. |
| Manage processes | Dev servers, logs, cancellable long tasks. |
| Git operations | Diff, branch, commit, push, recover. |
| Package/runtime access | Install dependencies and run language tools. |

Android's app sandbox, scoped storage, toybox shell, and lack of bundled Python/Node/git/compilers mean ClosePaw must either bring a userland, embed a limited runtime, or delegate execution.

## Viable Approaches

| Approach | Ceiling | Tradeoffs | Feasibility | Use |
| --- | --- | --- | --- | --- |
| Native Android shell | `ls`, `cat`, `stat`, simple inspection | Always available, but no real toolchain and high bypass risk if relaxed | High as fallback, low as primary | Limited `NativeCodingBackend` |
| Shizuku/ADB-like shell | Android system diagnostics as shell UID | Useful for platform work; not a developer distro; higher blast radius | Medium | Optional device diagnostics backend |
| App-native file tools | Edit/read/search selected files | Safe and Play-friendly; cannot run tests | High | Phase 1 foundation |
| Embedded runtimes (QuickJS, Lua, Chaquopy/Python, WASI) | Small scripts and transforms | Offline, but limited packages/native deps/git/toolchains | Medium | Later helper runtime |
| Bundled Unix binaries | Curated `rg`/git/runtime subset | APK size, ABI, licensing, security patch burden | Medium for a few tools, low for full dev | Only small first-party helpers |
| Termux RUN_COMMAND | Run commands in Termux context | Official inter-app path; awkward result streaming/process control | High | Bootstrap bridge, simple commands |
| Termux bridge daemon | Full on-device Linux userland with package repos | Requires Termux setup and lifecycle management | High | Primary on-device backend |
| Termux + proot-distro | Debian/Ubuntu packages inside Termux | Slower, more layers, more state for agent to reason about | Medium | Advanced profile |
| Remote SSH/bridge | Full desktop/server/devcontainer power | Network, credentials, privacy, workspace isolation | High | Primary serious-work backend |
| Cloud dev/CI worker | High build/test ceiling, strong isolation if ephemeral | Cost, accounts, secrets, less interactive | Medium | Future backend |
| Browser IDE automation | Can operate Codespaces/Gitpod through UI | Fragile, slow, hard to verify | Medium demo, low product | Fallback only |
| Root/chroot device | High on-device ceiling | Not mainstream-safe; very large blast radius | Low product, high lab | Expert-only |

Recommendation: build a backend-neutral coding layer with Termux bridge and remote bridge as the real backends. Keep native Android as limited fallback only.

## Recommended Architecture

```
LLM
  |
  v
ToolSpec coding tools
  |
  v
ToolRouter
  |
  v
PolicyEngine
  |-- UI policy: foreground AppTier
  |-- Coding policy: workspace, operation risk, backend trust
  |
  v
CodingExecutionBackend
  |-- NativeCodingBackend (limited)
  |-- TermuxBridgeBackend (primary on-device)
  |-- RemoteBridgeBackend (primary serious work)
  |-- ShizukuDeviceShellBackend (optional diagnostics)
```

Backend selection:

1. If the user pins a backend, use it or fail clearly.
2. Else use healthy Termux bridge.
3. Else use healthy remote bridge.
4. Else use native limited mode and report limited capability to the model.

The LLM should not know transport details. It sees capabilities through prompt context and `coding_status`.

## LLM Tool Surface

Register these only when coding mode is enabled:

| Tool | Purpose | Policy note |
| --- | --- | --- |
| `coding_status` | Backend, workspace, cwd, git summary, capabilities | Auto-allow |
| `list_files` | Bounded list/glob under workspace | Auto-allow inside workspace |
| `search_files` | Literal/regex search, use `rg` when available | Auto-allow inside workspace |
| `file_read` | Read bounded file slice | Ask/redact for likely secrets |
| `file_write` | Create/replace file | Auto-allow inside workspace in SMART |
| `apply_patch` | Apply unified patch | Primary edit tool |
| `shell_exec` | Foreground command with cwd/timeout/output cap | Dynamic command risk |
| `git` | Structured git status/diff/log/add/commit/push | Easier to classify than raw shell |
| `process_start` | Start long-running command | Phase 3, approval for servers |
| `process_read` | Read process output | Phase 3 |
| `process_stop` | Stop process | Phase 3 |
| `package_install` | Install packages through backend manager | Always ask |

Existing UI tools remain unchanged: `mobile_action`, `system_button`, `open_app`, `wait`, `scratchpad`, `write_todos`, `ask_user`, `complete_task`, `remember_experience`.

Important naming rule: `shell` stays Android inspection. `shell_exec` means coding backend command.

## Backend Contract

Add `ai.closepaw.coding.backend`:

```kotlin
interface CodingExecutionBackend {
    val id: String
    val displayName: String

    suspend fun status(): CodingBackendStatus
    suspend fun exec(request: ExecRequest): ExecResult
    suspend fun readFile(request: FileReadRequest): FileReadResult
    suspend fun writeFile(request: FileWriteRequest): FileWriteResult
    suspend fun applyPatch(request: PatchRequest): PatchResult
    suspend fun search(request: SearchRequest): SearchResult
    suspend fun startProcess(request: ExecRequest): ProcessHandleResult
    suspend fun readProcess(id: String, maxBytes: Int): ProcessOutputResult
    suspend fun stopProcess(id: String): ProcessStopResult
}
```

Rules:

- Every request carries backend id, workspace id, cwd, timeout, and output cap.
- ClosePaw classifies risk before sending.
- Backend canonicalizes paths and enforces workspace boundaries.
- Bridge backends enforce policy too; the server never trusts the app or LLM alone.
- Results include exit code, stdout, stderr, truncation flag, duration, and changed paths.

## Termux Backend

Use two integration layers:

1. `RUN_COMMAND` for bootstrap/start.
2. A persistent `closepaw-bridge` daemon for normal operation.

Bridge design:

- Python stdlib HTTP/WebSocket server in Termux.
- Bind only to `127.0.0.1`.
- Require `Authorization: Bearer <random-token>`.
- Config at `~/.closepaw/config.json`.
- Default workspace root: `~/closepaw-workspaces`.
- Endpoints: `/health`, `/exec`, `/file`, `/patch`, `/search`, `/process/start`, `/process/{id}/output`, `/process/{id}` delete.

Setup:

1. User installs Termux.
2. ClosePaw verifies package and requests `com.termux.permission.RUN_COMMAND`.
3. User enables Termux external command setting.
4. ClosePaw runs or displays a setup script to install `python`, `git`, `ripgrep`, and bridge files.
5. ClosePaw stores the bridge token in app-private encrypted storage.

Use Termux home for worktrees. Shared storage is for import/export only; active repos on `/sdcard` have poor Unix semantics for builds and git.

## Remote Backend

Use the same bridge protocol on desktop/server:

- Initial transport: HTTPS over Tailscale/private network using existing OkHttp dependency.
- Later transport: SSH with an Android-compatible SSH library.
- Run as a dedicated OS user or inside a devcontainer.
- Workspace roots are explicit allow-listed directories.
- Same audit log and policy categories as Termux.

Remote should be the recommended backend for large Android/Kotlin repos, Docker, browser tests, language servers, and heavy builds.

## Security Model

### Coding Mode Config

Add to `SessionConfig`:

```kotlin
data class CodingConfig(
    val enabled: Boolean = false,
    val backendPreference: CodingBackendPreference = CodingBackendPreference.AUTO,
    val workspaceRoot: String? = null,
    val approvalMode: CodingApprovalMode = CodingApprovalMode.SMART
)
```

If disabled, do not register coding tools and do not inject coding prompt text.

### Tool Metadata

Move policy source of truth out of `ToolName.isScreenChanging`:

```kotlin
enum class ToolDomain { UI, COGNITIVE, CODING }
enum class CodingRisk { READ, WORKSPACE_WRITE, COMMAND_SAFE, COMMAND_MUTATING, DESTRUCTIVE, NETWORK, SECRET, DEVICE_ADMIN }

data class ToolPolicyMetadata(
    val domain: ToolDomain,
    val codingRisk: CodingRisk? = null
)
```

`ToolRouter` already creates the invocation before policy check. Let invocations expose dynamic metadata. Example: `shell_exec("git status")` is `READ`; `shell_exec("rm -rf .")` is `DESTRUCTIVE`.

This also fixes the current drift risk where a new tool omitted from `ToolName` becomes `Unknown`.

### Workspace Boundary

Canonical rule:

- File tools default to active workspace only.
- Backend resolves real/canonical paths.
- Symlink escapes require approval or are denied.
- Hidden credentials are ask/redact by default.
- Android app-private data is blocked. Coding tools must not bypass app-tier masking.

### SMART Decisions

| Operation | Decision |
| --- | --- |
| Read/list/search in workspace | Allow |
| `git status`, `git diff`, `git log`, `pwd`, `ls`, `cat`, `rg` | Allow |
| `apply_patch`, `file_write` in workspace | Allow with audit |
| Build/test commands in workspace | Allow with timeout |
| Package installs | Ask |
| `git commit` | Ask or per-workspace setting |
| `git push`, uploads, publishing | Ask |
| Outside-workspace writes | Ask or deny |
| Likely secrets (`.env`, keys, token files) | Ask and redact output |
| `rm -rf`, `git reset --hard`, force push | Ask with destructive wording |
| `su`, `pm`, `settings`, `input`, `uiautomator`, `am start` from coding shell | Deny or route to UI/device tools |

### Approval Grants

Current approval scopes are package-based. Coding needs typed subjects:

```kotlin
sealed interface ApprovalSubject {
    data class AndroidPackage(val packageName: String) : ApprovalSubject
    data class CodingWorkspace(val backendId: String, val workspaceRoot: String) : ApprovalSubject
    data class CommandPattern(val backendId: String, val workspaceRoot: String, val pattern: String) : ApprovalSubject
}
```

Extend `ApprovalDetails` with policy domain, risk, workspace, changed path preview, and diff summary.

### Audit and Redaction

- Trace every coding op: tool, command, cwd, duration, exit code, changed paths.
- Cap output and add continuation for large logs.
- Redact common secret patterns before observations.
- Bridge logs must avoid secret values.
- Stop/cancel must propagate through `ToolRouter.cancelAll()`.

## System Prompt Extension

Inject only when coding mode is enabled:

```text
## Operating Modes

You can use Android UI tools and coding tools. Use UI tools for phone apps and visible-device state. Use coding tools for repositories, files, commands, tests, git, and package/runtime work.

Do not use coding shell commands to control Android UI, bypass app restrictions, inspect blocked app data, enter credentials, or change Android security settings. Use Android UI tools and approval flow for phone actions.

## Coding Environment

Backend: {{coding_backend_name}}
Workspace: {{coding_workspace_root}}
Current directory: {{coding_cwd}}
Capabilities: {{coding_capabilities}}

Prefer search/list before broad reads, `file_read` for targeted context, `apply_patch` for edits, `file_write` for new files, `shell_exec` for verification, and `git` for git operations.

After changing code, run the narrowest meaningful verification. If verification cannot run because the backend is limited or missing dependencies, say exactly what blocked it.

For mixed tasks, keep domains separate: gather phone data with UI tools, process/code with coding tools, then return to UI tools only when the phone UI must change.
```

Role strategy:

- BASIC/Standalone can support coding first.
- PRO Planner may use coding tools once stable.
- Existing UI Executor stays UI-only.
- Add `CodingExecutorRoleDef` later: coding tools only, no UI tools, bounded subtask.

## Implementation Phases

### Phase 0: Config and Gate

Scope: `SessionConfig`, settings UI, session creation.

Acceptance:

- Coding mode defaults off.
- Existing automation behavior/tool list is unchanged when off.
- User can select backend preference and workspace.

### Phase 1: Policy and Native File Tools

Scope: `ToolSpec.kt`, `ToolRouter.kt`, `PolicyEngine.kt`, `ToolName.kt`, `tool/impl/coding/**`, `coding/backend/**`, tests.

Acceptance:

- Policy uses tool/invocation metadata.
- UI tools keep current app-tier behavior.
- Unknown tools fail closed.
- `coding_status`, `list_files`, `search_files`, `file_read`, `file_write`, and `apply_patch` work in a local test workspace.
- Path traversal and symlink escape are blocked.

### Phase 2: Termux Bridge

Scope: manifest, setup UI, `coding/backend/termux/**`, `tools/termux-bridge/**`, tests.

Acceptance:

- ClosePaw bootstraps/starts the bridge through Termux.
- `git --version`, file edit, patch, search, and simple test commands work.
- Bridge enforces token and workspace root.

### Phase 3: Remote Bridge and Processes

Scope: `coding/backend/remote/**`, process tools, settings UI, bridge docs.

Acceptance:

- Remote health, exec, file, patch, search work over the same protocol.
- Tailscale/private URL plus token config works.
- `process_start/read/stop` supports dev server and log workflows.
- Session stop cancels or detaches processes according to request policy.

### Phase 4: Packages and PRO Mode

Scope: `package_install`, prompt injection, `agent/definition/**`, sub-agent delegation.

Acceptance:

- Package installs are approval-gated.
- Planner can delegate bounded coding tasks to a coding-only executor.
- UI executor never sees coding tools.

## Key Decisions

| Decision | Choice | Reason |
| --- | --- | --- |
| Existing `shell` | Keep restricted | Avoid UI-policy bypass and false promise of full coding. |
| Primary on-device backend | Termux bridge | Best real Linux userland without root. |
| Primary serious-work backend | Remote bridge | Highest ceiling for large repos/builds. |
| Tool surface | Backend-neutral | The model reasons about files/commands, not transport. |
| Edits | `apply_patch` first | Robust for multi-file code changes. |
| Policy | Add coding domain | App tier does not describe filesystem/shell risk. |
| Approval | Workspace-scoped grants | Package allow-lists do not apply to coding. |
| Termux protocol | RUN_COMMAND bootstrap, daemon runtime | Simple setup plus reliable streaming/process control. |
| Embedded runtimes | Later helper | Useful, not sufficient for real project work. |

## Sharp Edges

- Output must be paginated or stored as artifacts with continuation reads.
- Secrets in repos are common; default to ask/redact for `.env`, keys, token files.
- Malicious repos can hide destructive work in scripts; package installs and generated scripts need approval.
- Coding shell must not run Android UI/control commands as a shortcut around app-tier policy.
- Termux bridge lifecycle depends on Android background limits and vendor battery policy.
- Remote backend needs dedicated users/containers and audit logs.
- Git publish actions need explicit approval.

## Self-Review

This design avoids the false simple solution: arbitrary Android shell. That would still lack a real userland and would weaken current safety. The backend abstraction is the smallest shape that supports native limited mode, Termux, remote, and future cloud/devcontainer backends.

The unavoidable complexity is policy. Foreground app tier, workspace boundaries, command risk, secrets, and backend trust are different dimensions. Encoding them explicitly is safer and more testable than relying on prompt text or command blocklists alone.

