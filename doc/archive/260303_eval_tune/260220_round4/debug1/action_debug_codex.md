# Action Debug Report (Codex)

## Target
- Eval run: `eval/results/20260219_163232`
- Task: `aw_20260219_163232_FilesMoveFile_1_0`
- Focus action: turn 10 (`mobile_action click element_index=0`)

## 1) Trace extraction: `element_index -> sanitized_a11y_tree -> center`

### Key extracted mappings
- turn 8: `element_index=7`
  - tree: `eval/results/20260219_163232/artifacts/aw_20260219_163232_FilesMoveFile_1_0/trace/artifacts/sanitized_a11y_tree/126_sanitized_1771537045287.json`
  - element: `text='More options'`, `clickable=true`, `center=[1017,191]`
- turn 10: `element_index=0`
  - tree: `eval/results/20260219_163232/artifacts/aw_20260219_163232_FilesMoveFile_1_0/trace/artifacts/sanitized_a11y_tree/161_sanitized_1771537059495.json`
  - element: `class='ScrollView'`, `clickable=false`, `bounds=[0,0,1080,2209]`, `center=[540,1104]`
- turn 14/20: 同 turn 10（仍是 `element_index=0`, `center=[540,1104]`）

### False-success evidence in trace
- turn 10:
  - args: `eval/results/20260219_163232/artifacts/aw_20260219_163232_FilesMoveFile_1_0/trace/artifacts/tool_call_args/170_turn_10_mobile_action_synthetic_mobile_action_text_d39b1a17-8fc6-4f96-a1ba-15b58.txt`
  - result: `eval/results/20260219_163232/artifacts/aw_20260219_163232_FilesMoveFile_1_0/trace/artifacts/tool_result/175_turn_10_mobile_action_synthetic_mobile_action_text_d39b1a17-8fc6-4f96-a1ba-15b58.txt`
  - pre tree: `eval/results/20260219_163232/artifacts/aw_20260219_163232_FilesMoveFile_1_0/trace/artifacts/sanitized_a11y_tree/161_sanitized_1771537059495.json`
  - post tree: `eval/results/20260219_163232/artifacts/aw_20260219_163232_FilesMoveFile_1_0/trace/artifacts/sanitized_a11y_tree/172_sanitized_1771537063471.json`
  - verdict: `pre_count=10`, `post_count=10`, `changed=False`
- turn 14/20: 同模式（`changed=False`）

## 2) Manual navigation to error state (adb)
在 `emulator-5554` 上手动复现到 chooser/open-with 态：
1. 进入 Files (`com.google.android.documentsui`)
2. 打开 `sdk_gphone64_arm64/Podcasts`
3. 长按 `holiday_photos.jpg`
4. 点右上角 `More options`
5. 进入 `ChooserActivity`（与 trace 的 open-with 错误态同类）

注：为复现场景，当前设备上补了测试文件 `/sdcard/Podcasts/holiday_photos.jpg`。

## 3) Action test matrix (`./scripts/action-test.sh`)
Test point: `(x=540,y=1104)`

### L0 adb
- Command: `./scripts/action-test.sh click --x 540 --y 1104 --adb --tag filesmove_t10_l0_adb`
- Evidence:
  - `debug-output/action-test/filesmove_t10_l0_adb/before_adb.png`
  - `debug-output/action-test/filesmove_t10_l0_adb/after_adb.png`
  - screenshot sha1 前后不同（UI changed）

### L1 node
- Command: `./scripts/action-test.sh click --x 540 --y 1104 --use-node true --tag filesmove_t10_l1_node`
- Result: `debug-output/action-test/filesmove_t10_l1_node/result.json`
  - `action_accepted.status = failure`
  - `action_accepted.message = "No clickable node at (540,1104)"`
  - `ui_changed.verdict = unchanged`

### L1 gesture
- Command: `./scripts/action-test.sh click --x 540 --y 1104 --use-node false --tag filesmove_t10_l1_gesture`
- Result: `debug-output/action-test/filesmove_t10_l1_gesture/result.json`
  - `action_accepted.status = success`
  - `ui_changed.verdict = changed`

## 4) Classification

Primary classification: **node targeting issue**
- Pattern matched:
  - L0(adb) works
  - L1(node) fails (`No clickable node`)
  - L1(gesture) works
- 结论：不是 gesture 注入链路故障，也不是纯 timing 问题。

Secondary note: **perception mismatch signal exists**
- Trace 多次选择了 `clickable=false` 的 `element_index=0`（根 ScrollView），这本身是 target 选择质量问题。
- 在原 trace 中该动作出现“success 文本 + UI unchanged”的 false success；action-test 拆分后可见 node path 实际不成立。

## 5) Environment notes
- 为了让 `action-test.sh` 在本机 bash 3.2 正常执行，修复了毫秒转秒处的 awk 兼容写法：`scripts/action-test.sh`
- 为启用 debug action receiver，执行了 `./gradlew installDebug` 到 `emulator-5554`
