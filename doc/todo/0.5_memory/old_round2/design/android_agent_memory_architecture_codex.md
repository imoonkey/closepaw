# Android Agent Memory System 建议（Codex）

## 1. 先给结论
面向你当前 Android agent（ReAct 回路 + a11y 感知 + session/history/todos/scratchpad）的现实约束，我建议采用 **Local-first、分层记忆、策略化写入、预算化检索** 的 memory system。

最关键不是“再加一个向量库”，而是把现在已有的 `History + Todo + Scratchpad` 提升为一套明确分工的 memory architecture。

## 2. 现状与约束（基于当前项目）
你已经有很强的短期记忆基础：
- `HistoryManager`：对话与工具调用历史、屏幕观察压缩、token 预算治理。
- `TodoState` / `ScratchpadState`：跨 turn 的 working memory。
- `SessionCheckpointCoordinator`：进程死亡恢复。

Android 侧的真实约束：
- 端上资源有限（CPU/内存/电量），记忆系统不能太“重”。
- 数据敏感（a11y tree、聊天内容、账号信息），需要默认本地优先。
- 任务是“动作导向”的，memory 必须服务于 action success，而不是只做问答 recall。

## 3. 建议的目标架构（四层）

### L0: Working Memory（已存在，继续保留）
- 组成：`History + Todos + Scratchpad + 当前屏幕观察`。
- 作用：当前任务的强时效上下文。
- 策略：保持你现有的压缩与预算机制，不改为“永久记忆”。

### L1: Episodic Task Memory（建议新增）
- 组成：任务片段（目标、关键步骤、失败点、最终结果、涉及 app/package）。
- 作用：让 agent 下次遇到类似任务时少走弯路。
- 来源：任务完成、失败、强纠错事件后写入。

### L2: Semantic Profile Memory（建议新增）
- 组成：用户稳定偏好与事实（语言偏好、常用 app、通知习惯、风格偏好）。
- 作用：减少重复问答和重复配置。
- 策略：只存“稳定信息”，高波动信息不进该层。

### L3: Procedural App Memory（建议新增，最贴合 Android 自动化）
- 组成：按 `app + intent` 聚合的操作经验（常用入口、常见阻塞、成功动作序列模板）。
- 作用：提升 UI 自动化成功率与速度。
- 形式：不是硬编码脚本，而是“可检索的策略片段”。

## 4. 写入架构建议（Write Path）
写入必须是“有门槛的”，不要把所有 turn 都存成长期记忆。

建议写入流水线：
1. Candidate 收集：从 `TaskCompleted`、工具失败、用户纠正、显式偏好表达中抽取候选记忆。
2. 类型分类：判定进 L1/L2/L3，或丢弃。
3. 脱敏与规范化：去除账号、验证码、可识别隐私等高风险字段。
4. 去重/强化：同类记忆做 reinforcement（计数+最近时间），避免无限堆积。
5. 持久化：按 scope 写入（至少 `user_id + device_id + app_package + agent_role`）。
6. 汇总：定期把高频 episode 汇总成更短的 retrieval 单元。

这一步主要借鉴：
- memU/mem0 的“dedupe + reinforcement + scope-first”
- openclaw 的“生命周期钩子触发写入”

## 5. 检索架构建议（Read Path）
检索建议做 **分级与预算控制**，不要每轮都全库检索。

建议流程：
1. Retrieve Gate：先判断“需不需要检索”（当前 turn 信息足够就不检索）。
2. Route：按意图路由检索层级：
   - 动作执行场景优先 L3（procedural）
   - 目标相似场景查 L1（episodic）
   - 用户偏好冲突时查 L2（profile）
3. Re-rank：结合 `当前 app、任务目标、最近失败信号` 做二次排序。
4. Budget Pack：只注入少量高价值 memory（例如 3~6 条），并给简洁证据来源。

这一步主要借鉴：
- memU 的分层检索与 sufficiency gate
- Letta 的“核心记忆少量稳定注入”
- OpenViking 的层级上下文装配思路

## 6. 存储形态建议（Android 友好）
建议 **Local-first 双通道**：
- 结构化存储：SQLite（记忆元数据、scope、reinforcement、timestamps）。
- 检索存储：
  - P0: 先用关键词 + 规则检索（轻量）
  - P1: 再加向量检索（sqlite-vec 或独立向量后端，按设备能力开关）

不建议一开始就上重型多后端（图数据库 + 远程服务）。先让端上方案跑稳。

## 7. 与当前代码结构的贴合建议
建议新增 `memory/` 包，而不是侵入式改 `history/`：
- `memory/domain/`：Memory 类型与策略（Episode/Profile/Procedure）
- `memory/store/`：SQLite repository + optional vector adapter
- `memory/pipeline/`：WritePipeline / RetrievePipeline
- `memory/runtime/`：`MemoryOrchestrator`（对外入口）

建议接入点：
- 写入钩子：`TurnExecutionPhaseRunner`、`TaskCompleted`、`TurnErrorClassifier` 后。
- 检索注入：`PromptBuilder.buildMemorySection()` 之前追加“Long-term memory section”（预算可控）。
- checkpoint：长期记忆独立于 session checkpoint，不随会话销毁。

## 8. 安全与隐私建议（必须）
- 默认本地存储，远程同步作为可选功能且默认关闭。
- 对 a11y 文本做敏感字段检测（手机号、验证码、卡号、token 等）后再写记忆。
- 增加 app 级 denylist（如银行/支付类 app 默认不写长期记忆）。
- 给用户“查看/删除/禁用某类记忆”的入口（至少设置页开关 + 清空）。

## 9. 从参考系统借鉴什么，不借鉴什么
建议借鉴：
- MemOS：多记忆平面思想（而不是单一向量记忆）。
- memU/mem0：scope-first、reinforcement、分层检索。
- Letta：核心记忆与归档记忆分离。
- openclaw：生命周期钩子、compaction 前后记忆落盘思路。

建议暂不引入：
- PageIndex 那种重 LLM 树搜索主路径（端上成本偏高）。
- Second-Me 那种强 persona 建模（你现在优先级是任务执行稳定性）。
- 过早引入复杂多租户/RBAC（当前是单用户 Android agent 语境）。

## 10. 一句话落地策略
先做“**L1 episodic + L2 profile 的轻量版**”，把长期记忆真正接到当前执行 loop 上，确认能提升任务成功率，再演进到 L3 procedural memory。
