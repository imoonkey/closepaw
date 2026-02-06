# Code Navigation Refactor Plan

> Owner: Codex + Moonkey  
> Created: 2026-02-06  
> Status: In Progress (Phase 1 started)

## 1. Background

当前代码结构以“技术层分包”为主（`agent/session/tool/ui/...`），但实际高频改动是“问题场景驱动”（例如：历史恢复、设置链路、planner memory）。这导致处理单一问题时，经常横跨多个 folder tree，影响理解速度和维护稳定性。

本计划目标：

1. 缩小单次问题修复的目录跨度。
2. 降低反向依赖（尤其是 `history -> ui`）。
3. 提升“按功能定位代码”的可读性。

## 2. Evidence（来自当前代码与历史）

1. 平均每次提交涉及约 `2.48` 个顶层目录，最大达到 `10`。
2. 高频联动目录对：`agent|session`、`app|ui`、`agent|tool`。
3. 存在明显层次倒置：`history/model/MessageConverter.kt` 直接依赖 `ui.chat.model`。
4. 设置代码散在 `app/` 与 `ui/settings/`，设置链路变更时需要跨层跳转。
5. 核心超长文件（如 `AgentTurnRunner.kt`、`AccessibilityPlatform.kt`）职责密集，阅读成本高。

## 3. Refactor Principles

1. 先做“低风险高收益”的目录收敛，不先做大爆炸式迁移。
2. 每个 phase 都保持可编译、可回滚。
3. 优先消除反向依赖，再做进一步 feature 聚合。
4. 文档同步更新，防止 `doc/main` 与实现漂移。

## 4. Phased Plan

## Phase 1: 依赖纠偏 + 设置聚合（Low Risk）

1. `history -> ui` 依赖反转  
Action: 将 `MessageConverter` 移到 UI 侧（`ui/chat/history`），让 `history` 仅保留持久化模型与服务。

2. 设置代码同域聚合  
Action: 将 `AppSettingsState/AppSettingsStore` 从 `app/` 移至 `ui/settings/`，减少设置改动时的目录跨度。

3. 文档同步  
Action: 更新 `doc/main/app/history.md` 等受影响文档中的 file map。

Deliverable:
- 单个“历史恢复/设置”问题，主要集中在 `ui/` + `history/` 子树内处理。

## Phase 2: Planning Memory 聚合（Medium Risk）

1. 将 `Todo/Scratchpad` 的模型、状态与工具实现聚到 `feature/planning`（或等价命名域）。
2. 降低 `protocol/session/tool/agent` 四处分散修改频率。

Deliverable:
- planner memory 改动路径收敛到 1-2 个子树。

## Phase 3: Runtime 拆分（Medium/High Risk）

1. 拆分 `AgentTurnRunner`：`PerceptionPhase`、`PlanningPhase`、`ExecutionPhase`。
2. 拆分 `AccessibilityPlatform`：截图、手势、节点查找/动作执行辅助。

Deliverable:
- 核心流程文件长度与职责可控（更接近 <= 400 行/文件的约束）。

## 5. Risks & Mitigation

1. 包迁移导致 import 断裂。  
Mitigation: 每个 phase 后执行 Kotlin 编译校验。

2. 文档与代码再度漂移。  
Mitigation: 每次 phase 结束同步更新 `doc/main` 对应章节。

3. 过早大规模改目录影响迭代节奏。  
Mitigation: 仅分阶段落地，每阶段控制在可独立验证范围内。

## 6. Verification Strategy

1. Build: `./gradlew :app:compileDebugKotlin`
2. Search:
   - 禁止 `history` 再 import `ui.*`
   - 检查旧包路径引用是否残留
3. 文档检查：
   - `doc/main` 中 file structure 与实际路径一致

## 7. Execution Log

### 2026-02-06

- [x] 完成分析与分阶段计划落盘。
- [x] Phase 1-1: `MessageConverter` 迁移到 UI 侧。
- [x] Phase 1-2: `AppSettingsState/AppSettingsStore` 迁移到 `ui/settings`。
- [x] Phase 1-3: 文档同步 + 编译验证。

### 本次落地变更

1. `history/model/MessageConverter.kt` 删除，迁移至 `ui/chat/history/MessageConverter.kt`。
2. `ChatSessionHistoryController` 改为引用 UI 侧 converter。
3. `AppSettingsState/AppSettingsStore` 迁移到 `ui/settings/`（并更新 `MainActivity` import）。
4. 同步更新：
   - `doc/main/app/history.md`
   - `doc/main/app/settings.md`
5. 验证：
   - `./gradlew :app:compileDebugKotlin` 通过。
