# Reference Mobile Agents 中 Planner-Executor 拆分证据分析（独立）

## 0. 范围与方法

本分析只基于代码与配置证据，不依赖 `doc/todo/planner_executor_improv/` 目录下既有文档。

分析对象：
- `.reference/mobile_agent/droidrun`
- `.reference/mobile_agent/minitap-mobile-use`
- `.reference/mobile_agent/autodevice_android_world`
- `.reference/mobile_agent/MobileAgent`
- 额外：`.reference/eval/MobileWorld` 的 `planner_executor`

证据类型：
- Prompt（system/human模板）
- Agent loop / 状态机 / graph
- Tool schema / tool registry / action mapping
- 模型与角色配置

---

## 1. DroidRun：Manager 产计划，Executor 做“字面原子执行”

### 1.1 拆分方式

- `reasoning=True` 时走 Manager/Executor 双阶段；`reasoning=False` 走单体 CodeAct。
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/droid/droid_agent.py:6`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/droid/droid_agent.py:96`
- 运行链路是 `run_manager -> handle_manager_plan -> run_executor -> handle_executor_result -> 回到 manager`。
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/droid/droid_agent.py:617`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/droid/droid_agent.py:658`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/droid/droid_agent.py:806`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/droid/droid_agent.py:841`

### 1.2 Planner（Manager）产物是什么

- Manager prompt 强制输出 `<thought> / <add_memory> / <plan>`，完成时才允许 `<request_accomplished ...>`。
  - 证据：`.reference/mobile_agent/droidrun/droidrun/config/prompts/manager/system.jinja2:164`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/config/prompts/manager/system.jinja2:168`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/config/prompts/manager/system.jinja2:177`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/config/prompts/manager/system.jinja2:181`
- 允许在 plan 中嵌 `<script>`（离线 Python）与 `TEXT_TASK`（文本编辑），说明 planner 负责“任务分流”。
  - 证据：`.reference/mobile_agent/droidrun/droidrun/config/prompts/manager/system.jinja2:88`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/config/prompts/manager/system.jinja2:49`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/droid/droid_agent.py:679`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/droid/droid_agent.py:697`
- Manager 解析时把 plan 第一项抽成 `current_subgoal`，交给 executor。
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/manager/prompts.py:68`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/manager/prompts.py:96`

### 1.3 Executor 承担的任务 level/type

- Prompt 明确把 executor 定义为“LOW-LEVEL ACTION EXECUTOR”“dumb robot”“literal execution”。
  - 证据：`.reference/mobile_agent/droidrun/droidrun/config/prompts/executor/system.jinja2:1`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/config/prompts/executor/system.jinja2:23`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/config/prompts/executor/system.jinja2:92`
- 单轮职责是：接收 subgoal -> 输出一个 atomic action JSON -> 执行。
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/executor/executor_agent.py:57`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/executor/executor_agent.py:95`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/executor/executor_agent.py:236`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/executor/prompts.py:6`
- Tool 粒度以原子操作为主（click/type/swipe/system_button/wait 等），仅少量 custom（`open_app`, `type_secret`）。
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/utils/signatures.py:23`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/utils/signatures.py:151`
  - 证据：`.reference/mobile_agent/droidrun/droidrun/agent/utils/signatures.py:130`

### 1.4 结论（DroidRun）

Executor 是 **L1 原子执行器**：不做长程任务分解，不拥有完成判定；它只把当前 subgoal 机械映射为一次具体动作并执行。

---

## 2. minitap-mobile-use：Planner 定 WHAT，Cortex 定 HOW，Executor 做工具执行

### 2.1 拆分方式

- Graph 明确拆成 `planner -> orchestrator -> contextor -> cortex -> executor -> executor_tools -> summarizer`，失败时回 planner replan。
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/graph/graph.py:104`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/graph/graph.py:109`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/graph/graph.py:111`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/graph/graph.py:155`

### 2.2 Planner 与 Cortex 的职责边界

- Planner prompt 明确“subgoals not too granular”“Planner defines WHAT milestone”。
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.md:25`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.md:92`
- Cortex prompt 明确“你是 brain，给 Executor（hands）结构化决策”；输出 `decisions`（stringified JSON）和 `complete_subgoals_by_ids`。
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/cortex/cortex.md:3`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/cortex/types.py:5`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/cortex/types.py:13`

### 2.3 Executor 承担的任务 level/type

- Executor prompt 明确：解析 Cortex 决策并按顺序调用工具，“Don't reason about strategy”。
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/executor/executor.md:3`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/executor/executor.md:9`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/executor/executor.md:40`
- ExecutorNode 直接接 `structured_decisions`，绑定工具后生成 tool calls；工具节点串行执行，失败即中止后续调用。
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/executor/executor.py:33`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/executor/executor.py:67`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/executor/tool_node.py:20`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/executor/tool_node.py:63`
- 工具集合包含设备原子交互 + scratchpad；细节工具内部做 target fallback（例如 tap 先 bounds 再 resource_id 再 text）。
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/index.py:27`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/index.py:40`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/mobile/tap.py:52`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/mobile/tap.py:82`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/mobile/tap.py:102`
- 角色模型分离（planner/cortex/executor 各自 profile），强化了“角色专职化”。
  - 证据：`.reference/mobile_agent/minitap-mobile-use/llm-config.defaults.jsonc:4`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/llm-config.defaults.jsonc:20`
  - 证据：`.reference/mobile_agent/minitap-mobile-use/llm-config.defaults.jsonc:28`

### 2.4 结论（minitap）

Executor 是 **L2 决策解释执行器**：不负责任务规划，但可在一轮里执行多工具序列；策略/完成判定主要由 Cortex/Orchestrator 负责。

---

## 3. autodevice_android_world（AutoDev）：Planner 发“语义工具调用”，Executor 在子会话内多步落地

### 3.1 拆分方式

- 初始化即分离 `planner_llm`、`planner_tools_dict` 与 `executor_tools_dict`。
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py:135`
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py:139`
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py:140`
- 主循环先 planner，再对 planner 的某些 tool call 进入 `execute_step()`（executor 子循环）。
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py:386`
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py:518`

### 3.2 Planner 输出层级

- Planner prompt 明确“NEVER directly interact with the device”，只“tell EXECUTOR what to do”。
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/prompts.py:4`
- Planner tools 是语义 intent，不给坐标；文档反复说 executor 会自己找位置。
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/planner_tools.py:7`
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/planner_tools.py:9`
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/planner_tools.py:161`

### 3.3 Executor 承担的任务 level/type

- `execute_step` 为单个 planner tool call 启动一个 executor 会话，最多 `MAX_EXECUTOR_STEPS=10` 步。
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py:37`
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py:546`
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py:586`
- Planner tool call 会先被 `tool_call_to_query()` 翻译成自然语言子任务，再让 executor 多步执行。
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py:580`
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/util.py:40`
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/util.py:55`
- Executor prompt强调“完成完整子目标后再 report”；executor tools 是可直接落到环境动作的具体函数（click/swipe/input_text等）。
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/prompts.py:208`
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/prompts.py:224`
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/executor_tools.py:15`
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py:825`
- 子会话终止条件是 `report`/`extracted_data`/错误/步数耗尽，并把总结回传 planner。
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py:706`
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py:740`
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py:997`
  - 证据：`.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py:1040`

### 3.4 结论（AutoDev）

Executor 是 **L3 子任务工作器（mini-loop）**：planner 给语义意图，executor 在本地多步完成并回报结果/摘要。

---

## 4. MobileAgent（v3 mobile + os_world）：Manager 规划、Executor 选原子动作、Reflector验收

### 4.1 拆分方式

- mobile_v3 运行顺序是 Manager -> Operator(Executor) -> 执行动作 -> ActionReflector（可选 Notetaker）。
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/run_mobileagentv3.py:100`
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/run_mobileagentv3.py:134`
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/run_mobileagentv3.py:241`
- README 也把 v3 描述为多代理框架。
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/README.md:26`

### 4.2 Planner（Manager）层

- Manager 负责高层 plan，且要求问答类任务把 `answer` 放在最后一步。
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py:72`
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py:73`
- 迭代中 manager 根据历史动作、失败记录改 plan。
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py:119`
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py:129`

### 4.3 Executor 承担的任务 level/type

- Executor prompt：输入 overall plan + current subgoal（从 plan 截取），输出单个原子 action JSON。
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py:195`
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py:198`
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py:260`
- 动作空间是 click/long_press/type/system_button/swipe/answer 等原子接口。
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py:158`
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py:179`
- ActionReflector 判断 A/B/C 成败并回流给 manager，用于下一轮规划修正。
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py:274`
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py:300`

### 4.4 os_world_v3 中的变体（同构但可两阶段 grounding）

- 核心仍是 Manager/Executor/Reflector 三段。
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/os_world_v3/mm_agents/mobileagent_v3/mobile_agent.py:254`
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/os_world_v3/mm_agents/mobileagent_v3/mobile_agent.py:255`
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/os_world_v3/mm_agents/mobileagent_v3/mobile_agent.py:256`
- 若 `grounding_stage>0`，executor 可输出 element 描述（非坐标），再由 Grounding 模块转坐标执行。
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/os_world_v3/mm_agents/mobileagent_v3/mobile_agent.py:378`
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/os_world_v3/mm_agents/mobileagent_v3/mobile_agent_modules.py:487`
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/os_world_v3/mm_agents/mobileagent_v3/mobile_agent_modules.py:548`
  - 证据：`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/os_world_v3/mm_agents/mobileagent_v3/mobile_agent_modules.py:577`

### 4.5 结论（MobileAgent）

Executor 主体仍是 **L1 原子动作选择器**；在二阶段设置下，坐标 grounding 可外包给额外模块。

---

## 5. MobileWorld 的 `planner_executor`：Planner 决策 + Executor 仅做坐标 grounding

### 5.1 实现结构

- `planner_executor` 在 registry 中是独立 agent 类型。
  - 证据：`.reference/eval/MobileWorld/src/mobile_world/agents/registry.py:25`
- 主类 `PlannerExecutorAgentMCP`：主 LLM 做规划/动作输出；executor 从 `GROUNDING_MODELS` 注入。
  - 证据：`.reference/eval/MobileWorld/src/mobile_world/agents/implementations/planner_executor.py:124`
  - 证据：`.reference/eval/MobileWorld/src/mobile_world/agents/implementations/planner_executor.py:166`
  - 证据：`.reference/eval/MobileWorld/src/mobile_world/agents/grounding/__init__.py:3`

### 5.2 Planner 产物

- planner prompt 要求每轮输出 `Thought:` + `Action:`，其中 Action 是单个 JSON。
  - 证据：`.reference/eval/MobileWorld/src/mobile_world/agents/utils/prompts.py:61`
  - 证据：`.reference/eval/MobileWorld/src/mobile_world/agents/utils/prompts.py:63`
- 代码中 `parse_action` 直接按 `Action:` 分割，之后做 action_type 标准化/别名归一化。
  - 证据：`.reference/eval/MobileWorld/src/mobile_world/agents/implementations/planner_executor.py:37`
  - 证据：`.reference/eval/MobileWorld/src/mobile_world/agents/implementations/planner_executor.py:72`
  - 证据：`.reference/eval/MobileWorld/src/mobile_world/agents/implementations/planner_executor.py:15`

### 5.3 Executor 承担的任务 level/type

- 只有在 `click/long_press/double_tap/drag` 这类“需要定位坐标”的动作时，才调用 executor。
  - 证据：`.reference/eval/MobileWorld/src/mobile_world/agents/implementations/planner_executor.py:361`
- 对 `drag` 会分别 grounding 起点/终点 target；对 click 类动作按 `action_type + target` 下发 grounding 指令。
  - 证据：`.reference/eval/MobileWorld/src/mobile_world/agents/implementations/planner_executor.py:368`
  - 证据：`.reference/eval/MobileWorld/src/mobile_world/agents/implementations/planner_executor.py:387`
- UIINS grounding 本质是“描述 -> 坐标”，再映射成 click/press action。
  - 证据：`.reference/eval/MobileWorld/src/mobile_world/agents/grounding/uiins.py:17`
  - 证据：`.reference/eval/MobileWorld/src/mobile_world/agents/grounding/uiins.py:114`
  - 证据：`.reference/eval/MobileWorld/src/mobile_world/agents/grounding/uiins.py:155`

### 5.4 结论（MobileWorld）

这个 `planner_executor` 的 executor 不是“执行器循环”，而是 **L0 空间 grounding 专家**：只负责把目标描述落到像素坐标。

---

## 6. 横向对比：executor 到底在做哪一层

| 系统 | Planner 输出单元 | Executor 自主性 | Executor 任务层级 | 完成判定主要归属 |
|---|---|---|---|---|
| DroidRun | 文本 plan 第一子目标 | 低 | L1：单步原子动作执行 | Manager (`request_accomplished`) |
| minitap-mobile-use | 高层 subgoals + Cortex decisions | 中 | L2：按 Cortex 决策串行多工具执行 | Cortex + Orchestrator |
| AutoDev | 语义 planner tool call | 高（局部） | L3：子任务 mini-loop（最多10步） | Planner（接收 executor report） |
| MobileAgent v3 | 高层 plan / current subgoal | 低 | L1：单步原子动作选择 + 反思反馈闭环 | Manager + Reflector |
| MobileWorld planner_executor | Thought + 单 JSON action | 极低（仅在定位） | L0：target 描述到坐标的 grounding | Planner |

---

## 7. 对“planner-executor 改进”最关键的观察

1. “Executor”在不同项目里语义并不统一：
- 有的是纯执行（DroidRun/MobileAgent），
- 有的是决策解释器（minitap），
- 有的是子任务代理（AutoDev），
- 有的只是 grounding 模块（MobileWorld）。

2. 如果要比较或迁移方案，先统一接口语义：
- 输入是“subgoal文本 / structured decisions / semantic tool call / action+target”？
- 输出是“单动作 / 多工具序列 / 子任务报告 / 坐标”？

3. 任务拆分粒度与 executor 能力强绑定：
- Executor 越“弱”（L0/L1），planner 就必须给更明确、可直接执行的下一步；
- Executor 越“强”（L2/L3），planner 可以只给意图或阶段目标，但要有可靠回报协议（report/summary/failure taxonomy）。
