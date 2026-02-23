# `.reference/mobile_agent` Click 实现调研总结（Codex + Subagents）

## 调研范围

- `.reference/mobile_agent/MobileAgent`（含 v3 / v3.5 / UI-S1 相关分支）
- `.reference/mobile_agent/autodevice_android_world`

## 结论先行

参考实现整体比我们当前链路简单很多：**模型给坐标 -> 直接执行 tap**。  
优点是路径短、可解释；缺点是语义鲁棒性和失败恢复能力弱。

---

## A) MobileAgent 分支（视觉坐标驱动）

### 1) Tool / Prompt 定义

- click 通常以 `action="click"` + `coordinate`（二维数组）出现。
- 常见约定是 0~1000 归一化坐标（分辨率无关）。

### 2) 目标定位（grounding）

- 由 VLM 在 screenshot 上直接预测点击点（无 a11y element_index）。
- 执行前做坐标缩放映射（normalized -> device pixels）。

### 3) 执行

- 主要是 ADB tap（`shell input tap x y`）单路径。

### 4) 重试/回退

- click 本身几乎没有多策略回退；
- 主要重试集中在 screenshot 抓取和 LLM 调用层。

### 5) 可观测性

- 有基础日志和标注截图（打点）；
- 但点击失败语义（遮挡/no-op/边缘）分类不细。

---

## B) autodevice_android_world 分支（索引或坐标 -> ADB tap）

### 1) 执行管线

- `JSONAction(click)` -> `actuation.execute_adb_action` -> `adb_utils.tap_screen`。
- `index` 路径：先 `UIElement bbox center`，再 tap。
- `coordinate` 路径：直接 tap。

### 2) 回退策略

- tap 级别几乎无 retry/fallback；
- retry 更偏基础设施层（LLM 调用、断连恢复）。

### 3) 设计特征

- 分层清晰：动作解析与低层注入是分开的；
- 但“点了没反应”后的恢复机制不足。

---

## 对我们可借鉴的点

1. **执行路径短**：减少 click runtime 的隐式分支，便于 debug。
2. **坐标归一化表达**：对多分辨率更稳定（可选，不必强制替换 element_index）。
3. **分层职责更硬**：`解析/定位` 与 `执行注入` 清晰分离。
4. **可视化打点**：每次点击保留点击点标注，排障效率高。

## 不能直接照搬的点

1. 纯 ADB tap 缺少语义选择器，不适合我们当前 a11y-first 架构。
2. 失败恢复过弱，无法解决 round2 里的遮挡/no-op/边缘等高频问题。
3. 缺少针对 Android 原生复杂控件（系统弹窗、可访问性节点异常）的策略。

## 迁移启发（面向 reimplement）

- 可以学习它们的“短链路思维”：  
  **把 click 执行层简化为 1 个主路径 + 1~2 个明确 fallback**，而不是多层启发式叠加。
- 保留我们现有 a11y 语义优势，但把失败分类和停止条件做成显式状态机。
