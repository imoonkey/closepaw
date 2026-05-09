# Android Agent Memory 落地路线（Codex）

## 1. 目标
用最小风险方式把 memory system 接入现有 Android agent，优先提升“任务执行成功率与稳定性”，而不是追求 memory 功能完整度。

## 2. Phase 0（1~2 周）：最小可用长期记忆
交付：L1 episodic + L2 profile 的本地版，不改你现有 turn loop 主干。

范围：
- 新增本地 memory store（SQLite）。
- 新增两类记忆：`EPISODE`, `PROFILE`。
- 仅在关键事件写入：`TaskCompleted`, `User纠正`, `重复失败`。
- Prompt 仅注入 top-k（3~5 条）长期记忆摘要。

验收指标：
- 相似任务重复执行时，平均 turn 数下降。
- 重复错误（同 app 同动作）频率下降。
- token 增量可控（每轮 memory 注入上限固定）。

## 3. Phase 1（2~4 周）：程序性记忆（L3）
交付：面向 app 自动化的 procedural memory。

范围：
- 新增 `PROCEDURE` 记忆类型（按 `app + intent` 聚合）。
- 记录成功动作模式与高频失败模式。
- 检索路由加优先级：执行场景优先查 PROCEDURE。

验收指标：
- 首次动作命中率提升（更少无效点击/无效滚动）。
- loop warning 触发率下降。

## 4. Phase 2（4+ 周）：检索与治理增强
交付：更稳的检索质量与更强的用户可控性。

范围：
- 增加去重强化（reinforcement）和过期衰减策略。
- 增加 memory explainability（每条记忆显示来源与更新时间）。
- 设置页支持：分类开关、按 app 清理、全部清空。
- 可选接入向量检索（设备能力与配置开关控制）。

验收指标：
- 用户可解释率提升（能看懂“为什么注入这条记忆”）。
- 长期运行下 memory 体积增长可控，无明显性能回退。

## 5. 建议的技术边界
建议做：
- Local-first。
- Scope-first（至少 user/device/app）。
- 预算化检索与注入（严格 top-k + token cap）。

建议不做（当前阶段）：
- 全量云端记忆依赖。
- 重型图数据库与复杂多租户权限模型。
- 过度主动记忆推送（先保证检索正确，再做主动）。

## 6. 风险与规避
主要风险：
- 记忆噪声过多，反而干扰决策。
- 敏感信息误写入长期记忆。
- 检索命中不准导致错误动作被“强化”。

规避策略：
- 写入 gate + 去重强化 + TTL/衰减。
- 敏感信息脱敏与 app denylist。
- 只在“高置信事件”写长期记忆，低置信事件先不写。

## 7. 推荐的最先实现顺序
1. 事件驱动写入框架（先不做复杂检索）。
2. L1/L2 基础检索 + Prompt 注入。
3. 指标埋点与 A/B 对比（有数据再扩展）。
4. L3 procedural memory。
