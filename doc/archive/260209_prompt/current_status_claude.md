# Android Agent Prompt & Behavior 全景图

> Last updated: 2026-02-09
> 目标读者：Agent researcher — 快速了解所有决定 agent behavior 的部分：prompt 结构、action space、state representation、context packing

---

## 1. Prompt 总结构 (Per-Turn Request)

使用 OpenAI Responses API。每个 turn 发出的请求由三部分组成：

```
Request {
  "model":        "gpt-5.2",
  "instructions":  <system_prompt>,          // ① 角色指令
  "input":        [<ResponseInputItem>...],  // ② 历史 + 当前上下文
  "tools":        [<FunctionTool>...]        // ③ 可用工具 schema
}
```

### 构建入口代码

| 步骤 | 文件 | 函数 |
|------|------|------|
| 请求组装 | `llm/OpenAILLMClient.kt:303` | `buildResponseParams()` |
| Turn 输入构建 | `agent/Turn.kt:40` | `buildInputItems()` |
| Prompt 构建调度 | `agent/AgentTurnRunner.kt:211` | `runPlanningPhase()` |
| User message 构建 | `agent/cognition/prompt/PromptUtils.kt:36` | `buildUserMessage()` |
| Screen JSON 生成 | `perception/Perceptor.kt:79` | `toPromptJson()` |

> 所有文件路径相对于 `app/src/main/kotlin/com/moonkey/androidagent/`

---

## 2. ① System Prompt (instructions)

System prompt 由 AgentDef 子类提供，根据 agent 角色不同而不同。

### 2.1 Agent 角色定义

| Agent | 文件 | ID | 角色 | 允许工具 |
|-------|------|----|------|----------|
| **Planner** | `agent/definition/PlannerAgentDef.kt` | `planner` | 主代理，规划+委托 | `open_app`, `write_todos`, `scratchpad`, `delegate_task`, `complete_task` |
| **Executor** | `agent/definition/ExecutorAgentDef.kt` | `executor` | 子代理，执行原子 UI 动作 | `mobile_action`, `system_button`, `wait`, `open_app`, `scratchpad`, `complete_task` |
| **Standalone** | `agent/definition/StandaloneAgentDef.kt` | `standalone` | 单体模式，直接执行 | `mobile_action`, `system_button`, `wait`, `open_app`, `scratchpad`, `write_todos`, `complete_task` |

### 2.2 Planner System Prompt (完整)

```
You are the MAIN PLANNER agent for Android automation.

You do NOT perform low-level UI actions directly.
Delegate all grounded UI execution to the executor agent via delegate_task.

## Tool Calling
- Use function calling tools only; do NOT emit raw JSON or <action> tags.
- Call exactly one execution tool per turn (`delegate_task` or `open_app`), then wait.
- Use `open_app` to launch apps directly — do NOT delegate app-opening to the executor or navigate the app drawer.
- Use `write_todos` and `scratchpad` to track progress and facts.
- When the overall goal is achieved, call complete_task(status="success", answer="...").
- If blocked, call complete_task(status="failure", answer="...") with partial progress.

## Workflow
1. Observe current screen context (JSON element list)
2. Decide the next ATOMIC action
3. Call delegate_task(agent_name="executor", query="...") with ONE intent
4. Read the result, store extracted data in scratchpad if needed
5. Repeat until the overall user goal is achieved

## CRITICAL: Atomic Delegation
Each delegate_task should be ONE semantic action. Examples:
- tap(intent): "Tap on the 'Inbox' label", "Tap the first email in the list"
- scroll(intent): "Scroll down to reveal more emails", "Scroll up to see header"
- extract(intent): "Extract the sender, subject, and first paragraph from current email"
- type(intent): "Type 'hello' into the search field"
- go_back: "Press back to return to inbox"

BAD (too high-level):
- "Open Gmail, read all emails, summarize them" ← This is a MEGA-TASK, not atomic!

GOOD (atomic):
- "Tap on the first email in the inbox"
- After result: "Extract sender and subject from current email view"
- After result: "Press back to return to inbox"
- Then: "Tap on the second email"
- ... repeat until done

## Writing Good Executor Queries
When calling delegate_task, your query should be specific and actionable:
- Include app/screen context
- State the success criteria

## Failure Recovery
When executor reports failure or step-limit summary:
1. Avoid repeating the same method.
2. Switch strategy: search/filter/back/open another entry point before delegating again.
3. Use accessibility tree evidence first; screenshot is optional secondary evidence.

## Scratchpad (Shared with Executor)
Use scratchpad to store extracted data and progress so the Executor can read/write it:
- Scratchpad context shows keys only. Read values explicitly when needed.
- Write facts before navigation when data may disappear.
- scratchpad(action="write", key="email_1", value="From: X, Subject: Y")
- scratchpad(action="write", key="emails_read", value="3")
- scratchpad(action="read", key="email_1")
```

### 2.3 Executor System Prompt (完整)

```
You are an Executor agent. You execute ONE atomic UI action per delegation.

## Your Job
The Planner gives you a semantic intent like "Tap on the first email" or "Extract sender info".
You ground that intent to a specific UI action using the screen state, execute it, then COMPLETE.

## Tool Calling
- Use function calling tools only; do NOT emit raw JSON or <action> tags.
- Execute ONE action per turn, then STOP and observe the result.
- Never call `complete_task` together with another action in the same turn.
- Call complete_task(status="success", answer="...") after verifying the goal on screen.
- Call complete_task(status="failure", answer="...") if blocked (include the blocker).

## CRITICAL: Complete Quickly
- Most queries are ATOMIC (tap, scroll, extract, type, back).
- Execute the ONE action, then call complete_task on the next turn after observing the result.
- Do NOT loop or take multiple actions unless absolutely necessary.
- Expected turns: 1-3 for most queries.

## Core Rules
1. Read the query - it's your ONLY context. Execute exactly what it asks.
2. Ground decisions on the CURRENT screen state (JSON element list).
3. Execute ONE action, verify result, then complete_task.
4. Include `agent_thought` in tool calls to explain WHY you chose the target.
5. Prefer semantic selectors (`element_index`, `text`) over raw coordinates.
6. Use coordinate taps only as a last resort and never on blank/unlabeled regions.

## Query Types & How to Handle

### TAP queries ("Tap on X", "Click the Y button")
1. Find the element matching the intent in the JSON list
2. mobile_action(action="click", element_index=N)
3. complete_task(status="success", answer="Tapped [element description]")

### SCROLL queries ("Scroll down", "Scroll to find X")
1. mobile_action(action="swipe", direction="up") to scroll DOWN
2. If looking for element: check if visible after scroll
3. complete_task(status="success", answer="Scrolled [direction]. [What's now visible]")

### EXTRACT queries ("Extract sender and subject", "Read the content")
1. Find the relevant elements in the JSON list
2. Extract the requested information
3. Optionally store in scratchpad: scratchpad(action="write", key="...", value="...")
4. complete_task(status="success", answer="Extracted: [data]")

### TYPE queries ("Type 'hello' into search")
1. Find the input field (editable=true)
2. mobile_action(action="type", input_text="hello", element_index=N)
3. complete_task(status="success", answer="Typed '[text]' into [field]")

### BACK queries ("Go back", "Return to inbox")
1. system_button(button="back")
2. complete_task(status="success", answer="Pressed back")

### OPEN APP queries ("Open Gmail", "Launch Settings")
1. open_app(app_name="Gmail") — always use this, do NOT navigate the app drawer manually
2. complete_task(status="success", answer="Opened Gmail")

## Scratchpad (Shared with Planner)
Use scratchpad to store extracted data so the Planner can access it:
- Scratchpad context shows keys only. Use read when you need a stored value.
- scratchpad(action="write", key="email_1_sender", value="John Doe")
- scratchpad(action="read", key="...")

## Failure Recovery
If progress stalls:
1. Re-check the latest accessibility JSON before acting again.
2. Avoid repeating the same interaction 3+ times; choose an alternative UI path.
3. If blocked, call complete_task(status="failure", answer="...") with concrete blocker details.

## Anti-patterns (AVOID)
- Do NOT take multiple actions when one suffices
- Do NOT loop through items - that's the Planner's job
- Do NOT keep going after achieving the query goal
- Do NOT click random elements - be precise
```

### 2.4 Standalone System Prompt (完整)

```
You are a standalone Android automation agent.

## Your Job
Complete the user's goal end-to-end by directly interacting with the Android UI.
You are not a planner-only role and should execute grounded actions yourself.

## Tool Calling
- Use function calling tools only; do NOT emit raw JSON or <action> tags.
- Execute ONE UI action per turn when possible, then observe.
- Use `write_todos` for multi-step goals to keep progress explicit.
- Use `scratchpad` to store extracted facts and avoid repeated extraction.
- Scratchpad context shows keys only; use `scratchpad(action="read", key="...")` when value is needed.


## Core Loop
1. Observe current screen state (JSON element list)
2. Pick the best next action
3. Execute one tool action
4. Verify progress and continue
5. Complete the task promptly when done


## Execution Quality
- Be precise and evidence-driven from the current accessibility JSON.
- Prefer semantic selectors (`element_index`, `text`) over coordinate taps.
- Use coordinate taps only as a last resort, and never probe blank/unlabeled areas.
- Avoid repeated identical actions when no state change occurs.
- If an action fails, switch strategy instead of brute-force retries.
- Use `system_button(button="enter")` only when a text field is focused after typing.
- Keep answers concise and factual in complete_task.
```

---

## 3. ② Input Items (历史 + 当前上下文)

### 3.1 构建流程

```
Turn.buildInputItems(userMessage) → List<ResponseInputItem>
```

步骤：
1. 估算历史 token 数，超过 20k 时自动压缩到 15k
2. 遍历 `historyManager.forPrompt()`，按类型转换：
   - `ResponseItem.Message` → `EasyInputMessage` (user/assistant)
   - `ResponseItem.FunctionCall` → `ResponseFunctionToolCall`
   - `ResponseItem.FunctionCallOutput` → `FunctionCallOutput`
3. 最后追加当前 turn 的 user context item

### 3.2 Input Items 完整排列

```
input_items = [
  # --- 历史部分 (随 turn 增长) ---
  {role: "user",      content: "Goal: <用户原始目标>"},          # turn 1 首条
  {role: "assistant",  content: "..."},                          # LLM 文本回复
  {type: "function_call",        name: "...", arguments: "..."}, # 工具调用
  {type: "function_call_output", output: "..."},                 # 工具结果
  ...
  # --- 当前 turn 上下文 (每轮重新生成) ---
  {role: "user", content: "<user_message>", image: <screenshot?>}
]
```

**关键代码位置：**
- 历史管理：`history/HistoryManager.kt`
- Token 估算与压缩：`Turn.kt:41-46`
- 初始 goal 注入：`agent/Agent.kt` (session 启动时)

### 3.3 User Message 结构 (每轮重新生成)

由 `PromptUtils.buildUserMessage()` 生成，包含：

```
Current screen state (N elements):
```json
[
  {
    "index": 0,
    "text": "Inbox",
    "resource_id": "com.google.android.gm:id/inbox",
    "resource_id_index": 0,
    "text_index": 0,
    "class": "TextView",
    "desc": "",
    "clickable": true,
    "editable": false,
    "scrollable": false,
    "enabled": true,
    "focused": false,
    "long_clickable": false,
    "bounds": [0, 100, 200, 150],
    "center": [100, 125]
  },
  ...
]
```

Available tools: complete_task, mobile_action, open_app, scratchpad, system_button, wait, write_todos

Current Todos
1. [IN_PROGRESS] Open Gmail app
2. [PENDING] Read first email
3. [PENDING] Extract sender info

Scratchpad
- email_count: 5
- current_app: Gmail

Screenshot attached (compressed).

What action should I take next to achieve the goal?

<system_reminder>
LOOP WARNING (HIGH): Screen unchanged after 3 identical actions. Try a different approach.
</system_reminder>

<system_reminder>
Todo status: 3 actionable item(s) (3 total tracked). In progress: Open Gmail app. Next: Read first email; Extract sender info.
</system_reminder>
```

**关键代码位置：**

| 组件 | 文件 | 说明 |
|------|------|------|
| Screen JSON | `perception/Perceptor.kt:79` `toPromptJson()` | 最多 80 个元素，优先交互元素 |
| Tool 名称列表 | `PromptUtils.kt:58` | 按字母排序 |
| Todo 上下文 | `session/TodoState.kt:32` `toPromptContext()` | 格式：`N. [STATUS] description` |
| Scratchpad 上下文 | `session/ScratchpadState.kt:45` `toPromptContext()` | 格式：`- key: value` (keys only, values not shown in context) |
| Loop warning | `agent/cognition/policy/LoopDetectionPolicy.kt` | 检测屏幕不变/动作重复 |
| Step budget warning | `agent/cognition/policy/ExecutorStepPolicy.kt` | 接近 maxTurns 时触发 |
| Screenshot (可选) | `PromptUtils.kt:50-52` | 仅 OpenAI backend 时附加 |

> **Note:** Scratchpad reminder was removed from PromptUtils. Context now shows only keys, requiring explicit `scratchpad(action="read")` to get values.

### 3.4 Executor 子代理的 Goal 不同

Executor 通过 `SubAgentRequest.toGoal()` 拼接，格式为：

```
Delegated query: Tap on the first email in the inbox
Current subgoal: Read all emails    (可选)
Important notes:                     (可选)
- Gmail inbox is currently open
- There are 5 unread emails
```

---

## 4. 动态 Reminders 机制

除了 system prompt 和 base user message，系统会根据运行状态动态注入 `<system_reminder>` 标签。

### 4.1 Reminder 类型

| Reminder | 触发条件 | 注入位置 | 代码 |
|----------|----------|----------|------|
| **Loop Warning** | 屏幕不变/动作重复 | user message 尾部 | `LoopDetectionPolicy.kt` |
| **Turn Budget Warning** | 接近 maxTurns (75%) | user message 尾部 | `ExecutorStepPolicy.kt` |
| **Final Turn Warning** | 达到 maxTurns | user message 尾部 | `AgentTurnRunner.kt:656` |
| **Todo Reminder** | 有未完成 todo | user message 尾部 | `PromptUtils.kt:105-131` |

> **Note:** Scratchpad reminder has been removed. Scratchpad keys are shown in context, but values require explicit read.

### 4.2 注入流程

```
AgentTurnRunner.buildPromptContext()
  → PromptContext(loopWarning, systemReminders, todos, additionalContextBlocks)
  → PromptUtils.buildUserMessage(context)
    → buildBaseText()    // screen JSON + tools + context blocks
    → buildReminders()   // loop + budget + todo reminders
    → finalText = baseText + reminders
```

---

## 5. State Representation (环境感知)

### 5.1 PerceptionElement 字段

每个 UI 元素在 JSON 中的字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `index` | int | 当前屏幕唯一 ID (0-based) |
| `text` | string | 可见文本 (最长 60 字符) |
| `resource_id` | string | Android 资源 ID (如 `com.app:id/button`) |
| `resource_id_index` | int? | 同名 resource_id 的第几个 (0-based，仅重复时出现) |
| `text_index` | int? | 同名 text 的第几个 |
| `desc_index` | int? | 同名 desc 的第几个 |
| `class` | string | 简化类名 (如 `TextView`, `ImageView`) |
| `desc` | string | accessibility contentDescription |
| `clickable` | bool | 是否可点击 |
| `editable` | bool | 是否可编辑 (包含 ACTION_SET_TEXT 检测) |
| `scrollable` | bool | 是否可滚动 |
| `enabled` | bool | 是否可用 |
| `focused` | bool | 是否聚焦 |
| `long_clickable` | bool | 是否可长按 |
| `bounds` | [l,t,r,b] | 屏幕坐标边界 |
| `center` | [x,y] | 元素中心坐标 |

### 5.2 感知策略

- **最大元素数**：80 (`Perceptor.MAX_ELEMENTS`)
- **两阶段遍历**：先收集交互元素 (clickable/editable/scrollable)，不足 80 再收集有内容的非交互元素
- **过滤**：键盘元素 (Gboard, Samsung, SwiftKey 等) 被排除
- **去重**：通过 `buildElementKey()` 基于 resourceId+className+text+desc+flags+bounds 去重
- **裁剪**：off-screen 元素被过滤，bounds 被裁剪到屏幕范围内
- **文本截断**：text/desc/resourceId 最长 60 字符

### 5.3 截图 (可选)

仅在 OpenAI backend 时附加压缩截图 (`ScreenImage.toDataUrl()`)，作为 user message 的第二个 content item。

---

## 6. Action Space (工具定义)

### 6.1 工具总览

| 工具 | 类型 | Planner | Executor | Standalone | 文件 |
|------|------|---------|----------|------------|------|
| `mobile_action` | UI 交互 | ✗ | ✓ | ✓ | `tool/impl/MobileActionTool.kt` |
| `system_button` | 系统按键 | ✗ | ✓ | ✓ | `tool/impl/SystemButtonTool.kt` |
| `wait` | 等待 | ✗ | ✓ | ✓ | `tool/impl/WaitTool.kt` |
| `open_app` | App 启动 | ✓ | ✓ | ✓ | `tool/impl/OpenAppTool.kt` |
| `write_todos` | 规划状态 | ✓ | ✗ | ✓ | `tool/impl/WriteTodosTool.kt` |
| `scratchpad` | 记忆存储 | ✓ | ✓ | ✓ | `tool/impl/ScratchpadTool.kt` |
| `delegate_task` | 子代理委托 | ✓ | ✗ | ✗ | `tool/impl/DelegateTaskTool.kt` |
| `complete_task` | 任务终止 | ✓ | ✓ | ✓ | `tool/impl/CompleteTaskTool.kt` |

> **Major change:** Former `app_control` tool has been split into separate `open_app`, `system_button`, and `wait` tools. The `list_apps` action and `filter` parameter are no longer exposed to the agent.

**注册入口**：`session/SessionServices.kt` → `registerBuiltInTools()`
- `delegate_task` 由 `SessionAgentRunner.ensureDelegationToolRegistered()` 按需注册

### 6.2 工具 Schema 如何转为 JSON

```
ToolSpec.parameterSchema (JSONObject)
  → ToolRegistry.generateResponsesApiTools()
    → FunctionTool.builder()
        .name(tool.name)
        .description(tool.description)
        .parameters(jsonObjectToJsonValueMap(tool.parameterSchema))
        .strict(false)  // 因为有可选参数
        .build()
```

---

## 7. 完整 Tool JSON Schema

以下是每个工具发送给 LLM 的完整 function schema。

### 7.1 `complete_task`

```json
{
  "type": "function",
  "name": "complete_task",
  "description": "Call this when you have finished working on the task. Call ONLY after verifying the outcome on screen.\n\nAlways provide a helpful answer even when failing - explain what you tried and why it didn't work.",
  "parameters": {
    "type": "object",
    "properties": {
      "status": {
        "type": "string",
        "enum": ["success", "failure"],
        "description": "\"success\" if the goal was achieved, \"failure\" if it cannot be completed"
      },
      "answer": {
        "type": "string",
        "description": "The response to return to the user (always required). For failures, include the reason here."
      }
    },
    "required": ["status", "answer"],
    "additionalProperties": false
  },
  "strict": false
}
```

### 7.2 `mobile_action`

```json
{
  "type": "function",
  "name": "mobile_action",
  "description": "Perform touch interactions on the mobile device screen.\n\nTargeting (for click, long_press, type):\nSpecify EXACTLY ONE targeting method per action:\n- element_index: index from current screen state (preferred)\n- text + text_index: visible text on screen\n- x, y: absolute pixel coordinates (last resort)\n\nActions:\n- click: Tap target. Example: {\"action\":\"click\",\"element_index\":3}\n- long_press: Long press target. Example: {\"action\":\"long_press\",\"text\":\"Delete\"}\n- type: Type text. Example: {\"action\":\"type\",\"input_text\":\"hello\",\"element_index\":5}\n- swipe: Swipe gesture. Example: {\"action\":\"swipe\",\"direction\":\"up\"}",
  "parameters": {
    "type": "object",
    "properties": {
      "action": {
        "type": "string",
        "enum": ["click", "long_press", "swipe", "type"],
        "description": "The action to perform"
      },
      "agent_thought": {
        "type": "string",
        "description": "Brief reason for why this action is being performed"
      },
      "element_index": {
        "type": "integer",
        "description": "Index from current screen state. Preferred selector when available."
      },
      "text": {
        "type": "string",
        "description": "Target element by visible text. Use text_index for disambiguation."
      },
      "text_index": {
        "type": "integer",
        "description": "Zero-based index when multiple elements match text (default 0)"
      },
      "x": {
        "type": "integer",
        "description": "Target X coordinate in pixels"
      },
      "y": {
        "type": "integer",
        "description": "Target Y coordinate in pixels"
      },
      "input_text": {
        "type": "string",
        "description": "Text to type (type action only)"
      },
      "clear": {
        "type": "boolean",
        "description": "Clear field before typing (type action, default false)"
      },
      "start": {
        "type": "array",
        "description": "Swipe start coordinate [x, y] in pixels",
        "items": {"type": "integer"}
      },
      "end": {
        "type": "array",
        "description": "Swipe end coordinate [x, y] in pixels",
        "items": {"type": "integer"}
      },
      "direction": {
        "type": "string",
        "description": "Swipe direction. up/down scroll content opposite direction.",
        "enum": ["up", "down", "left", "right"]
      },
      "distance": {
        "type": "string",
        "description": "Directional swipe distance: short=1/4, medium=1/2, long=3/4 screen (default medium)",
        "enum": ["short", "medium", "long"]
      },
      "duration_ms": {
        "type": "integer",
        "description": "Hold duration for long_press in milliseconds (default 1000)"
      }
    },
    "required": ["action"],
    "additionalProperties": false
  },
  "strict": false
}
```

**Action 子类型：**

| Action | 必须参数 | 可选参数 | Target 选择器 |
|--------|----------|----------|---------------|
| `click` | (至少一个 target) | `agent_thought` | `element_index` / `text` / coords(`x,y`) |
| `long_press` | (至少一个 target) | `duration_ms`, `agent_thought` | 同 click |
| `type` | `input_text` | `clear`, `agent_thought`, (target for focus) | `element_index` / `text` / coords (可选) |
| `swipe` | `start`+`end` 或 `direction` | `distance`, `agent_thought` | N/A (坐标或屏幕中心) |

> **Major changes from previous version:**
> - Removed `system_button` and `wait` actions (now separate tools)
> - Removed `resource_id` and `resource_id_index` selectors (single-target design)
> - Renamed `text` parameter for type action to `input_text`
> - Removed `target_text` and `target_text_index` for type focusing
> - Strict single-target enforcement: only ONE of element_index/text/x,y allowed

### 7.3 `system_button`

```json
{
  "type": "function",
  "name": "system_button",
  "description": "Press an Android system button. This does not require element targeting.\n\nButtons:\n- back\n- home\n- enter (sends IME enter to the currently focused editable field)\n- recents\n\nExamples:\n- system_button(button=\"back\")\n- system_button(button=\"home\")",
  "parameters": {
    "type": "object",
    "properties": {
      "agent_thought": {
        "type": "string",
        "description": "Brief reason for pressing this button"
      },
      "button": {
        "type": "string",
        "enum": ["back", "home", "enter", "recents"],
        "description": "System button to press"
      }
    },
    "required": ["button"],
    "additionalProperties": false
  },
  "strict": false
}
```

### 7.4 `wait`

```json
{
  "type": "function",
  "name": "wait",
  "description": "Wait for UI updates to settle when transitions, animations, or async loading are in progress.\n\nExample:\n- wait(duration_ms=1500)",
  "parameters": {
    "type": "object",
    "properties": {
      "agent_thought": {
        "type": "string",
        "description": "Brief reason for waiting"
      },
      "duration_ms": {
        "type": "integer",
        "description": "Wait duration in milliseconds (default 1000, max 30000)"
      }
    },
    "required": [],
    "additionalProperties": false
  },
  "strict": false
}
```

### 7.5 `open_app`

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

> **Major changes from previous `app_control`:**
> - Simplified to single `open_app` tool (was multi-action `app_control`)
> - Removed `list_apps` action — no longer exposed to agent
> - Removed `package_name` parameter — name resolution is internal
> - Removed `filter` parameter
> - Added well-known alias resolution (e.g., "Gmail" → "com.google.android.gm")
> - Added foreground check (skips re-launch if app already open)
> - Added fuzzy suggestions on failure

### 7.6 `write_todos`

```json
{
  "type": "function",
  "name": "write_todos",
  "description": "Update the task plan. Pass the FULL list (replaces previous).\nEach item has description + status (pending, in_progress, completed, cancelled).\nAt most one item can be in_progress at a time.\nUpdate todos when new requirements appear during execution.\nDo not use for tasks that need only 1-2 actions.",
  "parameters": {
    "type": "object",
    "properties": {
      "todos": {
        "type": "array",
        "description": "Full list of todo items",
        "items": {
          "type": "object",
          "properties": {
            "description": {
              "type": "string",
              "description": "Todo description"
            },
            "status": {
              "type": "string",
              "enum": ["pending", "in_progress", "completed", "cancelled"],
              "description": "Todo status"
            }
          },
          "required": ["description", "status"],
          "additionalProperties": false
        }
      },
      "agent_thought": {
        "type": "string",
        "description": "Brief reason for this update. When changing the plan, explain what changed and why."
      }
    },
    "required": ["todos"],
    "additionalProperties": false
  },
  "strict": false
}
```

### 7.7 `scratchpad`

```json
{
  "type": "function",
  "name": "scratchpad",
  "description": "Store key-value data for multi-step tasks and cross-app handoffs.\n\nScratchpad keys are always shown in context every turn.\nUse read only when you need the full value for a specific key.\n\nGood usage:\n- Write facts before navigating away from the current screen\n- Store actual extracted content (not vague references)\n- Use short semantic keys (email_1_subject, total_price)\n\nActions:\n- write: Store key=value\n- read: Get value for key\n- delete: Remove key\n\nLimits:\n- Max keys: 20\n- Max key length: 100 chars\n- Max value length: 2048 chars",
  "parameters": {
    "type": "object",
    "properties": {
      "action": {
        "type": "string",
        "enum": ["write", "read", "delete"],
        "description": "Action to perform"
      },
      "key": {
        "type": "string",
        "description": "Key for write/read/delete"
      },
      "value": {
        "type": "string",
        "description": "Value for write action"
      },
      "agent_thought": {
        "type": "string",
        "description": "Brief reason for why this action is being performed"
      }
    },
    "required": ["action"],
    "additionalProperties": false
  },
  "strict": false
}
```

> **Changes from previous version:**
> - Removed `list` action (keys are always shown in context)
> - Updated description to clarify context-visibility behavior

### 7.8 `delegate_task` (仅 Planner 可用)

```json
{
  "type": "function",
  "name": "delegate_task",
  "description": "Delegate ONE atomic UI action to a sub-agent.\n\nAvailable agents:\n<动态生成的 agent 目录>\n\n## Query Format (ATOMIC intents):\n- TAP: \"Tap on the 'Send' button\", \"Tap the first email in the list\"\n- SCROLL: \"Scroll down to reveal more items\", \"Scroll up\"\n- EXTRACT: \"Extract sender, subject from current email view\"\n- TYPE: \"Type 'hello' into the search field\"\n- BACK: \"Press back to return to previous screen\"\n\nBAD: \"Open app, navigate to settings, change theme\" (too many steps!)\nGOOD: \"Tap on the Settings icon\" (one atomic action)\n\nThe executor will ground your semantic intent to the actual UI element.",
  "parameters": {
    "type": "object",
    "properties": {
      "agent_name": {
        "type": "string",
        "description": "Name of sub-agent to run"
      },
      "query": {
        "type": "string",
        "description": "Complete instruction for the sub-agent"
      },
      "current_subgoal": {
        "type": "string",
        "description": "Optional current subgoal context"
      },
      "important_notes": {
        "type": "array",
        "description": "Optional short notes to preserve context",
        "items": {"type": "string"}
      },
      "agent_thought": {
        "type": "string",
        "description": "Brief reason for this delegation"
      }
    },
    "required": ["agent_name", "query"],
    "additionalProperties": false
  },
  "strict": false
}
```

---

## 8. Multi-Agent 架构

### 8.1 Planner-Executor 协作流程

```
User Goal
    │
    ▼
┌─────────────────────────────────────┐
│ PLANNER (主代理)                     │
│ system: PlannerAgentDef.systemPrompt│
│ tools: open_app, write_todos,       │
│        scratchpad, delegate_task,   │
│        complete_task                │
│ history: 完整历史 (累积)             │
│                                     │
│ Turn N:                             │
│   observe screen → decide →         │
│   delegate_task(executor, query)    │
│                   │                 │
│                   ▼                 │
│   ┌───────────────────────────────┐ │
│   │ EXECUTOR (子代理)              │ │
│   │ system: ExecutorAgentDef      │ │
│   │ tools: mobile_action,         │ │
│   │        system_button, wait,   │ │
│   │        open_app, scratchpad,  │ │
│   │        complete_task          │ │
│   │ history: 隔离 (仅委托内)       │ │
│   │ goal: "Tap on ..."            │ │
│   │                               │ │
│   │ Turn 1: mobile_action         │ │
│   │ Turn 2: complete_task         │ │
│   └───────────────────────────────┘ │
│                   │                 │
│   ← result (success/failure) ←      │
│                                     │
│ Turn N+1: observe → next action     │
│ ...                                 │
│ Turn M: complete_task (全局完成)     │
└─────────────────────────────────────┘
```

### 8.2 关键差异

| 维度 | Planner | Executor |
|------|---------|----------|
| 目标 | 用户原始请求 | 被委托的原子意图 |
| History | 累积所有 turn | 隔离，仅委托内 turn |
| 操作粒度 | 高层规划 | 低层执行 (1-3 turns) |
| 直接 UI 交互 | ✗ | ✓ (mobile_action, system_button, wait) |
| Todo/Planning | ✓ | ✗ |
| Scratchpad | ✓ (共享) | ✓ (共享) |

---

## 9. Tool Arbitration 策略

LLM 可能在一次回复中返回多个 tool call。`TurnToolPolicy` 会仲裁：

- **每 turn 只执行一个主要 tool call** (选择优先级最高的)
- **complete_task 与其他工具同时出现时**：先执行其他工具，延迟 completion
- **重复工具调用**：去重

代码：`agent/cognition/policy/TurnToolPolicy.kt`

---

## 10. 文件索引速查

### Prompt 相关

| 文件 | 说明 |
|------|------|
| `agent/definition/PlannerAgentDef.kt` | Planner system prompt |
| `agent/definition/ExecutorAgentDef.kt` | Executor system prompt |
| `agent/definition/StandaloneAgentDef.kt` | Standalone system prompt |
| `agent/definition/AgentDef.kt` | AgentDef 抽象基类 |
| `agent/definition/AgentDefRegistry.kt` | Agent 定义注册表 |
| `agent/cognition/prompt/PromptUtils.kt` | User message 构建 (screen JSON + reminders) |
| `agent/AgentTurnRunner.kt` | Turn 调度 (prompt 组装 + 执行) |
| `agent/Turn.kt` | Input items 构建 + LLM 调用 |

### Tool 相关

| 文件 | 说明 |
|------|------|
| `tool/ToolSpec.kt` | Tool 接口定义 |
| `tool/ToolRegistry.kt` | 工具注册 + schema 生成 |
| `tool/ToolRouter.kt` | 工具执行路由 |
| `tool/ToolSchemaConverters.kt` | JSON → OpenAI JsonValue 转换 |
| `tool/impl/MobileActionTool.kt` | UI 交互工具 (4 actions: click, long_press, type, swipe) |
| `tool/impl/SystemButtonTool.kt` | 系统按键工具 (back, home, enter, recents) |
| `tool/impl/WaitTool.kt` | 等待工具 |
| `tool/impl/OpenAppTool.kt` | App 启动工具 |
| `tool/impl/WriteTodosTool.kt` | Todo 列表工具 |
| `tool/impl/ScratchpadTool.kt` | 键值存储工具 |
| `tool/impl/DelegateTaskTool.kt` | 子代理委托工具 |
| `tool/impl/CompleteTaskTool.kt` | 任务完成元工具 |

### State & Context 相关

| 文件 | 说明 |
|------|------|
| `perception/Perceptor.kt` | 屏幕感知 (a11y tree → elements → JSON) |
| `session/TodoState.kt` | Todo 状态管理 + `toPromptContext()` |
| `session/ScratchpadState.kt` | Scratchpad 状态管理 + `toPromptContext()` |
| `history/HistoryManager.kt` | 对话历史管理 + token 压缩 |
| `agent/cognition/policy/LoopDetectionPolicy.kt` | 循环检测策略 |
| `agent/cognition/policy/ExecutorStepPolicy.kt` | 步数预算策略 |
| `agent/cognition/policy/TurnToolPolicy.kt` | Tool call 仲裁策略 |

### LLM 相关

| 文件 | 说明 |
|------|------|
| `llm/LLMClient.kt` | LLM 客户端抽象 |
| `llm/OpenAILLMClient.kt` | OpenAI Responses API 实现 |
| `agent/AgentExecutionConfig.kt` | Agent 执行配置 (model, maxTurns, tools) |

---

## 11. 设计特征总结 (For Researchers)

### Prompt Engineering 特征
- **Role-based system prompts**：不同角色有针对性的指令，而非通用 prompt
- **Structured state representation**：JSON 格式的 a11y tree，包含丰富的 UI 属性
- **Dynamic reminders**：通过 `<system_reminder>` 标签在运行时注入上下文感知的提示
- **Tool descriptions as behavioral guidance**：工具描述包含使用模式、约束、示例

### Action Space 特征
- **Separated tool design**：`mobile_action` 专注 touch interactions，`system_button` 和 `wait` 独立工具
- **Single-target enforcement**：click/long_press/type 只允许一种 selector (element_index OR text OR x,y)
- **Disambiguation indices**：`text_index`, `desc_index` 解决同名元素问题
- **agent_thought 参数**：所有工具都支持，鼓励 LLM 输出推理过程

### State Representation 特征
- **Two-phase perception**：先交互元素后内容元素，保证操作相关信息优先
- **Max 80 elements**：控制 token 预算
- **Keyboard filtering**：自动排除 IME 节点
- **Screen clipping**：裁剪到屏幕范围内

### Memory & Planning 特征
- **Todo list**：全量替换式更新，强制只有一个 in_progress
- **Scratchpad**：跨屏幕持久化 key-value，Planner/Executor 共享，keys 显示在 context 中
- **History compression**：超过 20k token 自动压缩到 15k
- **Isolated executor history**：子代理有独立历史，避免 context 膨胀

---

## Changelog

### 2026-02-09 Update

**Major Tool Refactoring:**
1. **`app_control` → `open_app` + `system_button` + `wait`**
   - `open_app`: Simplified app launching tool with name-only interface
   - `system_button`: Dedicated tool for back/home/enter/recents
   - `wait`: Dedicated tool for UI settle delays
   - Removed `list_apps` action from agent interface

2. **`mobile_action` Simplification:**
   - Reduced from 6 actions to 4: `click`, `long_press`, `type`, `swipe`
   - Removed `system_button` and `wait` actions (now separate tools)
   - Removed `resource_id` and `resource_id_index` selectors
   - Renamed `text` to `input_text` for type action
   - Strict single-target enforcement (one of element_index/text/x,y only)

3. **`scratchpad` Changes:**
   - Removed `list` action (keys auto-shown in context)
   - Updated description to clarify read-for-value pattern

**System Prompt Updates:**
- Planner: References `open_app` instead of `app_control`
- Executor: References `system_button`, `wait`, `open_app` as separate tools
- Executor: Uses `input_text` parameter for type action
- Standalone: References new tool structure

**Context Building:**
- Removed scratchpad reminder from PromptUtils
- Scratchpad keys shown in context; values require explicit read
