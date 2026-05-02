# ClosePaw General Agent: Termux Backend Design

## Goal

Turn ClosePaw from a UI-only phone automation agent into a general-purpose agent: read/write files, execute commands, use git, run tests, process data — on-device via Termux or remotely via SSH/HTTPS. The agent seamlessly combines UI tools (phone automation) with coding tools (shell/files) in a single conversation.

The existing UI tool surface and ShellTool are untouched. Coding capabilities are purely additive.

**Success**: The LLM sees a small, backend-neutral coding tool surface. Full coding works through Termux or a remote bridge. The restricted `shell` tool stays an Android inspection tool. Coding policy is separate from foreground-app policy. Mixed UI+coding tasks compose safely.

## Core Design Principle: Radical Minimalism

Claude Code's architecture validates that **four primitives** (read, write, edit, bash) compose into everything else. Vercel's d0 agent saw 3.5× faster execution, 100% success rate (from 80%), and 37% fewer tokens by reducing tool count. Git, search, build, and test are all shell commands — they don't need dedicated tools.

Our tool surface: **five tools**. Our backend interface: **three methods** (exec, readFile, writeFile). Everything else composes through shell.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                    ClosePaw Agent                     │
│                                                      │
│  ┌───────────────┐       ┌────────────────────────┐  │
│  │  UI Tools      │       │  Coding Tools          │  │
│  │  ────────────  │       │  ─────────────────     │  │
│  │  mobile_action │       │  shell_exec            │  │
│  │  system_button │       │  file_read             │  │
│  │  open_app      │       │  file_write            │  │
│  │  wait          │       │  file_edit             │  │
│  │  shell (insp)  │       │  code_search           │  │
│  │  scratchpad    │       │                        │  │
│  │  ...           │       │                        │  │
│  └──────┬────────┘       └──────────┬─────────────┘  │
│         │                           │                 │
│         ▼                           ▼                 │
│  ┌────────────────┐    ┌──────────────────────────┐   │
│  │ Accessibility   │    │ PolicyEngine             │   │
│  │ Platform        │    │ ├─ UI: AppTier path      │   │
│  └────────────────┘    │ └─ Coding: CodingRisk    │   │
│                         └───────────┬──────────────┘   │
│                                     ▼                  │
│                         ┌──────────────────────────┐   │
│                         │ ExecutionBackend          │   │
│                         │ (pluggable interface)     │   │
│                         └───┬────────┬────────┬────┘   │
└─────────────────────────────┼────────┼────────┼────────┘
                              │        │        │
                   ┌──────────┘        │        └───────────┐
                   ▼                   ▼                    ▼
            ┌────────────┐    ┌──────────────┐     ┌──────────────┐
            │  Termux    │    │   Remote     │     │   Native     │
            │  Bridge    │    │   SSH/HTTPS  │     │   (toybox)   │
            │  (primary) │    │   (heavy)    │     │   (fallback) │
            └────────────┘    └──────────────┘     └──────────────┘
```

UI tools flow through Accessibility Platform → AppTier policy (unchanged).
Coding tools flow through CodingRisk policy → ExecutionBackend (new).

---

## 1. Execution Backend

The backend is a dumb execution pipe. It knows nothing about git, search, patches, policy, or risk classification — those belong to the tool layer and policy engine.

### Interface

```kotlin
// ai.closepaw.coding.backend

interface ExecutionBackend {
    val id: String
    val displayName: String

    suspend fun exec(request: ExecRequest): ExecResult
    suspend fun readFile(path: String, offset: Long? = null, limit: Int? = null): FileContent
    suspend fun writeFile(path: String, content: ByteArray)
    suspend fun healthCheck(): BackendHealth
}

data class ExecRequest(
    val command: String,
    val cwd: String? = null,
    val env: Map<String, String>? = null,
    val timeoutMs: Long = 120_000,
    val maxOutputBytes: Int = 65_536
)

data class ExecResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long,
    val truncated: Boolean = false
)

data class FileContent(
    val data: ByteArray,
    val size: Long,
    val truncated: Boolean = false
)

data class BackendHealth(
    val healthy: Boolean,
    val capabilities: Set<String> = emptySet(),  // "python", "git", "rg", "node"
    val workspaceRoot: String? = null
)
```

Three methods. That's the entire contract. File read/write are separate from exec because:
- Binary-safe (exec stdout is text)
- No shell escaping issues with filenames containing spaces/quotes
- Bounded reads (offset/limit) without composing `head`/`tail`
- Cleaner error handling (404 vs exit code 1)

### Implementations

**TermuxBackend** — OkHttp client to `http://127.0.0.1:8422`. Primary on-device backend.

**RemoteBackend** — OkHttp client to `https://<host>:<port>` (same HTTP API as the bridge). For heavy workloads (large repos, Docker, builds). Uses Tailscale/private network.

**NativeBackend** — Android `Runtime.exec()` with expanded limits. Fallback when nothing else is available.
- Allows pipes and redirects (unlike the restricted `shell` tool)
- 120s timeout, 64KB output cap
- Blocks system commands (am, pm, su, reboot)
- Limited to toybox (no git, python, node)
- healthCheck reports `capabilities = emptySet()` so the LLM knows it's limited

### Selection

```kotlin
class BackendManager(
    private val backends: List<ExecutionBackend>,  // ordered: termux, remote, native
    private val preference: BackendPreference
) {
    suspend fun select(): ExecutionBackend {
        if (preference != AUTO) {
            val pinned = backends.first { it.id == preference.backendId }
            val health = pinned.healthCheck()
            if (!health.healthy) throw BackendUnavailableException(pinned.displayName)
            return pinned
        }
        return backends.firstOrNull { it.healthCheck().healthy }
            ?: throw BackendUnavailableException("No healthy backend")
    }
}

enum class BackendPreference(val backendId: String?) {
    AUTO(null), TERMUX("termux"), REMOTE("remote"), NATIVE("native")
}
```

Auto-selection priority: Termux → Remote → Native. User can pin in settings.

---

## 2. Tool Surface

Five tools. Each implements `ToolSpec`. Each routes through the session's active `ExecutionBackend`.

### shell_exec

The power tool. Full bash. Pipes, redirects, everything. The LLM uses this for git, build, test, grep, package install, process management — anything.

```
shell_exec(command: String, cwd: String?, timeout_seconds: Int?)
→ { stdout, stderr, exit_code, duration_ms }
```

- Default timeout: 120s
- Default output cap: 64KB, truncated with notice
- cwd defaults to workspace root

### file_read

Bounded file reading. Returns numbered lines (like `cat -n`).

```
file_read(path: String, offset: Int?, limit: Int?)
→ { content: String, total_lines: Int, truncated: Boolean }
```

- Default: first 2000 lines
- Prevents context flooding on large files
- Text files only; returns error message for binary

### file_write

Create or overwrite a file. For new files and full replacements.

```
file_write(path: String, content: String)
→ { bytes_written: Int }
```

### file_edit

Replace exact text in a file. Fails if old_string is not found or not unique (unless replace_all is true). Proven by Claude Code to be the best edit mechanism for LLMs.

```
file_edit(path: String, old_string: String, new_string: String, replace_all: Boolean?)
→ { success: Boolean }
```

Implementation: `readFile` → find old_string → replace → `writeFile`. Logic in the tool, not the backend.

### code_search

Search for patterns in files. Uses `rg` when available, falls back to `grep -rn`. Structured output for LLM consumption. Bounded results.

```
code_search(pattern: String, path: String?, regex: Boolean?)
→ { matches: [{ file, line, content }], total_matches: Int, truncated: Boolean }
```

- Max 100 matches returned (configurable)
- Implementation: `backend.exec("rg --json ...")` with output parsing

### Why not more tools?

| Rejected tool | Reason |
|---|---|
| `git` | Just `shell_exec("git status")`. Policy classifies git commands dynamically. |
| `list_files` / `glob` | `shell_exec("find . -name '*.kt'")` or `shell_exec("ls -la")`. |
| `coding_status` | Injected once in system prompt. LLM calls `shell_exec("git status")` for dynamic state. |
| `process_start/read/stop` | Phase 2 stretch goal. `shell_exec` with timeout handles most cases. |
| `package_install` | `shell_exec("apt install ...")`. Policy catches it via command classification. |
| `web_fetch` | Separate concern, not a coding-backend operation. |

---

## 3. Termux Bridge Protocol

### Why Not RUN_COMMAND for Runtime?

Termux's `RUN_COMMAND` intent has a **100KB combined stdout/stderr limit** (Android Binder `TransactionTooLargeException`). Build outputs, test logs, and file reads routinely exceed this. RUN_COMMAND is for bootstrap only. Runtime communication uses a persistent HTTP daemon.

### The Daemon: closepaw-bridge

Python stdlib script (~150 lines). `ThreadingHTTPServer` for concurrent requests. No pip dependencies.

```
Termux process
├── closepaw-bridge.py (HTTP server, 127.0.0.1:8422)
│   ├── POST /exec     → subprocess.run()
│   ├── GET  /read     → open().read()
│   ├── PUT  /write    → open().write()
│   └── GET  /health   → capabilities probe
└── (user's shell sessions, unaffected)
```

### API

All requests require `Authorization: Bearer <token>`. All responses are JSON.

**Health check:**
```
GET /health
→ 200 {
    "status": "ok",
    "version": "1",
    "capabilities": ["python3", "git", "rg", "node"],
    "workspace": "/data/data/com.termux/files/home/projects"
  }
```

Capabilities are auto-detected: the daemon probes `which python3`, `which git`, etc. at startup.

**Execute command:**
```
POST /exec
{
  "command": "git status",
  "cwd": "/data/data/com.termux/files/home/projects/myapp",
  "env": {"TERM": "dumb"},
  "timeout": 120,
  "max_output": 65536
}
→ 200 {
    "stdout": "On branch main\nnothing to commit\n",
    "stderr": "",
    "exit_code": 0,
    "duration_ms": 42,
    "truncated": false
  }
```

- Command runs via `subprocess.Popen(["bash", "-c", command])`
- Timeout enforced via `process.wait(timeout)` + `process.kill()`
- Output capped at `max_output` bytes, `truncated: true` if exceeded
- cwd validated: must be under workspace root (defense in depth)

**Read file:**
```
GET /read?path=/home/projects/main.kt&offset=0&limit=2000
→ 200 {
    "content": "package ai.closepaw...",
    "size": 4521,
    "total_lines": 142,
    "truncated": false
  }
→ 404 {"error": "File not found"}
→ 403 {"error": "Path outside workspace"}
```

- Path resolved to canonical form (resolves symlinks)
- Canonical path must be under workspace root

**Write file:**
```
PUT /write
{
  "path": "/home/projects/main.kt",
  "content": "package ai.closepaw..."
}
→ 200 {"bytes_written": 4521}
→ 403 {"error": "Path outside workspace"}
```

- Creates parent directories if needed
- Workspace boundary enforced

### Bootstrap Flow

```
                   ┌─────────────────────────┐
                   │ User installs Termux     │
                   │ (F-Droid / GitHub)       │
                   └────────────┬────────────┘
                                ▼
                   ┌─────────────────────────┐
                   │ ClosePaw detects Termux  │
                   │ → shows "Set up coding"  │
                   └────────────┬────────────┘
                                ▼
                   ┌─────────────────────────┐
                   │ User taps setup          │
                   │ → RUN_COMMAND intent:     │
                   │   setup.sh (background)   │
                   │   + PendingIntent for     │
                   │     result callback       │
                   └────────────┬────────────┘
                                ▼
┌─────────────── setup.sh in Termux ──────────────────┐
│  pkg install -y python git ripgrep                   │
│  mkdir -p ~/.closepaw                                │
│  python3 -c "import secrets;                         │
│    print(secrets.token_hex(32))" > ~/.closepaw/token │
│  # Install bridge script to ~/.closepaw/bridge.py    │
│  echo "$(cat ~/.closepaw/token)"  ← stdout           │
└──────────────────────┬──────────────────────────────┘
                       ▼
          PendingIntent result to ClosePaw:
          stdout = "<64-char hex token>"
                       ▼
┌─────────────── ClosePaw ────────────────────────────┐
│  Parse token from result bundle                      │
│  Store in EncryptedSharedPreferences                 │
│  Send RUN_COMMAND: "python3 ~/.closepaw/bridge.py"   │
│  Probe localhost:8422/health until ready              │
└──────────────────────────────────────────────────────┘
```

Token exchange via RUN_COMMAND PendingIntent — no storage permissions needed, no user interaction beyond the initial tap. Fallback: user copies token manually from Termux (`cat ~/.closepaw/token`) into ClosePaw settings.

**Termux requirements:**
- `com.termux.permission.RUN_COMMAND` in ClosePaw's manifest
- User enables `allow-external-apps` in `~/.termux/termux.properties`
- Termux v0.109+ (for PendingIntent result support)

### Lifecycle

```
State machine:

  UNKNOWN ──[probe health]──→ CONNECTED
  UNKNOWN ──[probe fails]───→ DISCONNECTED
  CONNECTED ─[health fail]──→ DISCONNECTED
  DISCONNECTED ─[restart]───→ STARTING
  STARTING ──[health OK]────→ CONNECTED
  STARTING ──[timeout 10s]──→ DISCONNECTED
```

- ClosePaw probes health on session start and every 30s during a coding session
- Bridge has a 30-minute idle watchdog: exits if no requests (saves battery)
- ClosePaw restarts bridge on-demand via RUN_COMMAND if it dies
- For long sessions: user should enable "Acquire Wakelock" in Termux notification to prevent Android from killing the process

### Bridge Installation

The bridge script ships as a raw resource in ClosePaw's APK. During setup, ClosePaw writes it to Termux's home via the setup script:

```bash
# In setup.sh (sent via RUN_COMMAND EXTRA_STDIN or EXTRA_ARGUMENTS)
cat > ~/.closepaw/bridge.py << 'BRIDGE_EOF'
... bridge script content ...
BRIDGE_EOF
```

Updates: ClosePaw checks bridge version via `/health` response. If outdated, re-deploys via RUN_COMMAND.

---

## 4. Security & Policy

### Design Principle

Coding tools need their own policy axis. The current `PolicyEngine` gates on AppTier (which app is foreground). Coding tools aren't screen-bound — a `file_write` in a workspace has nothing in common with clicking a button in a banking app. Two orthogonal policy paths, one engine.

### CodingRisk Classification

```kotlin
enum class CodingRisk {
    READ,             // ls, cat, grep, git status/log/diff, pwd, which
    WORKSPACE_WRITE,  // file_write, file_edit within workspace
    COMMAND_SAFE,     // Build/test: gradlew, pytest, npm test, cargo build
    COMMAND_MUTATE,   // git commit, mv, cp, mkdir, chmod
    PUBLISH,          // git push, npm publish, curl -X POST, scp
    DESTRUCTIVE,      // rm -rf, git push --force, git reset --hard, dd
    SYSTEM,           // am, pm, su, reboot, settings, input
    SECRET_ACCESS     // Reading .env, .pem, key files, credentials
}
```

### Command Classifier

Pattern-match the first token. Simple, fast, good enough. Misclassification defaults to COMMAND_MUTATE (allow with audit), not DESTRUCTIVE (block). Over-restricting hurts more than a rare false negative.

```kotlin
object CommandClassifier {
    fun classify(command: String): CodingRisk {
        val tokens = command.trim().split("\\s+".toRegex())
        val cmd = tokens.first().substringAfterLast('/')

        return when {
            cmd in SYSTEM_COMMANDS -> CodingRisk.SYSTEM
            isDestructive(tokens) -> CodingRisk.DESTRUCTIVE
            isPublish(tokens) -> CodingRisk.PUBLISH
            isSecretAccess(tokens) -> CodingRisk.SECRET_ACCESS
            cmd in READ_COMMANDS -> CodingRisk.READ
            cmd in BUILD_COMMANDS -> CodingRisk.COMMAND_SAFE
            else -> CodingRisk.COMMAND_MUTATE
        }
    }

    private val READ_COMMANDS = setOf(
        "ls", "cat", "head", "tail", "grep", "rg", "find", "wc",
        "file", "stat", "pwd", "which", "echo", "env", "printenv",
        "tree", "du", "df", "less", "more", "diff", "sort", "uniq"
    )
    private val BUILD_COMMANDS = setOf(
        "gradlew", "gradle", "make", "cmake", "mvn",
        "pytest", "python", "python3", "node", "npm", "npx", "yarn",
        "cargo", "go", "rustc", "javac", "kotlinc", "gcc", "g++",
        "pip", "pip3", "bundle", "ruby"
    )
    private val SYSTEM_COMMANDS = setOf(
        "am", "pm", "su", "reboot", "settings", "input",
        "uiautomator", "dumpsys", "cmd"
    )

    private fun isDestructive(tokens: List<String>): Boolean {
        val cmd = tokens.first().substringAfterLast('/')
        val joined = tokens.joinToString(" ")
        return cmd == "rm" && ("-rf" in tokens || "-fr" in tokens) ||
               joined.contains("git push --force") ||
               joined.contains("git push -f") ||
               joined.contains("git reset --hard") ||
               cmd == "dd"
    }

    private fun isPublish(tokens: List<String>): Boolean {
        val cmd = tokens.first().substringAfterLast('/')
        val joined = tokens.joinToString(" ")
        return joined.startsWith("git push") ||
               joined.contains("npm publish") ||
               (cmd == "curl" && tokens.any { it in setOf("-X", "--request") })
    }

    private fun isSecretAccess(tokens: List<String>): Boolean {
        val path = tokens.getOrNull(1) ?: return false
        return path.contains(".env") || path.endsWith(".pem") ||
               path.endsWith(".key") || path.contains("credentials") ||
               path.contains(".ssh/")
    }
}
```

### SMART Mode Decisions

| Risk | SMART decision | Rationale |
|---|---|---|
| READ | Allow | No side effects |
| WORKSPACE_WRITE | Allow | User opted into coding mode |
| COMMAND_SAFE | Allow | Build/test in workspace is expected |
| COMMAND_MUTATE | Allow (audit log) | Common workflow, low blast radius |
| PUBLISH | Ask user | Visible to others, hard to reverse |
| DESTRUCTIVE | Ask user (warning UI) | Could lose data |
| SYSTEM | Deny | Use UI tools for Android control |
| SECRET_ACCESS | Ask user | Privacy protection |

### PolicyEngine Extension

```kotlin
// Add to PolicyEngine
fun checkCoding(
    toolName: String,
    params: JSONObject,
    workspace: WorkspaceConfig
): PolicyDecision {
    val mode = approvalMode.get()
    if (mode == ApprovalMode.ALWAYS_ASK)
        return PolicyDecision.AskUser(reason = "Approval required for all actions")
    if (mode == ApprovalMode.AUTO_APPROVE)
        return PolicyDecision.Allow

    // SMART mode
    val risk = when (toolName) {
        "file_read", "code_search" -> CodingRisk.READ
        "file_write", "file_edit" -> {
            val path = params.optString("path", "")
            if (workspace.contains(path)) CodingRisk.WORKSPACE_WRITE
            else CodingRisk.PUBLISH  // outside workspace → ask
        }
        "shell_exec" -> CommandClassifier.classify(params.getString("command"))
        else -> CodingRisk.COMMAND_MUTATE
    }

    return when (risk) {
        CodingRisk.READ, CodingRisk.WORKSPACE_WRITE,
        CodingRisk.COMMAND_SAFE, CodingRisk.COMMAND_MUTATE -> PolicyDecision.Allow
        CodingRisk.PUBLISH -> PolicyDecision.AskUser(reason = "Publishing action")
        CodingRisk.DESTRUCTIVE -> PolicyDecision.AskUser(reason = "Destructive command")
        CodingRisk.SYSTEM -> PolicyDecision.Deny("Use UI tools for Android control")
        CodingRisk.SECRET_ACCESS -> PolicyDecision.AskUser(reason = "Accessing potential secrets")
    }
}
```

### ToolRouter Integration

ToolRouter dispatches to the right policy path based on tool domain:

```kotlin
// In ToolRouter.execute(), replace the single policyEngine.check() with:
val policyDecision = if (toolName in CODING_TOOL_NAMES) {
    policyEngine.checkCoding(toolName, params, workspace)
} else {
    policyEngine.check(toolName, params, packageName, destinationPackage)
}
```

Same approval flow (AskUser → deferred → user responds). Same state machine. No changes to the ToolRouter lifecycle.

### Workspace Boundary

```kotlin
data class WorkspaceConfig(
    val root: String  // canonical absolute path
) {
    fun contains(path: String): Boolean {
        val canonical = File(path).canonicalPath
        return canonical.startsWith(root)
    }
}
```

- All file tool paths resolved to canonical form (follows symlinks)
- Canonical path must start with workspace root
- Violation → AskUser, not Deny (user may legitimately need `/etc/config`)
- Shell commands run with cwd = workspace root

### Secrets Handling

Best-effort redaction before returning tool output to the LLM:
- Scan for `(?i)(api[_-]?key|secret|password|token|credential)\s*[=:]\s*\S+`
- If found in file_read output: ask user before showing
- Known secret files (.env, .npmrc, *.pem, ~/.ssh/*): file_read triggers SECRET_ACCESS risk

This is a usability guard, not a security boundary. The user opted into coding mode knowing the LLM sees their files.

---

## 5. Agent Integration

### System Prompt Extension

Injected once at session start when coding is enabled. Appended after the role's base system prompt:

```
## Coding Environment

You have a full development environment alongside phone UI tools.

Backend: {{backend_name}} | Capabilities: {{capabilities_csv}}
Workspace: {{workspace_root}}

### Coding Tools
- shell_exec(command, cwd?, timeout_seconds?): Execute shell commands. Full bash — pipes,
  redirects, everything. Use for git, builds, tests, package management, any CLI operation.
- file_read(path, offset?, limit?): Read file contents with line numbers. Default: first 2000 lines.
- file_write(path, content): Create or overwrite a file.
- file_edit(path, old_string, new_string, replace_all?): Replace exact text in a file.
  Fails if old_string not found or not unique. Prefer over file_write for targeted edits.
- code_search(pattern, path?, regex?): Search for patterns in files. Returns file:line:content.

### When to Use Which
- Coding tools: files, commands, git, builds, scripts, data processing.
- UI tools: phone app interactions, screen navigation, tapping, typing in apps.
- Combined: scrape data via browser UI → process with a script. Download attachment in
  email app → analyze with Python. Read calendar via UI → write automation script.

### Guidelines
- Prefer file_edit for changes, file_write for new files.
- After changing code, verify: build, test, or lint as appropriate.
- For large output, redirect to file and read the relevant portion.
- Check command exit codes — don't assume success.
```

### Session Configuration

Extend `SessionConfig`:

```kotlin
// In protocol/SessionConfig.kt
data class SessionConfig(
    // ... existing fields ...
    val codingConfig: CodingConfig? = null
)

data class CodingConfig(
    val enabled: Boolean = false,
    val backendPreference: BackendPreference = BackendPreference.AUTO,
    val workspaceRoot: String? = null
)
```

When `codingConfig` is null or `enabled == false`: no coding tools registered, no prompt injection, zero behavior change. Fully backward compatible.

### Conditional Tool Registration

```kotlin
// In SessionToolingBootstrapper
private fun ToolRegistry.registerBuiltInTools(
    sessionState: AgentSessionState,
    codingConfig: CodingConfig?,
    backend: ExecutionBackend?
) {
    // Always register UI tools (unchanged)
    register(CompleteTaskTool())
    register(MobileActionTool())
    register(SystemButtonTool())
    // ... etc ...

    // Conditionally register coding tools
    if (codingConfig?.enabled == true && backend != null) {
        val workspace = WorkspaceConfig(codingConfig.workspaceRoot ?: backend.healthCheck().workspaceRoot ?: "~")
        register(ShellExecTool(backend, workspace))
        register(FileReadTool(backend, workspace))
        register(FileWriteTool(backend, workspace))
        register(FileEditTool(backend, workspace))
        register(CodeSearchTool(backend, workspace))
    }
}
```

### Role Definitions

Extend `StandaloneRoleDef` to include coding tools when enabled:

```kotlin
fun standaloneAllowedTools(codingEnabled: Boolean): Set<String> = buildSet {
    addAll(setOf(
        "mobile_action", "system_button", "wait", "open_app",
        "scratchpad", "shell", "write_todos", "complete_task",
        "ask_user", "remember_experience"
    ))
    if (codingEnabled) addAll(setOf(
        "shell_exec", "file_read", "file_write", "file_edit", "code_search"
    ))
}
```

### ToolName Extension

Add to the `ToolName` sealed class:

```kotlin
data object ShellExec : ToolName("shell_exec", isScreenChanging = false)
data object FileRead : ToolName("file_read", isScreenChanging = false)
data object FileWrite : ToolName("file_write", isScreenChanging = false)
data object FileEdit : ToolName("file_edit", isScreenChanging = false)
data object CodeSearch : ToolName("code_search", isScreenChanging = false)
```

None are screen-changing → they bypass the AppTier path entirely, routing through `checkCoding()` instead.

---

## 6. Implementation Tasks

### Phase 1: Backend Foundation

**Slug**: `backend-foundation`
**Scope**: `coding/backend/*.kt`, `protocol/SessionConfig.kt`
**Dependencies**: None
**Estimate**: 2 days

Create:
- `ExecutionBackend` interface
- `ExecRequest`, `ExecResult`, `FileContent`, `BackendHealth` data classes
- `BackendPreference` enum
- `BackendManager` with auto-selection logic
- `NativeBackend` (Android Runtime.exec with expanded limits)
- `CodingConfig` added to `SessionConfig`

Acceptance:
- `NativeBackend.exec("ls /sdcard")` returns file listing
- `NativeBackend.readFile("/sdcard/test.txt")` returns content
- `NativeBackend.healthCheck()` returns healthy with empty capabilities
- `BackendManager` returns NativeBackend when no others configured

### Phase 2: Coding Tools

**Slug**: `coding-tools`
**Scope**: `tool/impl/coding/*.kt`, `tool/ToolName.kt`, `session/SessionToolingBootstrapper.kt`
**Dependencies**: `backend-foundation`
**Estimate**: 3 days

Create:
- `ShellExecTool` — validates command non-empty, routes to `backend.exec()`
- `FileReadTool` — validates path, routes to `backend.readFile()`, formats as numbered lines
- `FileWriteTool` — validates path + content, routes to `backend.writeFile()`
- `FileEditTool` — reads file, finds old_string, replaces, writes back. Fails on not-found or ambiguous match.
- `CodeSearchTool` — composes `rg --json` or `grep -rn` via `backend.exec()`, parses structured output
- Add 5 entries to `ToolName` sealed class
- Conditional registration in `SessionToolingBootstrapper`

Acceptance:
- All 5 tools work against NativeBackend in a test workspace
- `file_edit` fails correctly when old_string not found
- `file_edit` fails correctly when old_string matches multiple locations (and replace_all=false)
- `code_search` returns structured matches
- `shell_exec("ls")` returns directory listing with exit code
- Tools NOT registered when `codingConfig.enabled == false`

### Phase 3: Coding Policy

**Slug**: `coding-policy`
**Scope**: `tool/CodingPolicy.kt`, `tool/PolicyEngine.kt`, `tool/ToolRouter.kt`
**Dependencies**: `coding-tools`
**Estimate**: 2 days

Create:
- `CodingRisk` enum
- `CommandClassifier` object with first-token pattern matching
- `WorkspaceConfig` with canonical path containment check
- `PolicyEngine.checkCoding()` method
- ToolRouter dispatch: coding tools → checkCoding(), UI tools → check()

Acceptance:
- `shell_exec("ls")` → Allow (READ)
- `shell_exec("git push origin main")` → AskUser (PUBLISH)
- `shell_exec("rm -rf /")` → AskUser with warning (DESTRUCTIVE)
- `shell_exec("am start ...")` → Deny (SYSTEM)
- `file_read(".env")` → AskUser (SECRET_ACCESS)
- `file_write` inside workspace → Allow (WORKSPACE_WRITE)
- `file_write` outside workspace → AskUser
- All UI tools unchanged

### Phase 4: Agent Integration

**Slug**: `agent-integration`
**Scope**: `agent/definition/*.kt`, `session/AgentSession*.kt`
**Dependencies**: `coding-tools`, `coding-policy`
**Estimate**: 2 days

Changes:
- System prompt extension injected when coding enabled
- `StandaloneRoleDef` tool list extended conditionally
- Backend health + capabilities injected into prompt context
- End-to-end wiring: SessionConfig.codingConfig → BackendManager → tools → policy → prompt

Acceptance:
- Agent sees coding tools in function call schema when coding enabled
- Agent does NOT see coding tools when coding disabled
- System prompt includes workspace path and backend capabilities
- Mixed conversation: UI action + shell_exec in same session works
- Approval flow works for PUBLISH/DESTRUCTIVE shell commands

### Phase 5: Termux Bridge

**Slug**: `termux-bridge`
**Scope**: `coding/backend/TermuxBackend.kt`, `tools/termux-bridge/*`, `AndroidManifest.xml`
**Dependencies**: `backend-foundation`
**Estimate**: 3 days

Create:
- `closepaw-bridge.py` (~150 lines Python stdlib)
  - ThreadingHTTPServer on 127.0.0.1:8422
  - Bearer token auth
  - Endpoints: /health, /exec, /read, /write
  - Workspace boundary enforcement on file ops
  - Process timeout + kill
  - Capability auto-detection (which python3, git, rg, node)
  - 30-minute idle watchdog
- `setup.sh` bootstrap script
- `TermuxBackend` Kotlin (OkHttp client)
- `TermuxSetupManager` — detects Termux, sends RUN_COMMAND, handles PendingIntent result for token
- Add `com.termux.permission.RUN_COMMAND` to manifest
- Add Termux package visibility query to manifest

Acceptance:
- Setup flow: installs packages, deploys bridge, exchanges token
- Bridge starts and responds to /health
- `TermuxBackend.exec("python3 --version")` returns version string
- `TermuxBackend.exec("git status")` works in a git repo
- `TermuxBackend.readFile` / `writeFile` work with workspace enforcement
- Auto-detection: BackendManager picks TermuxBackend when bridge is healthy
- Token auth: requests without valid token get 401

### Phase 6: Remote Backend

**Slug**: `remote-backend`
**Scope**: `coding/backend/RemoteBackend.kt`, settings UI
**Dependencies**: `backend-foundation`
**Estimate**: 2 days

Create:
- `RemoteBackend` — same HTTP client as TermuxBackend, different host
- Configuration: host, port, token, workspace root
- Health check polling with reconnect
- Settings UI for remote backend config
- Deployable `closepaw-bridge.py` for desktop/server (same script, different default port)

Acceptance:
- Remote exec, file read/write work over HTTPS
- Works over Tailscale private network
- BackendManager falls back to remote when Termux unavailable
- Connection loss detected and reported to user

### Phase 7: Settings UI + Polish

**Slug**: `settings-ui`
**Scope**: UI layer, settings screens
**Dependencies**: `agent-integration`, `termux-bridge`
**Estimate**: 2 days

Create:
- Coding mode toggle in session settings
- Backend status indicator (connected/disconnected/starting)
- Workspace path selector (browse Termux filesystem)
- Termux setup wizard flow
- Remote backend configuration screen
- Backend preference selector

### Stretch: Process Management

**Slug**: `process-management`
**Scope**: `tool/impl/coding/ProcessTool.kt`, bridge updates
**Dependencies**: `termux-bridge`

Add `process_start`, `process_read`, `process_stop` tools. Bridge daemon tracks spawned processes. Useful for dev servers, log tailing, long builds. Not needed for v1 — `shell_exec` with appropriate timeout covers most cases.

---

## 7. Key Decisions

| Decision | Choice | Why | Rejected alternative |
|---|---|---|---|
| Tool count | 5 (shell_exec, file_read, file_write, file_edit, code_search) | Matches Claude Code's proven minimalism. Vercel d0 showed 3.5× speedup with fewer tools. | 10+ specialized tools (git, process_*, package_install, list_files, glob) — tool sprawl hurts LLM performance |
| Backend interface | 3 methods (exec, readFile, writeFile) | Everything else composes through shell. Search, git, patch — all just exec(). | Rich backend with search/patch/process methods — over-engineered, duplicates shell |
| Edit mechanism | old_string → new_string | Proven by Claude Code. Simple for LLM, fails safely on ambiguity. | Unified diff / apply_patch — harder for LLM to compose correctly |
| Runtime protocol | HTTP daemon on localhost (not RUN_COMMAND) | RUN_COMMAND has 100KB result limit. HTTP is synchronous, unlimited output, concurrent. | RUN_COMMAND for all ops — broken for large outputs, async complexity |
| Bridge language | Python stdlib | Zero dependencies. http.server + subprocess + json. Ships with `pkg install python`. | Go (compile step), Node (less common), Shell (fragile JSON handling) |
| Token exchange | RUN_COMMAND PendingIntent result | Automatic, no storage permissions, no user interaction beyond setup tap. | Clipboard (visible), shared file (needs permissions), manual paste (friction) |
| Command classification | First-token pattern match | Simple, fast, works. Default = COMMAND_MUTATE (allow with audit), not DESTRUCTIVE. | Full shell parsing — impossible in general, diminishing returns |
| Policy integration | Extend existing PolicyEngine with checkCoding() | Same approval flow, same ToolRouter lifecycle. Minimal code change. | Separate CodingPolicyEngine — unnecessary duplication |
| Existing ShellTool | Keep unchanged | Different purpose (Android inspection), different policy (AppTier), different restrictions. | Expand ShellTool — would compromise its safety guarantees |
| NativeBackend | Include as fallback | Testing surface before Termux ready. Works offline. Limited but better than nothing. | Skip — complicates implementation for rare use case. Worth it for testing. |

## 8. What This Unlocks

ClosePaw becomes a **mobile AI agent that can both interact with phone apps AND execute code** — a capability surface that neither Claude Code (no phone UI) nor existing Android assistants (no coding) can provide.

Combined workflows:
- "Open email, find the CSV attachment, download it, write a Python script to analyze it, tell me the summary"
- "Clone this repo, fix the failing test, run the suite, create a PR"
- "Take a screenshot of this crash, search the codebase for the error message, suggest a fix"
- "Read my calendar events for this week, generate a markdown summary, save it to my project"

The architecture also enables domain-specific agents built on the same coding foundation:
- **OpenClaw**: open-source agent — git clone, code review, PR creation
- **Hermes**: messaging agent — compose messages using templates/scripts, schedule via cron
- **Data agent**: download, transform, analyze data using Python/pandas

The coding tools are the base layer. Domain agents compose them with UI tools and domain-specific prompts.
