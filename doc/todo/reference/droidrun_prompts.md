# DroidRun Prompt Templates Reference

> **Source**: `.reference/mobile_agent/droidrun/droidrun/config/prompts/`

## 概述

DroidRun 使用 Jinja2 模板系统管理 Agent prompts，支持运行时变量注入和自定义覆盖。

## Prompt 文件结构

```
config/prompts/
├── manager/
│   ├── system.jinja2       # 主 Manager prompt
│   ├── stateless.jinja2    # 无状态模式
│   └── trained.jinja2      # 训练版本
├── executor/
│   └── system.jinja2       # Executor prompt
├── codeact/
│   ├── system.jinja2       # CodeAct 系统 prompt
│   └── user.jinja2         # 用户消息模板
└── scripter/
    └── system.jinja2       # Scripter prompt
```

---

## Manager Prompt 分析

### 变量注入

| 变量 | 类型 | 说明 |
|------|------|------|
| `instruction` | str | 用户原始目标 |
| `device_date` | str | 设备当前日期 |
| `app_card` | str | 应用操作指南 |
| `important_notes` | str | 重要提示 |
| `error_history` | list | 最近失败操作 |
| `custom_tools_descriptions` | str | 自定义工具描述 |
| `available_secrets` | list | 可用密钥 ID |
| `text_manipulation_enabled` | bool | 文本编辑开关 |
| `scripter_execution_enabled` | bool | 脚本执行开关 |
| `output_schema` | dict | 输出模型定义 |

### 核心 XML 结构

```xml
<user_request>{{ instruction }}</user_request>

{% if device_date %}
<device_date>{{ device_date }}</device_date>
{% endif %}

{% if app_card %}
<app_card>{{ app_card }}</app_card>
{% endif %}

{% if error_history %}
<potentially_stuck>
You have encountered several failed attempts:
{% for error in error_history %}
- Attempt: Action: {{ error.action }} | Description: {{ error.summary }} | Outcome: Failed
{% endfor %}
</potentially_stuck>
{% endif %}

<guidelines>
1. Use `open_app` to open apps, not app drawer
2. Use search for quick navigation
3. Store info in Memory section with step context
4. File names must match exactly
5. Don't do more than asked
</guidelines>
```

### TEXT_TASK 机制

```xml
<text_manipulation>
1. Use **TEXT_TASK:** prefix for text field modifications
2. TEXT_TASK is for editing/formatting existing text
3. Do NOT use for: extracting text, typing new text, composing messages
4. Example: 'TEXT_TASK: Add "Hello" at the beginning'
</text_manipulation>
```

### Script 委托机制

```xml
<scripter_execution>
**When to use <script>:**
- Downloading files
- HTTP API calls
- Webhooks
- Data processing (JSON, XML, CSV)
- Any non-UI operation

**Format:**
<script>
Clear description of what needs to be accomplished
</script>

**Example plan:**
<plan>
<script>
Fetch weather data from https://api.weather.com/city/london
</script>
1. Open the weather app
2. Navigate to settings
</plan>
</scripter_execution>
```

### 输出格式约束

```xml
<thought>
An explanation of your rationale for the updated plan
</thought>

<add_memory>
Store important info with step context:
"At step X, I obtained [content] from [source]"
</add_memory>

<plan>
Update or copy existing plan according to progress
</plan>

<request_accomplished success="true">
Confirmation message when task completed
</request_accomplished>

<!-- OR when failed -->
<request_accomplished success="false">
Explanation of why task could not be completed
</request_accomplished>
```

---

## Executor Prompt 分析

### 变量注入

| 变量 | 类型 | 说明 |
|------|------|------|
| `instruction` | str | 用户目标 |
| `app_card` | str | 应用操作指南 |
| `device_state` | str | 设备 UI 状态 |
| `plan` | str | 完整计划 |
| `subgoal` | str | 当前子目标 |
| `progress_status` | str | 进度状态 |
| `atomic_actions` | dict | 原子操作列表 |
| `available_secrets` | list | 可用密钥 |
| `action_history` | list | 操作历史 |

### 关键设计：Dumb Robot Mode

```
You are a LOW-LEVEL ACTION EXECUTOR for an Android phone.
You do NOT answer questions or provide results.
You ONLY perform individual atomic actions.

EXECUTION MODE: You are a dumb robot.
Find the exact text/element mentioned in the subgoal and
perform the specified action on it.
```

### Subgoal 解析规则

```
### SUBGOAL PARSING MODE ###
Read the current subgoal exactly as written. Look for:
- Action words: "tap", "click", "swipe", "type", "press", "open"
- Target elements: specific text, buttons, fields
- Locations: "header", "bottom", coordinates

Convert directly to atomic action:
- "tap/click" → click action
- "swipe" → swipe action
- "type" → type action
- "press [system button]" → system_button action
- "open [app]" → open_app action
```

### 输出格式

```markdown
### Thought ###
Break down the subgoal:
(1) What atomic action is required?
(2) What target/location is specified?
(3) What parameters do I need?

### Action ###
{"action": "click", "index": 5}

### Description ###
Brief description of the chosen action
```

---

## CodeAct Prompt 分析

### 变量注入

| 变量 | 类型 | 说明 |
|------|------|------|
| `tool_descriptions` | str | 可用函数描述 |
| `available_tools` | list | 工具名列表 |
| `available_secrets` | list | 可用密钥 |
| `output_schema` | dict | 输出模型 |

### 上下文说明

```
## Context:
- **ui_state**: List of visible UI elements with indices
- **screenshots**: Visual screenshot of current state
- **phone_state**: Current app context
- **chat history**: Previous action history
- **execution result**: Result of last Action

NOTE: screenshots won't be saved in chat history.
Make sure to describe what you see in your thoughts.
```

### 响应格式

```python
**(Step 1) Agent Analysis:**
I can see the Settings app is open. Looking at the UI elements,
I can see "Wi-Fi" option at index 3.

**(Step 1) Agent Action:**
```python
click(3)
```

**(Step 2) Agent Analysis:**
Good! Now I see the Wi-Fi settings. The toggle is at index 1.

**(Step 2) Agent Action:**
```python
click(1)
complete(success=True, reason="Successfully enabled Wi-Fi")
```
```

---

## Prompt 自定义

### 运行时覆盖

```python
DroidAgent(
    goal="...",
    config=config,
    prompts={
        "manager_system": "Your custom Jinja2 template...",
        "executor_system": "Your custom Jinja2 template...",
        "codeact_system": "Your custom Jinja2 template...",
        "codeact_user": "Your custom Jinja2 template...",
        "scripter_system": "Your custom Jinja2 template..."
    }
)
```

### 模板加载顺序

1. 检查 `prompts` 参数中的自定义模板
2. 检查 `config/prompts/` 目录
3. 使用内置默认模板

---

## 成功率优化要点

### 1. Manager Prompt 设计
- 使用 XML 标签强制结构化输出
- 明确 `<request_accomplished>` 的 success 属性
- 通过 `<potentially_stuck>` 展示错误历史帮助重新规划

### 2. Executor Prompt 设计
- "Dumb robot" 模式防止过度推理
- 严格的 action 到 atomic_action 映射
- 明确禁止重复失败操作

### 3. Memory 使用规范
- 要求包含步骤上下文
- 格式: "At step X, I obtained [content] from [source]"
- append-only，不覆盖

### 4. Action History 利用
- 只展示最近 5 条
- 包含成功/失败状态
- 帮助 Agent 避免重复错误

### 5. App Card 集成
- 提供应用特定的操作指南
- 减少 Agent 对 UI 的猜测
- 提高复杂应用的成功率
