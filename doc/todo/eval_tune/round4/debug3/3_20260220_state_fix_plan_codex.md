# State & Fix Plan (2026-02-20)

## 当前状态（已验证）

在 `FilesMoveFile` 的 `Podcasts/holiday_photos.jpg` 场景下：

- `click --use-node true`：可生效（`changed`）
- `click --use-node false`：`Gesture completed` 但 `unchanged`
- `long_press --use-node true`：`No long-clickable node at (...)`
- `long_press --use-node false`：`Gesture completed` 但 `unchanged`

=> setup 不是根因，问题在 action execution 路径。

## 目标

1. Minimal goal
- `long_press --use-node true` 至少应可工作（和 `click --use-node true` 一样可触发有效 UI 变化）。

2. Ideal goal
- `long_press --use-node false` 与 `click --use-node false` 在该场景也能工作。

## 初步根因假设

1. Node long-click 目标匹配过严
- 当前仅依赖 `isLongClickable`；可能遗漏 `ACTION_LONG_CLICK` 可用但 `isLongClickable=false` 的节点。

2. Gesture path 可能是“零长度 stroke”被系统忽略
- `injectTap` / `injectLongPress` 使用 `Path.moveTo()` 单点路径；某些设备/系统可能回调 completed 但未注入有效触摸。

## 计划

1. 放宽 node long-click 匹配规则
- 匹配条件改为：`isLongClickable || actionList 包含 ACTION_LONG_CLICK`。

2. 修 gesture 注入路径
- tap/long-press 改为极短非零长度 path（epsilon line），避免零长度 stroke 被吞。

3. 回归验证（同一 setup、每轮复位）
- 对照跑四组：
  - long_press use_node=true/false
  - click use_node=true/false
- 记录 status/verdict/message/pre/post selection。

