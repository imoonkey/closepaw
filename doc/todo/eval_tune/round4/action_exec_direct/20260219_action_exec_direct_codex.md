# Round4 Tech Design: Action Execution Direct Debug Harness (Codex)

## 1. Problem

当前 `click / long_press / scroll / swipe` 常出现：
- tool 返回 succeeded
- 但 UI 实际没有发生预期变化（false success）

现有调试链路依赖完整 agent task（LLM 规划+执行），反馈太慢，且无法快速定位问题到底在：
1. tool executor 层（target resolve / fallback / settle）
2. platform action 层（`performAction` / `dispatchGesture` / node action）
3. accessibility API vs adb 输入机制差异

## 2. Goal

提供一个**短链路、可重复、可对照**的调试系统，在当前屏幕直接执行动作，不经过 agent 主流程。

必须支持：
1. 直接执行 `mobile_action`（走现有 executor）
2. 直接执行 `UIAction`（绕过 executor，测 platform 原子动作）
3. adb baseline 对照执行（同场景下对比稳定性）
4. pre/post 截图 + 结构化结果 + 统一 run_id，便于回放和统计

## 3. Non-Goals

1. 不改 LLM prompt/认知策略
2. 不在本轮重构 executor 逻辑
3. 不引入新的线上产品能力（仅 debug 能力，Debug build 可用）

## 4. Design Overview

新增一条 Debug 专用执行通道：`Action Exec Direct`

```text
Host script (adb) 
  -> broadcast to AgentService (debug-only)
    -> DirectActionRunner
      -> Layer A: mobile_action executor
      -> Layer B: platform.performAction(UIAction)
      -> (Layer C adb baseline runs on host script side)
  -> pull artifacts + summarize
```

三层调试模型：
1. `tool_executor`：复用 `MobileActionTool` + `*Executor` 全链路
2. `ui_action`：直接 `AndroidPlatform.performAction(UIAction.*)`
3. `adb_baseline`：`adb shell input tap/swipe/...`（host 执行）

## 5. Core Interfaces

## 5.1 Broadcast Action (Debug-only)

- Action: `com.moonkey.androidagent.DEBUG_DIRECT_ACTION`
- Receiver: `AgentService`（仅 `BuildConfig.DEBUG` 注册）
- Payload（JSON string extra）:

```json
{
  "run_id": "direct_20260219_153000_001",
  "layer": "tool_executor",
  "capture_mode": "both",
  "settle_ms": 350,
  "params": {
    "action": "click",
    "element_index": 3
  }
}
```

`layer` 枚举：
- `tool_executor`
- `ui_action`

`adb_baseline` 不进 app，走 host 脚本直接执行。

## 5.2 Result Artifact Schema

app 侧输出：`/sdcard/Android/data/com.moonkey.androidagent/files/direct-action/<run_id>/result.json`

```json
{
  "run_id": "...",
  "layer": "tool_executor",
  "started_at": 0,
  "ended_at": 0,
  "duration_ms": 0,
  "request": {...},
  "execution": {
    "transport_success": true,
    "engine_result": "success|error|cancelled",
    "message": "...",
    "attempt_trail": ["..."]
  },
  "effect": {
    "a11y_changed": true,
    "a11y_fingerprint_before": "...",
    "a11y_fingerprint_after": "...",
    "pixel_changed": true,
    "pixel_change_ratio": 0.13,
    "verdict": "changed|unchanged|inconclusive"
  },
  "artifacts": {
    "pre_png": "pre.png",
    "post_png": "post.png",
    "pre_tree": "pre_tree.json",
    "post_tree": "post_tree.json"
  }
}
```

关键点：把“动作被系统接受（transport_success）”和“UI 是否变化（effect.verdict）”拆开，显式识别 false success。

## 6. Execution Semantics

## 6.1 `tool_executor`

执行步骤：
1. capture pre snapshot
2. `MobileActionTool.validate(params)`
3. `createInvocation(params)`
4. 构造轻量 `ToolExecutionContext`（platform + pre snapshot）
5. `invocation.execute(context)`
6. settle（请求可配，默认 300ms）
7. capture post snapshot
8. 计算 effect verdict 并落盘

特点：完整复用你现在的 action engine，不经过 LLM/turn orchestration。

## 6.2 `ui_action`

执行步骤：
1. capture pre snapshot
2. payload 直接映射 `UIAction`（如 `TapAt`, `ClickNodeAt`, `Swipe`, `ScrollNodeAt`）
3. `platform.performAction(uiAction)`
4. settle
5. capture post snapshot
6. 计算 effect verdict

特点：隔离 executor 逻辑，直接测 platform + accessibility API。

## 6.3 `adb_baseline`（Host Script）

执行步骤：
1. `adb exec-out screencap -p > pre.png`
2. 执行 `adb shell input ...`
3. sleep settle
4. `adb exec-out screencap -p > post.png`
5. 本地计算像素变化（可选）

特点：提供稳定基线，用于与 accessibility 通道对照。

## 7. Host Script Design

新增：`scripts/action-direct-debug.sh`

核心命令：

1. Tool executor 单次执行
```bash
./scripts/action-direct-debug.sh run \
  --layer tool_executor \
  --params '{"action":"click","element_index":3}'
```

2. UIAction 单次执行
```bash
./scripts/action-direct-debug.sh run \
  --layer ui_action \
  --params '{"type":"TapAt","x":540,"y":1680}'
```

3. A/B 对照（tool vs adb）
```bash
./scripts/action-direct-debug.sh compare \
  --tool '{"action":"click","x":540,"y":1680}' \
  --adb 'input tap 540 1680'
```

`compare` 模式输出：
- `debug-output/action-direct/<run_id>/tool/*`
- `debug-output/action-direct/<run_id>/adb/*`
- `summary.json`（成功率、变化率、结论）

## 8. Determinism Contract

1. 每次请求只执行一次 action（无外层 retry）
2. settle 时间显式可配，默认固定值
3. 同一 run_id 下 artifact 命名稳定
4. 并发策略：串行队列（DirectActionRunner 内 `Mutex`），避免多个调试动作互相污染

## 9. Effect Verification

判定信号分层：

1. A11y 结构变化（主信号）
- fingerprint: `package + element_count + topN(bounds,text,flags)` 哈希

2. 像素变化（辅信号）
- pre/post PNG 差异比率（快速阈值）

3. Verdict 规则
- 两者都变：`changed`
- 两者都不变：`unchanged`
- 仅一者变化：`inconclusive`

说明：`unchanged` 不等于动作失败，但这是你要抓的 false success 主样本。

## 10. Concurrency & Safety

1. 仅 Debug build 注册该 receiver
2. 只接受本包 action + 可选 debug token 校验
3. 若 agent 正在 running：
- 默认拒绝 direct action（返回 busy）
- 或要求先 takeover（可配策略）
4. 所有执行都带超时（例如 8s），避免 service 卡死

## 11. File-Level Plan

新增：
1. `app/src/main/kotlin/com/moonkey/androidagent/debug/direct/DirectActionModels.kt`
2. `app/src/main/kotlin/com/moonkey/androidagent/debug/direct/DirectActionRunner.kt`
3. `app/src/main/kotlin/com/moonkey/androidagent/debug/direct/DirectEffectAnalyzer.kt`
4. `app/src/main/kotlin/com/moonkey/androidagent/debug/direct/DirectArtifactStore.kt`
5. `scripts/action-direct-debug.sh`
6. `doc/todo/eval_tune/round4/action_exec_direct/20260219_action_exec_direct_codex.md`（本文）

修改：
1. `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`
   - 注册/注销 `DEBUG_DIRECT_ACTION` receiver
   - 将请求交给 `DirectActionRunner`
2. `app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceReceiverHelpers.kt`
   - 扩展 debug receiver 注册 helper（复用 stop receiver 模式）
3. （可选）`scripts/README.md`
   - 增加新脚本用法

## 12. Rollout Phases

## Phase 0 (MVP, 1-2天)

1. `tool_executor` 单次执行
2. pre/post 截图与 result.json
3. host 脚本 `run` 子命令

交付后你就能快速复现实验，不再依赖整条 agent task。

## Phase 1 (对照能力, 1天)

1. `ui_action` 层执行
2. `adb_baseline` + `compare` 模式
3. summary 聚合输出

交付后能直接定位：executor 问题 vs accessibility 原子动作问题。

## Phase 2 (稳定性统计, 1天)

1. 批量重复执行（N 次）
2. 输出每 action 在不同层的变更成功率矩阵
3. 产出 flaky 排行榜（按 action + app + widget 类型）

## 13. Success Criteria

1. 单次调试链路 < 5 秒（不启动 agent task）
2. 一条命令可拿到 pre/post + verdict + 原始返回
3. 同场景下可稳定跑 tool vs adb 对照
4. 能批量定位 false success 样本，并明确归因层级

## 14. Why This Fits Current Codebase

1. 你的 action 已经集中在 `MobileActionTool` + `*Executor`，可直接复用
2. `AndroidPlatform.performAction(UIAction)` 是天然原子层入口
3. `AgentService` 已有 debug broadcast 机制（STOP_AGENT），易扩展
4. 现有 trace/artifact 机制可平滑复用，不需要推翻架构

---

## Proceed Suggestion

建议先落地 Phase 0（`tool_executor` + artifact + run 脚本），当天就能开始大量收集 false success 样本；随后补 Phase 1 做 adb 对照归因。
