# Eval 20260220_162433 Turn Audit（Codex）

## Legend

- `R` = reasonable
- `Q` = questionable
- `U` = unreasonable
- `exec` = tool 执行层（tool_result）是否成功
- `effect` = 任务状态是否明显推进（不是只看 success 文案）

---

## 1) `aw_20260220_162433_BrowserMultiply_0_0`（failure / MaxTurnsReached）

- Turn verdict:
  - T1 R, T2 R, T3 Q, T4 Q, T5 Q, T6 U, T7 Q, T8 U, T9 Q, T10 Q, T11 Q, T12 Q, T13 Q, T14 Q, T15 Q, T16 Q, T17 Q, T18 Q, T19 Q, T20 Q, T21 Q, T22 Q, T23 Q, T24 Q, T25 Q, T26 Q, T27 Q, T28 Q, T29 Q, T30 Q
- Turn exec:
  - T1 open_app ok
  - T2 click ok
  - T3 click ok
  - T4 long_press ok
  - T5 click ok
  - T6 mobile_action(system_button) fail
  - T7 click ok
  - T8 mobile_action(system_button) fail
  - T9~T30 click/long_press mostly ok
- Click effect:
  - click-like 27 次，success 27 次，`same_tree` 9 次
  - package 长时间停留 `com.google.android.documentsui`，任务未推进到 Chrome + 网页按钮阶段

## 2) `aw_20260220_162433_CameraTakePhoto_1_0`（success）

- Turn verdict: T1 R, T2 R, T3 Q
- Turn exec:
  - T1 open_app ok
  - T2 click shutter ok
  - T3 complete_task ok
- Click effect:
  - click-like 1 次，`same_tree` 0 次
  - 结果成功，但拍照后的强证据较弱（保留 observation 风险）

## 3) `aw_20260220_162433_ClockTimerEntry_2_0`（failure）

- Turn verdict: T1 R, T2 R, T3 R, T4 R, T5 R, T6 R, T7 Q
- Turn exec:
  - T1 open_app ok
  - T2~T6 click 输入数字均 ok
  - T7 complete_task ok
- Click effect:
  - click-like 5 次，`same_tree` 0 次
  - 观测里能看到 `00h 16m 35s`，但 scripted 仍失败（偏 evaluation gap）

## 4) `aw_20260220_162433_ContactsAddContact_3_0`（success）

- Turn verdict: T1 R, T2 R, T3 R, T4 R, T5 R, T6 R, T7 R, T8 R, T9 R, T10 R
- Turn exec: 全部 ok
- Click effect:
  - click-like 5 次，`same_tree` 1 次（弱影响，不阻塞目标完成）

## 5) `aw_20260220_162433_ExpenseAddSingle_4_0`（success）

- Turn verdict: T1 R, T2 R, T3 R, T4 R, T5 R, T6 R, T7 R, T8 R, T9 R, T10 R
- Turn exec: 全部 ok
- Click effect:
  - click-like 3 次，`same_tree` 0 次

## 6) `aw_20260220_162433_FilesMoveFile_5_0`（failure / MaxTurnsReached）

- Turn verdict:
  - T1 R, T2 R, T3 R, T4 R, T5 Q, T6 Q, T7 Q, T8 Q, T9 Q, T10 Q, T11 Q, T12 Q, T13 Q, T14 Q, T15 Q, T16 Q, T17 Q, T18 Q, T19 Q, T20 Q, T21 Q, T22 Q, T23 Q, T24 Q, T25 Q, T26 Q, T27 Q, T28 Q, T29 Q, T30 Q
- Turn exec:
  - T1 open_app ok
  - T2~T30 click/long_press/scroll/system_button 全部 tool 层 ok
- Click effect:
  - click-like 24 次，`same_tree` 10 次
  - 多次长按/点击成功但 move 流程没有闭环完成

## 7) `aw_20260220_162433_MarkorCreateNote_6_0`（success）

- Turn verdict: T1 R, T2 R, T3 R, T4 R, T5 R, T6 R, T7 R, T8 R
- Turn exec: 全部 ok
- Click effect:
  - click-like 4 次，`same_tree` 0 次

## 8) `aw_20260220_162433_RecipeAddSingleRecipe_7_0`（success）

- Turn verdict: T1 R, T2 R, T3 R, T4 R, T5 R, T6 R, T7 R, T8 R, T9 R, T10 R, T11 R, T12 R, T13 R, T14 R, T15 R, T16 R, T17 R, T18 R
- Turn exec: 全部 ok
- Click effect:
  - click-like 8 次，`same_tree` 0 次

## 9) `aw_20260220_162433_SimpleSmsSend_8_0`（success）

- Turn verdict: T1 U, T2 R, T3 R, T4 R, T5 R, T6 R, T7 R, T8 R, T9 R
- Turn exec:
  - T1 open_app fail（app name 不匹配）
  - T2~T9 ok
- Click effect:
  - click-like 4 次，`same_tree` 1 次（不影响最终完成）

## 10) `aw_20260220_162433_SystemBluetoothTurnOn_9_0`（success）

- Turn verdict: T1 R, T2 R, T3 R, T4 R, T5 R, T6 R
- Turn exec: 全部 ok
- Click effect:
  - click-like 4 次，`same_tree` 0 次

## 11) `aw_20260220_162433_SystemBrightnessMax_10_0`（failure）

- Turn verdict: T1 Q, T2 Q, T3 Q, T4 Q, T5 Q, T6 R, T7 R, T8 R, T9 R, T10 R, T11 R, T12 R, T13 Q, T14 Q, T15 Q, T16 Q, T17 Q
- Turn exec: T1~T17 全部 tool 层 ok
- Click effect:
  - click-like 3 次，`same_tree` 0 次
  - 失败更像任务完成判据/策略问题，不是 click 执行失败

## 12) `aw_20260220_162433_SystemBrightnessMin_11_0`（failure）

- Turn verdict: T1 R, T2 R, T3 U
- Turn exec:
  - T1 write_todos + open_app ok
  - T2 scroll ok
  - T3 无 tool call（直接结束）
- Click effect:
  - 本任务无 click-like action
  - 属于 early complete / reasoning+evaluation 问题

## 13) `aw_20260220_162433_SystemWifiTurnOff_12_0`（success）

- Turn verdict: T1 R, T2 R, T3 R, T4 R
- Turn exec: 全部 ok
- Click effect:
  - click-like 2 次，`same_tree` 0 次
  - 可见 `"Wi-Fi is off"`，effect 明确

## 14) `aw_20260220_162433_SystemWifiTurnOn_13_0`（success）

- Turn verdict: T1 R, T2 R, T3 R, T4 R, T5 R
- Turn exec: 全部 ok
- Click effect:
  - click-like 3 次，`same_tree` 0 次
  - Wi-Fi switch `checked: true`，effect 明确

---

## Consolidated Interpretation

- click 好坏并存的主因不是“tap 注入随机坏”，而是：
  1. 文件任务里缺少 effect-level gating（大量 success 但状态不推进）
  2. 个别 action/schema 误用（`system_button` 传给 `mobile_action`）
  3. 少数任务是完成判据问题（brightness / timer），并非 click 本体问题

