# P2 Capability Expansion: Per-Task Config, Screen History, Shell Tool

These additions expand the agent's capability and eval flexibility. Per-task config overrides enable hybrid perception for canvas-based tasks. Screen history reduction saves tokens. The shell tool addresses file-reading tasks that are impractical via UI.

---

## P2-7: Per-Task Eval Config Overrides + Hybrid Perception

### Problem

BrowserDraw and BrowserMaze are impossible with accessibility-only perception. HTML canvas elements expose zero visual content through the a11y tree. These tasks need `perception_mode: hybrid`, but most tasks should stay `accessibility_only` to avoid token cost.

### Reference Findings: Screenshot Handling

All 6 surveyed reference mobile agents (DroidRun, MAI-UI, MobileAgent v3.5, minitap, AutoDevice, AndroidWorld baseline) use screenshots as **environmental input** — captured every turn alongside the a11y tree.

**Screenshot compression (pixel dimension reduction)**:

| Agent | Method | Effect |
|---|---|---|
| MobileAgent v3 (OWL) | Smart resize, factor=28, max ~1M pixels | Dynamic pixel-budget-aware resizing |
| MobileAgent v3.5 | Smart resize, factor=16, max 10M pixels | Looser pixel budget |
| AutoDevice | Scale factor 0.4 | 1440x2560 → 576x1024 |
| PC-Agent | Scale factor 0.5 | Simple 50% downscale |
| Agent-S v2.5 | Max edge 2400px | Aspect-ratio preserve |
| MAI-UI | No resize | Raw screenshot to model |
| DroidRun | No resize | Provider handles scaling |

Our `PerceptionConfig.maxDimension = 1024` caps the longest edge at 1024px, comparable to AutoDevice's 0.4 scale factor on typical phone screens.

### Existing Infrastructure

Screenshot capture and hybrid perception are already fully integrated:
- `PerceptionConfig` sealed class with `AccessibilityOnly`, `ScreenshotOnly`, `Hybrid` variants
- Wired into `SessionConfig.perceptionConfig` and eval intent extras
- Screenshot capture via `AccessibilityService.takeScreenshot()` (API 30+)

The remaining work is per-task perception override in eval config.

### Design: Per-Task Override in Runner

```yaml
# eval/config/default.yaml
bridge:
  perception_mode: accessibility_only  # default

  task_overrides:
    BrowserDraw: { perception_mode: hybrid }
    BrowserMaze: { perception_mode: hybrid }
```

In `runner.py`, resolve per-task config before each task:

```python
def _resolve_task_bridge_config(
    base: BridgeConfig,
    task_name: str,
    overrides: dict[str, dict[str, Any]],
) -> BridgeConfig:
    for prefix, fields in overrides.items():
        if task_name.startswith(prefix):
            return dataclasses.replace(base, **fields)
    return base
```

This uses `dataclasses.replace()` to create a per-task `BridgeConfig` copy with overrides applied. Any `BridgeConfig` field can be overridden per task (perception_mode, max_turns, excluded_tools, etc.).

### Files Changed

| File | Change |
|---|---|
| `eval/config/default.yaml` | Add `task_overrides` section |
| `eval/aw_bridge/runner.py` | Add `_resolve_task_bridge_config()`, store `task_overrides` in `RunnerConfig`, apply per-task before `run_task()` |

### Impact

- Unblocks BrowserDraw and BrowserMaze with hybrid perception
- Generic mechanism: any BridgeConfig field can be overridden per task

### Risks

- Per-task overrides add maintenance burden (need to update when adding new tasks)
- Token cost: ~1000 tokens per screenshot for hybrid mode tasks

---

## P2-9: Reduce Screen State Retention from 3 to 2 Turns

### Problem

Keeping 3 full screen observations in LLM context is expensive. A11y trees can be hundreds of tokens each. Reference analysis shows most agents keep 2-4 visual turns, with aggressive approaches (AutoDevice, minitap) keeping only 1.

### Reference Findings: Turn History

| Agent | Screenshot history | Text/a11y history |
|---|---|---|
| MobileAgent v3.5 | Last 4 turns | Older turns as text summaries |
| MAI-UI | Last 3 turns (current + 2 prev) | All action text preserved |
| Agent-S v2.5 | Last 8 image turns | All text preserved |
| AutoDevice | Current only (1) | History as text narrative |
| DroidRun Manager | No images | Last 5 action summaries |
| minitap | Current only (1) | Last 25 thoughts |

### Design

Change `HistoryConfig.recentFullScreens` default from 3 to 2. Both a11y tree and screenshot retention use the same count.

Older screens are automatically compressed to "Screen: N elements (compressed)" by `HistoryManager.downgradeOldScreens()`.

### Files Changed

| File | Change |
|---|---|
| `app/.../history/HistoryConfig.kt` | Change `recentFullScreens` default from 3 to 2 |

### Impact

- Saves ~200-500 tokens per turn by compressing one more old screen observation
- Aligns with reference agent practices (MAI-UI uses 3 turns including current = 2 previous)

### Risks

- Agent may lose context about screens 2 turns ago. Mitigated by scratchpad: agent should store important facts before navigating away.

---

## P2-8: Shell Tool for File Content Access

### Problem

ExpenseAddMultipleFromMarkor: Agent spent 24 turns trying to read `my_expenses.txt` in Markor's EditText. The a11y tree only exposes the currently visible portion of long text. The agent oscillated between scroll/click/long-press without successfully extracting the data.

### Design: Direct Shell Execution (On Device)

Execute via `Runtime.getRuntime().exec()` — no bridge HTTP roundtrip needed. Works in both eval and production (within app sandbox permissions). Shared storage (`/sdcard/Documents/`, `/sdcard/Download/`) is readable.

Implements the `ToolSpec` interface following existing tool patterns (ScratchpadTool, WaitTool). Uses a custom `ShellInvocation` for execution.

### Safety Constraints

- **Timeout**: 10-second hard limit per command (kill process after timeout)
- **Output size**: Truncate to 4KB (prevents LLM context overflow)
- **Destructive command blocklist**: reject commands matching `rm`, `mv`, `cp`, `chmod`, `chown`, `pm`, `am force-stop`, `settings put`

### Enablement

Register unconditionally in `SessionToolingBootstrapper.kt`. Add `"shell"` to `StandaloneAgentDef.allowedTools`. The tool is always available — use `excluded_tools` in eval config to disable it for specific tasks if needed.

### System Prompt Guidance

Add to StandaloneAgentDef:

```
## Shell Tool
- Use shell to read file contents directly when UI-based reading is impractical.
- Example: shell(command="cat /sdcard/Documents/my_file.txt") to read file content.
- Prefer UI interaction for most tasks. Use shell only when UI is insufficient
  (e.g., reading long text files, checking system state).
```

### Files Changed

| File | Change |
|---|---|
| `app/.../tool/impl/ShellTool.kt` | New file — shell command execution tool |
| `app/.../session/SessionToolingBootstrapper.kt` | Register `ShellTool()` |
| `app/.../agent/definition/StandaloneAgentDef.kt` | Add `"shell"` to `allowedTools` + prompt guidance |

### Impact

- ExpenseAddMultipleFromMarkor: Agent can `cat /sdcard/Documents/Markor/my_expenses.txt` in 1 turn instead of 24
- General: any task requiring file reading or device state checking becomes more efficient

### Risks

- Security: shell access could be misused (mitigated by timeout, output truncation, destructive command blocklist)
- Production: shell execution is limited by app sandbox — can't access other apps' private storage
- Model dependency: the model needs to learn when to use shell vs UI. Prompt guidance addresses this.
