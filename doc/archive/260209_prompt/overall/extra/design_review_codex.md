# Design Review: Claude vs Codex (Overall Prompt)

## 评审范围
仅基于以下 4 个文档进行比较（未阅读任何 `design_review*`）：
- `doc/todo/prompt/overall/overall_prompt_design_codex.md`
- `doc/todo/prompt/overall/01_prompt_structure_design_claude.md`
- `doc/todo/prompt/overall/02_design_decisions_claude.md`
- `doc/todo/prompt/overall/03_implementation_plan_claude.md`

## 总结论
从“设计思考质量（groundedness、边界清晰度、迁移风险控制、可验证性）”看，**Codex 方案整体优于 Claude 方案**。

- Codex 优势：更贴近当前代码事实、边界定义更稳、迁移节奏更可控。
- Claude 优势：信息顺序设计清晰（history -> memory -> observation），对模型阅读路径考虑更直接。
- 推荐：**以 Codex 为主干实现**，吸收 Claude 的局部表达策略（尤其是输入顺序与 warning 放置）。

## 主要发现（按严重度）

### High
1. Claude 在“最小手术”目标下引入了高侵入状态模型改造，和其自身原则不一致。
- 其原则写明 “Surgery, Not Rewrite”（`doc/todo/prompt/overall/03_implementation_plan_claude.md:8`），但实际要求把 screen observation 持久化进 history、给 `ResponseItem.Message` 增加 `isScreenObservation`、在 prompt build 阶段再压缩（`doc/todo/prompt/overall/02_design_decisions_claude.md:139`, `doc/todo/prompt/overall/03_implementation_plan_claude.md:205`, `doc/todo/prompt/overall/03_implementation_plan_claude.md:228`）。
- 这会把“运行时视觉上下文”与“会话历史”耦合在一起，影响历史压缩、回放一致性和后续维护复杂度。

2. Claude 的历史压缩方案在实现层面偏脆弱，依赖字符串内容再解析。
- 示例实现使用正则从 message content 里提取 element count 后再生成压缩文本（`doc/todo/prompt/overall/03_implementation_plan_claude.md:168`）。
- 这种“先序列化为文本，再反解析”的策略对 prompt 文本格式变化高度敏感，不利于长期演进。

### Medium
3. Codex 的边界设计更干净：history、memory、screen_window 三层分离，不把当前轮附加输入写回 history。
- 见其显式说明（`doc/todo/prompt/overall/overall_prompt_design_codex.md:94`）。
- 对比 Claude 的“screen observations 入 history”策略（`doc/todo/prompt/overall/02_design_decisions_claude.md:139`），Codex 在语义上更稳定，且更容易做 token guard 和局部替换。

4. 两者对 reminder 策略取向不同，Codex 更激进去噪，Claude 更保守。
- Codex 仅保留 critical（强 loop + 最终 turn）（`doc/todo/prompt/overall/overall_prompt_design_codex.md:120`）。
- Claude 保留 turn budget/final turn/loop，移除 todo reminder（`doc/todo/prompt/overall/02_design_decisions_claude.md:84`）。
- 在 executor 多步任务中，是否保留“approaching budget”提示需要 A/B 验证；这点 Claude 的风险意识更保守。

5. Codex 的 phased rollout 依赖关系表达更正确。
- 它明确 Task 3（去 tool observation）与 Task 4（screen window）需要绑定上线（`doc/todo/prompt/overall/overall_prompt_design_codex.md:193`），这在系统行为上是关键约束。
- Claude 虽也分 phase，但其方案把数据模型改动提前，导致阶段间回滚成本更高。

### Low
6. Claude 的“LLM 阅读叙事顺序”思路值得保留。
- `Single responsibility` 与结构顺序定义是该方案最强点（`doc/todo/prompt/overall/01_prompt_structure_design_claude.md:12`）。
- 这部分可以直接嫁接到 Codex 的 assembler 设计中，不需要引入 history 数据结构扩展。

## 维度评分（设计思考本身）
- Grounded 在现状代码：Codex 9/10，Claude 7/10
- 架构边界与职责分离：Codex 9/10，Claude 7/10
- 迁移风险与可回滚性：Codex 8.5/10，Claude 6.5/10
- 可测试性与验收闭环：Codex 8.5/10，Claude 8/10
- 信息组织对模型友好度：Codex 8/10，Claude 9/10

## 建议的融合方案
1. 采用 Codex 主架构：`TurnPromptAssembler + ScreenWindowState(ring buffer)`，保持 history 不承载 full screen。
2. 吸收 Claude 的顺序策略：严格 `history -> memory -> current observation`，且 warning 放在 observation 顶部。
3. 保留 Codex 的上线约束：Task 3 与 Task 4 绑定发布。
4. reminder 策略先走折中：保留 loop + final turn；`approaching budget` 通过实验开关控制，而不是默认常开。

## 最终评价
如果目标是“快速、安全、可验证地重构 prompt 管线”，**Codex 方案是更稳的主方案**；Claude 方案更像“理想结构化叙事方案”，其中一部分表达层设计可复用，但其核心状态建模改造不建议原样落地。
