status: draft

# Session Reload Root Cause (Codex)

## 1. 现象分两类

1. `Unable to reload selected session context`（见 `screenshot_session_continue_3.png`）  
2. 没有报 toast，但 agent 在 follow-up 里说“看不到之前对话记录”（见 `screenshot_session_continue_1.png` / `screenshot_session_continue_2.png` / `screenshot_session_continue_4.png`）

这两个现象不是同一个 bug，而是同一套状态管理混乱下的两种失败路径。

## 2. 当前状态被拆散在 5 个地方

1. `MainActivity.currentSession`（是否有运行时 `AgentSession` 实例）
2. `MainActivity.selectedSessionForReload`（UI 选择了哪条历史用于 reload）
3. `SessionRecordingService` 内部 current session/file（UI record 写入目标）
4. `AgentSession.state`（`Created/Running/Idle/Paused/Completed/Shutdown`）
5. 磁盘 checkpoint state（`RUNNING_DIRTY/IDLE_READY/CLOSED`）

问题不是“状态不够多”，而是“同一个业务语义被多个变量分别代理，且无单一 owner”。

## 3. 第一性原理下的关键语义缺失

系统缺了一个一等公民状态：**当前聊天线程绑定（thread binding）**。  

当前实现里，业务上“我还在这条会话里继续聊”被隐式等价成：
- 要么 `currentSession != null`
- 要么 `selectedSessionForReload != null`

这在 task 完成后会直接失真：
- task 结束后 `currentSession` 被清空（资源释放目标）
- 但如果用户没有显式“点历史”，`selectedSessionForReload` 也是空
- 下一条 follow-up 就会走 fresh session，历史自然丢失

## 4. 为什么会出现“同样是 follow-up，有时报 toast，有时假继续”

1. 当 `selectedSessionForReload != null` 且 snapshot 不可 reload：触发 toast（硬失败）。  
2. 当 `selectedSessionForReload == null`：直接 fresh session（软失败，UI 还在同一聊天画面，但 LLM 已换上下文）。  

第二种最危险，因为用户以为“继续同一会话”，实际上上下文已断开。

## 5. 结构性结论

`Completed` / `Idle` 这些 runtime 状态不是主问题；主问题是**session-level 与 task-level 状态没分层**。  

需要把状态分成三层并显式建模：
1. Thread binding（会话线程身份）
2. Runtime lease（运行时资源是否持有）
3. Task execution（当前任务是否运行/暂停）

只要 thread binding 不丢，即使 runtime 被释放，follow-up 仍可稳定 reload。
