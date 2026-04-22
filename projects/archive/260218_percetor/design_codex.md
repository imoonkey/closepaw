# Perceptor Improvement Plan (Codex)

## Summary
目标是把当前 `Perceptor` 从“可用的 a11y 元素抽取器”升级为“高命中率、低噪声、可调优的感知管线”，重点提升三件事：
1. selector 命中率（尤其是重复文本、弱文本页面）
2. token 效率（减少无效节点）
3. capture 稳定性（空树/抖动场景）

本计划参考了 `.reference/mobile_agent` 下多个 agent 的 a11y tree 处理实现，并映射到当前代码结构。

## Reference Patterns（.reference/mobile_agent）

### 1) AndroidWorld
- 参考文件：
  - `.reference/mobile_agent/autodevice_android_world/android_world/env/representation_utils.py`
  - `.reference/mobile_agent/autodevice_android_world/android_world/env/android_world_controller.py`
- 可迁移策略：
  - 统一 UIElement schema（文本、状态、bounds、resource id）
  - bbox 像素/归一化双表示
  - a11y 获取重试与回退（`max_retries`）

### 2) DroidRun
- 参考文件：
  - `.reference/mobile_agent/droidrun/droidrun/tools/filters/concise_filter.py`
  - `.reference/mobile_agent/droidrun/droidrun/tools/filters/detailed_filter.py`
  - `.reference/mobile_agent/droidrun/droidrun/tools/formatters/indexed_formatter.py`
  - `.reference/mobile_agent/droidrun/droidrun/tools/ui/state.py`
- 可迁移策略：
  - 分层过滤（最小尺寸、屏幕相交、可见面积阈值、键盘过滤、边界裁剪）
  - 过滤后再索引/格式化
  - 遮挡感知 tap 点（不是固定中心点）

### 3) Minitap Mobile-Use
- 参考文件：
  - `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/clients/ui_automator_client.py`
  - `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/utils.py`
  - `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/utils/ui_hierarchy.py`
- 可迁移策略：
  - 保留宽字段（resource-id、content-desc、flags）
  - `resource_id_index` / `text_index` 显式化
  - ID 与 text 不一致时降级（防止误点）

### 4) MAI-UI
- 参考文件：
  - `.reference/mobile_agent/MAI-UI/src/base.py`
  - `.reference/mobile_agent/MAI-UI/src/unified_memory.py`
- 可迁移策略：
  - 原始 accessibility tree 作为轨迹证据保留（便于回放和误判排查）

## Current State Gap（当前项目）

### 已有能力
- `Perceptor` 已有两阶段遍历（交互优先 + 全量补充）
- 已有基础过滤：最小尺寸、屏幕相交、键盘节点过滤、bounds 裁剪
- 已有 `text_index`（重复文本消歧）
- `TargetResolver` 已有遮挡点位尝试（比纯中心点更稳）

### 关键缺口
- prompt JSON 缺少 `resource_id` 及其 occurrence index（目前主要靠 `element_index`/`text`）
- 缺少可见面积阈值过滤（仅相交判断，噪声仍偏高）
- 索引顺序主要受遍历顺序影响，跨帧稳定性不足
- capture 没有显式重试/质量标记（空树原因不透明）
- 缺少“compact prompt schema vs debug schema”的明确分层

## Phases

### Phase 1: Schema Upgrade（低风险）
涉及文件：
- `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/model/Models.kt`

改动：
1. 在 prompt JSON 增加 `resource_id`。
2. 增加 `resource_id_index`、`desc_index`（保留现有 `text_index`）。
3. 补充轻量状态位：`enabled`（可选保留 `focused`/`editable` 现有字段）。

收益：提升 selector 表达力，降低重复元素误选率。

### Phase 2: Filtering Pipeline（中风险）
涉及文件：
- `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/perception/PerceptionConfig.kt`

改动：
1. 新增可见面积比例过滤（默认阈值建议 0.1，对齐 DroidRun detailed filter 思路）。
2. 过滤规则参数化：`min_element_size_px`、`visibility_threshold`、`filter_keyboard`、`clip_bounds`。
3. 保留“父节点可见度低但子节点有效”场景（避免误删容器链路）。

收益：减少半屏外/边缘噪声节点，降低 token 占用。

### Phase 3: Stable Ordering & Selection（中风险）
涉及文件：
- `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt`

改动：
1. 改为稳定排序后再分配 index（如 `top -> left -> area -> class`）。
2. 以“交互性 + 文本/ID 信息密度 + 可见性”打分截断，而非纯 DFS 前 80。
3. 对重复键（同 id/同 text/同 bounds）做更可控去重策略。

收益：减少 index 抖动，提升 `element_index` 可复用性。

### Phase 4: Capture Robustness（中风险）
涉及文件：
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt`

改动：
1. `rootInActiveWindow` 增加短重试（如 2-3 次小延迟）。
2. 在 debug 元数据中记录 capture 质量（是否重试、是否空树、元素数）。
3. 空树时输出明确 reason hint（区分“真空树”与“采集失败”）。

收益：减少瞬时空树造成的错误决策。

### Phase 5: Selector Contract Alignment（中风险）
涉及文件：
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/TargetResolver.kt`

改动：
1. 评估加入 `resource_id` / `resource_id_index` 目标类型（保持 single-target 约束）。
2. 引入 ID-text 一致性校验（借鉴 Minitap mismatch guard 思路）。
3. 与 Perceptor 输出字段保持 1:1 语义对齐，减少 LLM 参数漂移。

收益：语义 selector 更稳，复杂页面（重复文案）鲁棒性更高。

### Phase 6: Rollout & Measurement（低风险）
涉及文件：
- `eval/` 相关评估脚本与结果对比流程

改动：
1. 建立前后对比指标：
   - action 成功率
   - 首次命中率
   - 平均每轮 a11y token
   - 空树率/重试率
2. 先灰度开启（配置开关），通过回放任务集验证后再默认启用。

## Risks & Mitigations

1. 风险：过滤过激导致关键节点被删。
- 缓解：阈值配置化 + 回放集 AB 对比 + 保留交互节点白名单。

2. 风险：字段增加导致 prompt 膨胀。
- 缓解：保留 compact schema（仅行动相关字段），debug 字段只入 trace。

3. 风险：index 规则调整影响现有行为。
- 缓解：引入特性开关，先在 eval 集回归，再切默认。

4. 风险：新 selector 与 tool schema 不一致。
- 缓解：先定义统一契约文档，再同时改 Perceptor 与 MobileActionTool。

## Testing Strategy

### Unit
- `Perceptor`：可见面积过滤、键盘过滤、occurrence index、排序稳定性。
- `TargetResolver`：重复 text/resource_id 选择、遮挡点选择。

### Integration
- `AccessibilityPlatform.captureScreen()`：重试路径、空树路径、trace 元数据。
- `mobile_action`：新 selector 合法性与错误提示。

### Eval
- 使用现有任务集做 A/B：
  - Baseline（当前 Perceptor）
  - Improved（Phase 1-4，Phase 5 可选）

## Affected Components (Planned)
- `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/perception/PerceptionConfig.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/model/Models.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/TargetResolver.kt`
- `app/src/test/...`（新增/更新相关单元测试）

## Suggested Execution Order
1. Phase 1（schema）
2. Phase 2（filter）
3. Phase 3（stable index）
4. Phase 4（capture robustness）
5. Phase 6（评估）
6. Phase 5（selector 扩展，按评估收益决定是否落地）

## Reality Check (debug-output + eval/results)
本节基于 `visual-debug` 工作流对真实 trace 做了统计校验，避免“方案正确但前提错误”。

### Dataset
- `debug-output/**/artifacts/raw_a11y_tree/*.json`: 3202 files
- `debug-output/**/artifacts/sanitized_a11y_tree/*.json` + `tool_observation_screen/*_screen.json`: 4987 files
- `eval/results/**/per_task.jsonl`: 4 runs, 26 task rows
- `eval/results/**/trace/trace.jsonl`: 19 traces

### Evidence
1. `resource_id` 确实稀缺，且分布极不均匀。
- Raw node 维度（debug-output）：`viewIdResourceName` 非空占比 `8.48%`。
- 交互节点维度（clickable/editable/scrollable）：非空占比仅 `3.45%`。
- 可见+交互节点维度：非空占比 `2.58%`。
- 按 snapshot 统计：`P50 = 0.0`（半数页面一个 id 都没有）。

2. Eval 场景中 `resource_id` 更弱。
- `eval/results/**/raw_a11y_tree`：id 非空占比 `6.21%`，snapshot `P50 = 0.0`。
- 包级差异明显：`com.android.htmlviewer` 有 id（~37%），`android`/`documentsui`/`chrome` 几乎无 id。

3. selector 实际使用与成功率（eval）。
- `mobile_action` 59 次：`element_index=43`, `x_y=7`, `text=2`, `none=7`。
- 成功率：
  - `element_index`: `20/43`（约 46.5%）
  - `x_y`: `0/7`
  - `text`: `0/2`
  - `none`（多为无目标动作）: `7/7`

4. 失败集中在低元素页面 + 特定包。
- 包级失败集中：`android`、`com.android.htmlviewer`。
- `screen_captured.elements <= 8` 时失败明显增多。
- 典型失败信息重复出现：`Click at (...) failed after all attempts`。

5. 目标可点击性影响显著（eval）。
- `element_index` 指向 `non_clickable`：`11 success / 16 failure`。
- `element_index` 指向 `clickable`：`9 success / 7 failure`。
- 说明当前“可读文本节点”被索引后常被误当作可操作目标。

### Plan Adjustment (based on data)
1. `resource_id` 不应作为近期主方案。
- 调整为“条件启用”字段：仅在当前页面 id 密度达到阈值时增强提示，不作为默认 selector 主路径。

2. 优先级上移：可点击目标优先与点击可达性。
- `element_index` 排序/截断策略中，提高 `isClickable/isEditable/isScrollable` 权重。
- 对 click/long_press 的候选目标增加“可点击优先”约束，降低 TextView-only 目标权重。

3. 优先级上移：坐标/边界健壮性。
- 在 `AccessibilityPlatform.captureAccessibilityTree()` 传入屏幕尺寸到 `Perceptor.snapshot(...)`，确保 bounds 裁剪生效。
- 对异常坐标（越界/比例异常）做运行时保护和诊断埋点。

4. Eval 指标新增“可操作性”维度。
- 除成功率外，新增：
  - `non_clickable_target_rate`
  - `selector_success_by_package`
  - `low_element_screen_failure_rate` (`elements<=8`)
