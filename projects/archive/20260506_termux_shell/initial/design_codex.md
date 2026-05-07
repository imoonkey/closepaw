# ClosePaw General Agent via Termux Backend - Codex Design

## Goal

Make ClosePaw a general Android agent with a real workspace execution layer:

- Keep today's Android UI automation untouched.
- Add backend-neutral tools for files, search, shell commands, git, and processes.
- Use a Termux bridge daemon as the primary on-device backend.
- Use the same bridge protocol for remote machines when work is too heavy for the phone.
- Keep the current native Android `shell` tool as-is. It remains a limited device inspection tool, not the coding/general execution runtime.

The product goal is not "make a coding feature." Coding-agent primitives are the general-agent base layer: read/write files, run commands, search data, use git, process artifacts, orchestrate tools, and build domain-specific agents like OpenClaw or Hermes on top.

Success means a session can combine both domains safely:

1. Use UI tools to interact with phone apps and visible Android state.
2. Use workspace tools to inspect files, transform data, run scripts/tests, and manage repos.
3. Keep UI policy and workspace policy separate.

## Non-Goals

- No Shizuku/root shell as part of this design.
- No embedded Python/JS/runtime in the APK.
- No changes to `ShellTool.kt` behavior.
- No attempt to make `/sdcard` the default build workspace. Active worktrees should live in Termux home or a remote workspace with normal Unix semantics.

## Current Codebase Fit

The existing architecture has the right seams:

- `ToolSpec.kt` defines declarative tool schemas and executable invocations.
- `ToolRegistry.kt` and `SessionToolingBootstrapper.kt` register session tools.
- `ToolRouter.kt` already validates, checks policy, handles approval, executes, cancels, and records observations.
- `PolicyEngine.kt` owns approval decisions.
- `StandaloneAgentDef.kt`, `PlannerAgentDef.kt`, and `ExecutorAgentDef.kt` scope tools and prompts by role.
- `SessionConfig.kt` is immutable per session, which is the right place to gate workspace tools.

The mismatch is policy and naming:

- `ToolName.isScreenChanging` mixes tool identity with policy. It works for UI tools, but file writes and `git push` are not screen-changing and are still risky.
- `PolicyEngine` is foreground-app based. Workspace tools need workspace, command, path, secret, network, and backend-risk dimensions.
- Current `shell` is intentionally restricted. Relaxing it would weaken UI safety while still not providing a real userland.

## Architecture

```
LLM
  |
  v
Role prompt + ToolSpec schemas
  |
  v
ToolRouter
  |
  v
PolicyEngine
  |-- UI policy: foreground package + AppTier + approval mode
  |-- Workspace policy: backend + workspace root + operation risk
  |
  v
Workspace ToolSpecs
  |
  v
CodingExecutionBackend
  |-- TermuxBridgeBackend     primary on-device
  |-- RemoteBridgeBackend     secondary heavy-work backend
  |-- NativeWorkspaceBackend  limited fallback
```

Use `ai.closepaw.coding` as the implementation package. The package name can say "coding"; the product concept shown to users should be "workspace tools" or "general agent tools."

## System Invariants

- The LLM sees operations, not transports. Termux, remote, and native fallback must expose the same tool semantics where they claim support.
- Android UI control goes through UI tools and UI policy only. Workspace shell is not a shortcut around app-tier restrictions.
- Every workspace path is canonicalized against one active workspace root before execution.
- Policy is checked in ClosePaw; boundary enforcement is repeated in the bridge. Neither side trusts prompt text.
- Secrets are not raw prompt material. Reads and command output are redacted or approval-gated before entering history/trace.
- Failure is explicit. Unsupported backend capabilities, setup gaps, policy denial, timeout, truncation, and workspace escape are distinct results.

## Core Runtime Model

Add session config:

```kotlin
data class CodingConfig(
    val enabled: Boolean = false,
    val backendPreference: CodingBackendPreference = CodingBackendPreference.AUTO,
    val workspaceRoot: String? = null,
    val defaultCwd: String? = null,
    val approvalMode: CodingApprovalMode = CodingApprovalMode.SMART,
    val remoteEndpointId: String? = null
)

enum class CodingBackendPreference { AUTO, TERMUX, REMOTE, NATIVE }
enum class CodingApprovalMode { ALWAYS_ASK, SMART, AUTO_APPROVE_READS }
```

`SessionConfig` gets `val coding: CodingConfig = CodingConfig()`. Non-secret settings are persisted by `AppSettingsStore`; bridge tokens and remote credentials go in encrypted app-private storage, not plain prefs and not `SessionConfig`.

Add a session-scoped runtime:

```kotlin
class CodingRuntime(
    val config: CodingConfig,
    val selector: CodingBackendSelector,
    val policy: WorkspacePolicyEngine
)

interface CodingExecutionBackend {
    val id: String
    val kind: CodingBackendKind

    suspend fun status(): BackendStatus
    suspend fun exec(request: ExecRequest): ExecResult
    suspend fun listFiles(request: ListFilesRequest): ListFilesResult
    suspend fun searchFiles(request: SearchRequest): SearchResult
    suspend fun readFile(request: ReadFileRequest): ReadFileResult
    suspend fun writeFile(request: WriteFileRequest): WriteFileResult
    suspend fun applyPatch(request: ApplyPatchRequest): ApplyPatchResult
    suspend fun startProcess(request: ExecRequest): ProcessStartResult
    suspend fun readProcess(request: ProcessReadRequest): ProcessReadResult
    suspend fun stopProcess(request: ProcessStopRequest): ProcessStopResult
}
```

Every request carries `workspaceRoot`, `cwd`, `timeoutMs`, `maxOutputBytes`, and `requestId`. Every result includes `backendId`, `durationMs`, truncation flags, and machine-readable error codes.

## Backend Selection

Backend selection is deterministic:

1. If coding mode is disabled, do not create `CodingRuntime` and do not register workspace tools.
2. If a backend is pinned, use only that backend. If it is unhealthy, expose `workspace_status` only and fail clearly.
3. In `AUTO`, prefer healthy Termux, then healthy remote, then native limited fallback.
4. Native fallback never pretends to be full shell. It supports status plus app-accessible file/list/search operations where possible and returns `unsupported` for full `shell_exec`.

State machine:

```
Disabled
  -> Discovering when coding mode is enabled
Discovering
  -> Ready(backend) when health succeeds
  -> NeedsSetup(reason) when Termux/remote is configured but missing setup
  -> Limited(native) when only native fallback exists
  -> Unavailable when no backend can satisfy requested tools
Ready
  -> Restarting when bridge health fails
Restarting
  -> Ready after successful restart
  -> NeedsSetup or Unavailable after bounded retries
```

Session startup should be quick. Health checks get a short timeout and run off main. Long setup belongs in the settings/setup UI, not in the LLM turn loop.

## Tool Surface

Register these only when `config.coding.enabled` is true. If no real backend is ready, register only `workspace_status`.

| Tool | Purpose | Phase | Policy |
| --- | --- | --- | --- |
| `workspace_status` | Report backend, workspace, cwd, capabilities, git summary | 1 | allow |
| `list_files` | Bounded tree/glob listing under workspace | 1 | read |
| `search_files` | Literal/regex search, using `rg` when available | 1 | read |
| `file_read` | Read a bounded UTF-8 slice | 1 | read, secret-aware |
| `file_write` | Create or replace a file | 1 | workspace write |
| `apply_patch` | Apply unified patch to one workspace | 1 | workspace write |
| `shell_exec` | Run foreground command with timeout and output cap | 2 | dynamic command risk |
| `git` | Structured git subcommands for status/diff/log/add/commit/push | 2 | dynamic git risk |
| `process_start` | Start long-running command | 3 | dynamic command risk |
| `process_read` | Read process output by cursor | 3 | allow if handle belongs to session |
| `process_stop` | Stop process by id | 3 | allow if handle belongs to session |

Do not add `file_edit` in v1. `apply_patch` plus `file_write` covers edits with less tool redundancy. Add old-string replacement later only if evals show the model needs it.

The existing `shell` name stays reserved for native Android inspection. `shell_exec` always means the selected workspace backend.

## Termux Bridge

Use two Termux integration paths:

1. `RUN_COMMAND` only for bootstrap/start/restart.
2. A persistent `closepaw-bridge` daemon for normal execution.

Termux setup requirements:

- Require Termux app `>= 0.109` for stdin bootstrap and PendingIntent result handling. If older or unsupported, fall back to a copyable setup script.
- Add `com.termux.permission.RUN_COMMAND` to the manifest.
- Add package visibility on Android 11+: at minimum `<package android:name="com.termux" />` plus an intent/service query for `com.termux.RUN_COMMAND`.
- Guide the user to grant ClosePaw the Termux run-command permission.
- Guide the user to set `allow-external-apps = true` in `~/.termux/termux.properties`.
- Explain the security implication: Termux external command execution is enabled at the Termux app level, while Android permission grants are per caller app. ClosePaw still authenticates its bridge separately.
- Prefer Termux home for workspaces: `~/closepaw-workspaces`.

Bootstrap flow:

1. Settings screen detects whether Termux is installed and whether the bridge health endpoint responds.
2. If permission/config is missing, show exact setup steps and a copyable fallback script.
3. If `RUN_COMMAND` is available, ClosePaw starts Termux's run-command service from Kotlin/Java, not by shelling out to `am`.
4. The bootstrap command is `/data/data/com.termux/files/usr/bin/sh`, receives the installer through stdin, sets the Termux background-command extra, and attaches a `PendingIntent` for setup result on Termux versions that support result bundles.
5. The installer installs minimal packages (`python`, `git`, `ripgrep`), writes `~/.local/bin/closepaw-bridge`, writes `~/.closepaw/config.json`, and starts the bridge.
6. ClosePaw stores the generated bridge token in encrypted storage and verifies `/v1/health`.

Keep the stdin installer well below Android intent size limits. If the bridge grows, ship a tiny bootstrap that installs or updates a versioned bridge artifact with checksum verification.

Use Python stdlib for the first daemon. Avoid Flask or extra Python dependencies.

Daemon defaults:

- Bind: `127.0.0.1:18765`.
- Auth: `Authorization: Bearer <random-token>`. Loopback avoids LAN exposure but is not app-private, so every endpoint requires the token.
- Config: `~/.closepaw/config.json`.
- Workspace root: `~/closepaw-workspaces`.
- Logs: `~/.closepaw/logs`, with secrets redacted.
- Artifacts: `~/.closepaw/artifacts`, for large command output.

Protocol:

```text
GET  /v1/health
POST /v1/exec
POST /v1/files/list
POST /v1/files/search
POST /v1/files/read
POST /v1/files/write
POST /v1/files/patch
POST /v1/processes/start
GET  /v1/processes/{id}/output?cursor=...&limit=...
DELETE /v1/processes/{id}
```

Common request fields:

```json
{
  "request_id": "tool-call-id",
  "workspace_root": "/data/data/com.termux/files/home/closepaw-workspaces/demo",
  "cwd": ".",
  "timeout_ms": 120000,
  "max_output_bytes": 65536
}
```

`/v1/exec` adds:

```json
{
  "command": "npm test",
  "env": { "CI": "1" },
  "stdin": null
}
```

Common response fields:

```json
{
  "ok": true,
  "request_id": "tool-call-id",
  "duration_ms": 1234,
  "stdout": "...",
  "stderr": "...",
  "exit_code": 0,
  "truncated": false,
  "output_ref": null,
  "changed_paths": []
}
```

Bridge enforcement:

- Canonicalize every path before access.
- Existing symlinks resolve inside the workspace or fail.
- New writes require the canonical parent to be inside the workspace.
- Reject path traversal before shell execution.
- Kill process groups on timeout/cancel.
- Return stable machine-readable errors: `unauthorized`, `workspace_escape`, `timeout`, `unsupported`, `not_found`, `invalid_request`, `internal_error`.
- Keep the daemon dumb about LLM policy, but never dumb about auth, workspace boundaries, timeouts, and output caps.

## Remote Backend

Remote uses the same protocol as Termux. The first implementation should be HTTPS over a private/Tailscale address with bearer token auth. SSH can be added later for starting/stopping the remote bridge, but normal tool execution should stay protocol-identical.

Remote host rules:

- Run as a dedicated OS user or inside a devcontainer.
- Configure an explicit allow-list of workspace roots.
- Use the same path canonicalization and output/artifact behavior as Termux.
- Treat remote as the recommended backend for large Android builds, Docker, browser automation, and language-server work.

## Security and Policy

Replace policy-by-tool-name with invocation metadata.

```kotlin
enum class ToolDomain { UI, COGNITIVE, WORKSPACE }
enum class UiEffect { NONE, SCREEN_CHANGE, ESCAPE }
enum class WorkspaceRisk {
    READ,
    WORKSPACE_WRITE,
    COMMAND_SAFE,
    COMMAND_BUILD_TEST,
    COMMAND_MUTATING,
    NETWORK,
    SECRET,
    DESTRUCTIVE,
    DEVICE_CONTROL
}

data class ToolPolicyMetadata(
    val domain: ToolDomain,
    val uiEffect: UiEffect = UiEffect.NONE,
    val workspaceRisk: WorkspaceRisk? = null,
    val workspaceRoot: String? = null,
    val cwd: String? = null,
    val preview: String = ""
)
```

`ToolRouter` already creates an invocation before policy check. Change `PolicyEngine.check(...)` to receive the invocation metadata instead of only `toolName` and `params`.

Policy split:

- UI tools keep current app-tier behavior.
- Cognitive tools like `scratchpad`, `write_todos`, `ask_user`, and `complete_task` are allowed.
- Workspace tools route to `WorkspacePolicyEngine`.
- Unknown or unannotated tools fail closed.

Workspace SMART policy:

| Operation | Decision |
| --- | --- |
| `workspace_status`, list, search | allow |
| File read inside workspace | allow unless likely secret |
| File write or patch inside workspace | allow with audit |
| Build/test commands inside workspace | allow with timeout |
| `git status`, `git diff`, `git log` | allow |
| Package install, curl/wget uploads, network access | ask |
| `git commit` | ask unless workspace grant exists |
| `git push`, publishing, deployment | ask |
| Writes outside workspace | deny by default |
| Secret files (`.env`, keys, tokens, `*.pem`) | ask and redact output |
| `rm -rf`, `git reset --hard`, force push | ask with destructive wording |
| `am`, `pm`, `settings`, `input`, `uiautomator`, `su`, `run-as` from `shell_exec` | deny; use UI/device tools instead |

Approval grants need typed subjects, not only package names:

```kotlin
sealed interface ApprovalSubject {
    data class AndroidPackage(val packageName: String) : ApprovalSubject
    data class Workspace(val backendId: String, val root: String) : ApprovalSubject
    data class CommandPattern(val backendId: String, val root: String, val pattern: String) : ApprovalSubject
}
```

Extend `ApprovalDetails` with `domain`, `workspaceRoot`, `cwd`, `risk`, `changedPathPreview`, and `commandPreview`. The UI can still render a simple approval card, but the policy data must be typed.

Redaction and audit:

- Redact common secret patterns in tool output before history/trace.
- Record tool, backend, cwd, command/path preview, duration, exit code, changed paths, and policy decision.
- Do not log raw environment values.
- Session shutdown calls `ToolRouter.cancelAll()` and `CodingRuntime.cleanup()` to stop or detach owned processes.

## Prompt Strategy

Inject the workspace prompt only when coding mode is enabled and a backend is ready.

Standalone/BASIC can get the dual-mode prompt first. PRO planner can use workspace tools directly for non-UI work and continue delegating UI actions to the UI executor. Do not give workspace tools to the current UI executor. Add a later `CodingExecutorRoleDef` for delegated coding-only subtasks.

Prompt extension:

```text
## Operating Modes

You have Android UI tools and workspace tools.

Use Android UI tools for visible phone apps, taps, text entry, app navigation, and any state that must be verified on screen.
Use workspace tools for files, repositories, shell commands, scripts, tests, git, and data processing.

Do not use workspace shell commands to control Android UI, bypass app restrictions, inspect blocked app data, enter credentials, or change Android security settings.

## Workspace Environment

Backend: {{coding_backend_name}}
Workspace: {{coding_workspace_root}}
Current directory: {{coding_cwd}}
Capabilities: {{coding_capabilities}}

Prefer search/list before broad reads. Prefer `apply_patch` for edits and `shell_exec` for verification. Run the narrowest meaningful check after changing files. If verification cannot run because the backend is limited or missing dependencies, say exactly what blocked it.

For mixed tasks, keep the domains separate: gather phone data with UI tools, process it with workspace tools, then return to UI tools only when the phone UI must change.
```

Implementation options:

- Add `CodingPromptContext` to `SessionServices`.
- Append the prompt extension in `SessionAgentRunner.resolvePromptTemplates(...)`.
- Keep role definitions readable by adding a `PromptExtensions.coding(context)` helper instead of embedding conditional text in every role.

## Session and UI Configuration

Settings should expose one compact section:

- Toggle: "Workspace tools".
- Backend: Auto, Termux, Remote, Native limited.
- Workspace root.
- Default cwd.
- Remote endpoint selector.
- Status row with health, capabilities, and setup action.

Do not expose bridge tokens in UI. For setup failures, show concrete state: Termux missing, permission missing, `allow-external-apps` missing, bridge not installed, bridge stopped, token mismatch, workspace invalid.

`SessionToolingBootstrapper.create(...)` should accept `codingRuntime: CodingRuntime?` and register workspace tools only when non-null and enabled. This keeps the default tool list unchanged when the feature is off.

## Implementation Tasks

- `coding-config-gate`
  - Scope: `SessionConfig.kt`, `AppSettingsStore.kt`, session creation in `MainActivity.kt` and `AgentService.kt`, settings UI.
  - Acceptance: coding mode defaults off; existing sessions expose the same tools as today; settings can persist backend preference and workspace root.
  - Dependencies: none.

- `tool-policy-metadata`
  - Scope: `ToolSpec.kt`, `ToolRouter.kt`, `PolicyEngine.kt`, `ToolName.kt`, `TurnToolPolicy.kt`, approval protocol/UI models.
  - Acceptance: policy uses `ToolPolicyMetadata`; UI tools preserve current behavior; unknown tools fail closed; turn arbitration no longer depends directly on `ToolName.isScreenChanging`.
  - Dependencies: `coding-config-gate`.

- `coding-runtime-contract`
  - Scope: `app/src/main/kotlin/ai/closepaw/coding/**`, unit tests.
  - Acceptance: backend interface, request/result models, backend selector, status model, path normalization helpers, and output redactor exist with tests.
  - Dependencies: `coding-config-gate`.

- `workspace-tools-v1`
  - Scope: `tool/impl/coding/**`, `SessionToolingBootstrapper.kt`, role allow-lists, prompt extension.
  - Acceptance: `workspace_status`, `list_files`, `search_files`, `file_read`, `file_write`, and `apply_patch` work against a fake/native test backend; path traversal and symlink escapes are denied.
  - Dependencies: `tool-policy-metadata`, `coding-runtime-contract`.

- `termux-bridge-daemon`
  - Scope: `tools/termux-bridge/**` or `app/src/main/assets/termux/**`, bridge docs/tests.
  - Acceptance: Python stdlib daemon serves health, exec, file, search, and patch endpoints; token auth, workspace enforcement, timeout, truncation, and process-group kill are tested on host.
  - Dependencies: `coding-runtime-contract`.

- `termux-backend`
  - Scope: manifest, Termux setup UI, `coding/backend/termux/**`, encrypted token storage, OkHttp client tests.
  - Acceptance: ClosePaw can detect Termux version/setup state, bootstrap/start the bridge through Termux when supported, provide a copyable fallback script when not supported, verify health, run `git --version`, read/write/patch/search a workspace, and report setup failures clearly.
  - Dependencies: `workspace-tools-v1`, `termux-bridge-daemon`.

- `shell-and-git-tools`
  - Scope: `ShellExecTool`, `GitTool`, command classifier tests, policy tests.
  - Acceptance: safe reads/builds run automatically in SMART mode; package installs, commits, pushes, destructive commands, and Android device-control commands are classified correctly.
  - Dependencies: `termux-backend`.

- `remote-backend`
  - Scope: `coding/backend/remote/**`, remote endpoint settings, bridge deployment docs.
  - Acceptance: the same workspace tools work over HTTPS to a Tailscale/private endpoint with token auth and root allow-listing.
  - Dependencies: `termux-bridge-daemon`, `shell-and-git-tools`.

- `process-tools`
  - Scope: bridge process endpoints, `process_start/read/stop` tools, session cleanup.
  - Acceptance: a long-running dev server can be started, logs can be read by cursor, and session shutdown can stop owned processes.
  - Dependencies: `shell-and-git-tools`.

- `dual-mode-agent-qa`
  - Scope: agent prompts, trace artifacts, eval/debug scripts, docs.
  - Acceptance: one pure workspace task, one pure UI task, and one mixed UI-plus-workspace task pass without exposing workspace tools to the UI executor.
  - Dependencies: `termux-backend`, `shell-and-git-tools`.

## Trade-Offs

- Termux bridge over native shell: more setup, much higher ceiling. Native Android shell remains a fallback and inspection tool.
- HTTP JSON over direct `RUN_COMMAND`: one-time setup cost, but reliable structured results, streaming/process support, smaller intents, and one protocol for remote.
- One bridge protocol for Termux and remote: slightly constrains transport details, but keeps tools and policy backend-neutral.
- `apply_patch` over `file_edit`: fewer edit tools and better multi-file behavior.
- Deny Android control commands from workspace shell: less convenient, but preserves the existing UI policy boundary.

## Open Questions

- Should the first remote backend be only HTTPS bridge, or should SSH be included only to install/start the bridge?
- Should `git commit` be allow-once, allow-workspace, or always ask in SMART mode?
- How much `workspace_status` context should be injected each turn before it becomes prompt noise?
- Should process handles survive session shutdown by explicit user request, or always stop by default?

## Self-Review

This design covers the requested backend interface, tool surface, Termux protocol, security model, dual-mode prompt, session config, and implementation phases.

The main simplification is treating files, shell, git, and process control as one workspace execution plane. Termux and remote differ only behind `CodingExecutionBackend`; the LLM and policy see the same operations.

The main risk is policy complexity. That complexity is real, not accidental: foreground app tier cannot describe filesystem writes, shell commands, secrets, or publishing. The design contains it by making workspace policy explicit and keeping UI policy unchanged.

The design avoids the bad shortcut: expanding the existing `shell` into arbitrary command execution. That would still lack a full userland and would create a UI-policy bypass. Termux bridge plus remote bridge is the simpler long-term foundation.
