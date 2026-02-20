# FilesMoveFile Long-Press Action Debug (2026-02-20)

## 背景
针对 `FilesMoveFile` 失败，定位 `holiday_photos.jpg` 长按是否为执行层问题。

测试前置：
- 已通过 `setup_task_only.py` 完成 FilesMoveFile setup。
- 手动进入 Files `Podcasts/` 并确保 `holiday_photos.jpg` 在屏内。
- 每次测试都执行复位规则：若出现选中态（`1 selected`），先点左上角 `X/Cancel` 再开始下一次。

## 关键发现
1. `adb` 基线可稳定长按选中，`a11y long_press` 在同坐标不生效。
2. `--use-node` 对 `long_press` 目前无效（true/false 行为完全一致）。
3. 现有 `action-test` 的 `long_press` 实际固定走 gesture 路径，不会走 node long-click。

## 实验与结果

### A. 严格复位后的 ADB 基线
坐标：`(120,1775)`，duration `1200ms`，每轮后点左上角 `X` 复位。

- `run=1 pre_clear -> post_sel, reset=OK`
- `run=2 pre_clear -> post_sel, reset=OK`
- `run=3 pre_clear -> post_sel, reset=OK`

结论：L0（adb 输入）可以稳定触发该行文件选中。

证据目录：
- `debug-output/action-test/lp_adb_icon_reset_run1`
- `debug-output/action-test/lp_adb_icon_reset_run2`
- `debug-output/action-test/lp_adb_icon_reset_run3`

### B. 严格复位后的 A11y long_press
坐标：`(120,1775)`，duration `1200ms`，每轮前确保 `pre_clear`。

- `run=1 pre_clear -> post_clear`
- `run=2 pre_clear -> post_clear`
- `run=3 pre_clear -> post_clear`

结论：L1（a11y long_press 当前实现）在该页面未触发选中。

证据目录：
- `debug-output/action-test/lp_a11y_icon_reset_run1`
- `debug-output/action-test/lp_a11y_icon_reset_run2`
- `debug-output/action-test/lp_a11y_icon_reset_run3`

### C. 长按时长扫描（A11y）
同坐标 `(120,1775)`，duration 分别 `600/1000/1500/2500ms`。

- 全部 `post_clear`。

结论：不是“按得不够久”的问题。

证据目录：
- `debug-output/action-test/lp_a11y_icon_d600`
- `debug-output/action-test/lp_a11y_icon_d1000`
- `debug-output/action-test/lp_a11y_icon_d1500`
- `debug-output/action-test/lp_a11y_icon_d2500`

### D. `--use-node` 对 long_press 的实际效果
命令分别为：
- `long_press ... --use-node true`
- `long_press ... --use-node false`

结果：
- 两者均 `status=success, verdict=unchanged, message=Gesture completed`
- `result.json.params` 均不含 `use_node`

结论：`--use-node` 对 long_press 当前不生效。

证据目录：
- `debug-output/action-test/lp_a11y_use_node_true_run1`
- `debug-output/action-test/lp_a11y_use_node_false_run1`
- `debug-output/action-test/lp_a11y_use_node_true_run2`
- `debug-output/action-test/lp_a11y_use_node_false_run2`

### E. 侧向对照（click）
同区域坐标 `(120,1775)`：

- `click`（默认 node）：`status=success, verdict=changed, message=ACTION_CLICK...`
- `click --use-node false`（gesture）：`status=success, verdict=unchanged, message=Gesture completed`

结论：node 动作链在该界面是可工作的，gesture 注入链存在“accepted 但无效果”现象。

证据目录：
- `debug-output/action-test/click_node_120_1775`
- `debug-output/action-test/click_gesture_120_1775`

## 代码定位

1. `scripts/action-test.sh:250`
- `long_press` 分支未透传 `--use-node` 到 broadcast extras。

2. `app/src/main/kotlin/com/moonkey/androidagent/debug/DebugActionExecutor.kt:144`
- `parseAction("long_press")` 固定返回 `UIAction.LongPressAt(...)`（gesture），没有 node long-click 分支。

3. `app/src/main/kotlin/com/moonkey/androidagent/tool/action/LongPressExecutor.kt:59`
- 正式 executor 里确实是“先 node long-click，失败再 fallback gesture long-press”。
- 但 action-test debug 通道当前无法单独验证 node long-click（因为第 1/2 点）。

## 结论（针对 FilesMoveFile）
- 当前问题主要是执行层：当 long-press 走到 gesture 通道时，在该页面经常出现“API success 但实际无交互效果”。
- `--use-node` 目前未真正接入 long_press，导致 action-test 不能直接比较 node vs gesture 长按，调试信息被掩盖。

## 建议下一步
1. 给 `action-test long_press` 接入 `--use-node`，并在 `DebugActionExecutor` 中实现：
- `use_node=true -> UIAction.LongClickNodeAt(x,y)`
- `use_node=false -> UIAction.LongPressAt(x,y,duration)`
2. 修复后重跑同一组“每轮复位”实验，确认 node 长按在 Files 列表是否稳定可选中。
3. 若 node 长按稳定成功，则在 `LongPressExecutor` 内提升 node 路径优先级和可点击父节点提升策略，减少跌入 gesture fallback。
