## 代码改动摘要（本轮）

1. `scripts/action-test.sh`
- `long_press` 现在支持并透传 `--use-node true|false`（之前该参数只对 `click` 生效）。
- usage/help 文案同步更新，明确 `click/long_press` 都支持 `--use-node`。

2. `app/src/main/kotlin/com/moonkey/androidagent/debug/DebugActionExecutor.kt`
- `long_press` 按 `use_node` 分流：
- `use_node=true` -> `UIAction.LongClickNodeAt(x,y)`
- `use_node=false` -> `UIAction.LongPressAt(x,y,duration)`
- `paramsJson` 增加 long_press 的 `use_node` 字段，便于 action-debug 结果审计。

3. `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityNodeFinder.kt`
- `findLongClickableNodeAtLocation` 判定放宽：不仅检查 `isLongClickable`，也接受 `actionList` 包含 `ACTION_LONG_CLICK` 的节点。

4. `app/src/main/kotlin/com/moonkey/androidagent/platform/NodeActionPerformer.kt`
- `performNodeLongClickAt` 增加 fallback：
- 先尝试 long-clickable 节点执行 `ACTION_LONG_CLICK`
- 若未找到，再对同点 clickable 节点尝试 `ACTION_LONG_CLICK`
- 用于覆盖部分 App “可长按但节点属性不标准”的场景。

5. `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityGestureInjector.kt`
- `injectTap` / `injectLongPress` 的 path 从“零长度点”改为“极短线段（epsilon）”，规避部分环境下零长度 stroke `dispatchGesture` 完成但无实际触控效果的问题。

## 重跑复测

### 执行方式（避免脏状态）

1. 每个 case 前先 `uiautomator dump` 检查状态。
2. 若出现 `1 selected`，只点击左上角 `Cancel` 复位（不再盲目连按 Back）。
3. 若出现 `Open with`，按一次 `Back` 返回列表。
4. 每个 case 重新从 dump 中读取 `holiday_photos.jpg` 的实时中心点（本轮为 `365,1420`）。

### 结果（BASE=`rerun_clean_20260219_232249`）

1. `long_press --use-node true`
- `status=success`
- `verdict=changed`
- `message=ACTION_LONG_CLICK at (365,1420)`

2. `long_press --use-node false`
- `status=success`
- `verdict=unchanged`
- `message=Gesture completed`

3. `click --use-node true`
- `status=success`
- `verdict=changed`
- `message=ACTION_CLICK at (365,1420)`
- 触发了 `Open with`（随后 Back 复位）

4. `click --use-node false`
- `status=success`
- `verdict=unchanged`
- `message=Gesture completed`

### 额外对照（ADB baseline）

同坐标下：
- `adb input tap` 可触发界面变化（可打开目标文件流程）
- `adb input swipe x y x y 1200` 可触发选中态（出现 `1 selected` / `Cancel`）

### 更新结论

1. `minimal goal` 已达成：`long_press --use-node true` 在正确 setup 下可稳定 work。
2. `ideal goal` 仍未达成：`--use-node false`（gesture path）在 `click/long_press` 两者上都表现为 `Gesture completed` 但 `ui_changed=unchanged`。
3. 因为同坐标 `adb input` 可生效，问题更聚焦在当前 `dispatchGesture` 这条实现/调用路径，而非任务 setup。

### 证据目录（本次重跑）

- `debug-output/action-test/rerun_clean_20260219_232249_longpress_use_node_true`
- `debug-output/action-test/rerun_clean_20260219_232249_longpress_use_node_false`
- `debug-output/action-test/rerun_clean_20260219_232249_click_use_node_true`
- `debug-output/action-test/rerun_clean_20260219_232249_click_use_node_false`
- `debug-output/action-test/adb_baseline_click_20260219_232339`
- `debug-output/action-test/adb_baseline_long_20260219_232339`
