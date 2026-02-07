# `app_control` Tool — Cross-Reference Analysis & Improvement Plan

> Analyst: Claude  
> Date: 2026-02-06  
> Target: `app_control` tool prompt, schema, and agent-facing behavior

---

## 1. Current Implementation

### 1.1 Tool Schema

```json
{
  "name": "app_control",
  "description": "Control apps on the device.\n\nActions:\n- list_apps: Get list of installed launchable apps. Use filter to search by name.\n- open_app: Launch an app by package_name (e.g., 'com.google.android.gm') or app_name (e.g., 'Gmail'). Package name takes precedence if both provided.",
  "parameters": {
    "properties": {
      "action": { "enum": ["list_apps", "open_app"] },
      "agent_thought": { "type": "string" },
      "package_name": { "type": "string", "description": "Package name for open_app (e.g., 'com.google.android.gm' for Gmail)" },
      "app_name": { "type": "string", "description": "Display name for open_app (e.g., 'Gmail'). Case-insensitive fuzzy match." },
      "filter": { "type": "string", "description": "Filter for list_apps. Case-insensitive substring match on app name." }
    },
    "required": ["action"]
  }
}
```

### 1.2 Which Agents Have It

| Agent | Has `app_control` |
|-------|:-:|
| Planner | ✓ |
| Executor | ✓ |
| Standalone | ✓ |

### 1.3 System Prompt Mentions

- Planner: `Call exactly one execution tool per turn (delegate_task or app_control), then wait.`
- Executor / Standalone: No specific guidance on when/how to use `app_control`.

### 1.4 Implementation Details (Code)

- **File**: `tool/impl/AppControlTool.kt`
- `open_app` resolution chain: exact label match → label contains → package contains → `AppAliases.PACKAGE_ALIASES`
- `list_apps` returns JSON array with `{package_name, label}` + count
- `AppAliases.SEARCH_ALIASES` for expanded search terms in `list_apps`
- Post-launch: 800ms delay + screen capture

---

## 2. Reference Implementations

### 2.1 AutoDevice / android_world

| Aspect | Details |
|--------|---------|
| **Tool name** | `open_app` (standalone action, not grouped under `app_control`) |
| **Parameter** | `app_name` (string) — only one parameter |
| **No `list_apps`** | Not exposed to agent. Only internal `get_all_apps()` utility |
| **Resolution** | Regex `_PATTERN_TO_ACTIVITY` dict (~80 entries), fallback: treat input as package name → `adb shell monkey` |
| **Multi-agent** | Planner: `open_app(app_name)` as semantic intent. Executor: `open_app(app_name) → JSONAction` |
| **Prompt guidance** | "Use the `open_app` action whenever you want to open an app (nothing will happen if the app is not installed), **do not use the app drawer** to open an app unless all other ways have failed." |
| **Code** | `android_world/env/adb_utils.py`, `agents/autodev/planner_tools.py`, `agents/m3a.py` |

### 2.2 DroidRun

| Aspect | Details |
|--------|---------|
| **Tool name** | `open_app` (high-level) + `start_app` (low-level, internal) |
| **Parameter** | `text` (string) — natural language name/description |
| **No `list_apps`** | Not exposed to agent. Internal `get_apps()` and `list_packages()` |
| **Resolution** | **LLM-based** (AppStarter workflow): fetches installed apps list → LLM matches description to package name → calls `start_app(package)` |
| **Multi-agent** | Manager plans, Executor executes |
| **Prompt guidance** | "Use the `open_app` action whenever you want to open an app, do not use the app drawer to open an app." |
| **Tool description** | `'Open an app by name or description. Usage: {"action": "open_app", "text": "Gmail"}'` |
| **Code** | `agent/utils/signatures.py:151-155`, `agent/oneflows/app_starter_workflow.py`, `tools/android/adb.py` |

### 2.3 Minitap

| Aspect | Details |
|--------|---------|
| **Tool name** | `launch_app` |
| **Parameters** | `app_name` (string), `agent_thought` (string) |
| **No `list_apps`** | Not exposed to agent. Internal `list_packages_async()` |
| **Resolution** | **LLM-based** (Hopper agent): fetches `pm list packages` → Hopper LLM matches app name to package |
| **Multi-agent** | Planner → Cortex (decision) → Executor (execution) |
| **Prompt guidance** | Planner: "Always prefer `launch_app` to open apps (not manual app drawer navigation)". Cortex: "ALWAYS use first with app name. Only try app drawer manually if launch_app fails." |
| **Anti-redundancy** | Planner prompt: "**NEVER** create 'Open X' subgoal" when current foreground app is already X |
| **Robustness** | `launch_app_with_retries`: max 3 retries, polls foreground package for 15 seconds |
| **Unpredictable action isolation** | Cortex: "`launch_app` is an unpredictable action → MUST be the ONLY action in that turn" |
| **Tool description** | "Finds and launches an application on the device using its natural language name." |
| **Code** | `tools/mobile/launch_app.py`, `agents/planner/planner.md`, `agents/cortex/cortex.md` |

### 2.4 MobileAgent V3

| Aspect | Details |
|--------|---------|
| **Tool name** | `open_app` / `OPEN` |
| **Parameter** | `text` (string) — app name |
| **Predefined app list** | `ALL_APPS` (19 apps) injected into prompt. GUI Owl lists ALL available apps in prompt. |
| **Resolution** | Regex `_PATTERN_TO_ACTIVITY` dict, fallback to `monkey` command |
| **Multi-agent** | Manager plans, Executor executes, Action Reflector evaluates |
| **Prompt guidance** | "Use the `open_app` action whenever you want to open an app, do not use the app drawer" |
| **Error handling** | Appends to `error_descriptions`: "Failed to open the app 'X'; the app name might not exist." |
| **Tool description** | `"Open an app. Usage example: {\"action\": \"open_app\", \"text\": \"the name of app\"}"` |
| **Code** | `agents/mobile_agent_v3_agent.py`, `env/adb_utils.py` |

### 2.5 Android World (eval baseline)

Same as AutoDevice (shared codebase). T3A, M3A, SeeAct agents all use `open_app` with identical guidance.

### 2.6 MobileWorld (eval)

| Aspect | Details |
|--------|---------|
| **Tool name** | `open_app` |
| **Parameter** | `app_name` (string) |
| **Available apps in prompt** | MAI UI Agent lists available apps: `["Settings","Chrome","Calendar","Gallery",...]` |
| **Resolution** | `APP_LOWER_DICT` + `COMMON_APP_MAPPER` dicts (~170 entries), uses `adb shell monkey` |
| **Prompt guidance** | Less explicit than others |
| **Code** | `runtime/controller.py`, `agents/utils/prompts.py` |

---

## 3. Cross-Implementation Comparison

### 3.1 Feature Matrix

| Feature | Ours | AutoDevice | DroidRun | Minitap | MAv3 | android_world | MobileWorld |
|---------|:----:|:----------:|:--------:|:-------:|:----:|:-------------:|:-----------:|
| `open_app` action | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `list_apps` action (agent-facing) | **✓** | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| `package_name` param (agent-facing) | **✓** | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| `filter` param | **✓** | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| "Don't use app drawer" guidance | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ~ |
| "Nothing happens if not installed" note | ✗ | ✓ | ✗ | ✗ | ✓ | ✓ | ✗ |
| Available apps in prompt | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ | ✓ |
| Foreground app anti-redundancy | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ |
| Retry/polling logic | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ |
| LLM-based name resolution | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ |
| `agent_thought` param | ✓ | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ |
| Grouped under multi-action tool | **✓** | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |

### 3.2 Parameter Count

| Repo | Agent-facing params for open_app | Total schema params |
|------|:--------------------------------:|:-------------------:|
| Ours | 3 (`action`, `package_name`, `app_name`) + 2 optional (`agent_thought`, `filter`) = **5** | 5 |
| AutoDevice | 1 (`app_name`) | 1 |
| DroidRun | 1 (`text`) | 1 |
| Minitap | 2 (`app_name`, `agent_thought`) | 2 |
| MobileAgent V3 | 1 (`text`) | 1 |

**Our tool has the highest parameter count (5 vs 1-2).** This increases schema token cost and decision complexity for the LLM.

---

## 4. Pros & Cons Analysis

### 4.1 Our Current Implementation

**Pros:**
- Dynamic app discovery via `list_apps` — useful for "what apps are installed?" queries
- `package_name` allows precise targeting when agent already knows it
- Fuzzy matching with `AppAliases` improves resolution robustness
- `filter` enables efficient app search without dumping full list
- `agent_thought` provides reasoning trace

**Cons:**
- **`list_apps` is unique to us — no reference repo exposes it to the agent.** This suggests it may encourage wasteful turns where the agent calls `list_apps` before every `open_app`, costing 1 extra turn + tokens.
- **`package_name` adds unnecessary complexity.** No reference repo exposes package names to the agent. The agent should think in human-readable app names; package resolution should be fully internal.
- **Missing "don't use app drawer" guidance** — every reference repo includes this. Without it, the agent may waste turns navigating the home screen / app drawer UI to find and tap app icons.
- **Missing "nothing happens if not installed" expectation setting** — reduces agent anxiety about error handling.
- **Tool description is too terse** — doesn't include any behavioral guidance (when to use, when not to use, best practices).
- **5 parameters bloat the schema** — more tokens per turn in the tools context.

### 4.2 AutoDevice / android_world

**Pros:**
- Minimal, clear: 1 param (`app_name`)
- Explicit "don't use app drawer" guidance
- Large `_PATTERN_TO_ACTIVITY` mapping for robust resolution
- Fallback to `monkey` command with raw package name

**Cons:**
- No `list_apps` capability — if agent doesn't know app name, it's stuck
- Static regex mapping can't handle unknown apps
- No error guidance in tool description

### 4.3 DroidRun

**Pros:**
- LLM-based resolution (AppStarter) — most flexible name matching
- Clean single-param interface (`text`)
- Internal `get_apps()` feeds LLM with real installed app list

**Cons:**
- LLM-in-the-loop for name resolution adds latency and cost
- If LLM fails to match, no fallback
- No agent-facing discovery mechanism

### 4.4 Minitap

**Pros:**
- **Best prompt guidance**: "ALWAYS use first", "ONLY action in that turn", "NEVER open app already open"
- Retry + polling logic for robustness
- LLM-based resolution (Hopper) from real `pm list packages`
- `agent_thought` for reasoning trace
- Anti-redundancy: checks foreground app before launching

**Cons:**
- LLM-in-the-loop cost
- More complex multi-agent coordination (Planner → Cortex → Executor)
- No discovery mechanism for unknown apps

### 4.5 MobileAgent V3

**Pros:**
- Predefined `ALL_APPS` list in prompt — agent always knows what's available
- Clean single-param interface
- Error feedback: "app name might not exist"

**Cons:**
- Static `ALL_APPS` list — won't reflect actual device state
- Doesn't scale to arbitrary devices

---

## 5. Improvement Recommendations

### Priority Legend
- **P0** = High impact on success rate & token efficiency, low implementation risk
- **P1** = Medium impact, moderate effort
- **P2** = Nice to have, lower priority

---

### R1 [P0]: Add "don't use app drawer" guidance to tool description

**Rationale:** 6 out of 6 reference repos include this guidance. It's the single most universal piece of advice. Without it, the agent frequently wastes 3-5 turns navigating home → app drawer → scroll → tap icon, when `open_app` would do it in 1 turn.

**Current:**
```
Control apps on the device.

Actions:
- list_apps: Get list of installed launchable apps. Use filter to search by name.
- open_app: Launch an app by package_name ...
```

**Proposed addition to description:**
```
Always use open_app to launch apps. Do NOT navigate the app drawer or home screen to open apps manually.
```

**Token cost:** +15 tokens. **Expected improvement:** Significant reduction in wasted turns.

---

### R2 [P0]: Remove `package_name` parameter from the agent-facing schema

**Rationale:** Zero reference repos expose `package_name` to the agent. The agent should think in human-readable names. Package resolution should be fully internal. This removes 1 parameter (~30 tokens saved per turn in schema) and reduces decision complexity.

**Current:**
```json
"package_name": { "type": "string", "description": "Package name for open_app (e.g., 'com.google.android.gm' for Gmail)" }
```

**Proposed:** Remove from schema. Keep internal resolution logic: if the `app_name` value looks like a package name (contains dots), resolve it directly.

**Code change needed:** In `OpenAppActionHandler.validate()`, if `app_name` matches a package-name pattern (e.g., `contains(".")` with 2+ segments), treat it as a package name internally. This preserves backward compatibility without exposing it in the schema.

---

### R3 [P0]: Simplify `open_app` description for app_name

**Current:**
```
"app_name": "Display name for open_app (e.g., 'Gmail'). Case-insensitive fuzzy match."
```

**Proposed:**
```
"app_name": "Name of the app to open (e.g., 'Gmail', 'Settings', 'Chrome'). Case-insensitive."
```

Rationale: "Display name" is jargon. More examples reduce ambiguity. Drop "fuzzy match" — implementation detail the LLM doesn't need.

---

### R4 [P1]: Discourage `list_apps` in tool description (or remove it)

**Rationale:** No reference repo exposes `list_apps` to the agent. The main risk is the agent calling `list_apps` before every `open_app`, wasting a turn.

**Option A — Keep but discourage:**
```
- list_apps: List installed apps. Only use when you genuinely don't know the app name. Do NOT call before open_app — just try open_app directly; it will tell you if the app wasn't found.
```

**Option B — Remove entirely:**
Remove `list_apps` action. If `open_app` fails, include available apps in the error message:
```
"App not found: 'Gmil'. Did you mean: Gmail (com.google.android.gm)? Use list_apps to see all apps."
```

**Recommendation:** Option A (keep but discourage). Some tasks genuinely require app discovery ("which social media apps are installed?"). But add strong discouragement for the common case.

**Token cost:** Option A adds ~20 tokens to description but may save many turns. Option B saves ~50 schema tokens.

---

### R5 [P1]: Add "nothing happens if not installed" note

**Rationale:** 3 out of 6 reference repos include this. It sets agent expectations and reduces unnecessary error-checking behavior.

**Proposed addition to open_app description:**
```
If the app is not installed, the action will report failure with suggestions.
```

---

### R6 [P1]: Improve error message on app-not-found

**Current:**
```
"App not found: '$appName'. Use list_apps to see available apps."
```

**Proposed:**
```
"App not found: '$appName'. Similar apps: [Gmail, Google Maps, ...]. Try open_app with the correct name."
```

Include top-3 fuzzy matches in the error message. This eliminates the need for a separate `list_apps` call in most cases.

---

### R7 [P1]: Add anti-redundancy check — don't re-open current foreground app

**Rationale:** Minitap's approach: "NEVER create 'Open X' subgoal when X is already in foreground." Re-opening an already-open app wastes a turn and may reset the app's navigation state.

**Implementation:** In `OpenAppInvocation.execute()`, check if `targetPackage == context.platform.getCurrentPackageName()`. If so, return early:
```
"App '$targetPackage' is already in the foreground. No action needed."
```

**Token cost:** 0 (code change only). **Expected improvement:** Saves 1 turn whenever agent re-opens current app.

---

### R8 [P2]: Consider listing common/available apps in system prompt or user context

**Rationale:** MobileAgent V3 and MobileWorld inject available apps into the prompt. This eliminates the need for `list_apps` calls entirely.

**Trade-off:** Adds tokens to every turn vs. saves entire `list_apps` turns. For a device with ~40 apps, this adds ~200 tokens per turn. For eval scenarios with known app sets, this is clearly net positive. For general use, it may not be worth the per-turn cost.

**Recommendation:** Not for now. Keep `list_apps` as the discovery mechanism. Re-evaluate if `list_apps` calls are observed to be frequent in real usage.

---

### R9 [P2]: Add retry/polling logic for app launch verification

**Rationale:** Minitap implements `launch_app_with_retries` (max 3 retries, 15s polling). Our current implementation has a fixed 800ms delay which may be insufficient for heavy apps.

**Recommendation:** This is a code-level improvement, not a prompt improvement. Note for future implementation work.

---

## 6. Proposed Final Tool Schema & Description

```json
{
  "type": "function",
  "name": "app_control",
  "description": "Control apps on the device. Always use open_app to launch apps — do NOT navigate the app drawer or home screen manually.\n\nActions:\n- open_app: Launch an app by name (e.g., 'Gmail', 'Chrome', 'Settings'). Just try it — if the app isn't found, you'll get suggestions.\n- list_apps: List installed apps. Only use when you genuinely need to discover what's installed. Do NOT call before open_app.",
  "parameters": {
    "type": "object",
    "properties": {
      "action": {
        "type": "string",
        "enum": ["list_apps", "open_app"],
        "description": "The action to perform"
      },
      "agent_thought": {
        "type": "string",
        "description": "Brief reason for this action"
      },
      "app_name": {
        "type": "string",
        "description": "Name of the app to open (e.g., 'Gmail', 'Settings', 'Chrome'). Case-insensitive."
      },
      "filter": {
        "type": "string",
        "description": "Filter for list_apps. Case-insensitive substring match on app name."
      }
    },
    "required": ["action"],
    "additionalProperties": false
  },
  "strict": false
}
```

### Changes Summary

| Change | Type | Tokens | Impact |
|--------|------|--------|--------|
| Add "don't use app drawer" guidance | Description | +15 | High — prevents 3-5 wasted turns |
| Remove `package_name` param | Schema | -30 | Medium — simplifies agent decisions |
| Add "just try it" guidance | Description | +10 | Medium — prevents unnecessary list_apps calls |
| Add "don't call before open_app" for list_apps | Description | +12 | Medium — prevents wasted turns |
| Simplify `app_name` description | Schema | -5 | Low — cleaner examples |
| Remove `package_name` description | Schema | -20 | Follows from param removal |

**Net token change:** ~-18 tokens per turn (schema reduction outweighs description additions).

---

## 7. Code Changes Needed (Summary)

| File | Change | Priority |
|------|--------|----------|
| `AppControlTool.kt` — `description` | Rewrite to proposed text (R1, R4) | P0 |
| `AppControlTool.kt` — `parameterSchema` | Remove `package_name` property (R2) | P0 |
| `AppControlTool.kt` — `app_name` description | Update to proposed text (R3) | P0 |
| `OpenAppActionHandler.validate()` | Accept `app_name` only; detect package-name pattern internally (R2) | P0 |
| `OpenAppInvocation.execute()` | Add foreground-app check (R7) | P1 |
| `OpenAppInvocation.execute()` — error message | Include fuzzy matches on failure (R6) | P1 |
| System prompts (`PlannerAgentDef.kt`, `ExecutorAgentDef.kt`, `StandaloneAgentDef.kt`) | No changes needed — tool description carries the guidance | — |

---

## 8. Appendix: Raw Evidence Locations

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

### android_world (eval)
- T3A prompt: `.reference/eval/android_world/android_world/agents/t3a.py:68-69, 94-96`
- M3A prompt: `.reference/eval/android_world/android_world/agents/m3a.py:75-76, 102-104`
- Launch impl: `.reference/eval/android_world/android_world/env/adb_utils.py:681-709`

### MobileWorld (eval)
- App dict: `.reference/eval/MobileWorld/src/mobile_world/runtime/utils/models.py:231-422`
- Launch impl: `.reference/eval/MobileWorld/src/mobile_world/runtime/controller.py:255-270`
- Available apps in prompt: `.reference/eval/MobileWorld/src/mobile_world/agents/utils/prompts.py:200`
