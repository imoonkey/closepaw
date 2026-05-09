# memU Memory Review

## 1. 一句话结论

memU 有真实的长期记忆系统：它把原始资源、原子记忆、主题摘要分层持久化并支持检索/注入；但它更像可嵌入 agent 的记忆中间层，而不是带遗忘、调度、主动执行闭环的完整 memory OS。

## 2. Product层面

- 产品叙事是 “memory as file system” 和 “24/7 proactive memory”（`README.md`、`docs/architecture.md`），本地代码实际交付的是 `MemoryService`、wrapper、tool integration 和 examples，不是一个自带后台调度器的完整主动 agent。
- 用户可感知的核心对象是三层：`Resource` 原始输入、`MemoryItem` 原子记忆、`MemoryCategory` 主题摘要；`examples/example_1_conversation_memory.py` 甚至把 category summary 直接输出成 `examples/output/conversation_example/*.md`，这和它的“像文件系统浏览记忆”定位是对齐的。
- 默认类别是 10 个固定主题（`src/memu/app/settings.py`），如 `personal_info`、`preferences`、`work_life`；但默认抽取类型其实只有 `profile` 和 `event`（`src/memu/prompts/memory_type/__init__.py`），`knowledge/behavior/skill/tool` 虽有模型和 prompt，但不是默认主路径。

## 3. System层面

- 组合根是 `src/memu/app/service.py` 的 `MemoryService`：负责 LLM profile、blob、本地/SQLite/Postgres 存储、workflow runner、interceptor、pipeline manager。
- 数据模型是明确的三级结构加边：`Resource` 保原始资源和 caption，`MemoryItem` 保摘要与 embedding，`MemoryCategory` 保主题 summary，`CategoryItem` 保 item-category 关系（`src/memu/database/models.py`）。
- `memorize` 是标准流水线：`ingest_resource -> preprocess_multimodal -> extract_items -> dedupe_merge -> categorize_items -> persist_index -> build_response`（`src/memu/app/memorize.py`）。
- `retrieve` 有 `rag` 和 `llm` 两条流水线，且都带 query rewrite 和 sufficiency check（`src/memu/app/retrieve.py`）；但 RAG 路径并不是“先 category 再收窄 item/resource 候选集”的严格层级检索，item/resource 仍然对当前 scope 的全量池做向量检索。
- category 检索依赖“当前 summary 再 embedding”：`_rank_categories_by_summary()` 每次查询都对 category summary 重新 embed，而不是直接用 `MemoryCategory.embedding`。好处是 summary 永远是最新语义，坏处是每次查询都有额外 embedding 成本。
- 存储抽象做得比较干净：`build_database()` 支持 `inmemory/sqlite/postgres`，SQLite 和 inmemory 是 brute-force，相对生产化的路径是 Postgres + pgvector（`src/memu/database/factory.py`、`docs/adr/0002-pluggable-storage-and-vector-strategy.md`）。

## 4. Lifecycle层面

- 写入生命周期是完整的：`LocalFS.fetch()` 先把本地或 HTTP 资源落到资源目录，再按 modality 预处理；conversation 可分段，audio/image/video/document 有各自预处理路径（`src/memu/blob/local_fs.py`、`src/memu/app/memorize.py`）。
- 写入后不是只存 chunks，而是会同步创建 resource、item、category relation，并让 LLM 重写 category summary；如果开启 `enable_item_references`，还会把被 summary 引用的 item 写回 `extra.ref_id`（`src/memu/app/memorize.py`、`src/memu/utils/references.py`）。
- 生命周期不只 append：`src/memu/app/crud.py` 提供 `create_memory_item / update_memory_item / delete_memory_item / clear_memory`，并通过 category patch prompt 增量修补 summary。
- 强化式生命周期是可选的，不是默认的：打开 `enable_item_reinforcement` 后，repo 会用 `content_hash + scope` 去重，维护 `reinforcement_count/last_reinforced_at`，检索可切到 salience 排序（`src/memu/database/*/repositories/memory_item_repo.py`、`src/memu/database/inmemory/vector.py`）。
- 但它没有真正的“记忆生老病死”：`_memorize_dedupe_merge()` 还是空实现；没有 save gate、访问频次、衰减、归档、过期删除、后台 consolidation/scheduler。仓库自己在 `docs/HACKATHON_MAD_COMBOS.md` 里把这些都列为缺口。

## 5. Injection层面

- 显式注入面有两种：`src/memu/integrations/langgraph.py` 提供 `save_memory/search_memory` tools；`src/memu/client/openai_wrapper.py` 提供 OpenAI client wrapper 自动 recall。
- OpenAI wrapper 的实现很薄：只取 `retrieve()` 返回的 `items`，把 `summary` 列表塞进第一个 system message 的 `<memu_context>`；category/resource 不注入，也没有结构化 citation、权重或 budget 控制。
- wrapper 只取“最后一条 user query”，不会把完整对话历史传给 `retrieve()`，所以 `retrieve()` 里本来支持的 query rewrite/context 在这个接入面基本被削弱了。
- wrapper 暴露了 `ranking/top_k/agent_id/session_id`，但当前实现里 `ranking/top_k` 没真正传入检索；而默认 `DefaultUserModel` 只有 `user_id`（`src/memu/app/settings.py`），如果直接传 `agent_id/session_id`，`retrieve(where=...)` 会校验失败，而 wrapper 又会静默吞异常，结果就是“看起来支持，实际上可能完全没注入”。

## 6. 抽象层面

- 最值得借鉴的是它的三层抽象：`Resource -> MemoryItem -> MemoryCategory`。这比“只有向量库 + chunk”更适合做人设、偏好、关系、经历等长期记忆。
- 第二个强点是 pipeline 抽象：`WorkflowStep` 显式声明 requires/produces/capabilities，`MemoryService` 可以 runtime `config/insert/replace/remove` step（`src/memu/workflow/*`、`src/memu/app/service.py`），调 prompt、加 rerank、接 observability 都比较顺。
- 第三个强点是 scope-first：`UserConfig.model` 会 merge 到所有持久化记录，scope 字段因此成为一等字段，而不是外挂 metadata（`src/memu/database/models.py`、`docs/adr/0003-user-scope-in-data-model.md`）。
- 但这个抽象落地并不完全闭合：`Context.categories_ready/category_ids/category_name_to_id` 是 service 级缓存（`src/memu/app/service.py`、`src/memu/app/memorize.py`），不是按 user scope 管理。代码上看，多用户共用一个 `MemoryService` 时，category 初始化可能只为第一个 scope 建立映射，这是明显实现风险。

## 7. 值得借鉴 / 明显局限

- 值得借鉴：把长期记忆拆成“原始资源、原子事实、主题摘要”三层，并让 category summary 成为人类可读、模型可检索的中间索引。
- 值得借鉴：自动抽取和手工 patch 两条路径并存，既能从对话/文档持续学习，也能做显式记忆编辑。
- 值得借鉴：workflow + storage abstraction 很实用，适合做二次开发，不会把 memory 逻辑硬编码在一个大函数里。
- 明显局限：repo 的“24/7 proactive”更多是 README 和 `examples/proactive` 的编排层叙事，核心库本身没有 scheduler、salience gate、forgetting/curation。
- 明显局限：默认抽取面偏窄，只开 `profile/event`；更丰富的 `knowledge/behavior/skill/tool` 需要额外配置才能真正进入主链路。
- 明显局限：reference traceability 是轻量版实现，`ref_id` 只是 6 位短 ID（`src/memu/app/memorize.py`），而且 reference-aware item recall 目前只真正接到了 LLM 路径，RAG 路径里只留了未接通的 helper。
- 明显局限：多租户/多 agent 抽象在 schema 层是对的，但 category cache 和 wrapper 参数契约还不够扎实，这对真正的生产级长期记忆系统是硬伤。
