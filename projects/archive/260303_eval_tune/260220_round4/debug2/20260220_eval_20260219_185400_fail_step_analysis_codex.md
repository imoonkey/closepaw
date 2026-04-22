# Eval Fail Step-by-Step Analysis (run: 20260219_185400)

## Scope
- Eval run: `eval/results/20260219_185400`
- Fail tasks only:
  - `aw_20260219_185400_FilesMoveFile_5_0`
  - `aw_20260219_185400_MarkorCreateNote_6_0`
- 目标：逐个 run、逐个 step 判断动作是否合理，并核对 tool execution 是否成功。

## Overall
- 总任务数: 14
- 失败任务数: 2
- summary 指标: `scripted_success_rate=0.8571`, `tool_failure_rate=0.0093`
- 注意：`FilesMoveFile` 在 `per_task.jsonl` 里显示 `turns_executed=0/tool_calls=0`，但 trace 实际有 30 turns、29 个 tool calls（统计口径/写回存在不一致）。

---

## Run A: FilesMoveFile
- Run ID: `aw_20260219_185400_FilesMoveFile_5_0`
- Goal: 把 `holiday_photos.jpg` 从 `Podcasts` 移动到 `DCIM`
- 实际结果: 未完成移动，且在第 30 turn 前后被终止（trace 以 turn30 的 `llm_request` 结束，无 `session_stopped`/`complete_task`）
- Tool execution:
  - 实际 tool 调用: 29 次
  - `tool_result.success=true`: 29/29
  - 显式 tool failure: 0
  - 关键执行告警: 4 次 `Warning: Element center likely occluded; using offset point`（T9/T15/T20/T26）

### Step Review
| Turn | Action | Tool success | 是否合理 | 证据与判断 |
|---|---|---|---|---|
| T1 | `write_todos` + `open_app(Files)` | 是 | 合理 | 初始化计划并打开 Files，方向正确。 |
| T2 | `click idx=0 (Show roots)` | 是 | 合理 | 进入根目录导航，符合找存储卷流程。 |
| T3 | `scroll down` | 是 | 基本合理 | 为查找目标存储卷做探索。 |
| T4 | `click idx=13 (sdk_gphone64_arm64)` | 是 | 合理 | 进入设备存储卷，路径正确。 |
| T5 | `scroll down` | 是 | 合理 | 继续找 `Podcasts`。 |
| T6 | `click idx=24 (Podcasts)` | 是 | 合理 | 进入源目录。 |
| T7 | `scroll down` | 是 | 合理 | 查找目标文件。 |
| T8 | `long_press idx=16 (holiday_photos.jpg)` | 是 | 合理 | 选中文件，符合 move 前置动作。 |
| T9 | `click idx=7 (More options)` | 是（有告警） | 不合理（执行风险） | result 告警“中心点遮挡，偏移点击”；随后进入 `android` 的 “Open with” 对话框，说明点击落点不稳定。 |
| T10 | `system_button(back)` | 是 | 合理 | 从错误弹窗回退，恢复到 Files。 |
| T11 | `long_press idx=16` | 是 | 基本合理 | 再次选择文件，仍可接受。 |
| T12 | `click idx=0 (Show roots)` | 是 | 不合理 | 再次触发到 “Open with” 方向，且未解决 move 菜单问题。 |
| T13 | `system_button(back)` | 是 | 合理 | 回退恢复。 |
| T14 | `long_press idx=16` | 是 | 不合理（重复） | 与前序失败模式重复，缺少新策略。 |
| T15 | `click idx=7 (More options)` | 是（有告警） | 不合理（重复失败） | 再次触发同类遮挡告警，继续进入错误路径。 |
| T16 | `system_button(back)` | 是 | 合理 | 回退恢复。 |
| T17 | `click idx=0 (Show roots)` | 是 | 不合理（重复失败） | 再次复现错误链。 |
| T18 | `system_button(back)` | 是 | 合理 | 回退恢复。 |
| T19 | `long_press idx=16` | 是 | 不合理（重复失败） | 仍未引入“移动到/剪切”新路径。 |
| T20 | `click idx=7 (More options)` | 是（有告警） | 不合理（重复失败） | 第 3 次同类遮挡告警。 |
| T21 | `system_button(back)` | 是 | 合理 | 回退恢复。 |
| T22 | `click idx=0 (Show roots)` | 是 | 不合理（重复失败） | 再次触发错误流。 |
| T23 | `system_button(back)` | 是 | 合理 | 回退恢复。 |
| T24 | `click idx=16 (holiday_photos.jpg)` | 是 | 不合理 | 单击文件导致打开/“Open with”方向，不是 move 操作。 |
| T25 | `system_button(back)` | 是 | 合理 | 回退恢复。 |
| T26 | `click idx=7 (More options)` | 是（有告警） | 不合理（重复失败） | 第 4 次同类遮挡告警。 |
| T27 | `system_button(back)` | 是 | 合理 | 回退恢复。 |
| T28 | `click text=Podcasts` | 是 | 不合理 | 仍在错误流中，未推进“移动到 DCIM”。 |
| T29 | `system_button(back)` | 是 | 合理 | 回退恢复。 |
| T30 | 无 tool（停在 `llm_request`） | N/A | 不合理（未完成） | 达到上限附近终止，无后续 action。 |

### Run A Conclusion
- Tool 层面“调用都成功”，但多次是“成功执行了错误点击/无进展动作”。
- 核心问题是 **执行稳定性 + 失败后策略退化**：
  - 顶栏 `More options` 点击多次触发遮挡偏移告警，导致误入 “Open with”。
  - 模型没有在重复失败后切换策略（例如：尝试明确的 `move to` 菜单路径、先取消选择再重选、换目标控件）。

---

## Run B: MarkorCreateNote
- Run ID: `aw_20260219_185400_MarkorCreateNote_6_0`
- Goal: 创建 `2023_01_26_wise_yacht.md`，内容 `Ignorance is bliss.`
- 实际结果: agent `GoalAchieved`，但 scripted eval 失败
- Tool execution:
  - 调用 7 次，成功 7 次
  - 无 tool failure / 无执行告警
- 评测失败关键证据（runner）:
  - `cat /storage/emulated/0/Documents/Markor/2023_01_26_wise_yacht.md`
  - 日志: `does not match Ignorance is bliss.`（读取内容为空字符串）

### Step Review
| Turn | Action | Tool success | 是否合理 | 证据与判断 |
|---|---|---|---|---|
| T1 | `open_app(Markor)` | 是 | 合理 | 正确打开目标应用。 |
| T2 | `click idx=9 (Create a new file or folder)` | 是 | 合理 | 进入建文件流程。 |
| T3 | `type idx=1: 2023_01_26_wise_yacht` | 是 | 合理 | 文件名输入正确（`.md` 由分离后缀控件提供）。 |
| T4 | `click idx=13 (OK)` | 是 | 合理 | 完成文件创建。 |
| T5 | `click idx=8 (editor)` | 是 | 合理 | 聚焦正文编辑区域。 |
| T6 | `type idx=8: Ignorance is bliss.` | 是 | 基本合理 | UI 观测中编辑框已显示该文本。 |
| T7 | `complete_task(success)` | 是 | 不合理（过早完成） | 未验证内容已持久化到文件系统；评测端读取到空内容。 |

### Run B Conclusion
- Tool 执行全成功，但 **完成判定过早**。
- 从 UI 看文本已输入；从文件系统校验看内容未落盘（或未被保存流程触发）。
- 问题是 **Reasoning/Verification gap**，不是 tool execution failure。

---

## Root Cause Classification
- `FilesMoveFile`
  - Primary: **Execution + Reasoning**
  - 表现: 点击命中风险（遮挡告警）+ 重复同一失败模式，未做策略切换。
- `MarkorCreateNote`
  - Primary: **Reasoning/Verification**
  - 表现: UI 满足即宣告完成，缺少“持久化成功”验证。

## Suggested Follow-up (for next tune round)
1. 在 `mobile_action click` 命中告警（occluded/offset）后，向模型显式注入“低置信度动作”标记，触发换策略而非重复点击同控件。
2. 对文件类任务增加“完成前持久化验证”策略（至少要求一次可观测保存动作或状态变化）再允许 `complete_task`。
3. 对重复失败模式（同页面、同目标、同结果连续 N 次）增加防循环约束与强制改道提示。

---

## Debug2 Continue: FilesMoveFile (visual-debug, 2026-02-20)

### Additional reruns
- 单任务重跑 1: `eval/results/20260219_193512` (`aw_20260219_193512_FilesMoveFile_0_0`)
- 单任务重跑 2: `eval/results/20260219_194401` (`aw_20260219_194401_FilesMoveFile_0_0`)
- 均为 `MaxTurnsReached`，但用于验证 execution 细节。

### Q1: click 偏低误点（是否坐标换算问题）

结论：**旧 run 的确存在 click 偏移 bug；已修复。**

证据对比（同目标：`element_index=7` / `More options`）：
- 旧 run `20260219_185400`:
  - 目标中心: `(1017,191)`，bounds: `[954,128,1080,254]`
  - 实际点击: `(1079,191)`
  - tool result: `Warning: Element center likely occluded; using offset point`
  - 出现在 T9/T15/T20/T26（反复触发）。
- 新 run `20260219_193512` & `20260219_194401`:
  - 实际点击稳定为 `(1017,191)`（中心点）
  - 无上述 offset 告警（T8/T10/T15/T20 等）。

定位与修复：
- 原因是 `TargetResolver` 的遮挡判断边界条件与 Android `Rect.contains` 不一致（right/bottom 误用闭区间），导致共享边界时误判中心点被遮挡，进而偏移到右侧危险点。
- 已修复为 `left/top inclusive, right/bottom exclusive` 语义，并加测试覆盖相邻控件共享边界场景。

### Q2: long_press 是否 execution code bug

结论：**有 execution 层可改进点，已修；但该任务里 long_press 仍“成功但无可观测效果”。**

已做 execution 修复：
- 将 long press 执行链从旧的 `swipe_to_self` 改为：
  1. `UIAction.LongClickNodeAt`（语义目标优先）
  2. 失败再 fallback `UIAction.LongPressAt`
- 对 long_press 加“无变化检测”：
  - pre/post 快照无变化时，tool result 增加
    - `Warning: Screen content unchanged after long press - action may have had no effect`
  - 并标记 `[unverified]`，避免误把“执行成功”当成“操作生效”。

关键证据（`20260219_194401`）：
- T7/T12/T19 都是 `long_press element_index=16`（`holiday_photos.jpg`）
- 目标元素属性：`clickable=true`, `long_clickable=false`, center=`(540,705)`
- tool result:
  - `node_action_long_click: No long-clickable node at (540,705)`
  - fallback `gesture_long_press: success`
  - 且带 `Screen content unchanged ... [unverified]`
- step 级比对也显示 `changed=false`（T7/T12/T19）。

判读：
- 当前 long_press 不再“静默成功”；现在会明确提示“可能无效”。
- 但在该 Files 场景中，`holiday_photos.jpg` 行没有暴露 long-clickable 语义，gesture long-press 也未带来可观测状态变化，导致流程仍卡住。

### Why Open with still appears after click fix

结论：**`Open with` 仍出现，但已不是“1079 偏移误点”那条路径。**

证据（`20260219_194401`）：
- T10 pre tree 中 `element_index=7` 明确是 top bar `More options`（center=`(1017,191)`）。
- T10 result 明确是 `Clicked (1017,191) via node_action_click`（无 offset）。
- T11 pre tree 直接是 `Open with` 对话框（`Open with`, `Retro Music`, `VLC`, `YouTube Music`, `Just once`, `Always`）。

这说明：
- 旧问题（偏移误点）已修掉；
- 但当前策略链（反复 long_press 未生效后再点 More options）仍会进入错误任务流，核心已转为 reasoning/flow control，而非坐标换算错误。

### Step-level rationality snapshot (rerun `20260219_194401`)

- 合理且推进（T1-T6）:
  - 打开 Files、进入存储、滚动、进入 Podcasts，行为正确。
- 关键失败段（T7-T21）:
  - T7/T12/T19 `long_press`：tool success 但 `changed=false` + `[unverified]`，不应继续假定“文件已选中”。
  - T10/T15/T20 `click More options`：tool success、坐标准确，但后续进入 `Open with`，对 move 目标无帮助。
  - T11/T14/T16/T18/T21 反复 `back`：只是从错误弹窗回退，未形成新策略。
- 后半段漂移（T22-T30）:
  - 出现文本索引错误（T22），并逐步偏离“move to DCIM”主线（转向打开文件/换 app 流）。

### Notes
- 我尝试了 `scripts/action-test.sh` 做 L0/L1 单步对照，但脚本默认优先真机，且模拟器状态未稳定在同一 `Podcasts` 画面，故这组单步数据不纳入结论（只采用 eval trace 证据）。
