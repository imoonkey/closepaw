# Review: `466382bb614adaa65b95b3e023e3e61232179b42..eb84b0ed65fc43d16573141feebf0d7df2702f14`

**Reviewer**: Codex  
**Date**: 2026-02-12  
**Recommendation**: **CHANGES_REQUESTED**

## Summary

本次区间引入了 Stage4 的核心能力（VD live preview、StatusIsland、录制链路重构、TaskCompleted.reason 等）。
我重点检查了平台切换、事件生命周期、录制持久化和会话收尾路径。

| Severity | Count |
|---|---:|
| Critical | 0 |
| High | 1 |
| Medium | 1 |
| Low | 1 |

## High

1. **`startNewSession()` 参数错位，导致 sessionId/model 元数据污染，且有文件名碰撞风险**

- 位置：`app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:54`、`app/src/main/kotlin/com/moonkey/androidagent/history/SessionHistoryManager.kt:151`
- 现状：`initializeNewSession()` 新签名为 `(sessionId, model, appVersion)`，但 `SessionHistoryManager.startNewSession()` 仍使用位置参数 `initializeNewSession(model, appVersion)`。
- 实际结果：
  - `sessionId` 会被写成 `model`（例如 `gpt-5.2`）
  - `model` 会被写成 `appVersion`
  - `SessionStorage.generateFileName()` 以 `sessionId` 拼文件名；同秒内重复 `startNewSession`（相同 model）会产生同名文件，存在覆盖风险。
- 建议修复：改为命名参数调用 `initializeNewSession(model = model, appVersion = appVersion)`，并补一个回归测试覆盖该调用点。

## Medium

1. **录制收尾调用了“另一套 recorder”，导致当前运行会话的 metadata 可能长期不完整**

- 位置：
  - 写入来源：`app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:239`（使用 `agentSession.getServices().recordingService`）
  - 实例创建：`app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt:149`
  - 收尾调用：`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:118` -> `app/src/main/kotlin/com/moonkey/androidagent/history/SessionHistoryManager.kt:186`
- 问题：事件写入的是 Session 内部 recorder，但任务完成时调用 `sessionHistoryManager.endSession()` 收尾的是 `SessionHistoryManager` 自己维护的 recorder（不是同一个实例）。
- 影响：当前实际运行会话可能没有走 `completeSession()`，`completedNormally/turnCount/summary` 等元数据可能不一致。
- 建议修复：统一 recorder 单一来源（推荐以 `currentSession.getServices().recordingService` 为准），并在 `SessionCompleted/Shutdown` 路径显式对同一实例执行 `completeSession()`。

## Low

1. **StatusIsland 顶部偏移未使用状态栏高度，刘海屏上可能贴边**

- 位置：`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/StatusIslandManager.kt:258`、`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/StatusIslandManager.kt:269`
- 问题：`statusBarHeight` 已计算但未用于 `y` 偏移；当前固定 `y = dp(4)`。
- 影响：部分设备上视觉位置偏高、可触达性下降。
- 建议：`y = statusBarHeight + dp(4)`（或改用 window inset）。

## Recommendation

**CHANGES_REQUESTED**。建议先修复 High + Medium 两项后再合并；Low 可在同一批次顺手修。
