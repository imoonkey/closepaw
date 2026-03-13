# 1. 一句话结论

ClawX 在本仓库里**没有实现独立的长期记忆系统**；它主要复用 OpenClaw 的会话 transcript 与工作区 bootstrap 文件来保留上下文，ClawX 自己做的是展示、复制、软删除和少量 UI 缓存。

# 2. Product层面

- 对用户可见的“memory”本质上是**聊天历史**，不是可管理的长期记忆产品。聊天页和侧边栏只暴露多会话、多上下文、按时间分桶的历史浏览与切换，核心入口在 `src/pages/Chat/index.tsx`、`src/components/layout/Sidebar.tsx`、`src/stores/chat.ts`。
- 会话标题不是语义摘要，而是从**首条 user message**截断生成，逻辑在 `src/stores/chat.ts`。这更像 history labeling，不是 memory distillation。
- Dashboard 里的 “Token Usage History” 也不是记忆能力，而是从 transcript 回放统计 usage，入口在 `src/pages/Models/index.tsx`，后端读取逻辑在 `electron/api/routes/usage.ts` 和 `electron/utils/token-usage.ts`。
- 代码和 README 都没有出现用户画像、事实记忆、跨会话召回、知识库管理、记忆开关/编辑/清理等产品面能力。

# 3. System层面

- 持久化核心不在 ClawX 自己，而在 `~/.openclaw`。`src/stores/chat.ts` 通过 Gateway RPC 调 `sessions.list`、`chat.history`、`chat.send`，说明会话记忆主体由 OpenClaw runtime 承担。
- 会话文件是 JSONL transcript。删除时不是数据库删除，而是把 `~/.openclaw/agents/<agentId>/sessions/<suffix>.jsonl` 重命名为 `.deleted.jsonl`，并同步更新 `sessions.json`，实现见 `electron/api/routes/sessions.ts` 和 `electron/main/ipc-handlers.ts`。
- ClawX 自己的 `electron-store` 仅存应用设置，不承担记忆建模，见 `electron/utils/store.ts`。
- 唯一明显的本地“memory-like”缓存是 renderer 里的 `localStorage` 键 `clawx:image-cache`，用于附件预览回填，见 `src/stores/chat.ts`。这是 UI cache，不是 agent memory。
- 在 `src/`、`electron/`、`resources/` 范围内看不到 embedding、vector store、RAG retrieval、记忆评分/压缩/写回等实现路径。

# 4. Lifecycle层面

- **创建**：新会话只是生成一个新的 `sessionKey`（`agent:<id>:session-<timestamp>`），并切到空上下文；不会做旧会话总结、摘录或迁移，见 `src/stores/chat.ts`。
- **读取**：历史读取完全依赖 `chat.history`，前端只做消息过滤、附件补全、UI 乐观态修复，见 `src/stores/chat.ts`。
- **保留**：创建新会话时明确避免 `sessions.reset`，理由是要保留旧 transcript 可回看，见 `src/stores/chat.ts` 的注释。
- **删除**：是 soft delete，不是语义遗忘；本质上只是让 `sessions.list` 不再返回该 transcript，底层文件仍以 `.deleted.jsonl` 形式存在，见 `electron/main/ipc-handlers.ts`。
- **跨 agent 继承**：新 agent 建立时会复制主工作区的 bootstrap 文件和部分 runtime 文件到目标 agent，见 `electron/utils/agent-config.ts`。这是一种文件级继承，不是记忆提炼。

# 5. Injection层面

- 真正接近“记忆注入”的部分是 `electron/utils/openclaw-workspace.ts`：它把 `resources/context/AGENTS.clawx.md`、`resources/context/TOOLS.clawx.md` 合并进 OpenClaw 工作区已有的 bootstrap markdown。
- 注入目标不是消息流，而是工作区里的长期文件；合并通过 `<!-- clawx:begin -->` / `<!-- clawx:end -->` 标记完成，属于静态 prompt/context patch，不是按 query 检索召回。
- 触发时机在 `electron/main/index.ts`：应用启动后、Gateway 运行后、Gateway 重连后都会调用 `ensureClawXContext()`，说明它把“记忆”更当成 workspace seed，而不是 runtime retrieval。
- `electron/utils/agent-config.ts` 还会把 `AGENTS.md`、`SOUL.md`、`TOOLS.md`、`USER.md`、`IDENTITY.md`、`HEARTBEAT.md`、`BOOT.md` 从主 workspace 复制到新 agent workspace。这里的持久上下文来自文件复制，不来自语义选择。

# 6. 抽象层面

这个仓库里与 memory 最接近的抽象其实只有三层：

- `session transcript`：episode/history，来源是 OpenClaw 的 JSONL。
- `workspace bootstrap files`：持久指令/人格/工具说明，来源是 markdown 文件。
- `UI cache`：附件预览之类的前端缓存。
- 它没有把 memory 抽象成独立域对象，没有 memory item/schema/store/provider/retriever/injector 这样的边界。
- 因而它也没有短期记忆与长期记忆的清晰分层；“记忆”被分散在 `src/stores/chat.ts`、`electron/utils/openclaw-workspace.ts`、`electron/utils/agent-config.ts`、`electron/api/routes/sessions.ts` 这些非统一模块里。

# 7. 值得借鉴 / 明显局限

## 值得借鉴

- 足够克制：不额外引入数据库或向量库，直接站在上游 runtime 的 transcript 和 workspace 机制上，复杂度低。
- 注入方式稳健：`openclaw-workspace.ts` 用 marker merge 而不是粗暴覆盖，降低了对上游 bootstrap 文件的破坏风险。
- 生命周期可解释：会话保留、软删除、agent workspace 复制都比较直白，排障成本低。

## 明显局限

- **没有真正长期记忆**：没有事实抽取、偏好沉淀、跨会话召回、语义检索、冲突消解。
- **没有记忆压缩**：历史主要依赖 transcript 原文，规模增长后上下文效率和可控性都会受限。
- **注入过于静态**：当前注入依赖固定 markdown 文件和 workspace 复制，缺少按任务/按 query 的选择性召回。
- **跨 agent 继承容易陈旧**：bootstrap 文件复制后会分叉，后续一致性只能靠人工或再次 merge。
- **UI 呈现偏 history，不是 memory**：首条消息命名、时间分桶、usage 回放都服务于“看历史”，不是“用记忆”。
