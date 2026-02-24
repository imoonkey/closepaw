# P1: DocumentsUI Move 按钮缺失分析与建议（Codex）

## 结论（更新版）

这是 **感知覆盖边界问题**，核心不是 prompt 推理。

- Agent 已进入正确目录（DCIM），但确认动作按钮在 a11y 树缺失。
- 单 root（`rootInActiveWindow` 或 `TYPE_APPLICATION` first root）模式会漏掉分层窗口中的关键动作栏。
- 正确方向不是“全量放开所有窗口”，而是：**display-scoped（按目标屏幕）+ multi-root（多窗口）+ staged filtering（分层过滤）**。

本次讨论新增约束：
- 要同时覆盖 `ACCESSIBILITY` 与 `VIRTUAL_DISPLAY` 两种 mode。
- 不能主屏和虚拟屏混采。
- 在抓到 `Move` 按钮的前提下，尽量不引入垃圾信息。

## 需求澄清（来自讨论）

目标不是“采更多”，而是“采对”：

1. 每个 mode 只采它的目标 display。
2. 目标 display 上要尽可能覆盖“当前可见窗口”的关键交互元素。
3. 默认保持低噪声，仅在必要时扩展窗口类型。

## 现状与缺口

### 1) Accessibility mode

- 当前路径：`AccessibilityPlatform.captureAccessibilityTree()` 仅使用 `service.rootInActiveWindow`。
- 问题：如果底部动作栏在同 display 的其他 window root，当前实现看不到。

### 2) Virtual display mode

- 当前路径已按 display 过滤窗口（`VirtualDisplayWindowAccessor.getWindowsOnDisplay()`）。
- 但 capture 仍只取一个 root（`TYPE_APPLICATION` 优先）。
- 问题：同一虚拟屏内部的其他可见窗口仍可能漏采，和 Accessibility mode 的漏采本质一致。

## 设计原则（推荐）

### 原则 A: Display Scoped First

任何 multi-window 采集都必须先确定 `targetDisplayId`，再过滤 windows：
- `ACCESSIBILITY`：target display = 当前 active window 所在 display（回退默认屏）。
- `VIRTUAL_DISPLAY`：target display = `displayIdProvider()` 的虚拟屏 id。

这样可以确保不会把主屏和虚拟屏混在同一份 snapshot。

### 原则 B: Multi-Root but Not Multi-Noise

同一 display 的多 root 要采，但采用分层策略：
- Pass 1（默认）：低噪声窗口集合（优先应用窗口）。
- Pass 2（条件触发）：仅当关键动作缺失时，临时放开额外窗口类型。

### 原则 C: Unified Merge

不做“每窗口单独 snapshot 再拼接”，改为统一候选池后再去重/排序/截断，保证：
- 索引稳定
- 重复可控
- 高价值交互元素保留率更高

## 推荐方案：Display-Scoped Multi-Root Capture（双模式统一）

### Step 1: 统一窗口采集接口

新增统一入口（命名可调整）：
- `WindowRootCollector.collectRoots(mode, targetDisplayId, policy)`

返回结构建议包含：
- `windowId`
- `displayId`
- `windowType`
- `packageName`
- `layer`
- `root`

### Step 2: 严格 display 过滤

- Accessibility mode：
  - 从 active root/window 解析 displayId。
  - 在 API 33+ 使用 `getWindowsOnAllDisplays()[displayId]`。
  - 低版本使用 `service.windows.filter { it.displayId == targetDisplayId }`。
- Virtual display mode：
  - 使用现有 `displayIdProvider`。
  - 只取该虚拟屏 display 的 windows。

### Step 3: 分层窗口策略（降噪关键）

建议窗口策略如下：

- Pass 1（默认主路径）
  - 仅 `TYPE_APPLICATION`（外加同包高置信窗口）。
  - 排除 `TYPE_ACCESSIBILITY_OVERLAY`（避免采到自家 overlay）。
  - 保留现有 `PerceptorFilterConfig`：`visibleToUser`、尺寸阈值、`maxElements`、keyboard filter。

- Pass 2（仅条件触发）
  - 触发条件：处于特定高风险场景且关键动作缺失。
  - 例如：检测到 `"Move to…"` 但缺少 `"Move"`/确认按钮。
  - 临时加入少量非应用窗口类型（同 display），再做一次 capture merge。

这样能在“尽量不增噪”的同时提高关键按钮召回率。

### Step 4: 统一 Perceptor 合并

在 `Perceptor` 新增多 root 入口（或内部候选池 API）：
- 输入：`List<RootWithMeta>`
- 输出：单一 `ScreenSnapshot`
- 流程：collect -> dedup -> enrich -> truncate -> spatialSort -> index

可选：在 debug artifact 中标注元素来源 `windowId/windowType`，仅用于排障，不进 prompt 主体。

## 关于“会不会采垃圾信息”的明确回答

如果直接把全部窗口全开，确实可能引入状态栏/输入法/系统浮层噪声。

但按本方案（display 过滤 + Pass1/Pass2 + 现有 Perceptor 过滤）：
- 默认不会明显增加垃圾信息。
- 仅在“关键动作缺失”时短暂扩采。
- 扩采也只在目标 display 内，不会跨屏污染。

## 兜底策略（次优）

若 multi-root 仍未抓到 Move，可加受限兜底（仅 DocumentsUI move 对话框）：
- 条件：存在 `"Move to…"` 且已在目标目录。
- 行为：底部右侧区域 `gesture_tap` 一次探测。
- 强校验：源目录消失 + 目标目录出现后才认定成功。

该兜底应挂在 feature flag 下，作为临时保障，不替代通用感知修复。

## 建议改动点

- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt`
  - 从单 root 改为 display-scoped 多 root 采集入口。
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayWindowAccessor.kt`
  - 保留 display 过滤，增加“返回多个 roots”的接口。
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayCaptureCoordinator.kt`
  - 从单 root snapshot 改为多 root 合并 snapshot。
- `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt`
  - 增加多 root 聚合路径（统一去重/排序/截断）。
- `app/src/main/kotlin/com/moonkey/androidagent/model/*`（可选）
  - debug 元信息（window 来源）支持。

## 验收标准

### 功能验收

1. `FilesMoveFile`：
- 进入 DCIM 的 move 对话框后，sanitized tree 可见 `Move` 或等价确认控件。
- 任务在该步骤可闭环完成，不再卡死。

2. 双模式一致性：
- `ACCESSIBILITY` 与 `VIRTUAL_DISPLAY` 都能在“目标 display 内”采到应采窗口。
- 不出现跨 display 元素混入。

### 噪声验收

1. 常规任务（设置、联系人、短信）：
- 元素总量无明显失控增长（建议对比基线增幅阈值，例如 <20%）。
- 无大量 overlay/输入法污染进入 prompt。

2. 质量指标（建议新增）：
- `window_count_total`
- `window_count_selected`
- `selected_window_types`
- `elements_per_window`
- `cross_display_filtered_count`

## 最小测试清单

### 单元/组件

- 窗口选择器：
  - 给定多 display windows，确认只选择 target display。
  - `TYPE_ACCESSIBILITY_OVERLAY` 正常排除。
  - Pass2 仅在触发条件满足时生效。

- Perceptor 多 root 合并：
  - 去重稳定
  - 索引稳定
  - 截断后交互元素保留率可接受

### 集成

- `FilesMoveFile` 在两种 mode 下复跑对比：
  - 是否出现 `Move` 按钮
  - 是否完成移动
  - turn 数变化

- 回归任务集：
  - 检查噪声指标、成功率和平均 turn 是否退化。
