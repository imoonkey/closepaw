# MemOS Memory Review

## 1. 一句话结论

MemOS 有真实的长期记忆系统，主线是基于图数据库的 `TreeTextMemory`，再用 `MemReader + MemScheduler + MemCube` 串起写入、检索、重组、反馈与删除；但 `parametric memory` 基本还是占位，部分“完整生命周期/治理”更多是 roadmap 而不是已闭环实现。

## 2. Product层面

- 产品面上不是只有“聊天前做一次 recall”。`src/memos/api/routers/server_router.py` 已经暴露了 `/product/add`、`/product/search`、`/product/chat`、`/product/get_memory`、`/product/delete_memory`、`/product/feedback`，说明 memory 已经被当成独立产品能力，而不是聊天附属物。
- 记忆是可观察、可管理的，不是黑箱向量库。`src/memos/api/handlers/memory_handler.py` 会把图里的 memory 导出成 tree/graph 结构返回，支持按 `memory_id`、`file_ids` 或 `filter` 删除。
- 多用户/多知识库是产品主设定。`src/memos/api/product_models.py` 里的 `ChatRequest` / `SearchRequest` 支持 `readable_cube_ids`、`writable_cube_ids`；`src/memos/multi_mem_cube/single_cube.py` 和 `src/memos/multi_mem_cube/composite_cube.py` 则把单 cube / 多 cube 读写封装成统一视图。
- 默认不是“只记聊天”。`src/memos/api/config.py` 默认 reader backend 是 `multimodal_struct`；`src/memos/mem_reader/multi_modal_struct.py` 能处理聊天、文档 chunk、tool trajectory、skill memory，产品定位明显是通用 agent memory，而不是单纯 chat memory。
- 但 repo 里同时存在两套 API 面：`src/memos/api/product_api.py -> product_router.py` 和 `src/memos/api/server_api.py -> server_router.py`。这说明产品接口层还在迁移，memory 能力是真实的，但产品面并不完全收敛。

## 3. System层面

- 核心容器是 `GeneralMemCube`（`src/memos/mem_cube/general.py`），它把 memory 明确拆成 `text_mem / act_mem / para_mem / pref_mem` 四条通道，而不是一个统一 store。
- 真正的长期记忆主线是 `TreeTextMemory`（`src/memos/memories/textual/tree.py`）。它内部接了 `MemoryManager`、`Searcher`、graph store、embedder、reranker；默认 product config 也优先走 `tree_text`（`src/memos/api/config.py`）。
- memory type 不是弱 tag，而是 schema 一等公民。`src/memos/memories/textual/item.py` 里显式定义了 `WorkingMemory`、`LongTermMemory`、`UserMemory`、`OuterMemory`、`ToolSchemaMemory`、`ToolTrajectoryMemory`、`RawFileMemory`、`SkillMemory`。
- 检索层不是单一路径。`src/memos/memories/textual/tree_text_memory/retrieve/searcher.py` + `recall.py` 会并行跑 WorkingMemory、LongTerm/UserMemory、可选 fulltext/BM25、可选 internet、可选 tool/skill memory，再统一 rerank。
- 存储层是可插拔的。主长期记忆走 graph DB（`src/memos/graph_dbs/factory.py` 支持 `neo4j`、`neo4j-community`、`nebular`、`polardb`、`postgres`）；偏好记忆 `PreferenceTextMemory` 走独立 vector DB（`src/memos/memories/textual/preference.py`）；简单文本记忆还有 `GeneralTextMemory` 这种 vector-only backend（`src/memos/memories/textual/general.py`）。
- `act_mem` 是真的，有 `KVCacheMemory`（`src/memos/memories/activation/kv.py`）；但 `para_mem` 基本不是真的，`src/memos/memories/parametric/base.py` 和 `lora.py` 都直接写着 TODO / placeholder。

## 4. Lifecycle层面

- 写入主链很清楚：`SingleCubeView._process_text_mem()`（`src/memos/multi_mem_cube/single_cube.py`）先调用 `mem_reader.get_memory()` 抽取 memory，再写入 `text_mem.add()`。
- `MemoryManager.add()`（`src/memos/memories/textual/tree_text_memory/organize/manager.py`）不是只写一份节点，而是会先落 `WorkingMemory`，再按类型写 `LongTermMemory / UserMemory / Tool / RawFile / Skill` 图节点。这意味着 WorkingMemory 是显式存储层，不只是 prompt buffer。
- async 写入链路也是真实存在的。`SingleCubeView` 在 async 模式下提交 `MEM_READ_TASK_LABEL`；`MemReadMessageHandler`（`src/memos/mem_scheduler/task_schedule_modules/handlers/mem_read_handler.py`）再把 fast memory 通过 `fine_transfer_simple_mem()` 细化成最终 memory，并处理 `merged_from` 归档逻辑。
- 合并/更新不是靠原地 `update()`。默认 reader 会在 fine 阶段尝试找相似 memory，并通过 LLM merge，结果写到 `metadata.info["merged_from"]`（`src/memos/mem_reader/multi_modal_struct.py`）；后续 handler 再把旧节点标为 `archived`。这是“追加新版本 + 归档旧版本”的思路。
- query 也会驱动 lifecycle。`QueryMessageHandler -> MemoryUpdateHandler`（`src/memos/mem_scheduler/task_schedule_modules/handlers/query_handler.py`、`memory_update_handler.py`）会根据 query history 检索相关记忆、重排并替换 `WorkingMemory`；`src/memos/mem_scheduler/base_mixins/memory_ops.py` 里还能顺手更新 activation memory。
- 结构重组是一个独立阶段。`GraphStructureReorganizer`（`src/memos/memories/textual/tree_text_memory/organize/reorganizer.py`）会对长期记忆做聚类、生成 summary parent node、补 `PARENT / INFERS / AGGREGATE_TO / FOLLOWS` 边，说明它不满足于“平铺向量检索”。
- 生命周期声明比实现更大。prompt/README 说的是 `Generated -> Activated -> Merged -> Archived -> Frozen`，但代码主路径里实际能看到的主要是 `Working -> Long/User -> merged_from -> archived / deleted`；`Frozen` 基本没有落到真实状态机里。

## 5. Injection层面

- 主注入路径仍然是 prompt injection。`MOSProduct._build_system_prompt()` 和 `_format_mem_block()`（`src/memos/mem_os/product.py`）会把召回 memory 格式化成 `# Memories` 区块，拼到 system prompt 里，再让 chat model 生成答案。
- 注入时带了一层 prompt-level memory governance。`src/memos/templates/mos_prompts.py` 明确要求模型做 Source / Attribution / Relevance / Freshness 四步校验，并强制用 `[i:memId]` 引用记忆。
- 检索注入不是简单 top-k embedding。`Searcher` 会先做 `TaskGoalParser`，再并行召回 WorkingMemory、LongTerm/UserMemory、tool/skill memory，必要时补 internet path，然后再 rerank（`src/memos/memories/textual/tree_text_memory/retrieve/searcher.py`）。
- 还有一条二级注入路径是 activation injection。`MOSCore.chat()` / `MOSProduct` streaming chat 会尝试把 `KVCacheMemory` 当 `past_key_values` 注入模型（`src/memos/mem_os/core.py`、`src/memos/mem_os/product.py`、`src/memos/memories/activation/kv.py`），但这更像加速/短期上下文优化，不是主长期记忆机制。
- 一个明显缺口是：`MemoryReasoner` 类虽然存在（`src/memos/memories/textual/tree_text_memory/retrieve/reasoner.py`），但我没有在 `Searcher.search()` 主路径里看到它被真正调用。也就是说，当前“memory reasoning”更多还是检索 + rerank + prompt 注入，而不是独立 reasoning stage。

## 6. 抽象层面

- 抽象分层是清楚的：`BaseMemory -> BaseTextMemory / BaseActMemory / BaseParaMemory`（`src/memos/memories/base.py` 等）负责 memory backend；`MemoryFactory` 负责实例化；`GeneralMemCube` 负责聚合；`MOSCore / MOSProduct` 负责用户与 cube 编排。
- 真正的“通用数据结构”不是 prompt，而是 `TextualMemoryItem + TreeNodeTextualMemoryMetadata`（`src/memos/memories/textual/item.py`）。sources、history、status、working_binding、file_ids、background、memory_type 都在这层统一。
- extraction / storage / retrieval / scheduling 是分开的：`MemReader` 负责抽取，`TreeTextMemory/MemoryManager` 负责落库，`Searcher` 负责召回，`MemScheduler` 负责异步编排。这种职责拆分比很多“一个类做完全部事情”的 memory repo 成熟。
- 但抽象成熟度不均衡：`tree_text` 是主线、`general_text` 是简化版、`pref_text` 是旁路、`kv_cache` 是半独立短期记忆、`lora` 还是空壳。这不是一个“所有 memory 形态都同等成熟”的系统。

## 7. 值得借鉴 / 明显局限

- 值得借鉴：把 `WorkingMemory / LongTermMemory / UserMemory / Tool / Skill` 做成显式 memory type，并让它们走不同检索与治理路径；这比“统一向量库 + metadata tag”更可控。
- 值得借鉴：`fast write -> async fine extraction -> reorganize` 这条链路很适合真实产品，能兼顾低延迟写入和高质量长期记忆。
- 值得借鉴：图结构不是摆设。`GraphStructureReorganizer` 会主动生成 summary parent、关系边和聚合节点，这让“长期记忆库”更接近可浏览、可治理的知识图，而不是一堆孤立 chunk。
- 值得借鉴：multi-cube 抽象比较实用，`SingleCubeView / CompositeCubeView` 让“个人 memory / 项目 memory / 共享 memory”可以统一读写。
- 明显局限：`parametric memory` 基本未落地，`LoRAMemory` 还是 placeholder；激活记忆也只在少数模型后端上真正能注入，离“多形态 memory OS”还有距离。
- 明显局限：repo 里有 `MemoryHistoryManager`、`PreUpdateRetriever` 这类更强的冲突/重复治理模块，但在我读到的主链（`SingleCubeView -> MemoryManager -> MemReadMessageHandler`）中没有真正接上，说明 memory governance 还没完全收敛。
- 明显局限：主回答仍主要依赖 prompt 注入；`MemoryReasoner` 没接进主检索链，所以“推理型记忆系统”的深度还不算特别强。
- 明显局限：多 cube 写入策略在 `CompositeCubeView` 里目前还是 naive fan-out，代码注释也直接写了“later we can add smarter routing”；这说明 cross-cube write policy 还比较初级。
- 明显局限：接口层双栈并存（`product_api.py` / `server_api.py`，`MOSProduct` / handler-based server），说明系统还在演化中，后续接入时要先判断哪条 memory 主链才是你要对齐的对象。
