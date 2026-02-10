# Shizuku Virtual Display 设计评审（Codex）

## 评审范围
- 评审对象：`shizuku_design_1.md`、`shizuku_design_2.md`
- 参考约束：`qi_note.md`
- 评审重点：设计思考质量（需求对齐、架构、可落地性、风险控制），不评价写作风格。

## 先看硬约束（来自 qi_note）
1. 采用 Shizuku 方案。
2. UI 需满足：
   - 真实屏幕有“灵动岛入口”；
   - 完整 capsule 在 Virtual Display 页面；
   - 底部上滑退出页面但任务继续。
3. 对不确定 API 做可靠性核验。
4. Shizuku 不可用时回落到主屏方案。
5. VirtualDisplayPlatform 仍使用 a11y tree，并尽量复用 AccessibilityPlatform 代码。

### 硬约束命中情况
| 项目 | design_1 | design_2 |
|---|---:|---:|
| Shizuku 主方案 | ✅ | ✅ |
| UI 三条交互 | ❌（明确不覆盖） | ✅ |
| API 可靠性核验 | ⚠️（有结论，引用不足） | ✅（有来源） |
| 不可用时 fallback | ✅ | ❌（明确反对 fallback） |
| 使用 a11y tree + 复用倾向 | ✅（有 a11y/filter） | ❌（V1 明确 `elements=emptyList`） |

## 分维度评分（1-10）
> 分数偏重 `qi_note` 约束与工程可落地性。

| 维度 | design_1 | design_2 | 评价要点 |
|---|---:|---:|---|
| 需求对齐度（qi_note） | 7.0 | 5.5 | design_1 缺 UI；design_2 与 fallback/a11y 两条硬约束冲突。 |
| 架构贴合现有系统 | 9.0 | 7.5 | design_1 改动半径小；design_2 引入更多新层和模型改造。 |
| 生命周期与资源模型 | 7.0 | 9.0 | design_2 明确把 `start/stop` 放进平台生命周期，设计更完整。 |
| API 可行性验证严谨度 | 6.5 | 9.5 | design_2 给出 AOSP/官方来源链路，可信度更高。 |
| 动作语义正确性 | 8.0 | 6.0 | design_1 保留 node-action 与 inject 分工；design_2 把 node action 降级为坐标语义有退化。 |
| 感知路径设计 | 8.5 | 5.5 | design_1 是 a11y + screenshot 混合；design_2 V1 只截图，损失结构化语义。 |
| 失败/降级策略 | 8.5 | 4.0 | design_1 与产品 fallback 诉求一致；design_2 “失败即停”不符合当前要求。 |
| UI 交互设计完成度 | 2.0 | 9.0 | design_1 明确不覆盖；design_2 对三条交互有直接设计。 |
| 实施与测试可执行性 | 8.0 | 8.5 | 两者都给了 phase/test，design_2 在验收条目更产品化。 |
| 可维护性（中期） | 8.5 | 7.0 | design_1 更克制；design_2 “删旧路径+不兼容”在迁移期风险更高。 |

### 综合分
- `design_1`: **7.3/10**
- `design_2`: **7.2/10**

结论：两份方案质量接近，但都不能原样落地。`design_1` 在当前约束下略优（更贴合 fallback/a11y 要求）；`design_2` 在 UI 与 API 证据链上明显更强。

## 关键设计问题（按严重度）

### P0（必须改）
1. `design_1` 未覆盖 UI 三条硬需求，无法满足产品验收。
2. `design_2` 明确拒绝 fallback，直接违背 `qi_note` 的运行策略。
3. `design_2` 将 V1 感知降到 screenshot-only，与“仍用 a11y tree 并复用现有能力”冲突。

### P1（应改）
1. `design_1` API 核验证据链不足，建议补充到 AOSP/官方文档级别（像 design_2 一样）。
2. `design_1` 生命周期虽然有 `start/stop`，但未纳入统一接口契约，长期容易漏释放。
3. `design_2` 将 `ClickNodeAt/LongClickNodeAt` 直接等价成坐标注入，语义精度和可解释性下降。

### P2（可优化）
1. 两份方案都应明确“a11y 不可见窗口/空树时”的一致退路（仅 screenshot + OCR/LLM）。
2. 两份方案都可补一个 capability probe 结果模型（display create、inject、launch 各自是否可用）。

## 建议的合并版方向（推荐）
1. 以 `design_1` 的平台主骨架为基础（小改动、与现有架构贴合）。
2. 合入 `design_2` 的 UI 设计（MiniIsland + VirtualDisplayActivity + 上滑仅退出页面）。
3. 合入 `design_2` 的 API 核验方式（每个关键 API 都附官方/AOSP来源）。
4. 生命周期采用折中：给 `AndroidPlatform` 增加 `start/stop`（或等价资源管理约定），避免 session 清理遗漏。
5. 保持混合感知：优先 a11y tree（displayId 过滤）+ screenshot，空树时再退到 screenshot-only。
6. 明确 fallback：Shizuku unavailable/permission denied/binder dead 时切回 `AccessibilityPlatform`，并在 UI 上可见地提示模式切换。

## 最终一句话
- **最佳落地路径不是二选一，而是 “design_1 的架构约束 + design_2 的 UI/API 证据链”。**
