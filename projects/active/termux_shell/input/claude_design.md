# ClosePaw Coding Agent — Claude Design

## Problem Statement

ClosePaw currently operates exclusively through Android's Accessibility Service to automate UI interactions. The goal is to extend it into a general-purpose coding agent — capable of writing scripts, executing shell commands, manipulating files, using git, and running code — similar to Claude Code or Codex, but running on/from an Android device.

## First-Principles Analysis

### What Android Actually Is

Android IS Linux. The kernel, process model, filesystem, and networking are all standard Linux. What Android removes is the **userland toolchain** (Python, Node, git, compilers, package managers) and adds **sandboxing** (per-app isolation, scoped storage, SELinux).

### What a Coding Agent Actually Needs

Decomposing Claude Code's capabilities into primitives:

| Capability | Primitive |
|---|---|
| Read | read bytes from a path |
| Write | write bytes to a path |
| Edit | diff-patch a file (old_string → new_string) |
| Bash | spawn process, capture stdout/stderr, return exit code |
| Search | grep content / glob paths |
| Git | shell commands to git binary |
| LSP | stdio to a language server process |
| WebFetch | HTTP GET + parse |

Items 1-3 are trivial on any OS. **Bash (arbitrary shell execution) is the linchpin** — everything else (git, grep, LSP, package install) is just a specific shell command. Solve arbitrary shell execution and you solve everything.

## Feasibility Analysis: Four Paths

### Path A: Expand Native Android Shell

Android ships toybox. The current `ShellTool` blocks pipes, redirects, and most useful commands.

- Remove metacharacter restrictions, expand command allowlist
- Add file write/edit via Kotlin File APIs
- Increase timeout (10s → 120s) and output cap (4KB → 64KB)

**Ceiling**: `cat`, `ls`, `grep`, `mkdir`, `cp`, `mv`, `sort`, `head`, `tail`, maybe `sed`/`awk`. No Python, Node, git, compilers, or package management. A 1970s Unix.

**Verdict**: Useful as a fallback layer, not sufficient as primary strategy.

### Path B: Termux as Execution Engine ⭐ (Recommended Primary)

Termux is a real Linux distribution inside an Android app. Has `apt`, Python, Node, Go, Rust, git, SSH, compilers — the full stack.

**Integration method — Local HTTP daemon:**
- Run a lightweight server in Termux accepting `{command, cwd, env, timeout}`
- Returns `{stdout, stderr, exit_code}`
- ClosePaw connects via `http://localhost:PORT`
- Full bidirectional structured I/O

**Ceiling**: Everything Claude Code can do, on-device. Limited only by device CPU/RAM and Termux's package repo (which is extensive).

**Tradeoff**: User must install Termux and run a one-time setup script.

### Path C: Embedded Runtime

Bundle a scripting engine in ClosePaw:
- **QuickJS** — JS engine, ~200KB, filesystem access, no npm
- **Chaquopy** — Python via Gradle plugin, pip for pure-Python packages
- **Lua** — Tiny, fast, limited stdlib

**Ceiling**: Script execution and file operations, but isolated from system tools. No git, compilers, system package management.

**Verdict**: Good supplement for quick in-app scripting, not a primary strategy.

### Path D: Remote Execution ⭐ (Recommended Secondary)

ClosePaw connects to a remote machine via SSH or custom API. The remote machine has the full toolchain.

**Ceiling**: Unlimited — full power of the remote machine.

**Tradeoff**: Requires network. Can't work offline. Adds latency. But Tailscale infrastructure already exists.

## Recommended Architecture: Path B+D Hybrid

### Core Principle: Pluggable Execution Backend

The LLM sees **one uniform tool interface**. The execution backend is swappable. The agent doesn't know or care whether commands run in Termux or on a remote server.

### Tool Layer (What the LLM Sees)

```
New "Coding" ToolSpecs:
  shell_exec(command, cwd?, timeout?)     → {stdout, stderr, exit_code}
  file_read(path, offset?, limit?)        → content
  file_write(path, content)               → success/error
  file_edit(path, old_string, new_string) → success/error
  code_search(query, path?, regex?)       → matches[]
  git(args)                               → output
  web_fetch(url, prompt?)                 → content
```

These are registered as standard `ToolSpec` implementations alongside existing UI tools.

### Backend Abstraction

```kotlin
interface ExecutionBackend {
    val name: String
    val status: BackendStatus  // Connected, Disconnected, Unavailable

    suspend fun exec(
        command: String,
        cwd: String? = null,
        env: Map<String, String>? = null,
        timeout: Duration = 2.minutes
    ): ExecResult

    suspend fun readFile(path: String, offset: Long? = null, limit: Int? = null): FileContent
    suspend fun writeFile(path: String, content: ByteArray): WriteResult
    suspend fun listDir(path: String): List<DirEntry>
    suspend fun healthCheck(): Boolean
}

data class ExecResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val truncated: Boolean = false
)

enum class BackendStatus { Connected, Disconnected, Unavailable }
```

### Backend Implementations

#### TermuxBackend

```
ClosePaw App
    │ HTTP (localhost:8422)
    ▼
Termux Daemon (closepaw-bridge)
    │ Process.exec()
    ▼
Full Linux userland (bash, python, git, node, ...)
```

The Termux daemon is a minimal HTTP server (~100 lines Python or shell):

```
POST /exec   {command, cwd, env, timeout} → {stdout, stderr, exit_code}
GET  /file   ?path=...&offset=...&limit=... → file content
PUT  /file   {path, content} → success
GET  /health → {status, capabilities}
```

Discovery: on session start, probe `localhost:8422/health`. If reachable → use Termux. If not → fall back.

#### RemoteBackend

```
ClosePaw App
    │ SSH (JSch) or HTTPS
    ▼
Remote machine (desktop, cloud VM)
    │
    ▼
Full Linux/macOS environment
```

Configuration: host, port, key/password, working directory. Could leverage Tailscale IPs.

#### NativeBackend (Fallback)

Uses Android's `Runtime.exec()` with expanded toybox commands. Limited but works offline without Termux.

### Backend Selection Strategy

```
1. If TermuxBackend.healthCheck() → use Termux
2. Else if RemoteBackend.healthCheck() → use Remote
3. Else → use Native (limited mode, warn user)
```

User can also pin a specific backend in settings.

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────┐
│                    ClosePaw Agent                        │
│                                                         │
│  ┌─────────────────────┐  ┌──────────────────────────┐  │
│  │   UI Tools          │  │   Coding Tools           │  │
│  │   ───────────────   │  │   ────────────────────   │  │
│  │   mobile_action     │  │   shell_exec             │  │
│  │   system_button     │  │   file_read              │  │
│  │   open_app          │  │   file_write             │  │
│  │   wait              │  │   file_edit              │  │
│  │   ...               │  │   code_search            │  │
│  │                     │  │   git                    │  │
│  └─────────┬───────────┘  └───────────┬──────────────┘  │
│            │                          │                  │
│            ▼                          ▼                  │
│  ┌─────────────────┐      ┌─────────────────────────┐   │
│  │ Accessibility    │      │ ExecutionBackend        │   │
│  │ Platform         │      │ (pluggable interface)   │   │
│  └─────────────────┘      └─────┬──────┬──────┬─────┘   │
│                                 │      │      │          │
└─────────────────────────────────┼──────┼──────┼──────────┘
                                  │      │      │
                    ┌─────────────┘      │      └──────────────┐
                    ▼                    ▼                     ▼
             ┌────────────┐    ┌──────────────┐      ┌──────────────┐
             │  Termux    │    │   Remote     │      │   Native     │
             │  Backend   │    │   Backend    │      │   Backend    │
             │  (local)   │    │   (SSH/API)  │      │   (toybox)   │
             └────────────┘    └──────────────┘      └──────────────┘
```

## Security Model

### Current Model Doesn't Fit

The existing PolicyEngine is **app-tier based** (which app is in foreground). Coding tools aren't screen-bound — they operate on a filesystem, not a UI. Need a new policy dimension.

### Proposed: Coding Permission Layer

```
1. "Coding Mode" — explicit user opt-in per session
   - Toggle in session config: "Allow coding tools"
   - When off, coding tools are not registered (LLM doesn't see them)

2. Per-command risk classification (like Claude Code):
   - READ operations (file_read, code_search, ls) → auto-allow
   - WRITE operations (file_write, file_edit) → auto-allow within workspace
   - SHELL operations → tiered:
     - Safe commands (grep, cat, ls, git status/log/diff) → auto-allow
     - Build/test commands (gradlew, pytest, npm test) → auto-allow
     - Mutating commands (git commit, rm, mv) → require confirmation
     - Destructive commands (git push --force, rm -rf) → always ask

3. Workspace boundary
   - Define a working directory (e.g., ~/projects/myapp/)
   - File operations outside workspace require confirmation
   - Shell commands run with cwd set to workspace
```

### Termux Daemon Security

The daemon listens on localhost only (no network exposure). Additional protections:
- Shared secret token (generated at setup, stored in both apps)
- Command logging for audit
- Optional: ClosePaw can verify Termux package signature

## System Prompt Extension

When coding tools are available, append to the agent's system prompt:

```
## Coding Environment

You have access to a full development environment in addition to phone UI tools.

### Available Coding Tools
- shell_exec: Execute shell commands (bash). Full Linux environment with Python, Node, git, etc.
- file_read: Read file contents with optional offset/limit.
- file_write: Write content to a file (creates or overwrites).
- file_edit: Edit a file by replacing old_string with new_string.
- code_search: Search for patterns in files (regex supported).
- git: Execute git commands.

### Working Directory
Current workspace: {{workspace_path}}

### Guidelines
- Use coding tools for file operations, scripting, and development tasks.
- Use UI tools (mobile_action, etc.) for phone app interactions.
- You can combine both: e.g., download a file via the browser UI, then process it with a shell script.
- For file edits, prefer file_edit (surgical) over file_write (full replace).
- Check command output before proceeding — don't assume success.
- For long-running commands, set appropriate timeouts.

### Backend
Connected via: {{backend_name}} ({{backend_status}})
```

## Implementation Plan

### Phase 1: Tool Layer Foundation (2 days)

1. Define `ExecutionBackend` interface in `tool/backend/`
2. Implement `NativeBackend` (expanded toybox, quick to build)
3. Implement coding `ToolSpec`s: `ShellExecTool`, `FileReadTool`, `FileWriteTool`, `FileEditTool`, `CodeSearchTool`
4. Register in `SessionToolingBootstrapper` (gated by coding mode flag)
5. Add to `StandaloneAgentDef.allowedTools` with system prompt extension

### Phase 2: Termux Backend (3 days)

1. Write `closepaw-bridge` daemon (Python, ~150 lines):
   - HTTP server on localhost:8422
   - Endpoints: `/exec`, `/file`, `/health`
   - Auth via shared token
   - Process management with timeout/kill
2. Setup script: `pkg install python && pip install flask && ...`
3. Implement `TermuxBackend` in Kotlin (OkHttp client)
4. Auto-discovery on session start
5. UI indicator showing backend status

### Phase 3: Remote Backend (2 days)

1. Implement `RemoteBackend` using JSch (SSH) or custom HTTPS
2. Configuration UI: host, port, auth method, working directory
3. Connection management with reconnect logic
4. Test with Tailscale peer (qiguo@desktop)

### Phase 4: Agent Intelligence (ongoing)

1. Dual-mode system prompt refinement
2. Context injection: working directory listing, git status, recent changes
3. Smart capability detection: "Python available", "git available", etc.
4. Cross-domain workflows: UI + coding tool chains

## What This Unlocks

ClosePaw becomes something that doesn't exist yet: **a mobile AI agent that can both interact with phone apps AND write/execute code.**

Example workflows:
- "Open email, find the CSV attachment, download it, write a Python script to analyze it, text me the summary"
- "Clone this repo, fix the bug, run the tests, create a PR"
- "Read my calendar, then write a script that backs up my project before the deadline"
- "Take a screenshot of this UI, write a Playwright test that validates it"

The combination of phone UI tools + coding tools in a single agent is a unique capability surface that neither Claude Code (no phone UI) nor existing Android assistants (no coding) can match.

## Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Backend abstraction | Interface + multiple impls | LLM shouldn't know/care where execution happens |
| Primary backend | Termux | On-device, offline-capable, full Linux |
| Secondary backend | Remote SSH | Unlimited power when connected |
| Security model | Coding mode toggle + command-risk tiers | Separates UI policy from shell policy |
| Tool granularity | Separate tools (read/write/edit/exec) | Matches Claude Code's proven tool design, clear intent signals |
| Daemon protocol | HTTP JSON on localhost | Simple, debuggable, no native dependencies |
| File editing | old_string → new_string diff | Proven by Claude Code to be extremely effective for LLM code editing |

## Open Questions

1. **Output streaming**: Shell commands can produce megabytes. Pagination? Truncation with "continue" tool? Stream to temp file and return path?
2. **Long-running processes**: Background execution support? Monitor tool equivalent?
3. **Multi-file context**: How much workspace context to inject per turn? Full tree? Git-aware?
4. **Termux distribution**: Ship a setup APK/script? F-Droid integration? Could we bundle Termux's bootstrap within ClosePaw?
5. **Coding-only mode**: Should there be a session mode where UI tools are disabled and only coding tools are available? (Useful for pure dev tasks where phone UI is irrelevant.)
