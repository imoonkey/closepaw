# supermemory memory 评审

## 1. 一句话结论

`supermemory` 的 memory 产品定义很完整，核心是“文档 -> 记忆 -> profile -> graph”的托管式长期记忆服务；但这个公开仓库本身主要提供 API 契约、SDK/中间件和可视化壳层，真正的长期记忆后端实现并不在仓库里，不能把它当成可直接复用的 memory engine 源码。

## 2. Product层面

- 它把 memory 明确定义成 AI 的“memory and context layer”，而不是单纯 chat history 或向量检索：包含 memory、user profile、hybrid search、connectors、multimodal extractors。对应 `README.md`、`apps/docs/concepts/how-it-works.mdx`、`apps/docs/concepts/user-profiles.mdx`。
- 产品心智模型很清楚地区分了 `Documents` 和 `Memories`：文档是输入原料，记忆是经过抽取/切分/关联后的知识单元；同一套结构既服务长期记忆，也服务 RAG。对应 `apps/docs/concepts/how-it-works.mdx`、`apps/docs/concepts/memory-vs-rag.mdx`。
- 用户侧不是“查若干条历史”，而是优先给一个持续维护的 `profile.static + profile.dynamic`，把长期稳定事实和近期上下文分开。对应 `apps/docs/concepts/user-profiles.mdx`。
- 作用域设计很产品化：`containerTag` / `project` 是第一等概念，用来隔离用户、项目、工作区或个人/工作上下文。`README.md`、`apps/docs/memory-api/ingesting.mdx`、`packages/ai-sdk/src/tools.ts` 都在围绕这个抽象展开。

## 3. System层面

- 公开仓库里的“系统”本质上是围绕托管 API 的一层适配网络，默认后端都是 `https://api.supermemory.ai`。可直接看到的入口包括 `packages/tools/src/shared/context.ts`、`packages/tools/src/shared/memory-client.ts`、`packages/tools/src/conversations-client.ts`、`apps/mcp/src/client.ts`、`apps/raycast-extension/src/api.ts`、`packages/lib/auth.ts`。
- 数据模型是完整的，但主要体现在 schema/contract，而不是实现：`packages/validation/schemas.ts` 定义了 `Document`、`Chunk`、`MemoryEntry`、`Space`，以及 `version`、`isLatest`、`parentMemoryId`、`rootMemoryId`、`memoryRelations`、`isInference`、`isForgotten`、`isStatic`、`forgetAfter`、embedding 等字段。
- 图谱也是“后端产物 + 前端视图层”的结构。`apps/mcp/src/client.ts` 调 `GET /v3/graph/bounds` 和 `POST /v3/graph/viewport`；`apps/web/components/memory-graph/hooks/use-graph-api.ts` 和 `use-graph-data.ts` 只是拿后端返回的文档、记忆、相似边、版本链来渲染。
- 一个很关键的反证是 `apps/docs/deployment/self-hosting.mdx`：自托管不是“拉源码部署”，而是企业客户拿一份“单独提供的 compiled JavaScript bundle”。这基本说明核心 memory backend 不在这个仓库内。

## 4. Lifecycle层面

- 写入入口是 `client.add()` / `POST /v3/documents`，仓库文档把它定义成“先创建 document，再异步转成 memories”。处理阶段是 `queued -> extracting -> chunking -> embedding -> indexing -> done/failed`。对应 `apps/docs/memory-api/ingesting.mdx`、`apps/docs/memory-api/creation/status.mdx`、`packages/validation/schemas.ts`。
- 更新有两条路：一是 `documents.update()`，二是用同一个 `customId` 再次 `add()` 做幂等 upsert；两者都会重新走处理流水线。对应 `apps/docs/update-delete-memories/overview.mdx`。
- 读取也分层：`search` 取 query 相关片段，`profile` 取 `static/dynamic` 广义上下文，`graph` 取关系视图。`packages/tools/src/shared/memory-client.ts` 直接把 `/v4/profile` 当作“记忆注入”的核心接口。
- 对话级持久化是单独建模的，不只是把整段文本塞进 documents。`packages/tools/src/conversations-client.ts` 提供 `/v4/conversations`，支持结构化 message、图片、tool call；`packages/tools/src/vercel/middleware.ts` 和 `packages/tools/src/mastra/processor.ts` 都会在有 `conversationId/threadId` 时优先走这条路径。
- 删除/遗忘是双轨：文档层有 hard delete（`apps/docs/update-delete-memories/overview.mdx`），记忆层还有 soft forget（`client.memories.forget`，见 `apps/mcp/src/client.ts`、`packages/tools/src/tools-shared.ts`）。另外 schema 里还有 `forgetAfter` / `isForgotten`，说明“时间性遗忘”是系统显式概念。

## 5. Injection层面

- 它不是只做 retrieval API，而是把 memory 注入做成多种接入面。最轻的是 tool calling：`packages/ai-sdk/src/tools.ts` 提供 `searchMemories` 和 `addMemory`；`apps/mcp/src/server.ts` 提供 `memory`、`recall` 和 `context` prompt。
- 更强的是自动 system prompt 注入。`packages/tools/src/shared/memory-client.ts` 先拉 `profile` / `searchResults`，`packages/tools/src/tools-shared.ts` 按 `static > dynamic > searchResults` 去重，`packages/tools/src/shared/prompt-builder.ts` 再格式化为 prompt 文本。
- 注入目标适配了多种 agent runtime：`packages/tools/src/vercel/memory-prompt.ts` / `middleware.ts` 给 Vercel AI SDK 用，`packages/tools/src/mastra/processor.ts` 给 Mastra 用，`apps/mcp/src/server.ts` 的 `context` prompt 则给 MCP client 在会话开始时注入。
- 作用域收敛得很好：无论是 `projectId -> sm_project_*`，还是显式 `containerTag/containerTags`，最终都回到同一个 namespace 抽象。这一点在 `packages/ai-sdk/src/tools.ts`、`packages/tools/src/tools-shared.ts`、`apps/mcp/src/server.ts` 里是一致的。
- 回写也被做成注入链的一部分：`withSupermemory` / `SupermemoryOutputProcessor` 在回复后自动保存对话，形成“先注入上下文，再回写新记忆”的闭环。对应 `packages/tools/src/vercel/index.ts`、`packages/tools/src/mastra/processor.ts`。

## 6. 抽象层面

- 这个仓库最值得看的是抽象，而不是实现：`Document` 是原始输入，`Chunk` 是处理中间层，`MemoryEntry` 是长期知识单元，`Profile` 是给 LLM 的压缩上下文，`Graph` 是关系可视化/遍历视图，`Space` / `containerTag` 是命名空间。
- 记忆语义是“关系优先”，不只是“向量优先”。`updates / extends / derives`、`isLatest`、`parentMemoryId`、`isForgotten` 这些字段都在表达“知识会演化、有版本、有失效”，见 `packages/validation/schemas.ts` 和 `apps/docs/concepts/graph-memory.mdx`。
- 另一个好的抽象是把“广义用户上下文”和“问题相关召回”拆开：`profile.static + profile.dynamic` 负责常驻底座，`searchResults` 负责针对当前 query 的补充。这个分层在 `apps/docs/concepts/user-profiles.mdx` 和 `packages/tools/src/shared/memory-client.ts` 里都很一致。
- 但要注意：这些抽象大多停留在 API contract 层。比如 `packages/validation/api.ts` 里有 `entityContext` 之类高层参数，说明系统允许外部引导抽取；可真正的抽取、打分、冲突消解逻辑在仓库里看不到。

## 7. 值得借鉴 / 明显局限

### 值得借鉴

- `documents != memories`、`profile.static != profile.dynamic`、`updates/extends/derives` 这套心智模型很清楚，适合直接借来定义我们自己的 memory 产品语言。
- `containerTag` 作为统一作用域原语很强：简单、廉价、跨 SDK/MCP/UI 一致，既能隔离用户，也能隔离 repo/project/task。
- 注入面设计值得学：同时提供显式 tool、自动 prompt 注入、会话后回写三条路径，而不是把 memory 只做成一个裸 API。
- `/v4/conversations` 这类“结构化对话写入”路径值得借鉴，比把整段历史压成一条纯文本 document 更像真正的 memory ingestion。
- 图谱可观测性做得好：`graph/bounds + graph/viewport + UI` 让 memory 至少可看、可排查、可解释。

### 明显局限

- 这个公开仓库并没有真正开源长期记忆后端，所以没法从中学习最关键的部分：抽取策略、embedding/rerank、关系生成、冲突处理、遗忘策略、profile 汇总逻辑。
- 因为核心引擎不在仓库里，这个 repo 更像“memory platform 的 SDK/adapter/frontend 单仓”，而不是“memory engine 单仓”。
- 文档和实现存在漂移。`apps/docs/ai-sdk/memory-tools.mdx` 与 `packages/ai-sdk/README.md` 提到 `fetchMemory` / `fetchMemoryTool`，但当前 `packages/ai-sdk/src/tools.ts` 实现里并没有这个工具。
- `packages/tools/src/claude-memory.ts` 也有明显未完成之处：`delete` 只返回成功文本并留了 TODO，没有真正调用删除；`rename` 是先创建新文档，删除旧文档同样只是 TODO。可文档 `apps/docs/integrations/claude-memory.mdx` 却把它描述成完整的文件系统式 delete/rename 映射。
- 所以如果要借鉴，建议只借它的产品抽象、注入架构和可观测性设计；不要假设这个公开仓库已经给出了可复用的 memory engine 实现细节。
