# 20260219 A11y Tree专项分析（Codex）

Run: `eval/results/20260219_124436`  
任务聚焦：
- `SystemBluetoothTurnOnVerify` (`aw_20260219_124436_SystemBluetoothTurnOnVerify_9_0`)
- `SystemWifiTurnOnVerify` (`aw_20260219_124436_SystemWifiTurnOnVerify_13_0`)
- `SystemBrightnessMaxVerify` (`aw_20260219_124436_SystemBrightnessMaxVerify_10_0`)
- `SystemBrightnessMinVerify` (`aw_20260219_124436_SystemBrightnessMinVerify_11_0`)

## 结论（先回答你的两个问题）

1. `Pattern 2: Toggle Ping-Pong (Bluetooth, Wi-Fi)` **是 perception 问题，但不是“完全没有状态信息”，而是“关键信号丢失 + 次级信号未被稳定利用”**。
- raw tree 里有明确状态：`android.widget.Switch.checked=true/false`。
- sanitized tree 把 Switch 节点丢掉了（无 text/desc 且不可点击，未通过当前 keep 条件），导致模型看不到最直接状态位。
- sanitized 仍有次级线索（Wi-Fi: `Searching for networks…`; BT: `Device name` / `Bluetooth will turn on to pair`），但模型没有稳定用它们做状态判定，出现盲点按。

2. `SystemBrightnessMaxVerify` / `SystemBrightnessMinVerify` 里 brightness bar 来回滑，**主要是 perception 问题（看不到当前亮度数值）叠加策略问题**。
- `MaxVerify` 并没有来回滑，它直接在 Display 页读到 `Brightness level | 100%` 后完成。
- `MinVerify` 在 Quick Settings 的 SeekBar 上，raw/sanitized 都只有 `SeekBar("Display brightness")`，**没有当前值/百分比/range**，所以 agent 无法确认“到底是不是已经最小”。
- 同时 tool_result 多次给了 `Screen content unchanged`，agent 也没有据此收敛（策略层问题）。

---

## 证据

## A. Wi-Fi / Bluetooth Ping-Pong：状态信息在 raw 可见，在 sanitized 退化

### A1. Wi-Fi（`SystemWifiTurnOnVerify`）

关键 turn：
- t04 pre: `.../sanitized_a11y_tree/57_sanitized_1771524274886.json`
- t04 pre raw: `.../raw_a11y_tree/56_raw_1771524274885.json`
- t05 pre: `.../sanitized_a11y_tree/74_sanitized_1771524281569.json`
- t05 pre raw: `.../raw_a11y_tree/73_raw_1771524281569.json`

观察：
- t04 pre（执行首次 Wi-Fi row 点击前）
  - raw: `android.widget.Switch.checked=true`
  - sanitized: 无 Switch，仅有 `Wi-Fi` + `Searching for networks…`
- t04 点击后到 t05 pre
  - raw: `android.widget.Switch.checked=false`
  - raw 还出现长文案（含“Wi-Fi is off”）
  - sanitized: Switch 仍不存在，且“Wi-Fi is off”长文案未保留，状态判定更弱

结论：
- Wi-Fi 的“明确开关状态”在 raw 存在，但在 sanitized 丢失。
- sanitized 仍有弱线索（如 `Searching for networks…`）可用，但当前 agent 没稳定利用。

### A2. Bluetooth（`SystemBluetoothTurnOnVerify`）

关键 turn：
- t05 pre: `.../sanitized_a11y_tree/72_sanitized_1771523911185.json`, raw `71_raw_1771523911185.json`
- t06 pre: `.../sanitized_a11y_tree/89_sanitized_1771523916810.json`, raw `88_raw_1771523916809.json`

观察：
- t05 pre:
  - raw: `Switch.checked=true`
  - sanitized: 有 `Device name | sdk_gphone64_arm64`（强提示“已开启”）
- t06 pre（点了 Use Bluetooth 后）:
  - raw: `Switch.checked=false`
  - sanitized: `Pair new device | Bluetooth will turn on to pair`（强提示“已关闭”）

结论：
- BT 的状态线索在 sanitized 并非完全缺失，但最直接的 `checked` 位丢失。
- agent 没有把 `Device name`/`turn on to pair` 映射成“当前状态”，导致 ON→OFF→ON ping-pong。

## B. Brightness：Quick Settings 的数值不可见

### B1. `SystemBrightnessMaxVerify`

关键 turn：
- t04 pre: `.../sanitized_a11y_tree/55_sanitized_1771523978884.json`

观察：
- sanitized 直接有 `Brightness level | 100%`。
- agent 未出现 slider 来回拖动，而是直接完成。

结论：
- Max 这个 case 里不是“看不到值”。值可见。

### B2. `SystemBrightnessMinVerify`

关键 turn（Quick Settings 滑条阶段）：
- t04 pre: `53_sanitized_...`, raw `52_raw_...`
- t05 pre: `70_sanitized_...`, raw `69_raw_...`
- t06 pre: `87_sanitized_...`, raw `86_raw_...`
- t07 pre: `104_sanitized_...`, raw `103_raw_...`

观察：
- raw/sanitized 均只见 `SeekBar` + text=`Display brightness`。
- raw 节点字段中没有 `rangeInfo/current/max` 或可直接判定百分比的字段。
- t05/t06 tool_result 已出现 `Screen content unchanged after swipe`，但 agent 继续反复尝试。

结论：
- 在 a11y-only 模式下，agent 无法从树里读到“当前亮度具体值”，这是 perception 缺口。
- 但“连续 unchanged 仍不收敛”属于策略缺口。

---

## 根因分类

- `Toggle Ping-Pong`：`Perception (primary)` + `Reasoning/Policy (secondary)`
- `BrightnessMin` 往复拖动：`Perception (primary)` + `Reasoning/Policy (secondary)`

---

## 可执行解决方案

### P0: 修 perception 表达（优先）

1. **保留 checkable 控件（即使无 text/desc）到 sanitized tree**
- 现状问题点：`Perceptor.kt` 的 `shouldKeep` 只看 clickable/editable/scrollable/hasContent。
- 建议：将 `checkable`（至少 `class in {Switch, CheckBox, RadioButton}`）纳入 keep 条件。
- 目标：sanitized 直接出现 `Switch` 节点，并带 `checked/checkable`。

2. **给 Switch/SeekBar 增加状态字段（raw + sanitized）**
- Switch: `checked` 已有，但应确保不会因过滤丢失。
- SeekBar/ProgressBar: 增加 `range_min/range_max/range_current/range_percent`（从 `AccessibilityNodeInfo.RangeInfo` / `stateDescription` 提取，拿不到则留空）。
- 目标：a11y-only 下可直接判断亮度是否已到 min/max。

3. **为无文本状态控件增加可读标签（可选增强）**
- 当控件自身无 text/desc 时，尝试绑定同 row 的 label（如 “Wi-Fi”, “Use Bluetooth”, “Display brightness”）。
- 减少“看到状态值但不知道它属于谁”的问题。

### P1: 策略补强（避免反复动作）

4. **Toggle Guard**
- 若已判定目标状态已满足（显式 `checked` 或强线索），禁止再次点按同 toggle row。
- 对 `Wi-Fi/Bluetooth` 增加线索映射：
  - Wi-Fi: `Searching for networks…` / 已列出可用网络 => ON
  - Bluetooth: `Device name` 出现 => ON；`Bluetooth will turn on to pair` => OFF

5. **Slider Boundary Convergence**
- 同一 slider 连续 2 次水平拖动 + `screen unchanged`，且目标是 min/max 时：
  - 判定可能已到边界，停止拖动；
  - 转“验证动作”（读 Display 页数值或使用新增 range 字段）。

### P2: 验证与回归

6. 回归用例
- `SystemWifiTurnOnVerify`：初始 ON 时应直接 complete（不产生 OFF 再 ON）
- `SystemBluetoothTurnOnVerify`：同上
- `SystemBrightnessMinVerify`：拖动不超过 1-2 次，且能解释“已在最小/已设为最小”

7. 观测指标
- `toggle ping-pong count`（同控件短时间反复切换）
- `slider redundant swipes`（同坐标轴重复拖动次数）
- `unchanged-after-swipe then same-action` 发生率

---

## 代码落点（建议）

- 感知过滤/导出：`app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt`
- raw dump 字段扩展：`app/src/main/kotlin/com/moonkey/androidagent/trace/A11yTreeDumper.kt`
- 数据模型扩展：`app/src/main/kotlin/com/moonkey/androidagent/model/Models.kt`
- 策略/提示词规则：`app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptBuilder.kt`（或对应 policy 文件）

