# ClosePaw General Agent via Termux Backend — 设计

## 目标

在现有 UI 自动化基础上，为 ClosePaw 增加 `termux_shell` tool，通过 Termux bridge daemon 获得完整 Linux shell 能力，使其成为通用 agent。

- 保持 Android UI 自动化（Accessibility Service、mobile_action、system_button 等）不变。
- 新增 `termux_shell` tool：完整 bash，支持 pipe/redirect，可用 python/git/node/gcc 等全部 Termux toolchain。
- 现有 `shell` tool 保持原样——它是受限的 toybox 设备检查工具，不是 workspace runtime。
- v1 不做独立的 file_read/write/edit/search/git tools——全部通过 shell 完成。
- 所有三个 agent role（Standalone、Planner、Executor）在 Termux 可用时都获得 `termux_shell`。

产品目标不是"做一个 coding 功能"。Coding agent 的原语就是 general agent 的基座层：跑命令、读写文件、搜索数据、用 git、处理 artifact。Domain-specific agent（OpenClaw、Hermes 等）建立在这个基座之上。

**成功标准**：一个 session 可以同时使用 UI tools 和 `termux_shell`。LLM 清楚两个 shell 的能力边界。混合 UI+workspace 任务可以组合。

## v1 Scope

**做：**
- Bridge daemon（Python stdlib，~150 行）
- `TermuxShellTool` implements `ToolSpec`（一个新 tool）
- `TermuxBridgeManager`（lifecycle-stable controller，owns state + setup + health）
- Termux detection + 分步 bootstrap flow（RUN_COMMAND intent）
- System prompt 扩展（Termux 可用时注入 `termux_shell` 描述）
- `shell` tool description 微调（标明 toybox 能力边界）
- Settings UI：Agent Behavior 页新增 Termux Shell 条目
- `network_security_config.xml` 添加 localhost cleartext 例外
- Manifest：Termux package visibility + RUN_COMMAND service query
- Bridge script 打包为 `app/src/main/res/raw/closepaw_bridge_py`

**不做：**
- 独立 file tools（read/write/edit/grep/find/ls）——通过 shell 完成
- 独立 git tool——通过 `termux_shell` 跑 git 命令
- Remote backend（SSH/HTTPS）——future
- NativeBackend 扩展——现有 `shell` 已经是 native fallback
- Shizuku/root shell、内嵌 Python/JS/WASM runtime
- Process management UX、无需审批的自动 package install
- Health check capabilities 动态探测
- Security/policy 加固——deferred（见 `projects/active/shell_security/`）
- `/v1/cancel` endpoint——v1 靠 timeout + client disconnect
- Bridge token auth——v1 靠 loopback-only binding（security deferred）
- Manual setup fallback——v1 要求 RUN_COMMAND 可用

## System Invariants

1. **Android UI 控制只能通过 UI tools 和 UI policy。** `termux_shell` 不能绕过 app-tier 限制。
2. **RUN_COMMAND 仅用于 bootstrap。** 100KB Binder result limit 使其无法用于运行时通信。
3. **Failure 必须显式。** Bridge 不可用、timeout、output 截断都是 machine-readable error。
4. **两个 shell tool 共存但完全独立。** 不同的能力、不同的执行路径。
5. **Workspace 在 Termux home `~/closepaw/workspace/`。** Android 11+ scoped storage 使 `/sdcard` 跨 app 共享不可靠。需要分享文件时 `cp` 到 `/sdcard/Download/`。
6. **Security/policy deferred。** v1 走 auto-allow（`isScreenChanging = false`），见 `projects/active/shell_security/`。
7. **Single source of truth。** `TermuxBridgeManager` 拥有 bridge state，tool registration 和 prompt injection 都读它。
8. **Termux availability 在 session 创建时固定。** `SessionServices.create()` 捕获 snapshot，整个 session 生命周期内不变（包括 Hot Idle follow-up tasks）。Setup 变更生效于**下一个 session**（新 task 或 reload），不是下一个 follow-up task。Settings UI 提示"生效于下个 session"。
9. **非零 exit code 不是 tool failure。** 命令 exit code 是给 LLM 的信息（`grep` 返回 1、build 失败有 stderr），不中断同一 turn 的后续 tool call。`ToolExecutionResult.Failure` 仅用于 transport/protocol 错误。
10. **`termux_shell` 不参与 turn reordering。** `TurnToolPolicy` 中只有 cognitive tools（scratchpad、write_todos）可被 hoist 到 screen-changing tools 前面。`shell`、`termux_shell`、`ask_user` 保持 LLM 返回的顺序。
11. **Bridge 同一时间只执行一个 `/v1/exec`。** Busy 时立即返回 HTTP 409，不排队。Health check 不受限。

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                    ClosePaw Agent                     │
│                                                      │
│  ┌───────────────┐       ┌────────────────────────┐  │
│  │  UI Tools      │       │  Workspace Tools       │  │
│  │  ────────────  │       │  ──────────────────    │  │
│  │  mobile_action │       │  termux_shell          │  │
│  │  system_button │       │  (完整 Linux bash)      │  │
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

## 1. 两个 Shell Tool 对比

| | `shell`（现有） | `termux_shell`（新增） |
|---|---|---|
| **实现** | `Runtime.exec()` in ClosePaw 进程 | HTTP → Termux bridge daemon → `subprocess.Popen()` |
| **Shell** | Android toybox | 完整 bash |
| **能力** | ls, cat, stat, grep, wc, head, tail 等。不支持 pipe/redirect。 | 完整 GNU coreutils + pipe/redirect + process control。可用 toolchain 按 Termux 安装的包而定 |
| **文件访问** | ClosePaw app-private + `/sdcard` | Termux home + `/sdcard` |
| **Timeout** | 10s | 120s（可配置） |
| **Output cap** | 4KB | 64KB（超限写 artifact，返回 `output_ref`） |
| **Policy** | Auto-allow（non-screen-changing） | Auto-allow（same，security deferred） |
| **Turn ordering** | 保持 LLM 顺序（不 hoist） | 保持 LLM 顺序（不 hoist） |
| **Exit code** | 透传给 LLM | 透传给 LLM。非零 exit code = `ToolExecutionResult.Success`，不是 `Failure` |
| **Description** | "Android toybox shell。可用命令有限（ls, cat, stat, grep 等），不支持 pipe/redirect。需要完整 toolchain（python/git 等）时请用 termux_shell。" | "完整 Linux bash shell（via Termux）。支持 pipe、redirect、所有 GNU coreutils。可用 toolchain：python3, node/npm, git, gcc, cargo, go 等（按安装的包而定）。工作目录：`~/closepaw/workspace/`。需要分享给其他 app 时 cp 到 /sdcard/Download/。" |

v1 不做独立 file tools。全部通过 shell 完成。eval 驱动后续扩展。

---

## 2. Termux Bridge Daemon

### 设计

基于 Python stdlib 的 daemon（约 150 行），`ThreadingHTTPServer`。不需要 pip 依赖。

```
Termux process
├── closepaw-bridge (HTTP server, 127.0.0.1:18422)
│   ├── POST /v1/exec     → subprocess.Popen(["bash", "-c", command])
│   └── GET  /v1/health   → status + version + bridge identity
└── (用户的 shell session，不受影响)
```

所有响应 JSON。Loopback only（127.0.0.1，不绑 0.0.0.0）。v1 不做 token auth——loopback binding 已阻止网络访问，设备上其他 app 能连 localhost 是 security concern，归入 deferred task。

**固定 port 18422**。Bridge 启动时通过 pidfile 判断 port ownership：
- Pidfile 存在 → check 是否为 ClosePaw bridge（读 pidfile 中的 identity）→ 是则 kill 旧进程 → rebind
- Port 被未知进程占用 → 返回 typed error `port_in_use`，不 blind kill
- Bridge 写 pidfile 含 `{ pid, port, version, identity: "closepaw-bridge" }`

**同一时间只执行一个 `/v1/exec`。** Bridge 内部用 lock 序列化。后续请求排队等待。Health check 不受限。避免并发 shell 命令在 workspace 中 race。

### Localhost Cleartext 例外

```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```

### Bridge 打包与版本

**Canonical source**: `tools/termux-bridge/closepaw_bridge.py`（开发、host test、编辑都在这）。

Gradle copy task 将其打包为 `app/src/main/res/raw/closepaw_bridge_py`。CI test 验证：raw resource 存在 + 字节一致 + 含预期 version line。

版本常量 `BRIDGE_VERSION = "1"` 定义在 bridge.py 顶部，出现在 `/v1/health` response 中。`TermuxBridgeManager` 部署时读 raw resource，health check 时比对版本，不匹配则 re-deploy。

### API

**Health check：**
```
GET /v1/health
→ 200 { "status": "ok", "version": "1", "identity": "closepaw-bridge" }
```

**执行命令：**
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

- `subprocess.Popen(["bash", "-c", command])`，process-group kill on timeout
- Client disconnect → bridge kill subprocess（v1 cancellation）
- cwd 默认 workspace root；null/missing → workspace root
- stdout/stderr 独立 truncate：各自有 `_truncated` 和 `_ref` 字段。超限部分写入 `~/.closepaw/artifacts/<call_id>_stdout` / `_stderr`
- **非零 exit code 返回 HTTP 200**，exit_code 字段携带值。HTTP 非 200 仅用于 transport/protocol 错误。
- **同一时间只执行一个 `/v1/exec`**。如果已有命令在跑，立即返回 `{ "error": "busy" }`（HTTP 409）。不排队等待，避免 timeout 歧义。Health check 不受限。

**Error codes**：`workspace_escape`, `timeout`, `invalid_request`, `internal_error`, `port_in_use`, `busy`

### Bridge Exec Loop（Python 实现契约）

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

        # 用两个 thread drain stdout/stderr，避免 pipe buffer deadlock
        stdout_reader = threading.Thread(target=drain, args=(proc.stdout, stdout_buf, max_bytes))
        stderr_reader = threading.Thread(target=drain, args=(proc.stderr, stderr_buf, max_bytes))
        stdout_reader.start(); stderr_reader.start()

        # 主循环：poll process + detect client disconnect
        while proc.poll() is None:
            if not client_connected():  # wfile.write probe 或 select on socket
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

        # 如果 output 超限，drain() 已写入 artifact 文件
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
    """逐块读取，超 max_bytes 后写入 artifact 文件。"""
    for chunk in iter(lambda: pipe.read(4096), b''):
        if buf.total < max_bytes:
            buf.append(chunk)
        else:
            buf.write_to_artifact(chunk)  # overflow → ~/.closepaw/artifacts/<call_id>
```

关键保证：
- **无 pipe deadlock**：stdout/stderr 用独立 thread drain，不等 process 退出再读
- **Client disconnect 主动检测**：主循环中 poll socket 可写性，disconnect 时立即 killpg
- **Output cap 精确**：drain 线程逐块计数，超限写 artifact 文件
- **Timeout 不被 exec lock 放大**：busy 立即返回 409，不排队

### Bootstrap Flow（分步幂等，probe-derived readiness）

每步的完成状态从 **实际 probe 推导**，不仅仅记住"step completed"。Termux 被 clear data 或包被删后，probe 会自动检测到不一致。

```
Step 1: Probe
  RUN_COMMAND echo "CLOSEPAW_PROBE=ok"
  验证：Termux 初始化完成、allow-external-apps、RUN_COMMAND permission、PendingIntent 可返回
  → 失败时显示具体原因

Step 2: Install packages
  RUN_COMMAND: pkg install -y python git ripgrep
  验证：RUN_COMMAND which python3 && which git && which rg
  → 幂等，已装不重装

Step 3: Deploy bridge
  RUN_COMMAND:
    部署 bridge.py（通过 EXTRA_STDIN 传入，base64 编码避免 shell quoting 问题）
    mkdir -p ~/closepaw/workspace ~/.closepaw/artifacts ~/.closepaw/logs
    echo "CLOSEPAW_DEPLOY=ok"
  → 验证 stdout 含 "CLOSEPAW_DEPLOY=ok"

Step 4: Start bridge
  RUN_COMMAND: python3 ~/.closepaw/bridge.py &
  轮询 /v1/health 直到就绪
  → Health 200 + version match → 状态变为 "Ready"
```

**Manifest 需求：**
- `com.termux.permission.RUN_COMMAND` permission
- `<package android:name="com.termux" />`
- `<intent><action android:name="com.termux.RUN_COMMAND" /></intent>` in queries

**v1 不做 manual setup fallback。** RUN_COMMAND 不可用时显示 "Termux Shell requires RUN_COMMAND permission" 并引导用户开启。

### TermuxBridgeManager（Process-Wide Controller）

项目无 Hilt/Dagger。使用 Holder pattern（类似现有 `AuthStoreHolder`）：

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

    suspend fun runSetup()           // 分步 bootstrap，内部 mutex 序列化
    suspend fun healthCheck()        // probe bridge，更新 state
    suspend fun restart()            // kill + start bridge
    fun setEnabled(enabled: Boolean) // toggle 存 AppSettingsStore
    fun snapshot(): TermuxCapabilitySnapshot
}
```

### RUN_COMMAND Adapter Contract

```kotlin
class TermuxRunCommandAdapter(private val appContext: Context) {
    // 发送 RUN_COMMAND 并等待 PendingIntent result
    suspend fun execute(
        command: String,
        stdin: String? = null,         // EXTRA_STDIN（bridge.py 部署用 base64 编码）
        background: Boolean = true,
        timeoutMs: Long = 60_000       // 每步 timeout
    ): RunCommandResult

    // 每次调用生成唯一 requestId，忽略 stale/late result
    // PendingIntent result 通过 BroadcastReceiver 接收
    // stdin 中的 bridge.py 内容用 base64 编码传输，避免 shell quoting 问题：
    //   echo '<base64>' | base64 -d > ~/.closepaw/bridge.py
}

data class RunCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
)

// 每个 bootstrap step 有明确的 success marker：
// Step 1 (probe):    stdout 含 "CLOSEPAW_PROBE=ok"
// Step 2 (install):  exitCode == 0 且 which 验证通过
// Step 3 (deploy):   stdout 含 "CLOSEPAW_DEPLOY=ok"
// Step 4 (start):    health check 200 with correct version

// 失败时 RunCommandResult 映射到 NeedsSetup(reason)：
// - exitCode != 0 → 看 stderr 提取原因
// - stdout 缺少 expected marker → 格式错误
// - timeout → Termux 无响应
// - SecurityException → permission 缺失
// - ActivityNotFoundException → Termux 未安装或 allow-external-apps 未开
```

### Settings UI

```
Termux Shell          [开关]
  Ready ✓
  Needs Setup — 点击设置 →
  Not Installed — 点击安装 →
```

底层 typed reason 支持 debug（permission missing、allow-external-apps missing、packages missing、bridge outdated、health timeout、port_in_use 等）。Setup 变更提示"生效于下个 session"。

### Lifecycle

- Session 启动时读 `TermuxBridgeManager.state` snapshot（fixed-at-task-start）
- 后台每 30s 探测 `/v1/health`，更新 `state`（影响下个 task，不影响当前）
- Bridge 30 分钟 idle watchdog（无请求自动退出省电）
- Bridge 挂掉时通过 RUN_COMMAND 按需重启
- Version mismatch 时 re-deploy bridge.py from raw resource

---

## 3. Agent Integration

### Session Capability Snapshot（Fixed-at-Task-Start）

Session 创建时（`SessionServices.create()`）从 `TermuxBridgeManager` 捕获 **immutable snapshot**，整个 session 生命周期内不变（包括 Hot Idle follow-up tasks）：

```kotlin
data class TermuxCapabilitySnapshot(
    val available: Boolean,   // bridge ready AND user enabled
    val enabled: Boolean,     // settings toggle（独立于 readiness，不被 health polling 覆盖）
    val status: TermuxBridgeStatus  // 用于 debug/logging
)

// SessionServices.create() 中一次性捕获：
val termuxSnapshot = bridgeManager.snapshot()

// 以下决策全部读同一个 snapshot：
// 1. Tool registration（available → register TermuxShellTool）
// 2. Prompt injection（available → append workspace prompt section）
// 3. Role allow-list adjustment（available → add "termux_shell"）
// 4. Executor timeout adjustment（available → 150s）
// 5. Checkpoint/reload → 新 task boundary，捕获 fresh snapshot（不序列化旧 snapshot）
```

Snapshot 穿透路径：`SessionServices.create()` 捕获 snapshot → 存在 `SessionServices` 上 → `SessionAgentRunner.start()` 用它构建 runtime role config → `AgentRoleRuntime.resolve(agentDef, snapshot)` 返回调整后的 allowedTools + timeout + prompt → `IsolatedSubAgentRunner` 同样读 resolved role。

```kotlin
// 不修改静态 AgentRoleDef，运行时 resolve
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

Prompt injection 跟着 tool exposure 走：如果 `termux_shell` 被 `excludedTools` 排除，workspace prompt section 也不注入。

后台 health polling 更新 `TermuxBridgeManager.state`，但不影响当前 session。下个 session（新建或 reload）捕获 fresh snapshot。

### Tool Result Semantics

`TermuxShellTool` 的 result mapping：

| Bridge response | ToolExecutionResult |
|---|---|
| HTTP 200, any exit_code | `Success`（stdout/stderr/exit_code 全部作为 tool output 给 LLM） |
| HTTP 200, `timed_out=true` | `Success`（partial stdout/stderr + `timed_out=true`，LLM 看到 timeout 信息自行决策） |
| HTTP 409 busy | `Failure`（bridge busy，LLM 可 retry） |
| HTTP 4xx/5xx | `Failure`（transport/protocol error） |
| Connection refused / timeout | `Failure`（bridge not available） |
| OkHttp IO error | `Failure`（network error） |

`grep` 返回 1、build 失败有 stderr → 都是 `Success`，LLM 看到 exit_code 和 stderr 自己判断。不中断同一 turn 的后续 tool call。

### Cancellation（OkHttp Wiring）

`TermuxShellTool.execute()` 使用 cancellable OkHttp call：

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

Coroutine cancel → `Call.cancel()` → TCP connection 断开 → bridge 检测 client disconnect → kill subprocess。不需要等 120s timeout。

### Timeout Ladder

```
用户请求 timeout_seconds（tool param）   默认 120s，上限 120s
  ↓
Bridge 收到 timeout_ms                  = timeout_seconds * 1000
  ↓ 超时则 process-group kill，返回 HTTP 200 + timed_out=true
OkHttp read/call timeout                = timeout_ms + 5s grace（125s）
  ↓ 超时则 IOException → ToolExecutionResult.Failure
Executor role timeout                   = 150s（当 termux_shell enabled）
  ↓ 超时则 sub-agent 被 kill
```

OkHttp timeout 必须显式配置为 `timeout_ms + grace`，不能用 OkHttp 默认值（10s read timeout 会在长命令完成前断开）。

### Bridge Timeout Response

Timeout 是命令级输出（HTTP 200），不是 transport failure。LLM 看到 partial output + `timed_out=true` 可以决定下一步。

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

Kotlin 侧 mapping：`timed_out=true` → `ToolExecutionResult.Success`（带 timeout 信息）。不中断同一 turn 后续 tool call。

### Turn Ordering

`TurnToolPolicy` 修改：只有 **cognitive tools**（`scratchpad`、`write_todos`、`remember_experience`）可以被 hoist 到 screen-changing tools 前面。`shell`、`termux_shell`、`ask_user`、`activate_skill` 保持 LLM 返回的顺序执行。

```kotlin
private val HOISTABLE_TOOLS = setOf("scratchpad", "write_todos", "remember_experience")

fun reorder(toolCalls: List<ToolCallRequest>): List<ToolCallRequest> {
    val hoistable = toolCalls.filter { it.name in HOISTABLE_TOOLS }
    val rest = toolCalls.filter { it.name !in HOISTABLE_TOOLS }
    return hoistable + rest  // cognitive first, then preserve LLM order
}
```

### System Prompt Extension

Termux 可用时注入（task 启动时 `bridgeManager.state` 为 Ready 且 enabled）：

```
## Workspace Shell

你有一个 termux_shell tool，提供完整 Linux bash 环境。
工作目录: ~/closepaw/workspace/

### termux_shell
完整 Linux bash shell（via Termux）。支持 pipe、redirect、所有 GNU coreutils。
可用 toolchain：python3, node/npm, git, gcc, cargo, go 等（按安装的包而定）。
工作目录：~/closepaw/workspace/。输入输出文件放在此目录下。
需要分享给其他 app 时 cp 到 /sdcard/Download/。

### 何时用哪个 shell
- termux_shell：需要完整 toolchain（python/git/node 等）或 pipe/redirect 时
- shell：快速检查设备文件（ls/cat/stat），不需要 Termux toolchain 时

### 何时用 UI tools vs shell
- UI tools（mobile_action 等）：手机 app 交互、屏幕导航
- termux_shell：文件/命令/git/build/脚本
- 组合：浏览器 UI 抓数据 → termux_shell 处理。邮件附件 → python 分析。

### 准则
- 不要用 termux_shell 控制 Android UI、绕过 app 限制。
- 输入输出文件放 ~/closepaw/workspace/ 下。
```

### Shell Tool Description 更新

```
Android toybox shell。可用命令有限（ls, cat, stat, grep, wc, head, tail,
sort, uniq, diff, du, df, file, touch, mkdir, cp, mv, rm, chmod 等），
不支持 pipe (|)、redirect (>/<)、命令替换 ($())。
需要完整 toolchain（python/git 等）时请用 termux_shell。
```

### Conditional Tool Registration

```kotlin
private fun ToolRegistry.registerBuiltInTools(
    sessionState: AgentSessionState,
    termuxSnapshot: TermuxCapabilitySnapshot  // immutable, captured at session start
) {
    // 始终注册 UI tools（不变）
    register(CompleteTaskTool())
    register(MobileActionTool())
    register(SystemButtonTool())
    register(WaitTool())
    register(OpenAppTool())
    register(ShellTool())
    register(WriteTodosTool(sessionState.todos))
    register(ScratchpadTool(sessionState.scratchpad))

    // 读 snapshot，不读 live state
    if (termuxSnapshot.available) {
        register(TermuxShellTool(bridgeManager))
    }
}
```

### Role Definitions

所有三个 role 在 Termux 可用时都获得 `termux_shell`：

- **Standalone**：获得 `termux_shell`（BASIC mode 直接使用）
- **Planner**：获得 `termux_shell`（PRO mode 下 **Planner 自己直接跑** workspace 命令，不 delegate 给 Executor）
- **Executor**：获得 `termux_shell`（Executor 可以执行 planner 分配的 workspace 任务）。**Executor timeout 从 30s 提升到 150s**（当 `termux_shell` exposed 时）

**PRO Mode Delegation Contract**：
- Planner 在需要 UI 操作时仍然 delegate 给 Executor（"delegate ONE atomic UI action"）
- Planner 在需要 workspace 操作时 **自己直接调 `termux_shell`**，不 delegate
- 这意味着 Planner prompt 需要补充："对于 workspace 命令（termux_shell），直接执行而不是 delegate"
- `delegate_task` tool description 保持 "delegate ONE atomic UI action" 不变
- 如果后续 eval 发现 Planner 自己跑 shell 效果不好，可以改为 delegate workspace 任务给 Executor

---

## 4. Security & Policy

**Deferred.** v1 走 auto-allow。详见 `projects/active/shell_security/problem.md`。

---

## 5. Implementation Phases

### Phase 1: Substrate（Manifest + Network + Resource）

**Scope**: `AndroidManifest.xml`, `network_security_config.xml`, `res/raw/`, build test

- Manifest：Termux package visibility + RUN_COMMAND service intent query
- `network_security_config.xml`：127.0.0.1 cleartext 例外
- `closepaw_bridge.py` 打包为 `res/raw/closepaw_bridge_py`
- Version 常量 `BRIDGE_VERSION = "1"`
- CI test：raw resource 存在且含预期 version line
- Instrumentation test：OkHttp 能 reach `http://127.0.0.1` under release config

### Phase 2: Bridge Daemon（Host-Side）

**Scope**: `tools/termux-bridge/closepaw_bridge.py`（开发/测试用），`res/raw/`（打包用）

- `ThreadingHTTPServer`，port 18422
- `/v1/exec` + `/v1/health` endpoints
- Pidfile + identity check（幂等启动，`port_in_use` error）
- Process-group kill on timeout + client disconnect
- Output cap + artifact overflow（stdout/stderr 独立 truncate + ref）
- Exec lock（busy 409）
- Idle watchdog (30 min)
- Host-side pytest：health, exec, timeout, exit code, output cap, stdout/stderr overflow, stale daemon, port conflict, busy

### Phase 3: TermuxBridgeManager + Bootstrap

**Scope**: `termux/TermuxBridgeManager.kt`, `termux/TermuxRunCommandAdapter.kt`, setup UI

- `TermuxBridgeManagerHolder`（process-wide，Holder pattern like `AuthStoreHolder`）
- `TermuxBridgeManager`（StateFlow，mutex-serialized setup/health/restart）
- `TermuxBridgeStatus`（NotInstalled, NeedsSetup(reason), SetupInProgress, Ready, Disabled）
- `TermuxCapabilitySnapshot`（immutable，session start 捕获）
- Termux detection（PackageManager）
- 4-step bootstrap：probe → install → deploy → start
- Health polling（30s interval）
- Bridge restart on failure
- Version mismatch → re-deploy from raw resource
- Enabled toggle 存 `AppSettingsStore`
- Instrumentation tests：bootstrap state machine transitions

### Phase 4: TermuxShellTool

**Scope**: `tool/impl/TermuxShellTool.kt`, `tool/ToolName.kt`

- `TermuxShellTool` implements `ToolSpec`
- OkHttp client，显式 read/call timeout = `timeout_ms + 5s grace`
- Cancellable call（`suspendCancellableCoroutine` + `Call.cancel()`）
- Tool parameters: `command`, `cwd?`, `timeout_seconds?`
- Result mapping：HTTP 200（含 timed_out）→ `Success`，409 busy / non-200 → `Failure`
- ToolName 添加 `TermuxShell`（`isScreenChanging = false`）
- `TurnToolPolicy` 修改：`termux_shell` 不参与 hoist reordering
- Unit tests：schema, result mapping, cancellation, non-zero exit code handling

### Phase 5: Agent Wiring

**Scope**: `agent/definition/*.kt`, `session/SessionToolingBootstrapper.kt`, prompt

- `shell` description 更新（toybox + 引导用 termux_shell）
- System prompt extension（读 `TermuxCapabilitySnapshot`）
- Conditional tool registration（fixed-at-task-start，读同一个 snapshot）
- `AgentRoleDef.resolve(snapshot)` → `ResolvedAgentRole`（allowedTools + prompt + timeout）
- `SessionAgentRunner` 和 `IsolatedSubAgentRunner` 都用 resolved role
- 三个 role 的 allow-lists 加 `termux_shell`
- Executor timeout 提升到 150s（when termux_shell available）
- Checkpoint reload = 新 task boundary，fresh snapshot
- Integration tests：tool present/absent matches prompt present/absent

### Phase 6: Settings UI + QA

**Scope**: UI layer, eval

- Agent Behavior 页 Termux Shell 条目（三态 + 开关 + typed reason）
- "Setup 变更生效于下个 session" 提示
- 纯 workspace task 通过（BASIC + PRO）
- 纯 UI task 通过（无 regression）
- 混合 UI+workspace task 通过
- Turn ordering test：`mobile_action` + `termux_shell` 不被 reorder
- Cancellation test：stop session during long command → process killed promptly
- Bootstrap test：每步失败和恢复
- Checkpoint/reload test：session restore 不 resurrect dead bridge

### Known Limitations (v1)

- **Cancellation**: 无 `/v1/cancel`。靠 `Call.cancel()` → client disconnect → bridge kill。
- **Shell-only file ops**: 多行写入需小心 quoting，binary 不支持。eval 驱动是否加 file tools。
- **Security**: auto-allow，无 command classification。deferred。
- **Fixed-at-task-start**: bridge 在 task 中挂掉不会立即禁用 tool，可能产生 transport error。

### Future

- Bridge token auth（见 `projects/active/shell_security/problem.md`）
- 独立 file tools（eval 驱动）
- Remote backend（SSH/HTTPS，复用 bridge protocol）
- Process management（process_start/read/stop）
- `/v1/cancel` endpoint
- Health check capabilities 字段
- `shell` → `device_inspect` 改名
- Live bridge state update（task 中 hot-reload tool 列表）

---

## Appendix: 设计讨论存档

### Q: Shizuku vs Termux？
Shizuku 系统控制力强但 toolchain 有限。Termux 通用计算力强。General agent 要 toolchain。

### Q: Remote backend？
v1 不做。

### Q: RUN_COMMAND vs daemon？
RUN_COMMAND = 点火钥匙（bootstrap），daemon = 引擎（runtime）。

### Q: Workspace 放哪？
Termux home `~/closepaw/workspace/`。`/sdcard` scoped storage 不可靠。

### Q: 一个 tool 还是两个？
两个独立 tool。能力/policy/执行路径全不同。

### Q: 独立 file/git tools？
v1 不做。shell 完成。eval 驱动扩展。

### Q: 文件权限差异？
各自 app sandbox。Workspace 在 Termux home，native shell 不需要访问。

### Q: Bridge 稳定性？
代码极简。主要风险是 Android 杀后台。HTTP daemon 最务实。

### Q: Security？
v1 auto-allow。deferred task。

### Q: Settings UI？
Agent Behavior 页，三态 + 开关 + typed reason。状态即入口。

### Q: 所有 role 都加？
是。Standalone/Planner/Executor 都加。PRO mode 默认用 Planner。

### Q: Health check capabilities？
v1 不需要。

### Q: Localhost cleartext？
127.0.0.1 narrow exception。

### Q: Setup 一步跑完？
不行。拆 4 步幂等。Probe-derived readiness。

### Q: Token 解析？
v1 不做 token auth。Loopback-only binding 足够。Token auth 归入 deferred security task（`projects/active/shell_security/problem.md`）。

### Q: Port/stale daemon？
固定 port + pidfile + identity check。不 blind kill。

### Q: Cancellation？
v1 timeout + client disconnect + `Call.cancel()`。

### Q: DI / Singleton 怎么做？
无 Hilt/Dagger。用 `TermuxBridgeManagerHolder.get(appContext)` pattern（类似 `AuthStoreHolder`）。Process-wide singleton，applicationContext。

### Q: Bridge exec loop 怎么避免 pipe deadlock？
stdout/stderr 用独立 thread drain。主循环 poll process + detect client disconnect。Output cap 在 drain 线程中逐块计数。stdout/stderr 独立 truncate。

### Q: 并发 `/v1/exec` 怎么处理？
Bridge busy 时立即返回 HTTP 409 `busy`。不排队。避免 timeout 歧义。

### Q: Fixed-at-task-start 怎么保证一致性？
`TermuxCapabilitySnapshot`（immutable）在 `SessionServices.create()` 捕获，整个 session 不变（包括 Hot Idle follow-up）。`AgentRoleDef.resolve(snapshot, excludedTools)` → `ResolvedAgentRole`。所有下游读 resolved role。Setup 变更生效于下个 session。

### Q: RUN_COMMAND 怎么避免 shell quoting 问题？
Bridge.py 通过 base64 编码传入 EXTRA_STDIN。每步有 requestId 防 stale result。

### Q: Timeout 算 Success 还是 Failure？
HTTP 200 + `timed_out=true` → `ToolExecutionResult.Success`。LLM 看到 partial output 和 timeout 信息自行决策。不中断同一 turn 后续 tool call。

### Q: OkHttp timeout 怎么配？
Read/call timeout = bridge timeout_ms + 5s grace。不用 OkHttp 默认值（10s 会断）。

### Q: Bridge script source-of-truth？
`tools/termux-bridge/closepaw_bridge.py` 是 canonical source。Gradle copy task 打包为 `res/raw/`。CI 验证字节一致。

### Q: stdout/stderr overflow 怎么区分？
独立字段：`stdout_truncated`/`stderr_truncated` + `stdout_ref`/`stderr_ref`。各自写独立 artifact 文件。

### Q: Manual setup fallback？
v1 不做。要求 RUN_COMMAND 可用。不可用时显示原因和修复引导。
