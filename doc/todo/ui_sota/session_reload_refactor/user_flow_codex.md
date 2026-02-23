status: draft

# Session-Level User Flow (Codex)

## 1. 设计目标（用户视角）

用户只关心两件事：
1. 我现在是在“同一条聊天线程继续”还是“新开线程”
2. agent 现在是在“执行中”还是“可继续提问”

用户不应感知 `currentSession` 是否已被释放。

## 2. Flow A: 新会话起点（Force Fresh）

1. 用户点击 `+` 或通过 debug-run `fresh_session=true` 启动
2. 系统创建新 thread binding（新 session file 对）
3. 首条输入触发任务执行
4. task 完成后，释放 runtime，但 thread binding 保留
5. 输入 follow-up 时，系统自动从同一 thread binding reload 后继续

预期：不需要用户重新点历史。

## 3. Flow B: 在当前聊天里直接 follow-up

1. 上一任务完成（UI显示完成）
2. 用户直接在当前输入框提 follow-up
3. 若 runtime 已释放：先按当前 thread binding reload
4. 再提交新输入，进入下一 task

预期：语义上连续；LLM 看见完整历史。

## 4. Flow C: 从历史列表进入并继续

1. 用户在左侧 history 选一条会话
2. UI先展示该会话记录（浏览态）
3. 用户发送新消息时：
   - 若该会话有 reloadable snapshot：resume + 执行
   - 若没有：明确提示“仅可浏览，无法继续”，并给出“新建会话”入口

预期：不要出现“看起来在继续，实际 fresh”。

## 5. Flow D: Stop 与 New Session 语义分离

1. Stop（任务级）：停止当前任务，thread binding 保留，可继续 follow-up  
2. New Session（会话级）：显式丢弃当前 binding，进入新线程  

预期：用户操作与系统语义一一对应，不共用同一个 `Shutdown` 心智。

## 6. Flow E: reload 失败降级

1. 当目标 thread 无可恢复 snapshot（损坏/缺失/版本不支持）
2. 状态进入 `Bound-ViewOnly`
3. 禁止“伪继续执行”，仅允许：
   - 浏览历史
   - 新建会话

预期：失败要显式，不做隐式 fresh fallback。

## 7. 关键 UX 不变量

1. 同一聊天页面中，follow-up 默认继续当前 thread（除非用户明确 New Session）
2. 任何时候都不能“静默切换到 fresh context”
3. “可继续”与“仅可浏览”在 UI 上必须可区分
