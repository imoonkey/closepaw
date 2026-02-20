# FilesMoveFile Action Debug Follow-up: long_press vs click (use_node true/false)

## 本轮目标
实现并验证 `/action-debug` 下一步建议：
1. 让 `long_press` 真正支持 `--use-node true/false`。
2. 在同一 setup 场景下，对比 `long_press` 与 `click` 的 node/gesture 两条路径。
3. 判断失败是否来自 setup 环境问题。

## 代码修改

1. `scripts/action-test.sh`
- `long_press` 分支新增 `--use-node` 透传：
  - 当传入 `--use-node true|false` 时，broadcast extras 会包含 `--ez use_node ...`。
- help 文案更新为：`click/long_press` 都支持 `--use-node`。

2. `app/src/main/kotlin/com/moonkey/androidagent/debug/DebugActionExecutor.kt`
- `parseAction("long_press")` 改为分流：
  - `use_node=true` -> `UIAction.LongClickNodeAt(x,y)`
  - `use_node=false` -> `UIAction.LongPressAt(x,y,duration)`
- `paramsJson` 增加 long_press 的 `use_node` 输出（true/false），便于结果审计。

## 测试前置与复位策略

- 场景：`Files in Podcasts`，`holiday_photos.jpg` 在屏内。
- 每个 case 前：确保 `pre_clear`（若已有 `1 selected`，点击左上角 `X/Cancel` 复位）。
- 坐标：`(120,1775)`（文件行左侧图标区域）。
- long_press duration：`1200ms`。

## 最终结果（同一场景）

1. `long_press --use-node true`
- `status=failure`
- `verdict=unchanged`
- `message=No long-clickable node at (120,1775)`
- `params={'x':120,'y':1775,'duration_ms':1200,'use_node':True}`

2. `long_press --use-node false`
- `status=success`
- `verdict=unchanged`
- `message=Gesture completed`
- `params={'x':120,'y':1775,'duration_ms':1200,'use_node':False}`

3. `click --use-node true`
- `status=success`
- `verdict=changed`
- `message=ACTION_CLICK at (120,1775)`
- `params={'x':120,'y':1775,'use_node':True}`

4. `click --use-node false`
- `status=success`
- `verdict=unchanged`
- `message=Gesture completed`
- `params={'x':120,'y':1775}`

## 结论

1. setup 不是根因。
- `click --use-node true` 明确可生效（`changed`），说明当前文件列表场景本身可操作。

2. `long_press` 的问题在执行链路本身（代码/API层），不是任务数据准备问题。
- node 长按路径：直接失败（找不到 long-clickable node）。
- gesture 长按路径：运输层 success，但效果层 unchanged（假成功）。

3. 本次对照同时显示了一个一致模式：
- 在该界面，`gesture` 注入（click/long_press）都表现为“completed 但 unchanged”。
- `node click` 可工作，`node long-click` 不工作。

## 证据目录

- `debug-output/action-test/final2_long_press_use_node_true`
- `debug-output/action-test/final2_long_press_use_node_false`
- `debug-output/action-test/final2_click_use_node_true`
- `debug-output/action-test/final2_click_use_node_false`

## 额外观察

- 调试中出现过 `com.google.androidenv.accessibilityforwarder keeps stopping` 弹窗，会污染测试状态。
- 为保证本轮稳定性，最终仅启用 `com.moonkey.androidagent/.app.AgentService` 进行 action-debug 对照。
