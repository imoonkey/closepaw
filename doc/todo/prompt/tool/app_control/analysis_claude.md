# `app_control` → `open_app` Tool — Cross-Reference Analysis & Improvement Plan

> Analyst: Claude  
> Date: 2026-02-06 (analysis), 2026-02-07 (implementation)  
> Target: `app_control` tool prompt, schema, and agent-facing behavior  
> Status: **IMPLEMENTED** ✅

---

## 0. Implementation Summary

Based on cross-repo analysis and user review, the following changes were implemented:

| Change | Status |
|--------|--------|
| **Rename** `app_control` → `open_app` (standalone tool, not multi-action) | ✅ Done |
| **Remove** `list_apps` action entirely | ✅ Done |
| **Remove** `package_name` parameter (internal auto-detect remains) | ✅ Done |
| **Remove** `filter` parameter | ✅ Done |
| **Add** "don't use app drawer" guidance in tool description + all 3 system prompts | ✅ Done |
| **Add** foreground-app anti-redundancy check | ✅ Done |
| **Add** fuzzy-match suggestions on app-not-found error | ✅ Done |
| **Add** internal package-name detection (if input contains dots) | ✅ Done |

### Final Tool Schema (as implemented)

```json
{
  "type": "function",
  "name": "open_app",
  "description": "Launch an app by name. Always use this to open apps — do NOT navigate the app drawer or home screen manually.\nIf the app is not found, suggestions will be provided.",
  "parameters": {
    "type": "object",
    "properties": {
      "app_name": {
        "type": "string",
        "description": "Name of the app to open (e.g., 'Gmail', 'Settings', 'Chrome'). Case-insensitive."
      },
      "agent_thought": {
        "type": "string",
        "description": "Brief reason for this action"
      }
    },
    "required": ["app_name"],
    "additionalProperties": false
  },
  "strict": false
}
```

**Parameter count: 2** (vs 5 before). Aligned with Minitap's `launch_app(app_name, agent_thought)`.

### Files Changed

| File | Change |
|------|--------|
| `tool/impl/OpenAppTool.kt` | **NEW** — replaces `AppControlTool.kt` |
| `tool/impl/AppControlTool.kt` | **DELETED** |
| `tool/ToolName.kt` | `AppControl` → `OpenApp`, canonical `open_app` |
| `tool/PolicyEngine.kt` | Risk mapping updated |
| `session/SessionServices.kt` | Import + registration updated |
| `agent/definition/PlannerAgentDef.kt` | allowedTools + system prompt |
| `agent/definition/ExecutorAgentDef.kt` | allowedTools + system prompt |
| `agent/definition/StandaloneAgentDef.kt` | allowedTools + system prompt |
| `agent/ActionDescriptionFormatter.kt` | Formatter updated |
| `ui/common/ToolUi.kt` | Icon mapping updated |
| `test/.../OpenAppToolTest.kt` | **NEW** — replaces `AppControlToolTest.kt` |
| `test/.../AgentDefTest.kt` | Updated expected tool names |
| `test/.../TurnToolFilteringTest.kt` | Updated tool name |
| `test/.../PolicyEngineTest.kt` | Updated tool name |
| `test/.../AgentMessageBufferTest.kt` | Updated tool name |

### Execution Implementation Details

**Name resolution strategy (ordered)**:
1. **Foreground check** — if target is already the foreground app, skip (returns "already in foreground")
2. **Exact label match** — case-insensitive
3. **Label contains** search term
4. **Well-known aliases** — `AppAliases.PACKAGE_MAP` (25 entries: gmail, chrome, maps, etc.)
5. **Package-name-shaped input** — if input contains dots (e.g. `com.google.android.gm`), try package match
6. **Failure** — returns "App not found: 'X'. Similar apps: [A, B, C]. Try again with the correct name."

**Fuzzy suggestions on failure**:
- Prefix match (e.g. "gma" → "Gmail") — score 4
- Substring match (e.g. "mail" → "Gmail") — score 3
- Package name contains term — score 2
- Character overlap > 50% — score 1
- Top 5 suggestions returned

**Error handling comparison across repos**:

| Repo | Error on not-found | Suggestions? |
|------|-------------------|:------------:|
| AutoDevice | Silent (returns app_name anyway) | ✗ |
| DroidRun | "Error parsing LLM response" / "Error: ..." | ✗ (LLM selects) |
| Minitap | "Package not found." / launch timeout errors | ✗ (LLM selects) |
| MobileAgent V3 | "Failed to open the app 'X'; the app name might not exist." | ✗ |
| MobileWorld | "Failed to launch the app: X" (log shows available apps) | ~ (log only) |
| **Ours (new)** | "App not found: 'X'. Similar apps: [A, B, C]." | **✓** |

Our approach is unique in providing actionable suggestions without requiring an LLM-in-the-loop for resolution. This is a good middle ground between DroidRun/Minitap's LLM approach (expensive) and AutoDevice's silent failure (unhelpful).

---

## 1. Previous Implementation (Before Changes)

### 1.1 Tool Schema

```json
{
  "name": "app_control",
  "description": "Control apps on the device.\n\nActions:\n- list_apps: Get list of installed launchable apps. Use filter to search by name.\n- open_app: Launch an app by package_name (e.g., 'com.google.android.gm') or app_name (e.g., 'Gmail'). Package name takes precedence if both provided.",
  "parameters": {
    "properties": {
      "action": { "enum": ["list_apps", "open_app"] },
      "agent_thought": { "type": "string" },
      "package_name": { "type": "string" },
      "app_name": { "type": "string" },
      "filter": { "type": "string" }
    },
    "required": ["action"]
  }
}
```

### 1.2 Which Agents Had It

| Agent | Had `app_control` |
|-------|:-:|
| Planner | ✓ |
| Executor | ✓ |
| Standalone | ✓ |

### 1.3 System Prompt Mentions

- Planner: `Call exactly one execution tool per turn (delegate_task or app_control), then wait.`
- Executor / Standalone: No specific guidance on when/how to use `app_control`.

---

## 2. Reference Implementations

### 2.1 AutoDevice / android_world

| Aspect | Details |
|--------|---------|
| **Tool name** | `open_app` (standalone action, not grouped under `app_control`) |
| **Parameter** | `app_name` (string) — only one parameter |
| **No `list_apps`** | Not exposed to agent. Only internal `get_all_apps()` utility |
| **Resolution** | Regex `_PATTERN_TO_ACTIVITY` dict (~80 entries), fallback: treat input as package name → `adb shell monkey` |
| **Error handling** | Silent — returns app_name even on failure |
| **Multi-agent** | Planner: `open_app(app_name)` as semantic intent. Executor: `open_app(app_name) → JSONAction` |
| **Prompt guidance** | "Use the `open_app` action whenever you want to open an app (nothing will happen if the app is not installed), **do not use the app drawer** to open an app unless all other ways have failed." |

### 2.2 DroidRun

| Aspect | Details |
|--------|---------|
| **Tool name** | `open_app` (high-level) + `start_app` (low-level, internal) |
| **Parameter** | `text` (string) — natural language name/description |
| **Resolution** | **LLM-based** (AppStarter workflow): fetches installed apps list → LLM matches description to package name → calls `start_app(package)` |
| **Error handling** | "Error parsing LLM response" or "Error: ..." |
| **Prompt guidance** | "Use the `open_app` action whenever you want to open an app, do not use the app drawer to open an app." |
| **Tool description** | `'Open an app by name or description. Usage: {"action": "open_app", "text": "Gmail"}'` |

### 2.3 Minitap

| Aspect | Details |
|--------|---------|
| **Tool name** | `launch_app` |
| **Parameters** | `app_name` (string), `agent_thought` (string) |
| **Resolution** | **LLM-based** (Hopper agent): fetches `pm list packages` → Hopper LLM matches app name to package |
| **Error handling** | "Failed to launch app 'X': Package not found." / timeout errors |
| **Prompt guidance** | "Always prefer `launch_app`", "ALWAYS use first", "NEVER open app already open" |
| **Anti-redundancy** | Planner: "NEVER create 'Open X' subgoal" when current foreground app is X |
| **Robustness** | `launch_app_with_retries`: max 3 retries, polls foreground for 15 seconds |

### 2.4 MobileAgent V3

| Aspect | Details |
|--------|---------|
| **Tool name** | `open_app` / `OPEN` |
| **Parameter** | `text` (string) — app name |
| **Predefined app list** | `ALL_APPS` (19 apps) injected into prompt |
| **Resolution** | Regex `_PATTERN_TO_ACTIVITY` dict, fallback to `monkey` command |
| **Error handling** | "Failed to open the app 'X'; the app name might not exist." |

### 2.5 Android World (eval baseline)

Same as AutoDevice (shared codebase). T3A, M3A, SeeAct agents all use `open_app` with identical guidance.

### 2.6 MobileWorld (eval)

| Aspect | Details |
|--------|---------|
| **Tool name** | `open_app` |
| **Parameter** | `app_name` (string) |
| **Resolution** | `APP_LOWER_DICT` + `COMMON_APP_MAPPER` dicts (~170 entries) |
| **Error handling** | "Failed to launch the app: X" (log includes available apps, but agent doesn't see them) |

---

## 3. Cross-Implementation Comparison

### 3.1 Feature Matrix (After Implementation)

| Feature | Ours (new) | AutoDevice | DroidRun | Minitap | MAv3 | MobileWorld |
|---------|:----------:|:----------:|:--------:|:-------:|:----:|:-----------:|
| `open_app` as standalone tool | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Params: `app_name` only | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `agent_thought` param | ✓ | ✗ | ✗ | ✓ | ✗ | ✗ |
| "Don't use app drawer" guidance | ✓ | ✓ | ✓ | ✓ | ✓ | ~ |
| Foreground app anti-redundancy | ✓ | ✗ | ✗ | ✓ | ✗ | ✗ |
| Fuzzy suggestions on failure | **✓** | ✗ | ✗ | ✗ | ✗ | ✗ |
| Internal package-name detection | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ |

### 3.2 Parameter Count (After)

| Repo | Agent-facing params | Required |
|------|:-------------------:|:--------:|
| **Ours (new)** | **2** (`app_name`, `agent_thought`) | 1 |
| AutoDevice | 1 (`app_name`) | 1 |
| DroidRun | 1 (`text`) | 1 |
| Minitap | 2 (`app_name`, `agent_thought`) | 1 |
| MobileAgent V3 | 1 (`text`) | 1 |

Now aligned with Minitap (closest match in features).

---

## 4. Recommendations Status

| # | Recommendation | Priority | Status |
|---|---------------|----------|--------|
| R1 | "Don't use app drawer" guidance in tool description | P0 | ✅ Implemented |
| R2 | Remove `package_name` param | P0 | ✅ Implemented |
| R3 | Simplify `app_name` description | P0 | ✅ Implemented |
| R4 | Remove `list_apps` entirely | P0 | ✅ Implemented (user decision: remove, not just discourage) |
| R5 | "Suggestions on failure" note in description | P1 | ✅ Implemented |
| R6 | Fuzzy-match suggestions in error message | P1 | ✅ Implemented |
| R7 | Foreground-app anti-redundancy check | P1 | ✅ Implemented |
| R8 | Available apps in system prompt | P2 | Deferred — re-evaluate later |
| R9 | Retry/polling for launch verification | P2 | Deferred — code-level improvement |

---

## 5. Appendix: Raw Evidence Locations

### AutoDevice / android_world
- Tool definition: `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/planner_tools.py:119-134`
- Executor tool: `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/executor_tools.py:225-238`
- Prompt guidance: `.reference/mobile_agent/autodevice_android_world/android_world/agents/m3a.py:76, 102-104`
- Launch impl: `.reference/mobile_agent/autodevice_android_world/android_world/env/adb_utils.py:684-712`
- Pattern mapping: `.reference/mobile_agent/autodevice_android_world/android_world/env/adb_utils.py:35-172`

### DroidRun
- Tool description: `.reference/mobile_agent/droidrun/droidrun/agent/utils/signatures.py:151-155`
- AppStarter workflow: `.reference/mobile_agent/droidrun/droidrun/agent/oneflows/app_starter_workflow.py:56-101`
- Executor handling: `.reference/mobile_agent/droidrun/droidrun/agent/executor/executor_agent.py:421-430`
- Prompt guidance: `.reference/mobile_agent/droidrun/droidrun/config/prompts/executor/system.jinja2:45`
- Low-level start_app: `.reference/mobile_agent/droidrun/droidrun/tools/android/adb.py:643-675`

### Minitap
- Tool definition: `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/mobile/launch_app.py:40-47`
- Hopper resolution: `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/mobile/launch_app.py:18-35`
- Planner guidance: `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.md:29`
- Cortex guidance: `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/cortex/cortex.md:19, 73`
- Anti-redundancy: `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.md:9-11`
- Retry logic: `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/utils/app_launch_utils.py:68-112`

### MobileAgent V3
- Tool description: `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/android_world_v3/android_world/agents/mobile_agent_v3_agent.py:214-217`
- ALL_APPS list: `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/android_world_v3/android_world/agents/mobile_agent_v3_agent.py:56-77`
- Prompt guidance: `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/android_world_v3/android_world/agents/mobile_agent_v3.py:106-108`
- Launch impl: `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/android_world_v3/android_world/env/adb_utils.py:635-663`
- Error handling: `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/android_world_v3/android_world/agents/mobile_agent_v3.py:391-405`

### android_world (eval)
- T3A prompt: `.reference/eval/android_world/android_world/agents/t3a.py:68-69, 94-96`
- M3A prompt: `.reference/eval/android_world/android_world/agents/m3a.py:75-76, 102-104`
- Launch impl: `.reference/eval/android_world/android_world/env/adb_utils.py:681-709`

### MobileWorld (eval)
- App dict: `.reference/eval/MobileWorld/src/mobile_world/runtime/utils/models.py:231-422`
- Launch impl: `.reference/eval/MobileWorld/src/mobile_world/runtime/controller.py:255-270`
- Error handling: `.reference/eval/MobileWorld/src/mobile_world/runtime/controller.py:260-270`
- Available apps in prompt: `.reference/eval/MobileWorld/src/mobile_world/agents/utils/prompts.py:200`
