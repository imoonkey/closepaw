# DroidRun Tools & Workflow Reference

> **Source**: `.reference/mobile_agent/droidrun/droidrun/tools/` & `/agent/utils/`

## 原子操作 (Atomic Actions)

### UI 交互

#### click(index)
点击指定索引的 UI 元素。

```json
{"action": "click", "index": 5}
```

#### long_press(index)
长按指定索引的元素。

```json
{"action": "long_press", "index": 3}
```

#### click_at(x, y) [默认禁用]
点击指定屏幕坐标。

```json
{"action": "click_at", "x": 500, "y": 300}
```

#### click_area(x1, y1, x2, y2) [默认禁用]
点击指定区域的中心点。

```json
{"action": "click_area", "x1": 100, "y1": 200, "x2": 300, "y2": 400}
```

#### long_press_at(x, y) [默认禁用]
在指定坐标长按。

```json
{"action": "long_press_at", "x": 500, "y": 300}
```

### 文本输入

#### type(text, index, clear=False)
向输入框输入文本。

- `text`: 要输入的文本
- `index`: 目标输入框的元素索引
- `clear`: 是否先清空现有内容 (推荐用于 URL 栏、搜索框)

```json
{"action": "type", "text": "example.com", "index": 7, "clear": true}
```

#### type_secret(secret_id, index) [自定义工具]
从凭证管理器输入密钥，不暴露实际值。

```json
{"action": "type_secret", "secret_id": "MY_PASSWORD", "index": 5}
```

### 系统操作

#### system_button(button)
按系统按键。

- `button`: `"back"` | `"home"` | `"enter"`

```json
{"action": "system_button", "button": "back"}
```

#### swipe(coordinate, coordinate2, duration=1.0)
滑动手势。

```json
{"action": "swipe", "coordinate": [500, 1500], "coordinate2": [500, 500], "duration": 1.5}
```

#### wait(duration)
等待指定秒数。

```json
{"action": "wait", "duration": 2.0}
```

### 应用管理

#### open_app(text) [自定义工具]
按名称或描述打开应用。

```json
{"action": "open_app", "text": "Gmail"}
```

### 任务控制

#### complete(success, reason) [CodeAct 专用]
标记任务完成。

```python
complete(success=True, reason="Successfully sent the email")
complete(success=False, reason="Unable to find the send button")
```

#### remember(information)
存储信息到记忆。

```python
remember("The total price is $45.99")
```

---

## 工具禁用配置

```yaml
tools:
  disabled_tools:
    - click_at      # 默认禁用
    - click_area    # 默认禁用
    - long_press_at # 默认禁用
```

禁用坐标类工具可以：
- 强制使用基于索引的操作
- 提高操作可靠性
- 减少因屏幕分辨率导致的错误

---

## 工作流详解

### Reasoning Mode (Manager + Executor)

```
┌─────────────────────────────────────────────────┐
│                  StartEvent                      │
└───────────────────────┬─────────────────────────┘
                        │ reasoning=True
                        ▼
┌─────────────────────────────────────────────────┐
│               ManagerInputEvent                  │
│  Pre-flight check: step_number < max_steps      │
└───────────────────────┬─────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│               ManagerAgent.run()                 │
│  - Gather context (UI state, screenshot)         │
│  - Build prompt with history                     │
│  - Call LLM                                      │
│  - Parse XML response                            │
└───────────────────────┬─────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│               ManagerPlanEvent                   │
│  - plan: 完整计划                                │
│  - current_subgoal: 下一步                       │
│  - thought: 推理过程                             │
│  - manager_answer: 最终答案 (如果完成)           │
└───────────────────────┬─────────────────────────┘
                        │
          ┌─────────────┼─────────────┐─────────────┐
          │             │             │             │
          ▼             ▼             ▼             ▼
     有 answer     <script>       TEXT_TASK     正常 subgoal
          │             │             │             │
          ▼             ▼             ▼             ▼
    FinalizeEvent  ScripterAgent TextManipulator ExecutorAgent
                        │             │             │
                        └─────────────┴─────────────┘
                                      │
                                      ▼
                        ┌─────────────────────────┐
                        │    ExecutorResultEvent  │
                        │    step_number++        │
                        └─────────────┬───────────┘
                                      │
                                      ▼
                              ManagerInputEvent (循环)
```

### Direct Mode (CodeAct)

```
┌─────────────────────────────────────────────────┐
│                  StartEvent                      │
└───────────────────────┬─────────────────────────┘
                        │ reasoning=False
                        ▼
┌─────────────────────────────────────────────────┐
│             CodeActExecuteEvent                  │
│  instruction = 用户目标                          │
└───────────────────────┬─────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│              CodeActAgent.run()                  │
│                                                  │
│  ┌────────────────────────────────────┐         │
│  │  prepare_chat (build prompts)      │         │
│  └──────────────────┬─────────────────┘         │
│                     ▼                            │
│  ┌────────────────────────────────────┐         │
│  │  handle_llm_input                  │         │
│  │  - Capture UI state                │         │
│  │  - Capture screenshot (if vision)  │         │
│  │  - Call LLM                        │         │
│  └──────────────────┬─────────────────┘         │
│                     ▼                            │
│  ┌────────────────────────────────────┐         │
│  │  execute_code                      │         │
│  │  - Parse Python code block         │<──┐     │
│  │  - Execute in sandbox              │   │     │
│  │  - Capture output                  │   │     │
│  └──────────────────┬─────────────────┘   │     │
│                     ▼                     │     │
│               complete() called?          │     │
│                  │         │              │     │
│                 Yes        No─────────────┘     │
│                  │                              │
└──────────────────┼──────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────┐
│               FinalizeEvent                      │
│  - success: bool                                │
│  - reason: str                                  │
│  - structured_output (if output_model)          │
└─────────────────────────────────────────────────┘
```

---

## ScripterAgent 工作流

```
┌─────────────────────────────────────────────────┐
│          ScripterExecutorInputEvent              │
│  task = "<script>...</script>" 内容              │
└───────────────────────┬─────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│            ScripterAgent.run()                   │
│                                                  │
│  循环 (max_steps=10):                            │
│  1. LLM 生成 thought + code                      │
│  2. 执行 Python code                             │
│  3. 如果没有 code → 视为完成                     │
│  4. 将结果加入 chat history                      │
│  5. 继续下一轮                                   │
│                                                  │
│  特点:                                           │
│  - 无设备 Tools                                  │
│  - 变量跨执行保持 (Jupyter 风格)                 │
│  - 可用: requests, json, 文件操作等              │
└───────────────────────┬─────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│        ScripterExecutorResultEvent               │
│  - success: bool                                │
│  - message: str                                 │
└───────────────────────┬─────────────────────────┘
                        │
                        ▼
                  ManagerInputEvent (继续主流程)
```

---

## 错误处理机制

### 连续错误升级

```python
# 默认阈值: 2 次连续错误
err_to_manager_thresh: int = 2

# Executor 失败后:
if consecutive_failures >= err_to_manager_thresh:
    error_flag_plan = True  # 触发重新规划
```

### 错误历史展示

Manager prompt 中展示最近失败:
```xml
<potentially_stuck>
You have encountered several failed attempts:
- Attempt: Action: click(5) | Outcome: Failed | Feedback: Element not found
- Attempt: Action: click(5) | Outcome: Failed | Feedback: Element not clickable
</potentially_stuck>
```

### 重试策略

Executor prompt 中明确:
```
IMPORTANT:
1. Do NOT repeat previously failed actions
2. Try changing to another action
```

---

## 坐标模式

### 绝对坐标 (默认)
```yaml
agent:
  use_normalized_coordinates: false
```

### 归一化坐标 [0-1000]
```yaml
agent:
  use_normalized_coordinates: true
```

好处：
- 分辨率无关
- 适合跨设备使用
- 需要转换: `x' = x * screen_width / 1000`

---

## MCP 集成

Model Context Protocol 扩展工具能力:

```yaml
mcp:
  enabled: true
  servers:
    filesystem:
      command: npx
      args: ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
      prefix: "fs_"  # 工具名: fs_read_file, fs_write_file
    
    fetch:
      command: uvx
      args: ["mcp-server-fetch"]
      prefix: ""
```

MCP 工具自动集成到 custom_tools，可被所有 Agent 使用。

---

## 外部 Agent 支持

### MAI-UI (阿里)
```yaml
agent:
  name: mai_ui

external_agents:
  mai_ui:
    llm:
      provider: OpenAILike
      model: Tongyi-MAI/MAI-UI-8B
      api_base: http://localhost:8000/v1
    history_n: 3  # 保留带图像的历史步数
```

### AutoGLM (智谱)
```yaml
agent:
  name: autoglm

external_agents:
  autoglm:
    llm:
      provider: OpenAILike
      model: autoglm-phone-9b
    lang: en
    stream: true
```

外部 Agent 绕过 DroidRun 原生 Agent，直接使用其自己的推理逻辑。
