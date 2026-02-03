# DroidRun Agent Architecture Reference

> **Source**: `.reference/mobile_agent/droidrun`
> **Benchmark**: 91.4% success rate

## Overview

DroidRun 是一个多 Agent 协作框架，用于通过 LLM 控制 Android 设备。支持两种执行模式：
- **Reasoning Mode** (`reasoning=True`): Manager + Executor 协作循环
- **Direct Mode** (`reasoning=False`): CodeActAgent 直接执行

## Agent 架构

```
┌─────────────────────────────────────────────────────────┐
│                      DroidAgent                          │
│                    (Orchestrator)                        │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌──────────────────┐    ┌──────────────────┐           │
│  │   ManagerAgent   │───▶│   ExecutorAgent  │           │
│  │   (Planning)     │◀───│   (Actions)      │           │
│  └────────┬─────────┘    └──────────────────┘           │
│           │                                              │
│           ▼                                              │
│  ┌──────────────────┐    ┌──────────────────┐           │
│  │  ScripterAgent   │    │ TextManipulator  │           │
│  │  (Off-device)    │    │ (Text editing)   │           │
│  └──────────────────┘    └──────────────────┘           │
│                                                          │
│  ┌──────────────────┐    ┌──────────────────┐           │
│  │   CodeActAgent   │    │ StructuredOutput │           │
│  │  (Direct exec)   │    │ (Data extract)   │           │
│  └──────────────────┘    └──────────────────┘           │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

## Agent 详解

### 1. DroidAgent (Orchestrator)
**角色**: 主控制器，协调所有子 Agent

**输入**:
- `goal`: 用户目标文本
- `config`: DroidrunConfig 配置对象
- `tools`: Tools 实例 (设备交互)
- `output_model`: 可选 Pydantic 模型 (结构化输出)

**输出**:
- `ResultEvent`: `{success: bool, reason: str, structured_output?}`

**触发方式**: 用户调用 `agent.run()`

**核心逻辑**:
```
StartEvent
    │
    ├─[reasoning=False]──▶ CodeActExecuteEvent ──▶ FinalizeEvent
    │
    └─[reasoning=True]───▶ ManagerInputEvent
                              │
                              ▼
                          ManagerPlanEvent
                              │
                    ┌─────────┼─────────┐
                    ▼         ▼         ▼
        <script> tag    TEXT_TASK   normal subgoal
             │              │             │
             ▼              ▼             ▼
    ScripterExecutor  TextManipulator  ExecutorInput
             │              │             │
             └──────────────┴─────────────┘
                              │
                              ▼
                      ManagerInputEvent (循环)
```

---

### 2. ManagerAgent (Planner)
**角色**: 分析当前状态，制定计划，跟踪进度

**输入** (通过 shared_state):
- `instruction`: 用户目标
- `formatted_device_state`: 设备 UI 状态
- `screenshot`: 当前截图 (如果 vision=true)
- `action_history`: 历史操作记录
- `memory`: 累积记忆

**输出** (解析自 LLM 响应):
```python
{
    "thought": str,      # 推理过程
    "plan": str,         # 完整计划
    "current_subgoal": str,  # 下一步任务
    "memory": str,       # 需记忆的信息
    "answer": str,       # 最终答案 (任务完成时)
    "success": bool | None  # 任务是否成功
}
```

**触发方式**: `ManagerInputEvent` (每个步骤开始)

**Prompt 结构** (XML 格式):
```xml
<user_request>{{ instruction }}</user_request>
<device_date>{{ device_date }}</device_date>
<app_card>{{ app_card }}</app_card>
<important_notes>{{ important_notes }}</important_notes>
<potentially_stuck>{{ error_history }}</potentially_stuck>
<guidelines>...</guidelines>
<custom_actions>{{ custom_tools_descriptions }}</custom_actions>
<available_secrets>{{ available_secrets }}</available_secrets>
<scripter_execution>...</scripter_execution>
<output_requirements>{{ output_schema }}</output_requirements>
```

**响应格式**:
```xml
<thought>推理过程</thought>
<add_memory>需要记忆的信息</add_memory>
<plan>
1. 第一步
2. 第二步
<script>Python 操作描述</script>
3. 第三步
</plan>
<request_accomplished success="true|false">完成消息</request_accomplished>
```

---

### 3. ExecutorAgent (Action Executor)
**角色**: 执行单个原子操作

**输入** (通过 shared_state + event):
- `subgoal`: 当前子目标
- `plan`: 完整计划
- `device_state`: UI 状态
- `action_history`: 最近 5 个操作

**输出**:
```python
{
    "action": str,          # JSON 格式动作
    "description": str,     # 动作描述
    "thought": str,         # 执行推理
    "success": bool,        # 执行结果
    "error": str | None     # 错误信息
}
```

**触发方式**: `ExecutorInputEvent`

**Prompt 结构** (Markdown 格式):
```markdown
### User Request ###
{{ instruction }}

### App Card ###
{{ app_card }}

### Device State ###
{{ device_state }}

### Overall Plan ###
{{ plan }}

### Current Subgoal ###
EXECUTE THIS SUBGOAL: {{ subgoal }}

### Atomic Actions ###
- click(index): ...
- type(text, index, clear=False): ...
...

### Latest Action History ###
{% for action in action_history[-5:] %}
Action: {{ action.action }} | Outcome: {{ outcome }}
{% endfor %}
```

**响应格式**:
```markdown
### Thought ###
机械式分解子目标

### Action ###
{"action": "click", "index": 5}

### Description ###
点击设置按钮
```

---

### 4. CodeActAgent (Direct Executor)
**角色**: 直接生成并执行 Python 代码

**输入**:
- `input`: 用户目标
- `remembered_info`: 记忆信息
- `ui_state`: UI 元素列表
- `screenshot`: 截图 (如果 vision=true)

**输出**:
```python
{
    "success": bool,
    "reason": str
}
```

**触发方式**: `CodeActExecuteEvent` (reasoning=False 时)

**Prompt 结构**:
```
You are a helpful AI assistant that can write and execute Python code...

## Context:
- ui_state: 可见 UI 元素列表
- screenshots: 当前截图
- phone_state: 当前 App
- execution result: 上次执行结果

## Tools:
{{ tool_descriptions }}

## Available Secrets:
{{ available_secrets }}
```

**响应格式**:
```python
```python
# 分析并执行
click(3)
```
```

---

### 5. ScripterAgent (Off-device Operations)
**角色**: 执行非设备操作 (HTTP 请求、数据处理等)

**输入**:
- `task`: 脚本任务描述 (从 `<script>` 标签提取)

**输出**:
```python
{
    "result": str,      # 执行结果消息
    "success": bool,
    "code_ran": str     # 执行的代码
}
```

**触发方式**: Manager 计划中的 `<script>...</script>` 标签

**可用能力**:
- HTTP 请求 (requests)
- JSON/数据处理
- 文件操作
- 任何不涉及设备 UI 的 Python 操作

---

### 6. TextManipulator (Text Editing)
**角色**: 修改输入框中的文本

**输入**:
- `current_text`: 当前输入框文本
- `current_subgoal`: 文本修改任务

**输出**:
- `text_to_type`: 修改后的文本
- `code_ran`: 执行的代码

**触发方式**: Manager 计划中的 `TEXT_TASK:` 前缀

---

### 7. StructuredOutputAgent (Data Extraction)
**角色**: 从最终答案提取结构化数据

**输入**:
- `output_model`: Pydantic 模型
- `manager_answer`: Manager 的最终答案

**输出**:
- 符合 output_model 的结构化数据

**触发方式**: 任务完成时自动调用 (如果指定了 output_model)

## Tools (原子操作)

### UI 交互
| Tool | 参数 | 描述 |
|------|------|------|
| `click` | `index` | 点击指定索引的元素 |
| `long_press` | `index` | 长按指定索引的元素 |
| `click_at` | `x, y` | 点击屏幕坐标 (默认禁用) |
| `click_area` | `x1,y1,x2,y2` | 点击区域中心 (默认禁用) |
| `long_press_at` | `x, y` | 长按坐标 (默认禁用) |
| `type` | `text, index, clear` | 输入文本，clear=True 先清空 |
| `system_button` | `button` | 按系统键 (back/home/enter) |
| `swipe` | `coord1, coord2, duration` | 滑动手势 |
| `wait` | `duration` | 等待指定秒数 |

### 自定义 Tools
| Tool | 参数 | 描述 |
|------|------|------|
| `open_app` | `text` | 按名称打开应用 |
| `type_secret` | `secret_id, index` | 输入加密凭证 |
| `remember` | `information` | 存储信息到记忆 |
| `complete` | `success, reason` | 标记任务完成 |

## 共享状态 (DroidAgentState)

```python
class DroidAgentState:
    # 任务上下文
    instruction: str          # 用户目标
    step_number: int          # 当前步数
    
    # 设备状态
    formatted_device_state: str   # UI 描述
    focused_text: str             # 焦点输入框文本
    a11y_tree: List[Dict]         # 原始 a11y 树
    screenshot: bytes             # 截图
    
    # 计划状态
    plan: str                     # 当前计划
    current_subgoal: str          # 当前子目标
    manager_answer: str           # 最终答案
    
    # 历史追踪
    action_history: List[Dict]    # 操作历史
    action_outcomes: List[bool]   # 成功/失败记录
    memory: str                   # append-only 记忆
    message_history: List[Dict]   # 对话历史
    
    # 错误处理
    error_flag_plan: bool         # 错误标记
    err_to_manager_thresh: int    # 错误阈值 (默认 2)
```

## 配置参数

```yaml
agent:
  max_steps: 15              # 最大步数
  reasoning: false           # 是否使用 Manager+Executor
  streaming: true            # 流式输出
  after_sleep_action: 1.0    # 动作后等待时间
  wait_for_stable_ui: 0.3    # UI 稳定等待

  manager:
    vision: false            # 是否使用截图
    stateless: false         # 是否无状态模式

  executor:
    vision: false

  codeact:
    vision: false
    safe_execution: false    # 安全执行模式
    execution_timeout: 50.0  # 代码执行超时

  scripter:
    enabled: true
    max_steps: 10
    execution_timeout: 30.0
```

## 成功率关键因素

### 1. 分角色设计
- **Manager**: 专注高层规划，不处理具体操作
- **Executor**: 专注单步执行，"dumb robot" 模式避免过度推理

### 2. App Cards
- 应用特定的操作指南
- 帮助 Agent 理解 UI 元素语义

### 3. 错误恢复
- `err_to_manager_thresh`: 连续错误 2 次后升级到 Manager
- `error_history`: 向 Manager 展示最近失败操作
- `<potentially_stuck>` 提示帮助重新规划

### 4. Memory 机制
- append-only 记忆存储
- 需记录步骤上下文: "At step X, I obtained [content] from [source]"
- 避免复制粘贴，使用记忆传递信息

### 5. Script 委托
- 非 UI 操作委托给 ScripterAgent
- HTTP 请求、数据处理等不阻塞主流程

### 6. 结构化输出
- `output_model` 确保结果格式正确
- StructuredOutputAgent 自动提取数据

### 7. Prompt 设计原则
- Manager: XML 标签结构化输出
- Executor: Markdown headers + JSON action
- 明确的输出格式约束减少解析错误

### 8. Vision (可选)
- 截图辅助理解 UI
- 对复杂界面有帮助但增加 token 消耗
