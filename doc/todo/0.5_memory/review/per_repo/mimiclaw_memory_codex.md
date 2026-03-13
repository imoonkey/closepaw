# MimicLaw Memory Review

## 1. 一句话结论
MimiClaw 有真实的持久化记忆，但本质是 `SPIFFS` 上的纯文本文件体系（`MEMORY.md`、按日笔记、`tg_*.jsonl`）加每轮 prompt 注入；它不是带检索、归纳、晋升策略的完整 long-term memory system，更像“可编辑本地状态 + 最近会话回放”。

## 2. Product层面
- 产品承诺很明确：`README.md` 把“learns from memory / remembers across reboots”当成核心卖点，并把记忆直接暴露成可读可改文件：`SOUL.md`、`USER.md`、`MEMORY.md`、每日笔记、`tg_12345.jsonl`。
- 这套设计的用户价值是 `local-first + inspectable`：所有记忆都落在 flash 上，用户或开发者可直接查看和编辑，不是黑盒数据库。
- 但产品语义偏“状态文件”而不是“主动成长的记忆系统”。长期记忆会不会更新，主要取决于模型是否遵守提示词去调用文件工具，而不是系统自己做稳定的提炼和写回。

## 3. System层面
- `main/memory/memory_store.c` 负责两类持久记忆：`/spiffs/memory/MEMORY.md` 的整文件读写，以及 `/spiffs/memory/YYYY-MM-DD.md` 的按日追加与最近几天读取。
- `main/memory/session_mgr.c` 负责会话记忆：每个 `chat_id` 一个 `tg_<chat_id>.jsonl`，单行 JSON 保存 `{role, content, ts}`；读取时只回放最近 `20` 条，并丢掉 `ts`。
- `main/agent/context_builder.c` 在每轮构造 system prompt 时，直接拼入 `SOUL.md`、`USER.md`、`MEMORY.md`、最近 3 天 daily notes、skills 摘要。
- `main/tools/tool_registry.c` + `main/tools/tool_files.c` 把 `read_file`、`write_file`、`edit_file`、`list_dir` 暴露给 LLM，所以模型确实可以改记忆；但这不是专用 memory API，而是通用文件操作。
- 数据模型非常轻：只有 Markdown 和 JSONL，没有 embedding、vector store、memory ranking、importance scoring、schema 化 memory item。

## 4. Lifecycle层面
- 初始化很薄：`memory_store_init()` 和 `session_mgr_init()` 基本只打日志，没有目录准备、迁移、压缩、修复或一致性校验。
- 每轮生命周期是固定的：`agent_loop.c` 先加载 session history，再构造 system prompt，再跑工具循环；最后只把用户文本和最终 assistant 文本追加进 session 文件。
- 长期记忆不会被系统自动“提炼”。`memory_write_long_term()` / `memory_append_today()` 存在，但 agent loop 不直接调用；长期记忆写入只能靠 CLI，或靠模型在对话中自己用文件工具修改 `/spiffs/*`。
- 最近事件的自动回看只有 3 天窗口，且没有把旧 session 自动总结进 `MEMORY.md` 的机制；因此“跨天记住”更多依赖 `MEMORY.md` 被维护得好不好。
- 重启后记忆会保留，因为数据在 SPIFFS；但没有版本化、冲突解决、衰减、过期或去重机制。

## 5. Injection层面
- 注入策略是“整块塞进上下文”，不是按需检索。`MEMORY.md` 和 recent notes 在 `context_builder.c` 中直接展开到 system prompt；session history 则作为普通 messages 数组注入。
- 容量是硬限制驱动的：`main/mimi_config.h` 把 `MIMI_CONTEXT_BUF_SIZE` 设为 `16 KB`；`context_builder.c` 读长期记忆和 recent notes 时各自只给了 `4096` 字节 buffer，session history 上限是 `20` 条消息。
- 提示词已经明确要求模型“主动记住用户信息”“写 MEMORY.md”“写今日笔记”，所以写回策略主要是 prompt policy，不是 runtime policy。
- 存在一个实际不一致：`context_builder.c` 告诉模型 daily notes 路径是 `/spiffs/memory/daily/<YYYY-MM-DD>.md`，但 `memory_store.c` 和 `docs/ARCHITECTURE.md` 的真实读写路径是 `/spiffs/memory/<YYYY-MM-DD>.md`。这会导致模型可能写到一个自动读取逻辑根本不会回看的路径。
- 写回语义也比较脆：`write_file` 是整文件覆盖，`edit_file` 只替换第一次出现的字符串；没有 append-only memory tool、结构化 patch、乐观并发或防覆盖保护。

## 6. 抽象层面
- 这套系统其实已经分出 4 层记忆：
  - `SOUL.md` / `USER.md`：身份与用户画像
  - `MEMORY.md`：长期语义记忆
  - `YYYY-MM-DD.md`：近期事件型记忆
  - `sessions/tg_*.jsonl`：当前对话上下文
- 这种分层是合理的，尤其适合资源很小的设备：人格、用户画像、长期事实、近期事件、会话历史没有混成一个大文件。
- 但抽象边界仍然很低层，核心对象不是 `MemoryItem` / `Fact` / `Episode`，而是“某个文件路径里的文本”。LLM 需要自己理解文件名、自己做 diff、自己决定什么时候晋升到长期记忆。
- 另外，`MEMORY.md` 和 daily notes 是全局单份，不是按 chat/user 隔离；只有 session 是 per-chat。若系统面向多用户，多人记忆会互相污染。

## 7. 值得借鉴 / 明显局限
- 值得借鉴：极简、local-first、纯文本可审计，特别适合 ESP32 这类小设备；`memory/` 与 `session/` 分层清楚，维护和调试成本低。
- 值得借鉴：通过通用文件工具让模型“自写记忆”，实现成本很低；`tool_files.c` 也至少做了 `/spiffs` 前缀和 `..` traversal 校验。
- 明显局限：没有真正的检索层、归纳层、晋升层，长期记忆质量高度依赖模型是否听 prompt。
- 明显局限：注入是固定窗口 + 固定 buffer，文件一长就会截断；重要信息没有 ranking，只能靠人工整理或模型自己压缩。
- 明显局限：daily note 路径提示与真实实现不一致，是会直接影响记忆闭环的设计/实现裂缝。
- 明显局限：会话文件只保存最终 assistant 文本，不保存 tool trace 和结构化结果；对后续记忆提炼帮助有限。
