# P2-8: Shell Tool for File Content Access

## Problem

ExpenseAddMultipleFromMarkor: Agent spent 24 turns trying to read `my_expenses.txt` in Markor's EditText UI. The a11y tree doesn't expose full file content — only the currently visible portion. The agent oscillated between scroll/click/long-press without successfully extracting the data.

User's note: "可以做。看看mobile agents有没有设计直接read file的tool的。我想设计个shell tool得了，这样灵活度更高。"

## Reference Research Findings

**No reference mobile agent provides a shell command or file-reading tool.** All handle file content via UI navigation + screen reading:

| Repo | File Access Strategy |
|---|---|
| DroidRun | UI navigation only |
| MAI-UI | UI navigation only |
| MobileAgent v3.5 | UI navigation only |
| minitap | UI navigation + `transcribe_screen()` |
| AutoDevice | UI navigation + `transcribe_screen()` + scratchpad |
| AndroidWorld baseline | UI navigation only |

AutoDevice comes closest with `transcribe_screen()` (extracts visible text via OCR/a11y), but it's still bound to what's on-screen.

**Why no reference has a shell tool**: Mobile agents interact via accessibility services, not ADB. In production, the agent runs on the device without root/ADB access. Shell access is a "cheat" that bypasses the normal UI interaction channel.

## Design: ADB Shell Tool (Eval-Only)

Despite no reference precedent, the user explicitly wants a shell tool for flexibility. This makes sense for eval (where ADB is available) even if it's not available in production.

### Tool Specification

```kotlin
class ShellTool : BaseTool() {
    override val spec = ToolSpec(
        name = "adb_shell",
        description = """Execute a shell command on the device via ADB.
            Use for reading file contents, checking device state, or other
            operations that are faster via command line than UI navigation.
            Common uses:
            - cat /path/to/file (read file contents)
            - ls /path/to/dir (list directory)
            - date (check current date/time)
            - dumpsys activity top (check current activity)
            """,
        parameters = listOf(
            ToolParameter("command", "string", "Shell command to execute", required = true)
        )
    )
}
```

### Execution Path

Two options depending on where the agent runs:

**Option A: Via eval bridge (ADB from host)**
- The shell tool sends a request to the eval bridge via HTTP
- Bridge executes `adb shell <command>` on the host
- Returns stdout/stderr to the agent

```
Agent (on device) → HTTP → eval bridge (on host) → adb shell → device → result
```

**Option B: Direct shell execution (on device)**
- Execute via `Runtime.getRuntime().exec()` on the device
- Doesn't need ADB or eval bridge
- Works in production too (within app sandbox permissions)

```kotlin
override suspend fun execute(invocation: ToolInvocation, context: ToolExecutionContext): ToolExecutionResult {
    val command = invocation.arguments.getString("command")
    return withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            ToolExecutionResult.success(
                observation = "exit=$exitCode\n$stdout${if (stderr.isNotEmpty()) "\nstderr: $stderr" else ""}"
            )
        } catch (e: Exception) {
            ToolExecutionResult.failure("Shell execution failed: ${e.message}")
        }
    }
}
```

### Recommended: Option B (Direct Shell)

- Simpler — no bridge HTTP roundtrip needed
- Works in both eval and production
- The app already has filesystem access within its sandbox
- For reading files in other apps' storage (e.g., Markor's `/sdcard/Documents/`), Android file permissions apply — shared storage is readable

### Security Constraints

- **Timeout**: 10-second hard limit per command
- **Output size**: Truncate to 4KB (prevents LLM context overflow)
- **No destructive commands**: The tool is read-oriented; the agent shouldn't need `rm`, `mv`, etc.
- **Optional: allowlist** in eval config:
  ```yaml
  bridge:
    shell_tool:
      enabled: true
      allowed_commands: ["cat", "ls", "date", "dumpsys", "getprop"]
  ```

### System Prompt Guidance

Add to StandaloneAgentDef:
```
## Shell Tool (adb_shell)
- Use adb_shell to read file contents directly when UI-based reading is impractical.
- Example: `adb_shell(command="cat /sdcard/Documents/my_file.txt")` to read file content.
- Use adb_shell to check device date/time for relative date calculations.
- Prefer UI interaction for most tasks. Use adb_shell only when UI is insufficient
  (e.g., reading long text files, checking system state).
```

### Registration

Similar to write_todos — registered in `SessionToolingBootstrapper.kt`:

```kotlin
register(ShellTool())
```

Add to StandaloneAgentDef.allowedTools:
```kotlin
"adb_shell"
```

### Eval Config Toggle

```yaml
bridge:
  shell_tool_enabled: true  # false to disable for specific eval configs
```

Pass via intent extra; conditionally register the tool.

## Files Changed

| File | Change |
|---|---|
| `app/.../tool/impl/ShellTool.kt` | New file — shell command execution tool |
| `app/.../session/SessionToolingBootstrapper.kt` | Register ShellTool (conditional on config) |
| `app/.../agent/definition/StandaloneAgentDef.kt` | Add `adb_shell` to allowedTools + prompt guidance |
| `eval/config/default.yaml` | Add `shell_tool_enabled: true` |
| `eval/aw_bridge/native_agent_bridge.py` | Pass shell_tool_enabled via intent extra |

## Impact

- ExpenseAddMultipleFromMarkor: Agent can `cat /sdcard/Documents/Markor/my_expenses.txt` to read file content in 1 turn instead of 24
- General: any task requiring file reading, device state checking, or date/time lookup becomes much more efficient

## Risks

- Security: shell access could be misused (mitigated by timeout, output truncation, optional allowlist)
- Production: shell execution is limited by app sandbox — can't access other apps' private storage. Shared storage (Downloads, Documents) is accessible.
- Model dependency: the model needs to know when to use shell vs UI. The system prompt guidance addresses this, but weaker models may over-use or under-use the tool.
