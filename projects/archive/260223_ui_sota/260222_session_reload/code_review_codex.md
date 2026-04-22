# Code Review (Codex): Session Reload + Task Lifecycle

按 `/code-review + /coding-standard + /tdd` 做完了。下面是按严重级别排序的 findings（先问题，后结论）。

**Findings**
1. **[Critical] Reload 后第一条用户消息会把“已恢复的会话记录”重置掉。**  
`MainActivity` 在 reload 后先 `resumeSession(...)`（`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:396`），但 `AgentSession` 仍处于 `Created`，收到首条 `UserInput` 会无条件 `initializeNewSession(...)`（`app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:246`）。  
结果：旧 `SessionRecord` 被新空记录覆盖路径，可能产生重复 session 文件/历史割裂。

2. **[Critical] Reload 配置未冻结完整 LLM 路由，Local 会话会被错误恢复成默认 OpenAI。**  
`toSessionConfig()` 没有恢复 `llm` 字段（`app/src/main/kotlin/com/moonkey/androidagent/session/SessionCheckpointCoordinator.kt:81`），会落到 `SessionConfig` 默认值。  
结果：原本 local backend 的会话 reload 后语义漂移，甚至因 key/模型不匹配直接失败。

3. **[High] “Fresh session” 语义被破坏：即使 fresh，也会优先 reload 旧快照。**  
`freshSession` 分支最终仍走 `ensureSessionAndSend()`（`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:198`），而该路径只要 `currentSession == null` 就先 `tryReloadSession()`（`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:317`）。  
结果：用户显式要求新会话时仍可能接到旧上下文。

4. **[High] 删除会话不会删 context 快照，已删会话可能被“复活”。**  
`SessionStorage.deleteSessionPair(...)` 已实现（`app/src/main/kotlin/com/moonkey/androidagent/history/storage/SessionStorage.kt:238`），但 `SessionHistoryManager` 仍只删 `session-*`（`app/src/main/kotlin/com/moonkey/androidagent/history/SessionHistoryManager.kt:113`）。`tryReloadSession()` 又会扫描全部 `context-*`（`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:375`）。  
结果：UI 删除后仍可被自动 reload。

5. **[High] Task 完成后未释放重资源，和设计目标不一致。**  
`handleAgentComplete()` 仍把状态设为 `Idle`（`app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:311`），未执行 `services.cleanup()`；UI 侧也明确写着“keep session alive”（`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:107`）。  
结果：`session_reload` 的资源效率目标（task 后释放）未达成。

6. **[High] 强制 checkpoint 实际是异步 fire-and-forget，存在落盘丢失窗口。**  
`flushIdleReady()/flushClosed()` 调用 `forceCheckpoint()`（`app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:315` / `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:404`），而 `forceCheckpoint()` 内部 `scope.launch` 不等待（`app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:306`）。  
结果：任务刚结束/刚 shutdown 即被系统杀进程时，checkpoint 可能没写完。

7. **[High] Reload hydrate 用 `addItem()` 可能触发二次 auto-compress，破坏“精确重建”。**  
`AgentSession.reload()` 逐条 `addItem()`（`app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:142`），而 `HistoryManager.addItem()` 会 `autoCompressIfNeeded()`（`app/src/main/kotlin/com/moonkey/androidagent/history/HistoryManager.kt:43`）。  
结果：重载过程本身可能改变 history（尤其多次 reload 后），违背 byte-identical 目标。

8. **[Medium] 设计里的 debounce checkpoint 路径没有接上线。**  
`scheduleCheckpoint()` 定义了（`app/src/main/kotlin/com/moonkey/androidagent/session/SessionCheckpointCoordinator.kt:33`），但当前无调用点。  
结果：`RUNNING_DIRTY` 基本不会落盘，checkpoint 策略不完整。

9. **[Medium][TDD] 核心改动缺少测试覆盖。**  
新增了 `SessionRuntimeSnapshot`/`HistoryItemConverter`/reload 协调逻辑，但没有对应单测（未见新增测试文件）。这块属于状态机+数据转换核心路径，按 `/tdd` 应先覆盖 round-trip 和 lifecycle。  

10. **[Medium] 当前测试总集是红的。**  
我跑了 `./gradlew test`，失败 4 个（`ClickExecutorTest`/`LongPressExecutorTest`，debug/release 各一套）。这组看起来是 action priority 相关，可能非本次改动直接引入，但当前分支整体不可合入。

**Open Questions / Assumptions**
1. 你是否有意保留“task 完成后 session 常驻内存”？如果是，就和这份 design 的 lifecycle 目标冲突，需要先统一目标。  
2. “fresh session” 预期是否必须硬性禁止自动 reload？从产品语义看应该是必须。

**Recommendation**
`CHANGES_REQUESTED`。  
这版已经搭起框架，但还有几处会直接影响正确性/一致性，建议先修复 Critical + High，再补上最小单测集（converter round-trip、reload 首条消息、fresh session 不 reload、delete 后不可 reload）。

## 问题澄清与决策建议（补充）

### 1) Task 完成后是否保留 session 常驻内存

结论：**不建议常驻**，按设计应在 `TaskCompleted` 后释放重资源（platform/llm/tool runtime）。

理由：
- 降低资源占用和后台压力是这个改造的核心目标；
- “保持常驻”带来的延迟收益通常有限，且会让生命周期复杂化；
- 只要 checkpoint 落盘可靠，follow-up 的可用性可以通过 reload 保证。

建议策略：
- 默认：`TaskCompleted -> flush checkpoint -> release runtime`；
- 如需优化“短时间连续追问”的体验，可后续做可选 grace window（比如 5-10s），但不作为 v1 默认。

### 2) `fresh session` 与自动 reload 的产品语义

你的判断是对的。基于当前 UI“会话边界清晰”的事实，主流程应拆成 2 类，不需要 `CONTINUE_LATEST` 默认路径：

1. **FORCE_FRESH（手动 New Session / debug-run fresh）**
- 必须禁止自动 reload；
- 行为应等价于用户点“新会话”：清理当前 active session，创建全新 session，首条消息进入新上下文。

2. **RESUME_SELECTED（用户点选某个旧 session）**
- 必须加载该指定 session 的上下文（按 sessionId / fileName 精确命中）；
- 不能退化为“加载最近的可用 snapshot”。

3. **CONTINUE_LATEST（仅非 UI 入口可选）**
- 不作为主 UI 聊天入口默认行为；
- 仅可用于无会话上下文的外部入口（例如某些 debug/automation 场景）；
- 即使保留，也应通过显式参数开启，而不是隐式扫描“最新 snapshot”。

对 debug-run 的建议：
- `fresh_session=true` 应只表达 **FORCE_FRESH**，不应触发“全局 setup”式副作用；
- 可以补一个显式参数（例如 `resume_policy=fresh|latest|session:<id>`）来区分评测模式和手工 follow-up 模式；
- one-shot 评测默认可继续 `stop_agent`，但需要 follow-up 调试时应提供 `--keep-session` 开关而不是复用 `fresh_session` 语义。

对 Main UI 的落地建议：
- 移除“无 active session 时自动遍历 snapshot 取最近”的逻辑；
- 新消息如果当前是“新会话上下文”则始终走 `INIT_NEW`；
- 只有用户显式选择了某个旧 session 才允许 `RELOAD_SELECTED`。
