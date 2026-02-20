# dispatchGesture: eval vs action-test 链路排查（Codex）

## 背景

- 现象：`action-test.sh`（尤其 click/tap）看起来比较稳定有效，但 `eval/results/20260220_141111` 在 Settings 页面里点击稳定无变化。
- 目标：先不改代码，检查 `eval/.venv/bin/python eval/aw_bridge/runner.py --tasks "SystemBrightnessMax,SystemBrightnessMin"` 全链路，定位是否有 hacky setup、优先级或环境差异导致 false success。

## 本次结论（先给答案）

1. 这不是 `Settings` 页面特判逻辑导致的。
2. 更可能是**运行态不一致**叠加**成功判定过宽**：
   - eval 路径是 active session（任务进行中）；
   - action-test 路径默认要求 session inactive；
   - active session + OTHER_APP 时会启用全屏 overlay touch intercept；
   - click 先走 `gesture_tap`，只在 gesture 失败时才 fallback node click；
   - gesture transport 成功就被判定 success，即使 UI 没变化。
3. 另有实现脆弱点：tap 是零长度 path（log 里出现 `len=0.0, from=NaN,NaN`），会放大环境波动。

## 关键证据

### 1) eval 结果层

- `eval/results/20260220_141111/per_task.jsonl` 两条任务都 `scripted_success=false`：
  - `SystemBrightnessMax`
  - `SystemBrightnessMin`

### 2) action 报 success，但页面不变

- `tool_result` 中多次是：
  - `Success: Tapped (...) via gesture_tap`
- 同时 post-observation 内容重复（相同页面结构反复出现），符合“点击被接受但页面未变化”。

### 3) dispatchGesture 日志异常形态

- 在对应 click 时段，logcat 多次出现：
  - `dispatchGesture dispatched=true, ... len=0.0, from=NaN,NaN, to=NaN,NaN`
- 例如：
  - `eval/results/20260220_141111/artifacts/aw_20260220_141111_SystemBrightnessMax_0_0/logcat.log:9078`
  - `eval/results/20260220_141111/artifacts/aw_20260220_141111_SystemBrightnessMin_1_0/logcat.log:6972`
- 同时也能看到 `dispatchGesture completed`，说明 transport 层成功并不等于业务层生效。

### 4) eval 运行时确实进入了“active + OTHER_APP + capsule overlay”状态

- 任务开始：
  - `...SystemBrightnessMin_1_0/logcat.log:5246`
- 窗口切到 Settings 且 `hasActiveTask=true`：
  - `...SystemBrightnessMin_1_0/logcat.log:6321`
- Capsule overlay shown：
  - `...SystemBrightnessMin_1_0/logcat.log:6324`

## 代码链路审计（从 runner 到点击执行）

### A. eval 启动链路

- `eval/aw_bridge/runner.py`
  - 使用 `bridge.platform_mode=accessibility`（默认配置）
  - 启动 MainActivity 并传 `goal + fresh_session + auto_start`
- `eval/config/default.yaml`
  - `platform_mode: accessibility`
  - `fresh_session: true`
  - `auto_start: true`

### B. forwarder 相关 setup（明确存在“环境改写”）

- AndroidWorld 侧 runner.log 可见 forwarder 先被启用（`enabled_accessibility_services` 写入 forwarder）。
- 随后 `eval/aw_bridge/native_agent_bridge.py` 在 `_ensure_accessibility_service()` 会把服务列表裁剪为仅：
  - `com.moonkey.androidagent/.app.AgentService`
- 这是显式“去 forwarder”的 setup 差异点。

### C. click 执行优先级

- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ClickExecutor.kt`
  - primary: `UIAction.TapAt`（gesture）
  - fallback: 仅当 gesture failure，且 target semantic，才 `UIAction.ClickNodeAt`
  - 因此“gesture success + UI unchanged”不会触发 node fallback。

### D. tap 手势构造

- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityGestureInjector.kt`
  - `injectTap()` 使用 `Path().moveTo(...)`，未形成非零长度线段。
  - 与日志里的 `len=0.0 / NaN` 一致。

### E. overlay interaction lock（高风险差异）

- `app/src/main/kotlin/com/moonkey/androidagent/app/OverlayLocationPolicy.kt`
  - A11y 模式下 `location == OTHER_APP` 且非终态/非 takeover 时返回 `true`（锁交互）。
- `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt`
  - `applyVisibility()` 调 `capsuleManager.setInteractionLocked(lockInteraction)`。
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/CapsuleOverlayHost.kt`
  - lock 时窗口升为 `MATCH_PARENT`；
  - 放置全屏 `View#setOnTouchListener { true }`，吞触摸。

## 为什么会和 action-test 矛盾

- `action-test` 走的是 `ActionDebugReceiver`，其前置条件是 session 必须 inactive：
  - `app/src/main/kotlin/com/moonkey/androidagent/debug/ActionDebugReceiver.kt`
  - 若 session active 会直接拒绝执行 debug action。
- eval 则是 active session 驱动下的真实任务回路。
- 两者并非同一运行态，所以“action-test 稳定有效”与“eval 稳定无效”可以同时成立。

## 已排除项

- 没发现针对 `com.android.settings` 或系统页的专门 click hack / 特判分支。
- 没发现 `mobile_action` 参数在该 run 被篡改成其他 action（点击参数本身正常）。

## 附带发现（统计层）

- `per_task.jsonl` 中 `tool_calls/tool_failures` 显示为 0，但 trace 实际存在多个 `tool_call/tool_result`。
- 这是 `eval/aw_bridge/trace_parser.py` 目前依赖 `run_summary` 字段导致的统计口径问题，不是这次点击失效的直接根因，但会误导诊断。

## 当前判断（不改代码版）

优先怀疑顺序：

1. **active session 下 overlay 全屏拦截触摸**（最符合“eval 稳定无效、action-test 稳定有效”）。
2. **tap 零长度 stroke 导致在部分窗口/时序下手势生效不稳定**。
3. **click success 判定缺少 effect-level gating**（导致 false success 被持续放大）。

