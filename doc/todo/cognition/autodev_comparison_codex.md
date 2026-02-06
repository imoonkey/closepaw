# AutoDev Agent vs AndroidAgent Cognition（Codex 深度对比）

> Date: 2026-02-04  
> Scope: 对比 `androidagent` 当前 Cognition 实现 与 `.reference/mobile_agent/autodevice_android_world` 中 AutoDev agent（`android_world/agents/autodev*`）

## 1. 结论先行

你的 Cognition 设计已经在“结构化可演进性”上明显优于 AutoDev（模块边界清晰、可测、可替换、可追踪）。

AutoDev 的优势在于“任务成功导向的战术细节密度”很高（大量 prompt 规则、滚动防环、分步日志、运行时多模型策略、transcribe 工具回路），但工程形态更像“高经验脚本系统”，而不是稳定的可扩展内核。

简化判断：
- 你的系统：**Factory/Lab 分层更对，长期演进更强**。
- AutoDev：**短期任务启发式更激进，战术容错和手工规则更重**。

最佳路线不是照抄 AutoDev，而是“把 AutoDev 的高价值战术能力，用你现有 Cognition 边界做产品化吸收”。

---

## 2. 对比对象（代码基准）

### 你的实现（AndroidAgent）
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentRuntime.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentPromptBuilder.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTrace.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/`（prompt/profile/context/policy/trace/metrics）

### AutoDev（参考实现）
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/prompts.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/llm.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/planner_tools.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/executor_tools.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/logging_system.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/scratchpad.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/todo_list.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/transcription.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/util.py`

---

## 3. 架构级差异（总览矩阵）

| 维度 | 你的 Cognition | AutoDev | 差异判断 |
|---|---|---|---|
| 核心分层 | 明确 `prompt/profile/context/policy/trace/metrics` | 主要集中在 `autodev_agent.py` + prompts/工具模块 | 你更架构化，AutoDev 更脚本化 |
| 循环形态 | `AgentRuntime` + `AgentTurnRunner` + `Turn`，单轮单仲裁 | Planner step + 内嵌 executor 多步循环（`MAX_EXECUTOR_STEPS=10`） | AutoDev 执行层更“长回路” |
| Prompt治理 | `PromptAssembler` + profile variant | 超大 prompt 文本（大量规则内嵌） | 你可维护性更高 |
| Profile实验 | `CognitionProfileRegistry` + `cognitionProfileId` | 无等价 profile registry（更多靠 prompt/模型硬编码） | 你更适合 A/B |
| Policy解耦 | `TurnToolPolicy` 已抽离 | 仲裁逻辑散布在 agent 主循环与 prompt 指令 | 你更可测 |
| Context打包 | `ContextPackager` 接口已立 | 主要直接拼 user message + 可选 transcription | 你有扩展槽，当前能力还浅 |
| 观测性 | trace artifacts + redaction + run_summary | 强步骤日志 + screenshots + timeline + summary | AutoDev 的“人类调试可读性”更强 |
| 安全 | trace redaction（email/token/jwt/敏感key） | 未见等价 redaction 层 | 你更安全 |
| 失败恢复 | 网络错误分类（DNS/超时/context limit） | LLM retry + ADB断连重连 + executor超步摘要 | AutoDev 在执行故障恢复更激进 |
| 测试覆盖 | policy/profile/context/trace 等单测已补 | 有部分测试，但 autodev 特性主要靠运行日志 | 你更工程化 |

---

## 4. Nitty Gritty 逐点对比

## 4.1 控制流与执行模型

### 你的实现
- 主循环是固定 `Perceive -> Think -> Act -> Observe`。
- LLM 每 turn 输出后，`TurnToolPolicy` 仲裁只执行一个 tool（优先非 `complete_task`）。
- 执行完成后回到下一 turn。

关键文件：
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicy.kt`

### AutoDev
- Planner 先产出 tool calls；若是执行类 tool，会进入 `execute_step()`。
- `execute_step()` 内部再跑一个 executor 小循环（最多 10 步），每步都可继续调用工具，直到 `report()` 或失败。
- 失败时会把“执行摘要”回传 planner。

关键文件：
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py`

### 差异影响
- 你的“每轮一个动作”可预测、可审计，适合稳定产品。
- AutoDev 的 executor 内循环减少 planner 往返，但会增加局部状态复杂度和不可控长尾。

---

## 4.2 Prompt 系统与规则密度

### 你的实现
- Prompt 由模板+组装器生成：`PlannerPromptTemplate`、`ExecutorPromptTemplate`、`SharedPromptRules`。
- profile 可切 `baseline/concise`。

关键文件：
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptAssembler.kt`

### AutoDev
- `prompts.py` 中 planner/executor 是超长规则文本，覆盖了大量业务策略（日期边界、去重流程、失败叙事、滚动防环、格式输出等）。
- 规则密度极高，行为指导非常具体。

关键文件：
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/prompts.py`

### 差异影响
- 你：高可维护，低耦合。
- AutoDev：高战术命中率，但容易演化为“prompt 巨石”。

---

## 4.3 Tool 语义与动作空间

### 你的实现
- 通过 `ToolRegistry` 暴露统一工具（如 `mobile_action`, `delegate_task`, `scratchpad`, `write_todos`, `complete_task`）。
- planner/executor 工具曝光由 `allowedToolNames` 和子代理定义决定。

### AutoDev
- planner_tools / executor_tools 分离，planner 工具偏语义（`tap(intent)`），executor 工具偏坐标动作（`click(x,y)`、`swipe_coords(...)`）。
- 通过 `tool_call_to_query()` 把 planner 调用翻译成 executor query。

关键文件：
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/planner_tools.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/executor_tools.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/util.py`

### 差异影响
- 你：工具体系更统一，减少双层翻译错误。
- AutoDev：planner/executor语义分层清晰，但转换链路更脆弱。

---

## 4.4 状态与记忆

### 你的实现
- `TodoState` + `ScratchpadState` 在 session 内共享，planner/executor 都能访问。
- 通过 `AgentPromptBuilder` 注入到 state context。

### AutoDev
- `TodoList` 和 `Scratchpad` 在 `AutoDevLLM` 层处理；通过 system reminder 文本持续注入。
- scratchpad 键规范为 `PAD-1/PAD-2`，操作工具为 `createItem/fetchItem`。

关键文件：
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/todo_list.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/scratchpad.py`

### 差异影响
- 你：状态模型和运行时分离更好。
- AutoDev：记忆使用规则更具体（流程约束强），但依赖 prompt 纪律。

---

## 4.5 可观测性与调试产物

### 你的实现
- 记录 `full_prompt`、`llm_input_items`、`run_summary`、history/tool artifacts。
- 落盘前 redaction（email/token/jwt/sensitive key）。

关键文件：
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTrace.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/trace/CognitionTraceRedactor.kt`

### AutoDev
- `TestRunLogger` 产出 run 目录、steps、screenshots、timeline、summary、success.json。
- 对“每步行为回放”极友好。
- 未见 redaction 层。

关键文件：
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/logging_system.py`

### 差异影响
- 你：更安全、更适合线上日志治理。
- AutoDev：更适合离线 debug 和行为复盘。

---

## 4.6 模型路由与成本控制

### 你的实现
- 当前由 `SessionConfig.model` 决定，profile 还未深度绑定 model routing。

### AutoDev
- planner model 根据 task difficulty 选择；executor 模型独立。
- Anthropic prompt cache（`cache_control`）及统计汇总。
- 历史里主动移除 image block 节省上下文。

关键文件：
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/llm.py`

### 差异影响
- AutoDev 在推理成本和模型分工上更“战术优化”。
- 你的架构已有 profile，可自然承载这类策略，但目前尚未接入。

---

## 4.7 故障恢复与反环

### 你的实现
- 区分 DNS/网络瞬断/context limit，生成 recoverable 标记。
- policy 层对多 tool + complete_task 冲突有稳定处理。

### AutoDev
- LLM 调用有 retry/backoff。
- ADB 断连尝试 `refresh_env()` 重连。
- 维护 navigation_state（seen_screenshots、scroll_count、seen_text_hashes）做防环与策略切换。
- executor 超步强制总结。

### 差异影响
- AutoDev 的“环境级恢复 + UI防环”更重。
- 你的“LLM输出治理 + session稳定性”更强。

---

## 4.8 安全与隐私

### 你的实现
- trace 前置 redaction 明确。
- 敏感字段最小化落盘思路正确。

### AutoDev
- 强日志系统但缺乏明显敏感信息处理层。

结论：你在“可审计合规日志”上明显更成熟。

---

## 4.9 测试与可演进性

### 你的实现
- `TurnToolPolicyTest`、`CognitionProfileRegistryTest`、`AgentTraceObservabilityTest`、`ContextPackagerTest` 已覆盖核心认知边界。

### AutoDev
- 其策略多依赖 prompt 行为与实跑结果，单元化程度较弱。

结论：你更容易做持续演进而不退化。

---

## 5. 关键差异清单（最细粒度）

1. AutoDev executor 内有“子循环”，你当前没有。  
2. AutoDev 有显式 `transcribe_screen` 工具链，你主要依赖 a11y tree。  
3. AutoDev prompt 对具体任务形态（count/duplicate/date-range）写死了大量规则；你采用可组合模板。  
4. AutoDev 有 screenshot hash + text hash 的反环启发式；你未实现同等级 UI 循环检测。  
5. AutoDev 支持 task difficulty -> model routing；你仅 profile->prompt variant。  
6. AutoDev 有 Anthropic cache 控制/统计；你无等价 token cache stats。  
7. 你的 trace 有 redaction，AutoDev 未体现。  
8. 你的 policy 解耦成独立引擎，AutoDev 主要在流程代码+prompt里隐式实现。  
9. 你的工具系统统一注册/过滤，AutoDev planner/executor 工具分层更硬。  
10. 你的 completion 决策可测，AutoDev completion 更依赖 `report()/finish_task` 纪律。

---

## 6. 改进建议（按优先级）

## P0（建议尽快）

1. **把 `RetryPolicy` 真正接入执行路径**  
- 现在 profile 里有 `retryPolicy` 字段，但未驱动 `AgentTurnRunner` 错误恢复策略。  
- 建议：将 recoverable 逻辑迁移到 policy 或 dedicated retry evaluator，并由 profile 控制。

2. **增加 UI 循环检测（screen signature + repeated action）**  
- 借鉴 AutoDev 的 `seen_screenshots/scroll_count`，但放到 `cognition/policy` 或 `context` 层。  
- 建议 artifact：`turn_loop_diagnostics.json`。

3. **把仲裁决策写入 trace**  
- 当前有 tool calls，但没有“为何丢弃其余 calls”的可读解释。  
- 建议记录 `dropped_tool_calls`, `reason`, `policy_mode`。

## P1（高价值）

4. **Profile 承载 model routing**  
- 扩展 `CognitionProfile`：`plannerModel`, `executorModel`, `routingPolicy`。  
- 支持按任务复杂度或失败次数切模型。

5. **扩展 ContextPackager**  
- 现在是 pass-through；建议加入：  
  - relative date grounding（当前设备日期）  
  - anti-loop warnings  
  - adaptive context budget（基于 token 压力切摘要）

6. **补一条 OCR fallback 路径（可选工具）**  
- 当 a11y 树质量差时，允许调用 OCR/transcription 工具并落 trace。

## P2（中期）

7. **Phase E 基准评测体系**  
- 固定任务集 + profile 对比报表（成功率、平均turn、工具调用数、重试率、循环率）。

8. **Prompt cache 与成本指标**  
- 记录输入 token、缓存命中、每 task 成本，形成 profile 经济性评价。

9. **策略 DSL/规则文件化**  
- 把高频战术规则（日期、计数、去重）从 prompt 巨文本迁到可测试规则层。

---

## 7. 推荐融合路线（你当前架构最优吸收方式）

1. 保持你现在的 Cognition 分层，不引入 AutoDev 式“单文件主导控制流”。
2. 从 AutoDev 借鉴的内容只吸收“可模块化能力”：
   - 反环诊断
   - 模型路由
   - 执行失败叙事摘要
   - 更细粒度 run replay（在现有 trace 上实现）
3. 禁止直接复制其超长 prompt；改为：
   - 规则分层（policy/context）
   - prompt 只做可读说明
   - 所有关键规则必须有测试

---

## 8. 一句话定位

你的 Cognition 已经从“能跑”进入“可持续演进”阶段；下一步应把 AutoDev 的强战术经验，转译为你现有 `profile/context/policy/trace` 的可测试能力，而不是转译为更长的 prompt。 
