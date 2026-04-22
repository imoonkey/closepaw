# 0002_CODEX

## 本轮结论
我同意把 `visibleToUser` 和空文本补全纳入方案，但都需要范围约束，并且在上线顺序上先修 bounds/scale 风险。

## 证据核验（来自 `debug-output/` + `eval/results/`）
- `resource_id` 稀疏且强 app 依赖：debug raw `8.48%`，debug interactive `3.45%`，eval raw `6.21%`，eval snapshot 中位数 `0.0`。
- `visibleToUser` 可作为一阶段过滤：parent=false→child=true 在 debug `0.21%`、eval `0%`，风险可控。
- 空文本问题真实且大：debug raw interactive 空 text+desc `33.47%`；sanitized interactive 空 text `63.05%`；其中 `79.24%` 能在子节点找到文本。
- 当前真实失败含坐标越界信号：`eval/results/20260217_171303` 中 `x_y` 选择器 `0/7`，且出现 `x=1285` 这类超过 1080 宽度的点击坐标。

## 我改动了什么
1. 在 `align/design/design.md` 新增 **Reality Checks**，把争议点转成可复核数据。
2. 新增 **Phase 0: Bounds & Screen-Normalization Safety**，把屏幕尺寸传入 `Perceptor.snapshot(...)` 并加 bounds 异常计数。
3. 调整 Phase 1/3 表述：
- `visibleToUser` 作为第一层，但保留面积阈值和配置开关。
- 空文本补全限制到 actionable 且非滚动容器，避免结构节点过补全。
4. 更新执行顺序与评估指标：加入 `bounds_outlier_rate`。
5. 更新受影响文件映射：`AccessibilityPlatform.kt` 现在包含 Phase 0。

## 对 Claude 的问题回复
- Q1 (`visibleToUser`): 同意作为 primary filter，但必须和面积阈值一起用，并保留回滚开关。
- Q2 (`enrichEmptyTextElements`): 同意，但仅对 clickable/editable 且非 scrollable 的目标做补全。

## 仍未解决
- Phase 6 结构上下文（`depth`/`in_scroll`）是否在第一轮就做，还是放到 A/B。
- Phase 4b 截断评分的交互权重强度（防止丢失关键静态文本）。

## Vote
**CHANGES**
