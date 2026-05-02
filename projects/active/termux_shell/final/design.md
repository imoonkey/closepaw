# ClosePaw General Agent via Termux Backend — Design

## Goal

Add a `termux_shell` tool to ClosePaw on top of its existing UI automation, gaining full Linux shell capability through a Termux bridge daemon, making it a general-purpose agent.

- Keep Android UI automation (Accessibility Service, mobile_action, system_button, etc.) unchanged.
- Add `termux_shell` tool: full bash, supports pipe/redirect, can use python/git/node/gcc and the entire Termux toolchain.
- Keep the existing `shell` tool as-is — it is a restricted toybox device inspection tool, not a workspace runtime.
- v1 does not add standalone file_read/write/edit/search/git tools — everything goes through shell.
- All three agent roles (Standalone, Planner, Executor) gain `termux_shell` when Termux is available.

The product goal is not "make a coding feature." Coding-agent primitives are the general-agent base layer: run commands, read/write files, search data, use git, process artifacts. Domain-specific agents (OpenClaw, Hermes, etc.) build on this foundation.

**Success criteria**: A single session can use both UI tools and `termux_shell`. The LLM understands the capability boundaries of the two shells. Mixed UI+workspace tasks can compose.

## v1 Scope

**In scope:**
- Bridge daemon (Python stdlib, ~150 lines)
- `TermuxShellTool` implements `ToolSpec` (one new tool)
- `TermuxBridgeManager` (lifecycle-stable controller, owns state + setup + health)
- Termux detection + step-by-step bootstrap flow (RUN_COMMAND intent)
- System prompt extension (inject `termux_shell` description when Termux is available)
- `shell` tool description tweak (clarify toybox capability boundaries)
- Settings UI: new Termux Shell entry in Agent Behavior page
- `network_security_config.xml` add localhost cleartext exception
- Manifest: Termux package visibility + RUN_COMMAND service query
- Bridge script packaged as `app/src/main/res/raw/closepaw_bridge_py`

**Out of scope:**
- Standalone file tools (read/write/edit/grep/find/ls) — done through shell
- Standalone git tool — run git commands through `termux_shell`
- Remote backend (SSH/HTTPS) — future
- NativeBackend extension — existing `shell` is already the native fallback
- Shizuku/root shell, embedded Python/JS/WASM runtime
- Process management UX, automatic package install without approval
- Health check capabilities dynamic probing
- Security/policy hardening — deferred (see `projects/active/shell_security/`)
- `/v1/cancel` endpoint — v1 relies on timeout + client disconnect
- Bridge token auth — v1 relies on loopback-only binding (security deferred)
- Manual setup fallback — v1 requires RUN_COMMAND to be available

## System Invariants

1. **Android UI control goes through UI tools and UI policy only.** `termux_shell` cannot bypass app-tier restrictions.
2. **RUN_COMMAND is for bootstrap only.** The 100KB Binder result limit makes it unusable for runtime communication.
3. **Failure must be explicit.** Bridge unavailable, timeout, output truncation are all machine-readable errors.
4. **The two shell tools coexist but are completely independent.** Different capabilities, different execution paths.
5. **Workspace is in Termux home `~/closepaw/workspace/`.** Android 11+ scoped storage makes cross-app sharing via `/sdcard` unreliable. When files need to be shared, `cp` to `/sdcard/Download/`.
6. **Security/policy deferred.** v1 uses auto-allow (`isScreenChanging = false`), see `projects/active/shell_security/`.
7. **Single source of truth.** `TermuxBridgeManager` owns the bridge state; tool registration and prompt injection both read from it.
8. **Termux availability is fixed at session creation time.** `SessionServices.create()` captures a snapshot that remains immutable for the entire session lifecycle (including Hot Idle follow-up tasks). Setup changes take effect on the **next session** (new task or reload), not the next follow-up task. Settings UI displays "takes effect on next session."
9. **Non-zero exit code is not a tool failure.** Command exit codes are information for the LLM (`grep` returning 1, build failure with stderr); they do not interrupt subsequent tool calls in the same turn. `ToolExecutionResult.Failure` is reserved for transport/protocol errors only.
10. **`termux_shell` does not participate in turn reordering.** In `TurnToolPolicy`, only cognitive tools (scratchpad, write_todos) can be hoisted before screen-changing tools. `shell`, `termux_shell`, `ask_user` maintain the order returned by the LLM.
11. **Bridge executes only one `/v1/exec` at a time.** When busy, it immediately returns HTTP 409; no queuing. Health checks are not affected.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                    ClosePaw Agent                     │
│                                                      │
│  ┌───────────────┐       ┌────────────────────────┐  │
│  │  UI Tools      │       │  Workspace Tools       │  │
│  │  ────────────  │       │  ──────────────────    │  │
│  │  mobile_action │       │  termux_shell          │  │
│  │  system_button │       │  (full Linux bash)     │  │
│  │  open_app      │       │                        │  │
│  │  wait          │       │  Future:               │  │
│  │  shell (toybox)│       │  file_read, file_write  │  │
│  │  scratchpad    │       │  file_edit, search...   │  │
│  │  ...           │       │                        │  │
│  └──────┬────────┘       └──────────┬─────────────┘  │
│         │                           │                 │
│         ▼                           ▼                 │
│  ┌────────────────┐    ┌──────────────────────────┐   │
│  │ Accessibility   │    │ PolicyEngine             │   │
│  │ Platform        │    │ (auto-allow, same as     │   │
│  │                 │    │  existing shell tool)    │   │
│  └────────────────┘    └───────────┬──────────────┘   │
│                                     │                  │
│                              ┌──────▼──────┐           │
│                              │ OkHttp      │           │
│                              │ localhost    │           │
│                              │ (cleartext  │           │
│                              │  exception) │           │
│                              └──────┬──────┘           │
└─────────────────────────────────────┼──────────────────┘
                                      │
                               ┌──────▼──────┐
                               │  Termux     │
                               │  Bridge     │
                               │  Daemon     │
                               │  (Python)   │
                               └─────────────┘
```

---

## 1. Two Shell Tools Compared

| | `shell` (existing) | `termux_shell` (new) |
|---|---|---|
| **Implementation** | `Runtime.exec()` in ClosePaw process | HTTP → Termux bridge daemon → `subprocess.Popen()` |
| **Shell** | Android toybox | Full bash |
| **Capabilities** | ls, cat, stat, grep, wc, head, tail, etc. No pipe/redirect support. | Full GNU coreutils + pipe/redirect + process control. Available toolchain depends on Termux installed packages |
| **File access** | ClosePaw app-private + `/sdcard` | Termux home + `/sdcard` |
| **Timeout** | 10s | 120s (configurable) |
| **Output cap** | 4KB | 64KB (excess written to artifact, returns `output_ref`) |
| **Policy** | Auto-allow (non-screen-changing) | Auto-allow (same, security deferred) |
| **Turn ordering** | Maintains LLM order (no hoist) | Maintains LLM order (no hoist) |
| **Exit code** | Passed through to LLM | Passed through to LLM. Non-zero exit code = `ToolExecutionResult.Success`, not `Failure` |
| **Description** | "Android toybox shell. Limited available commands (ls, cat, stat, grep, etc.), no pipe/redirect support. Use termux_shell when you need a full toolchain (python/git, etc.)." | "Full Linux bash shell (via Termux). Supports pipe, redirect, all GNU coreutils. Available toolchain: python3, node/npm, git, gcc, cargo, go, etc. (depends on installed packages). Working directory: `~/closepaw/workspace/`. To share with other apps, cp to /sdcard/Download/." |

v1 does not add standalone file tools. Everything goes through shell. Eval-driven subsequent expansion.

---

## 2. Termux Bridge Daemon

### Design

A Python stdlib-based daemon (~150 lines), `ThreadingHTTPServer`. No pip dependencies required.

```
Termux process
├── closepaw-bridge (HTTP server, 127.0.0.1:18422)
│   ├── POST /v1/exec     → subprocess.Popen(["bash", "-c", command])
│   └── GET  /v1/health   → status + version + bridge identity
└── (user's shell session, unaffected)
```

All responses are JSON. Loopback only (127.0.0.1, not bound to 0.0.0.0). v1 does not implement token auth — loopback binding already prevents network access. Other apps on the device being able to connect to localhost is a security concern, deferred to a later task.

**Fixed port 18422**. Bridge determines port ownership via pidfile at startup:
- Pidfile exists → check if it belongs to a ClosePaw bridge (read identity from pidfile) → if yes, kill old process → rebind
- Port occupied by unknown process → return typed error `port_in_use`, do not blind kill
- Bridge writes pidfile containing `{ pid, port, version, identity: "closepaw-bridge" }`

**Only one `/v1/exec` at a time.** Bridge uses an internal lock to serialize. Subsequent requests queue and wait. Health checks are not affected. This avoids concurrent shell commands racing in the workspace.

### Localhost Cleartext Exception

```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```

### Bridge Packaging and Version

**Canonical source**: `tools/termux-bridge/closepaw_bridge.py` (for development, host testing, and editing).

A Gradle copy task packages it as `app/src/main/res/raw/closepaw_bridge_py`. CI test verifies: raw resource exists + byte-identical + contains expected version line.

Version constant `BRIDGE_VERSION = "1"` defined at the top of bridge.py, appears in the `/v1/health` response. `TermuxBridgeManager` reads the raw resource during deployment and compares versions during health checks; on mismatch, it re-deploys.

### API

**Health check:**
```
GET /v1/health
→ 200 { "status": "ok", "version": "1", "identity": "closepaw-bridge" }
```

**Execute command:**
```
POST /v1/exec
{
  "command": "git status",
  "cwd": "~/closepaw/workspace/myapp",
  "env": {"TERM": "dumb"},
  "timeout_ms": 120000,
  "max_output_bytes": 65536
}
→ 200 {
    "stdout": "...",
    "stderr": "",
    "exit_code": 0,
    "duration_ms": 42,
    "timed_out": false,
    "stdout_truncated": false,
    "stderr_truncated": false,
    "stdout_ref": null,
    "stderr_ref": null
  }
```

- `subprocess.Popen(["bash", "-c", command])`, process-group kill on timeout
- Client disconnect → bridge kills subprocess (v1 cancellation)
- cwd defaults to workspace root; null/missing → workspace root
- stdout/stderr truncated independently: each has its own `_truncated` and `_ref` fields. Excess written to `~/.closepaw/artifacts/<call_id>_stdout` / `_stderr`
- **Non-zero exit code returns HTTP 200**, exit_code field carries the value. HTTP non-200 is reserved for transport/protocol errors only.
- **Only one `/v1/exec` at a time.** If a command is already running, immediately returns `{ "error": "busy" }` (HTTP 409). No queuing, avoiding timeout ambiguity. Health checks are not affected.

**Error codes**: `workspace_escape`, `timeout`, `invalid_request`, `internal_error`, `port_in_use`, `busy`

### Bridge Exec Loop (Python Implementation Contract)

```python
def handle_exec(request):
    if not exec_lock.acquire(blocking=False):
        return 409, {"error": "busy"}

    try:
        proc = subprocess.Popen(
            ["bash", "-c", command],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            cwd=validated_cwd, env=merged_env,
            start_new_session=True  # process group for clean kill
        )

        # Use two threads to drain stdout/stderr, avoiding pipe buffer deadlock
        stdout_reader = threading.Thread(target=drain, args=(proc.stdout, stdout_buf, max_bytes))
        stderr_reader = threading.Thread(target=drain, args=(proc.stderr, stderr_buf, max_bytes))
        stdout_reader.start(); stderr_reader.start()

        # Main loop: poll process + detect client disconnect
        while proc.poll() is None:
            if not client_connected():  # wfile.write probe or select on socket
                os.killpg(proc.pid, signal.SIGKILL)
                break
            if elapsed > timeout_ms:
                os.killpg(proc.pid, signal.SIGKILL)
                stdout_reader.join(); stderr_reader.join()
                return 200, {
                    "stdout": stdout_buf.text, "stderr": stderr_buf.text,
                    "exit_code": None, "timed_out": True,
                    "duration_ms": elapsed,
                    "stdout_truncated": stdout_buf.truncated,
                    "stderr_truncated": stderr_buf.truncated,
                    "stdout_ref": stdout_buf.artifact_path,
                    "stderr_ref": stderr_buf.artifact_path
                }
            time.sleep(0.1)

        stdout_reader.join(); stderr_reader.join()

        # If output exceeded limits, drain() has already written to artifact files
        return 200, {
            "stdout": stdout_buf.text, "stderr": stderr_buf.text,
            "exit_code": proc.returncode,
            "timed_out": False,
            "stdout_truncated": stdout_buf.truncated,
            "stderr_truncated": stderr_buf.truncated,
            "stdout_ref": stdout_buf.artifact_path,
            "stderr_ref": stderr_buf.artifact_path
        }
    finally:
        exec_lock.release()

def drain(pipe, buf, max_bytes):
    """Read in chunks; write to artifact file after exceeding max_bytes."""
    for chunk in iter(lambda: pipe.read(4096), b''):
        if buf.total < max_bytes:
            buf.append(chunk)
        else:
            buf.write_to_artifact(chunk)  # overflow → ~/.closepaw/artifacts/<call_id>
```

Key guarantees:
- **No pipe deadlock**: stdout/stderr are drained by independent threads without waiting for process exit
- **Active client disconnect detection**: main loop polls socket writability, immediately killpg on disconnect
- **Precise output cap**: drain threads count bytes chunk by chunk, write to artifact file on excess
- **Timeout not amplified by exec lock**: busy returns 409 immediately, no queuing

### Bootstrap Flow (Step-by-Step Idempotent, Probe-Derived Readiness)

Each step's completion status is **derived from actual probes**, not merely by remembering "step completed." If Termux is cleared or the package is deleted, probes will automatically detect the inconsistency.

```
Step 1: Probe
  RUN_COMMAND echo "CLOSEPAW_PROBE=ok"
  Verifies: Termux initialization complete, allow-external-apps, RUN_COMMAND permission, PendingIntent can return
  → On failure, display specific reason

Step 2: Install packages
  RUN_COMMAND: pkg install -y python git ripgrep
  Verify: RUN_COMMAND which python3 && which git && which rg
  → Idempotent, already-installed packages are not reinstalled

Step 3: Deploy bridge
  RUN_COMMAND:
    Deploy bridge.py (passed via EXTRA_STDIN, base64 encoded to avoid shell quoting issues)
    mkdir -p ~/closepaw/workspace ~/.closepaw/artifacts ~/.closepaw/logs
    echo "CLOSEPAW_DEPLOY=ok"
  → Verify stdout contains "CLOSEPAW_DEPLOY=ok"

Step 4: Start bridge
  RUN_COMMAND: python3 ~/.closepaw/bridge.py &
  Poll /v1/health until ready
  → Health 200 + version match → status becomes "Ready"
```

**Manifest requirements:**
- `com.termux.permission.RUN_COMMAND` permission
- `<package android:name="com.termux" />`
- `<intent><action android:name="com.termux.RUN_COMMAND" /></intent>` in queries

**v1 does not implement manual setup fallback.** When RUN_COMMAND is unavailable, display "Termux Shell requires RUN_COMMAND permission" and guide the user to enable it.

### TermuxBridgeManager (Process-Wide Controller)

The project does not use Hilt/Dagger. Uses the Holder pattern (similar to existing `AuthStoreHolder`):

```kotlin
object TermuxBridgeManagerHolder {
    @Volatile private var instance: TermuxBridgeManager? = null

    fun get(appContext: Context): TermuxBridgeManager {
        return instance ?: synchronized(this) {
            instance ?: TermuxBridgeManager(
                appContext.applicationContext,
                CoroutineScope(SupervisorJob() + Dispatchers.IO)
            ).also { instance = it }
        }
    }
}

class TermuxBridgeManager(
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    val state: StateFlow<TermuxBridgeStatus>

    suspend fun runSetup()           // Step-by-step bootstrap, serialized by internal mutex
    suspend fun healthCheck()        // Probe bridge, update state
    suspend fun restart()            // Kill + start bridge
    fun setEnabled(enabled: Boolean) // Toggle stored in AppSettingsStore
    fun snapshot(): TermuxCapabilitySnapshot
}
```

### RUN_COMMAND Adapter Contract

```kotlin
class TermuxRunCommandAdapter(private val appContext: Context) {
    // Send RUN_COMMAND and await PendingIntent result
    suspend fun execute(
        command: String,
        stdin: String? = null,         // EXTRA_STDIN (base64 encoded for bridge.py deployment)
        background: Boolean = true,
        timeoutMs: Long = 60_000       // Per-step timeout
    ): RunCommandResult

    // Each call generates a unique requestId, ignoring stale/late results
    // PendingIntent result received via BroadcastReceiver
    // bridge.py content in stdin is base64 encoded to avoid shell quoting issues:
    //   echo '<base64>' | base64 -d > ~/.closepaw/bridge.py
}

data class RunCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
)

// Each bootstrap step has a clear success marker:
// Step 1 (probe):    stdout contains "CLOSEPAW_PROBE=ok"
// Step 2 (install):  exitCode == 0 and which verification passes
// Step 3 (deploy):   stdout contains "CLOSEPAW_DEPLOY=ok"
// Step 4 (start):    health check 200 with correct version

// On failure, RunCommandResult maps to NeedsSetup(reason):
// - exitCode != 0 → extract reason from stderr
// - stdout missing expected marker → format error
// - timeout → Termux unresponsive
// - SecurityException → permission missing
// - ActivityNotFoundException → Termux not installed or allow-external-apps not enabled
```

### Settings UI

```
Termux Shell          [Toggle]
  Ready ✓
  Needs Setup — tap to set up →
  Not Installed — tap to install →
```

Underlying typed reasons support debug (permission missing, allow-external-apps missing, packages missing, bridge outdated, health timeout, port_in_use, etc.). Setup changes display "takes effect on next session."

### Lifecycle

- Session startup reads `TermuxBridgeManager.state` snapshot (fixed-at-task-start)
- Background probes `/v1/health` every 30s, updating `state` (affects next task, not current)
- Bridge has a 30-minute idle watchdog (auto-exits when no requests to save battery)
- When bridge dies, restart on-demand via RUN_COMMAND
- On version mismatch, re-deploy bridge.py from raw resource

---

## 3. Agent Integration

### Session Capability Snapshot (Fixed-at-Task-Start)

At session creation (`SessionServices.create()`), an **immutable snapshot** is captured from `TermuxBridgeManager`, remaining unchanged for the entire session lifecycle (including Hot Idle follow-up tasks):

```kotlin
data class TermuxCapabilitySnapshot(
    val available: Boolean,   // bridge ready AND user enabled
    val enabled: Boolean,     // settings toggle (independent of readiness, not overwritten by health polling)
    val status: TermuxBridgeStatus  // for debug/logging
)

// Captured once in SessionServices.create():
val termuxSnapshot = bridgeManager.snapshot()

// All following decisions read the same snapshot:
// 1. Tool registration (available → register TermuxShellTool)
// 2. Prompt injection (available → append workspace prompt section)
// 3. Role allow-list adjustment (available → add "termux_shell")
// 4. Executor timeout adjustment (available → 150s)
// 5. Checkpoint/reload → new task boundary, captures fresh snapshot (does not serialize old snapshot)
```

Snapshot propagation path: `SessionServices.create()` captures snapshot → stored on `SessionServices` → `SessionAgentRunner.start()` uses it to build runtime role config → `AgentRoleRuntime.resolve(agentDef, snapshot)` returns adjusted allowedTools + timeout + prompt → `IsolatedSubAgentRunner` also reads the resolved role.

```kotlin
// Do not modify static AgentRoleDef; resolve at runtime
data class ResolvedAgentRole(
    val allowedTools: Set<String>,    // agentDef.allowedTools + "termux_shell" - excludedTools
    val systemPrompt: String,         // agentDef.systemPrompt + workspace section if tool exposed
    val timeoutMs: Long               // agentDef.timeoutMs or 150_000 if executor + termux
)

fun AgentRoleDef.resolve(
    snapshot: TermuxCapabilitySnapshot,
    excludedTools: Set<String> = emptySet()
): ResolvedAgentRole {
    val baseTools = allowedTools - excludedTools
    val termuxExposed = snapshot.available && "termux_shell" !in excludedTools
    val tools = if (termuxExposed) baseTools + "termux_shell" else baseTools
    val prompt = if (termuxExposed) systemPrompt + WORKSPACE_PROMPT_SECTION else systemPrompt
    val timeout = if (termuxExposed && this is ExecutorRoleDef) 150_000L else timeoutMs
    return ResolvedAgentRole(tools, prompt, timeout)
}
```

Prompt injection follows tool exposure: if `termux_shell` is excluded by `excludedTools`, the workspace prompt section is also not injected.

Background health polling updates `TermuxBridgeManager.state` but does not affect the current session. The next session (new or reload) captures a fresh snapshot.

### Tool Result Semantics

Result mapping for `TermuxShellTool`:

| Bridge response | ToolExecutionResult |
|---|---|
| HTTP 200, any exit_code | `Success` (stdout/stderr/exit_code all provided as tool output to LLM) |
| HTTP 200, `timed_out=true` | `Success` (partial stdout/stderr + `timed_out=true`, LLM sees timeout info and decides next step) |
| HTTP 409 busy | `Failure` (bridge busy, LLM can retry) |
| HTTP 4xx/5xx | `Failure` (transport/protocol error) |
| Connection refused / timeout | `Failure` (bridge not available) |
| OkHttp IO error | `Failure` (network error) |

`grep` returning 1, build failure with stderr → both are `Success`; the LLM sees exit_code and stderr to judge for itself. Does not interrupt subsequent tool calls in the same turn.

### Cancellation (OkHttp Wiring)

`TermuxShellTool.execute()` uses a cancellable OkHttp call:

```kotlin
override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
    val call = okHttpClient.newCall(request)
    try {
        return withContext(Dispatchers.IO) {
            // suspendCancellableCoroutine ensures Call.cancel() on coroutine cancellation
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                // ... execute and resume
            }
        }
    } catch (e: IOException) {
        // call was cancelled or bridge disconnected
        return ToolExecutionResult.Failure("cancelled or bridge unavailable")
    }
}
```

Coroutine cancel → `Call.cancel()` → TCP connection drops → bridge detects client disconnect → kills subprocess. No need to wait for the 120s timeout.

### Timeout Ladder

```
User-requested timeout_seconds (tool param)   Default 120s, max 120s
  ↓
Bridge receives timeout_ms                    = timeout_seconds * 1000
  ↓ On timeout, process-group kill, returns HTTP 200 + timed_out=true
OkHttp read/call timeout                      = timeout_ms + 5s grace (125s)
  ↓ On timeout, IOException → ToolExecutionResult.Failure
Executor role timeout                         = 150s (when termux_shell enabled)
  ↓ On timeout, sub-agent is killed
```

OkHttp timeout must be explicitly configured to `timeout_ms + grace`; the OkHttp default (10s read timeout) would disconnect before long commands complete.

### Bridge Timeout Response

Timeout is command-level output (HTTP 200), not a transport failure. The LLM sees partial output + `timed_out=true` and can decide the next step.

```json
{
  "stdout": "... partial ...",
  "stderr": "",
  "exit_code": null,
  "duration_ms": 120042,
  "timed_out": true,
  "truncated": false,
  "output_ref": null
}
```

Kotlin-side mapping: `timed_out=true` → `ToolExecutionResult.Success` (with timeout info). Does not interrupt subsequent tool calls in the same turn.

### Turn Ordering

`TurnToolPolicy` modification: only **cognitive tools** (`scratchpad`, `write_todos`, `remember_experience`) can be hoisted before screen-changing tools. `shell`, `termux_shell`, `ask_user`, `activate_skill` maintain the order returned by the LLM.

```kotlin
private val HOISTABLE_TOOLS = setOf("scratchpad", "write_todos", "remember_experience")

fun reorder(toolCalls: List<ToolCallRequest>): List<ToolCallRequest> {
    val hoistable = toolCalls.filter { it.name in HOISTABLE_TOOLS }
    val rest = toolCalls.filter { it.name !in HOISTABLE_TOOLS }
    return hoistable + rest  // cognitive first, then preserve LLM order
}
```

### System Prompt Extension

Injected when Termux is available (at task startup, `bridgeManager.state` is Ready and enabled):

```
## Workspace Shell

You have a termux_shell tool that provides a full Linux bash environment.
Working directory: ~/closepaw/workspace/

### termux_shell
Full Linux bash shell (via Termux). Supports pipe, redirect, all GNU coreutils.
Available toolchain: python3, node/npm, git, gcc, cargo, go, etc. (depends on installed packages).
Working directory: ~/closepaw/workspace/. Input and output files go in this directory.
To share with other apps, cp to /sdcard/Download/.

### When to use which shell
- termux_shell: when you need a full toolchain (python/git/node, etc.) or pipe/redirect
- shell: quick device file checks (ls/cat/stat), when you don't need the Termux toolchain

### When to use UI tools vs shell
- UI tools (mobile_action, etc.): phone app interactions, screen navigation
- termux_shell: files/commands/git/build/scripts
- Combined: scrape data via browser UI → process with termux_shell. Email attachment → analyze with python.

### Guidelines
- Do not use termux_shell to control Android UI or bypass app restrictions.
- Input and output files go in ~/closepaw/workspace/.
```

### Shell Tool Description Update

```
Android toybox shell. Limited available commands (ls, cat, stat, grep, wc, head, tail,
sort, uniq, diff, du, df, file, touch, mkdir, cp, mv, rm, chmod, etc.),
no support for pipe (|), redirect (>/<), or command substitution ($()).
Use termux_shell when you need a full toolchain (python/git, etc.).
```

### Conditional Tool Registration

```kotlin
private fun ToolRegistry.registerBuiltInTools(
    sessionState: AgentSessionState,
    termuxSnapshot: TermuxCapabilitySnapshot  // immutable, captured at session start
) {
    // Always register UI tools (unchanged)
    register(CompleteTaskTool())
    register(MobileActionTool())
    register(SystemButtonTool())
    register(WaitTool())
    register(OpenAppTool())
    register(ShellTool())
    register(WriteTodosTool(sessionState.todos))
    register(ScratchpadTool(sessionState.scratchpad))

    // Read snapshot, not live state
    if (termuxSnapshot.available) {
        register(TermuxShellTool(bridgeManager))
    }
}
```

### Role Definitions

All three roles gain `termux_shell` when Termux is available:

- **Standalone**: gains `termux_shell` (BASIC mode, used directly)
- **Planner**: gains `termux_shell` (in PRO mode, **Planner runs workspace commands directly**, does not delegate to Executor)
- **Executor**: gains `termux_shell` (Executor can execute workspace tasks assigned by Planner). **Executor timeout increases from 30s to 150s** (when `termux_shell` is exposed)

**PRO Mode Delegation Contract**:
- Planner still delegates to Executor when UI operations are needed ("delegate ONE atomic UI action")
- Planner **directly calls `termux_shell` itself** when workspace operations are needed, without delegating
- This means the Planner prompt needs to add: "For workspace commands (termux_shell), execute directly instead of delegating"
- `delegate_task` tool description keeps "delegate ONE atomic UI action" unchanged
- If subsequent eval shows that Planner running shell itself performs poorly, it can be changed to delegate workspace tasks to Executor

---

## 4. Security & Policy

**Deferred.** v1 uses auto-allow. See `projects/active/shell_security/problem.md` for details.

---

## 5. Implementation Phases

### Phase 1: Substrate (Manifest + Network + Resource)

**Scope**: `AndroidManifest.xml`, `network_security_config.xml`, `res/raw/`, build test

- Manifest: Termux package visibility + RUN_COMMAND service intent query
- `network_security_config.xml`: 127.0.0.1 cleartext exception
- `closepaw_bridge.py` packaged as `res/raw/closepaw_bridge_py`
- Version constant `BRIDGE_VERSION = "1"`
- CI test: raw resource exists and contains expected version line
- Instrumentation test: OkHttp can reach `http://127.0.0.1` under release config

### Phase 2: Bridge Daemon (Host-Side)

**Scope**: `tools/termux-bridge/closepaw_bridge.py` (for development/testing), `res/raw/` (for packaging)

- `ThreadingHTTPServer`, port 18422
- `/v1/exec` + `/v1/health` endpoints
- Pidfile + identity check (idempotent startup, `port_in_use` error)
- Process-group kill on timeout + client disconnect
- Output cap + artifact overflow (stdout/stderr independent truncate + ref)
- Exec lock (busy 409)
- Idle watchdog (30 min)
- Host-side pytest: health, exec, timeout, exit code, output cap, stdout/stderr overflow, stale daemon, port conflict, busy

### Phase 3: TermuxBridgeManager + Bootstrap

**Scope**: `termux/TermuxBridgeManager.kt`, `termux/TermuxRunCommandAdapter.kt`, setup UI

- `TermuxBridgeManagerHolder` (process-wide, Holder pattern like `AuthStoreHolder`)
- `TermuxBridgeManager` (StateFlow, mutex-serialized setup/health/restart)
- `TermuxBridgeStatus` (NotInstalled, NeedsSetup(reason), SetupInProgress, Ready, Disabled)
- `TermuxCapabilitySnapshot` (immutable, captured at session start)
- Termux detection (PackageManager)
- 4-step bootstrap: probe → install → deploy → start
- Health polling (30s interval)
- Bridge restart on failure
- Version mismatch → re-deploy from raw resource
- Enabled toggle stored in `AppSettingsStore`
- Instrumentation tests: bootstrap state machine transitions

### Phase 4: TermuxShellTool

**Scope**: `tool/impl/TermuxShellTool.kt`, `tool/ToolName.kt`

- `TermuxShellTool` implements `ToolSpec`
- OkHttp client, explicit read/call timeout = `timeout_ms + 5s grace`
- Cancellable call (`suspendCancellableCoroutine` + `Call.cancel()`)
- Tool parameters: `command`, `cwd?`, `timeout_seconds?`
- Result mapping: HTTP 200 (including timed_out) → `Success`, 409 busy / non-200 → `Failure`
- ToolName adds `TermuxShell` (`isScreenChanging = false`)
- `TurnToolPolicy` modification: `termux_shell` does not participate in hoist reordering
- Unit tests: schema, result mapping, cancellation, non-zero exit code handling

### Phase 5: Agent Wiring

**Scope**: `agent/definition/*.kt`, `session/SessionToolingBootstrapper.kt`, prompt

- `shell` description update (toybox + guide to use termux_shell)
- System prompt extension (reads `TermuxCapabilitySnapshot`)
- Conditional tool registration (fixed-at-task-start, reads the same snapshot)
- `AgentRoleDef.resolve(snapshot)` → `ResolvedAgentRole` (allowedTools + prompt + timeout)
- `SessionAgentRunner` and `IsolatedSubAgentRunner` both use resolved role
- All three roles' allow-lists add `termux_shell`
- Executor timeout increases to 150s (when termux_shell available)
- Checkpoint reload = new task boundary, fresh snapshot
- Integration tests: tool present/absent matches prompt present/absent

### Phase 6: Settings UI + QA

**Scope**: UI layer, eval

- Agent Behavior page Termux Shell entry (three states + toggle + typed reason)
- "Setup changes take effect on next session" prompt
- Pure workspace tasks pass (BASIC + PRO)
- Pure UI tasks pass (no regression)
- Mixed UI+workspace tasks pass
- Turn ordering test: `mobile_action` + `termux_shell` are not reordered
- Cancellation test: stop session during long command → process killed promptly
- Bootstrap test: each step failure and recovery
- Checkpoint/reload test: session restore does not resurrect dead bridge

### Known Limitations (v1)

- **Cancellation**: No `/v1/cancel`. Relies on `Call.cancel()` → client disconnect → bridge kill.
- **Shell-only file ops**: Multi-line writes require careful quoting; binary not supported. Eval-driven decision on whether to add file tools.
- **Security**: auto-allow, no command classification. Deferred.
- **Fixed-at-task-start**: If bridge dies mid-task, the tool is not immediately disabled; may produce transport errors.

### Future

- Bridge token auth (see `projects/active/shell_security/problem.md`)
- Standalone file tools (eval-driven)
- Remote backend (SSH/HTTPS, reusing bridge protocol)
- Process management (process_start/read/stop)
- `/v1/cancel` endpoint
- Health check capabilities field
- `shell` → `device_inspect` rename
- Live bridge state update (hot-reload tool list mid-task)

---

## Appendix: Design Discussion Archive

### Q: Shizuku vs Termux?
Shizuku has strong system control but limited toolchain. Termux has strong general-purpose computing. A general agent needs the toolchain.

### Q: Remote backend?
Not in v1.

### Q: RUN_COMMAND vs daemon?
RUN_COMMAND = ignition key (bootstrap), daemon = engine (runtime).

### Q: Where does the workspace go?
Termux home `~/closepaw/workspace/`. `/sdcard` scoped storage is unreliable.

### Q: One tool or two?
Two independent tools. Completely different capabilities/policy/execution paths.

### Q: Standalone file/git tools?
Not in v1. Done through shell. Eval-driven expansion.

### Q: File permission differences?
Each has its own app sandbox. Workspace is in Termux home; native shell does not need access.

### Q: Bridge stability?
Minimal code. Main risk is Android killing background processes. HTTP daemon is the most pragmatic approach.

### Q: Security?
v1 auto-allow. Deferred task.

### Q: Settings UI?
Agent Behavior page, three states + toggle + typed reason. Status is the entry point.

### Q: All roles get it?
Yes. Standalone/Planner/Executor all get it. PRO mode defaults to Planner.

### Q: Health check capabilities?
Not needed for v1.

### Q: Localhost cleartext?
127.0.0.1 narrow exception.

### Q: Run setup in one step?
No. Split into 4 idempotent steps. Probe-derived readiness.

### Q: Token auth?
Not in v1. Loopback-only binding is sufficient. Token auth is deferred to the security task (`projects/active/shell_security/problem.md`).

### Q: Port/stale daemon?
Fixed port + pidfile + identity check. No blind kill.

### Q: Cancellation?
v1 timeout + client disconnect + `Call.cancel()`.

### Q: DI / Singleton approach?
No Hilt/Dagger. Uses `TermuxBridgeManagerHolder.get(appContext)` pattern (similar to `AuthStoreHolder`). Process-wide singleton, applicationContext.

### Q: How does the bridge exec loop avoid pipe deadlock?
stdout/stderr are drained by independent threads. Main loop polls process + detects client disconnect. Output cap is counted chunk by chunk in drain threads. stdout/stderr are truncated independently.

### Q: How are concurrent `/v1/exec` requests handled?
Bridge returns HTTP 409 `busy` immediately when busy. No queuing. Avoids timeout ambiguity.

### Q: How does fixed-at-task-start ensure consistency?
`TermuxCapabilitySnapshot` (immutable) is captured in `SessionServices.create()`, remaining unchanged for the entire session (including Hot Idle follow-up). `AgentRoleDef.resolve(snapshot, excludedTools)` → `ResolvedAgentRole`. All downstream reads the resolved role. Setup changes take effect on the next session.

### Q: How does RUN_COMMAND avoid shell quoting issues?
bridge.py is passed via base64-encoded EXTRA_STDIN. Each step has a requestId to prevent stale results.

### Q: Is timeout Success or Failure?
HTTP 200 + `timed_out=true` → `ToolExecutionResult.Success`. The LLM sees partial output and timeout info to decide the next step. Does not interrupt subsequent tool calls in the same turn.

### Q: How is OkHttp timeout configured?
Read/call timeout = bridge timeout_ms + 5s grace. Does not use OkHttp defaults (10s would disconnect prematurely).

### Q: Bridge script source of truth?
`tools/termux-bridge/closepaw_bridge.py` is the canonical source. Gradle copy task packages it as `res/raw/`. CI verifies byte-identical.

### Q: How are stdout/stderr overflow distinguished?
Independent fields: `stdout_truncated`/`stderr_truncated` + `stdout_ref`/`stderr_ref`. Each writes to its own artifact file.

### Q: Manual setup fallback?
Not in v1. Requires RUN_COMMAND to be available. When unavailable, display the reason and guide the user to fix it.
