## 1. 一句话结论
CoPaw 有真实的长期记忆，但它不是独立知识库，而是以 `WORKING_DIR/MEMORY.md` 与 `WORKING_DIR/memory/*.md` 为 source of truth、由 `reme-ai` 提供压缩与检索能力的文件型记忆系统。

## 2. Product层面
- 面向用户的记忆心智模型很清楚：`MEMORY.md` 存长期稳定事实，`memory/YYYY-MM-DD.md` 存每日运行日志与上下文摘要，见 `website/public/docs/memory.en.md`。
- 这些记忆不是藏在黑盒 DB 里；Console/API 直接提供列出、读取、写回能力，见 `src/copaw/app/routers/agent.py` 与 `console/src/api/modules/workspace.ts`。
- 但它是“工作区级记忆”而不是“用户级记忆”：`MemoryManager` 和 `AGENT_MD_MANAGER` 都绑定全局 `WORKING_DIR`，见 `src/copaw/constant.py`、`src/copaw/agents/memory/agent_md_manager.py`、`src/copaw/app/runner/runner.py`。

## 3. System层面
- 短期记忆是消息态；长期记忆是文件态。`CoPawAgent` 在启用 memory manager 时，会把默认 `InMemoryMemory` 替换成 `memory_manager.get_in_memory_memory()`，见 `src/copaw/agents/react_agent.py`。
- `MemoryManager` 自身很薄，只做 glue：读取 `EMBEDDING_*` / `FTS_ENABLED` / `MEMORY_STORE_BACKEND`，注册 `read_file` / `write_file` / `edit_file` 到 `summary_toolkit`，再把 `compact_memory` / `summary_memory` / `memory_search` 交给父类 `ReMeLight`，见 `src/copaw/agents/memory/memory_manager.py`。
- 仓内文档把检索定义为 “Vector + BM25 hybrid search”，并明确 “files are the source of truth”；但 chunking、indexing、watching、ranking 的本体不在 CoPaw 仓里，而依赖 `reme-ai==0.3.0.6b2`，见 `website/public/docs/memory.en.md` 与 `pyproject.toml`。

## 4. Lifecycle层面
- 启动时 `AgentRunner.init_handler()` 创建并 `start()` `MemoryManager`；每次请求再新建 `CoPawAgent` 并注入它，见 `src/copaw/app/runner/runner.py`。
- 每轮推理前 `MemoryCompactionHook` 会先估 token，保留 system prompt 与 recent messages，再同步生成 compressed summary，同时用 `add_async_summary_task()` 异步持久化，见 `src/copaw/agents/hooks/memory_compaction.py`。
- 请求结束后，当前短期 memory state 会按 `session_id/user_id` 落到 `sessions/*.json`；这保证“跨请求连续对话”，但它仍是 session state，不等于长期记忆，见 `src/copaw/app/runner/session.py`、`src/copaw/app/runner/runner.py`、`src/copaw/app/runner/command_dispatch.py`。
- 人工生命周期控制也有：`/compact`、`/new`、`/clear`、`/history` 在 `src/copaw/agents/command_handler.py`；而“定期把 daily log 提炼进 `MEMORY.md`”主要只是 `AGENTS.md` 里的行为约定，不是硬编码后台流程，见 `src/copaw/agents/md_files/en/AGENTS.md`。
- 从本地文档看，context overflow 的 auto-summary 目标是 `memory/YYYY-MM-DD.md`；但本仓代码里 `summary_memory()` 只是把 file tools 交给 `ReMeLight`，具体写入编排不在本地实现，见 `website/public/docs/memory.en.md` 与 `src/copaw/agents/memory/memory_manager.py`。

## 5. Injection层面
- 默认 system prompt 只加载 `AGENTS.md`、`SOUL.md`、`PROFILE.md`，不加载 `MEMORY.md`，见 `src/copaw/agents/prompt.py`。
- 记忆注入的主路径是“prompt policy + tool call”：`AGENTS.md` 明确要求在回答 past work / decisions / preferences / todos 之前先跑 `memory_search`；`CoPawAgent._setup_memory_manager()` 再把 `memory_search` 动态注册进 toolkit，见 `src/copaw/agents/md_files/en/AGENTS.md`、`src/copaw/agents/react_agent.py`、`src/copaw/agents/tools/memory_search.py`。
- Console 确实允许把 `MEMORY.md` 手动加入 system prompt，但 UI 自己警告这会让上下文过长，见 `console/src/pages/Agent/Workspace/components/useAgentsData.ts` 与 `console/src/locales/zh.json`。
- 这说明 CoPaw 的默认策略不是“把全部记忆注入模型”，而是“平时按需检索，必要时人工提升到 prompt 层”。

## 6. 抽象层面
- 它把记忆抽象成两层：`MEMORY.md` 是 curated long-term memory，`memory/*.md` 是 raw / daily log；这个抽象既适合 agent 写，也适合人直接检查和编辑，见 `website/public/docs/memory.en.md` 与 `src/copaw/agents/md_files/en/AGENTS.md`。
- “上下文压缩”和“长期记忆持久化 / 召回”被放在同一个 `MemoryManager` 接口后面，但本质是两条不同流水线：前者面向当前对话上下文，后者面向跨会话文件记忆，见 `src/copaw/agents/memory/memory_manager.py` 与 `website/public/docs/compact.en.md`。
- 所以它不是 schema-first / graph-first / entity-first memory；更像“可搜索工作日志 + 压缩摘要 + 少量人工整理”的操作系统。

## 7. 值得借鉴 / 明显局限
- 值得借鉴：把 Markdown 文件而不是向量库当 source of truth，调试、人工修订、迁移都更简单，见 `website/public/docs/memory.en.md`。
- 值得借鉴：明确区分 `MEMORY.md`（提炼后的长期记忆）和 `memory/*.md`（原始 / 每日记忆），这比“一个大记忆池”更好维护，见 `website/public/docs/memory.en.md`。
- 值得借鉴：默认不把 `MEMORY.md` 直接塞进 system prompt，而是优先走 `memory_search` 按需召回，见 `src/copaw/agents/prompt.py`、`src/copaw/agents/tools/memory_search.py`、`console/src/locales/zh.json`。
- 明显局限：检索和写入是否发生，很大程度仍依赖 `AGENTS.md` 的行为约束，而不是强制的 retrieval/write policy；模型不主动调工具就会漏召回，见 `src/copaw/agents/md_files/en/AGENTS.md` 与 `src/copaw/agents/tools/memory_search.py`。
- 明显局限：长期记忆默认是 workspace 共享，不按 user/channel 隔离；多用户、多渠道接入时容易混记忆，见 `src/copaw/constant.py`、`src/copaw/app/runner/runner.py`、`src/copaw/agents/memory/agent_md_manager.py`。
- 明显局限：核心索引、watcher、混合检索与摘要落盘都外包给 `reme-ai`；CoPaw 本仓更多是集成层，所以很多关键质量点不能只靠本仓代码判断，见 `pyproject.toml` 与 `src/copaw/agents/memory/memory_manager.py`。
- 明显局限：文档与代码有轻微偏差。文档把 `EMBEDDING_MODEL_NAME` 写成默认 `text-embedding-v4`，但 `MemoryManager` 代码默认值是空字符串，因此仅配 API key 时向量检索未必真的开启，见 `website/public/docs/memory.en.md` 与 `src/copaw/agents/memory/memory_manager.py`。
- 明显局限：`tests/` 下基本看不到 memory / compact 相关测试，这块更像依赖手工验证和上游库保证，回归风险偏高。
