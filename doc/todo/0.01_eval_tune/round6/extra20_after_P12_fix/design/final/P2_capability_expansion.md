# P2 Capability Expansion: Screenshot Perception, Shell Tool

These additions expand the agent's capability surface. Unlike P0/P1 (infrastructure fixes), these add new modalities. They are lower priority because the current eval model (qwen3.5) is text-only, so screenshot perception helps only when switching to a vision-capable model, and the shell tool addresses a narrow set of file-reading tasks.

---

## P2-7: Hybrid Perception (Screenshot + A11y Tree)

### Problem

BrowserDraw and BrowserMaze are impossible with accessibility-only perception. HTML canvas elements expose zero visual content through the a11y tree.

### Reference Findings

All 6 surveyed reference mobile agents (DroidRun, MAI-UI, MobileAgent v3.5, minitap, AutoDevice, AndroidWorld baseline) use screenshots as **environmental input** — captured every turn alongside the a11y tree. None use a dedicated screenshot tool. The consensus is that screenshots are ambient perception, not an on-demand action.

### Existing Infrastructure

The app already has `PerceptionConfig` in `perception/PerceptionConfig.kt`:

```kotlin
sealed class PerceptionConfig {
    data object AccessibilityOnly : PerceptionConfig()
    data class ScreenshotOnly(val maxDimension: Int = 1024, val jpegQuality: Int = 70) : PerceptionConfig()
    data class Hybrid(val maxDimension: Int = 1024, val jpegQuality: Int = 70) : PerceptionConfig()
}
```

This is already wired into `SessionConfig.perceptionConfig` and eval intent extras for global mode selection. The remaining work is:
1. Per-task perception override in eval config (canvas tasks use hybrid, others stay a11y-only)
2. Ensure the selected model actually supports vision; otherwise keep `accessibility_only`

### Design

#### Eval Config Wiring (Per-Task Override)

```yaml
# eval/config/default.yaml
bridge:
  perception_mode: accessibility_only  # default

  task_overrides:
    BrowserDraw: { perception_mode: hybrid }
    BrowserMaze: { perception_mode: hybrid }
```

Global `perception_mode` wiring is already in place. The new addition is per-task override resolution in runner.

#### Screenshot Capture

The agent runs as an AccessibilityService with screen capture permission. Use `AccessibilityService.takeScreenshot()` (API 30+) or `screencap` shell command as fallback. Encode to base64 JPEG (quality 70%, target ~100-200KB per screenshot).

#### LLM Integration

For vision-capable models, add the image as a content part in the LLM request. For text-only models (qwen3.5), screenshot capture is skipped even if `perception_mode` is `hybrid` — the LLM client checks model capability.

### Phased Rollout

1. Wire eval config → intent → PerceptionConfig. Default `accessibility_only`.
2. Test with a vision-capable model on BrowserDraw/BrowserMaze.
3. If successful, consider making `hybrid` the default for vision models.

### Files Changed

| File | Change |
|---|---|
| `eval/config/default.yaml` | Add `perception_mode` field + per-task overrides |
| `eval/aw_bridge/native_agent_bridge.py` | Pass `perception_mode` via intent extra |
| `eval/aw_bridge/runner.py` | Add per-task bridge override resolution for `perception_mode` |
| `app/.../llm/LlmClient.kt` | Validate vision-capability fallback behavior |

### Impact

- Unblocks BrowserDraw and BrowserMaze (and any future canvas/visual tasks)
- Enables richer perception for all tasks when used with vision models

### Risks

- Token cost: ~1000 tokens per screenshot for vision models
- Latency: screenshot capture adds ~200ms per turn
- Not actionable for current eval config (qwen3.5 is text-only) — this is structural preparation
- Hybrid mode + text-only model: must gracefully skip screenshot to avoid wasted compute

---

## P2-8: Shell Tool for File Content Access

### Problem

ExpenseAddMultipleFromMarkor: Agent spent 24 turns trying to read `my_expenses.txt` in Markor's EditText. The a11y tree only exposes the currently visible portion of long text. The agent oscillated between scroll/click/long-press without successfully extracting the data.

### Reference Findings

No reference mobile agent provides a shell command or file-reading tool. All handle file content via UI navigation + screen reading. This is because production mobile agents interact via accessibility services without ADB/root access.

However, the user explicitly wants a shell tool for flexibility ("我想设计个shell tool得了，这样灵活度更高"). For eval (where ADB is available) this makes sense, and on-device shell execution works within the app sandbox in production too.

### Design: Direct Shell Execution (On Device)

Execute via `Runtime.getRuntime().exec()` — no bridge HTTP roundtrip needed. Works in both eval and production (within app sandbox permissions). Shared storage (`/sdcard/Documents/`, `/sdcard/Download/`) is readable.

```kotlin
class ShellTool : BaseTool() {
    override val spec = ToolSpec(
        name = "shell",
        description = """Execute a shell command on the device.
            Use for reading file contents, checking device state, or other
            operations faster via command line than UI navigation.
            Common uses:
            - cat /path/to/file (read file contents)
            - ls /path/to/dir (list directory)
            - date (check current date/time)""",
        parameters = listOf(
            ToolParameter("command", "string", "Shell command to execute", required = true)
        )
    )

    override suspend fun execute(
        invocation: ToolInvocation,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val command = invocation.arguments.getString("command")
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                ToolExecutionResult.success(
                    observation = "exit=$exitCode\n$stdout" +
                        if (stderr.isNotEmpty()) "\nstderr: $stderr" else ""
                )
            } catch (e: Exception) {
                ToolExecutionResult.failure("Shell execution failed: ${e.message}")
            }
        }
    }
}
```

### Safety Constraints (Minimal Required)

- **Timeout**: 10-second hard limit per command (kill process after timeout)
- **Output size**: Truncate to 4KB (prevents LLM context overflow)
- **Destructive command blocklist**: reject commands beginning with `rm`, `mv`, `cp`, `chmod`, `chown`, `pm`, `am force-stop`, `settings put`

### Enablement

Register in `SessionToolingBootstrapper.kt`. Add `"shell"` to `StandaloneAgentDef.allowedTools`.

For eval-specific control, use the `excluded_tools` mechanism from P0-2 in reverse — or add an `additional_tools` config to enable tools not in the default set:

```yaml
# eval/config/default.yaml
bridge:
  additional_tools: ["shell"]  # tools to add beyond the default set
```

Default: not enabled. Enabled via task override or global config.

### System Prompt Guidance

Add to StandaloneAgentDef:

```
## Shell Tool (shell)
- Use shell to read file contents directly when UI-based reading is impractical.
- Example: shell(command="cat /sdcard/Documents/my_file.txt") to read file content.
- Use shell to check device date/time for relative date calculations.
- Prefer UI interaction for most tasks. Use shell only when UI is insufficient
  (e.g., reading long text files, checking system state).
```

### Files Changed

| File | Change |
|---|---|
| `app/.../tool/impl/ShellTool.kt` | New file — shell command execution tool |
| `app/.../session/SessionToolingBootstrapper.kt` | Register ShellTool (conditional on config) |
| `app/.../agent/definition/StandaloneAgentDef.kt` | Add `shell` to allowedTools + prompt guidance |
| `eval/config/default.yaml` | Add enablement config |

### Impact

- ExpenseAddMultipleFromMarkor: Agent can `cat /sdcard/Documents/Markor/my_expenses.txt` in 1 turn instead of 24
- General: any task requiring file reading, device state checking, or date/time lookup becomes more efficient

### Risks

- Security: shell access could be misused (mitigated by timeout, output truncation)
- Production: shell execution is limited by app sandbox — can't access other apps' private storage. Shared storage is accessible.
- Model dependency: the model needs to learn when to use shell vs UI. The system prompt guidance addresses this, but weaker models may over-use or under-use the tool.
