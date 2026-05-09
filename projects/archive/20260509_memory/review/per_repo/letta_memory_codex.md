# Letta Memory Review

## 1. 一句话结论
Letta 确实有“真长期记忆”，但不是单一 memory engine：`core memory` 是常驻 prompt 的 block，`recall memory` 本质是可检索的消息库，`archival memory` 才是独立的长期存储层；另外还有可选的 git-backed core memory。

## 2. Product层面
- `README.md` 直接把 Letta 定位成 `stateful agents` / `advanced memory` 产品，而不是普通聊天代理。
- Agent 创建时就能传 `memory_blocks`，README 示例默认就是 `human` + `persona`（`README.md`，`letta/schemas/agent.py`）。
- API 把 memory 当一等能力暴露：core memory block 的取/列/改/attach/detach，以及 archival memory 的列/写/搜/删都在 `letta/server/rest_api/routers/v1/agents.py`。
- 产品叙事上明确分三层：`recall/core/archival`，这不是文档随便起名，系统 prompt 里真按这三层教育模型（`letta/prompts/system_prompts/memgpt_chat.py`）。
- Core memory 也不只服务 persona/profile；`Block` 是通用 label-based memory surface，可以挂任意命名块（`letta/schemas/block.py`）。

## 3. System层面
- 数据模型上，memory 至少分成三套存储：`Agent.message_ids` 对应当前上下文切片，`Agent.core_memory` 对应 block 关系，`Archive` / `ArchivalPassage` 对应外部长时记忆（`letta/orm/agent.py`，`letta/orm/block.py`，`letta/orm/passage.py`）。
- `recall memory` 技术上就是消息库检索：`conversation_search` 走 `MessageManager.search_messages_async()`，支持 Turbopuffer 或 SQL fallback（`letta/services/message_manager.py`）。
- `archival memory` 是独立 passage store：`archival_memory_search` 走 `AgentManager.search_agent_archival_memory_async()`，支持 embedding/hybrid 检索、tag 过滤、时间过滤（`letta/services/agent_manager.py`）。
- `archival_memory_insert` 走 `PassageManager.insert_passage()`；第一次写入会自动创建默认 archive，并在启用外部向量后端时做 SQL + Turbopuffer 双写（`letta/services/passage_manager.py`，`letta/services/archive_manager.py`）。
- Core block 编辑和 archival 写入之后都会触发 system prompt 重编译，保证 prompt 中可见 memory 与底层存储对齐（`letta/services/tool_executor/core_tool_executor.py`，`letta/services/agent_manager.py`）。
- 可选的 `GitEnabledBlockManager` 把 git repo 当 memory source of truth，Postgres 只是 cache；repo 里的 block 以 markdown + YAML frontmatter 表示（`letta/services/block_manager_git.py`，`letta/services/memory_repo/memfs_client_base.py`，`letta/services/memory_repo/block_markdown.py`）。

## 4. Lifecycle层面
- 创建阶段：`memory_blocks` 会先落成 `Block`，`human/persona` 若没写 description 会补默认描述（`letta/services/agent_manager.py`，`letta/schemas/block.py`）。
- 运行阶段：每个 step 前，`BaseAgent._rebuild_memory_async()` 会从 DB 刷新 block、重新编译 memory string，并只在 memory/prompt 变化时重写 system message（`letta/agents/base_agent.py`）。
- 溢出阶段：上下文超限时会做 message compaction / summary，但完整消息历史仍保留在 message store，所以 recall memory 不依赖“还在上下文里”（`letta/agents/letta_agent.py`，`letta/services/context_window_calculator/context_window_calculator.py`）。
- 分支阶段：conversation 可以复制并覆盖指定 block，形成会话级 isolated memory，而不污染 agent 全局 block（`letta/services/conversation_manager.py`，`letta/orm/conversation.py`）。
- 后台整理阶段：`enable_sleeptime` 会把一部分 memory 管理转给后台 sleeptime agent 周期性处理（`letta/schemas/agent.py`，`letta/groups/sleeptime_multi_agent_v4.py`）。

## 5. Injection层面
- `Memory.compile()` 会把 core memory 渲染成结构化 prompt 片段，例如 `<memory_blocks>`、`<memory_filesystem>`、`<directories>`、`<tool_usage_rules>`（`letta/schemas/memory.py`）。
- `PromptGenerator` 再追加 `<memory_metadata>`，把“上次重编译时间、recall 条数、archival 条数、可用 archival tags”注入进去，并塞到 `{CORE_MEMORY}` 占位符；如果模板没留占位符，就直接 append 到 system prompt 末尾（`letta/prompts/prompt_generator.py`，`letta/services/helpers/agent_manager_helper.py`）。
- 关键点是非对称注入：core memory 的正文始终进 prompt；recall / archival 只把“有多少、怎么用”作为 metadata 进 prompt，具体内容必须靠 tool 检索（`letta/prompts/system_prompts/memgpt_chat.py`，`letta/prompts/prompt_generator.py`）。
- Git 模式下，注入形态会变：只有 `system/` 命名空间的 block 直接进上下文，同时额外渲染一个 memory filesystem 树，提示还有哪些 path-like memory（`letta/schemas/memory.py`）。

## 6. 抽象层面
- Letta 的核心抽象是 `Block` + `Memory`，不是“用户画像 JSON”或“单条 summary”。`Block` 自带 `label/description/value/limit/read_only/metadata`，本质上是可编辑的命名文档（`letta/schemas/block.py`，`letta/schemas/memory.py`）。
- 三层 memory 的职责边界很清楚：core 负责常驻上下文，recall 负责历史回溯，archival 负责长期沉淀；这比“全部丢到一个向量库”更可控。
- Memory 编辑被做成一等 tool 协议，而不是字符串拼接 hack：`core_memory_append/replace`、`memory_replace/insert/apply_patch/rethink` 都有明确语义和 guardrail（`letta/functions/function_sets/base.py`，`letta/services/tool_executor/core_tool_executor.py`）。
- 版本化也被显式建模：一条是 `BlockHistory` checkpoint/undo/redo，一条是 git commit history；说明作者把“记忆可回滚/可审计”当成系统能力，而不是运营工具（`letta/orm/block_history.py`，`letta/services/block_manager.py`，`letta/schemas/memory_repo.py`）。

## 7. 值得借鉴 / 明显局限
- 值得借鉴：把“始终可见的记忆”和“需要显式检索的长期记忆”彻底分层，避免把所有记忆都塞进 prompt。
- 值得借鉴：memory block 是通用抽象，不只适合 persona/human，也适合任务块、文件块、会话隔离块；再配精细编辑工具，工程可操作性很强。
- 值得借鉴：git-backed memory 很有价值，能把 agent memory 外化成可 diff、可审计、可回放的 repo。
- 明显局限：`recall memory` 本质仍是“消息库检索”，不是经过整理的高质量长期知识层；真正的长期沉淀主要还是 `archival memory`。
- 明显局限：archival 并不是完全自动化的 memory lake。默认仍依赖模型主动调用 `archival_memory_insert/search`；而且单次写入前会做 token limit 检查，`insert_passage()` 目前也没有自动 chunking（`letta/server/server.py`，`letta/services/passage_manager.py`）。
- 明显局限：一些关键实现比较脆。比如 system prompt rebuild 仍靠字符串包含判断 memory 是否变化；代码注释自己也承认这部分很 brittle（`letta/services/agent_manager.py`）。
- 明显局限：archival search 的多 archive / 高效 tag 过滤还不成熟，代码里明确写了多个 archive 的向量检索暂不支持，SQL 路径的 tag 过滤也是 Python 后过滤（`letta/services/agent_manager.py`）。
