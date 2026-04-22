# PageIndex memory review

## 1. 一句话结论
就这个开源仓本身看，PageIndex 不是一个真正的 agent 长期记忆系统，而是一个把单个长文档编译成树状外部记忆索引的工具；跨文档检索、会话记忆、用户偏好持久化主要依赖仓外 API、数据库或调用方自行实现。

## 2. Product层面
- 产品核心是长文档的 reasoning-based RAG，不是用户/会话 memory；`README.md` 明确主打 long document analysis，`tutorials/doc-search/README.md` 也明确写了默认是“single document”检索。
- 这个仓本地真正产出的“记忆产物”是文档树 JSON：`run_pageindex.py` 会把结果写到 `./results/*_structure.json`；样例可见 `tests/results/earthmover_structure.json`、`tests/results/2023-annual-report_structure.json`。
- 多文档场景没有内建 memory 产品层：`tutorials/doc-search/metadata.md`、`semantics.md`、`description.md` 给的是三种外围工作流，分别依赖 SQL 元数据检索、向量检索或 LLM description 路由，再把 `doc_id` 交给 PageIndex retrieval API。
- `README.md` 和 `cookbook/*.ipynb` 指向 Chat Platform / MCP / API，但本地源码树里没有 `PageIndexClient`、`submit_document`、`get_tree`、`chat_completions` 等实现；这说明完整产品化 memory 体验主要不在这个仓里。

## 3. System层面
- 本地 system 的核心 memory object 是层级节点，而不是 embedding chunk。节点字段主要是 `title`、`start_index`、`end_index`，可选加上 `node_id`、`summary`、`text`、`doc_description`，相关逻辑在 `pageindex/page_index.py`、`pageindex/page_index_md.py`、`pageindex/utils.py`。
- PDF 流程是：`get_page_tokens` 做分页文本与 token 统计，然后 `check_toc` / `meta_processor` 走 TOC 检测或直接抽树，再经过 `verify_toc`、`fix_incorrect_toc_with_retries` 做校验修正，最后 `post_processing` 生成树，`process_large_node_recursively` 继续细分过大的节点。
- Markdown 流程更简单：`pageindex/page_index_md.py` 先按标题抽节点，再聚合节点文本、可选 thinning、再构树并补 summary / doc_description。
- 这个 system 的“检索寻址”是结构寻址：先给 LLM 一棵树，让它按标题/摘要/页码范围推理出 `node_id`，而不是做向量相似度命中。这是一个清晰的“文档外部记忆”建模。
- 缺失的子系统也很明显：没有本地 retrieval runtime，没有用户画像存储，没有 session/episodic memory，没有增量更新管道，也没有真正的 memory store，只有 JSON 结果和日志文件。

## 4. Lifecycle层面
- 创建：输入 PDF 或 Markdown，经 `page_index_main` / `md_to_tree` 生成树结构；可选补 `node_id`、`summary`、`doc_description`。
- 校验：`verify_toc` 会抽样或全量核对 section title 是否真出现在对应页，`fix_incorrect_toc_with_retries` 会反复修正错误页码，`validate_and_truncate_physical_indices` 会截断越界页码。
- 持久化：CLI 只把结果写到 `./results`，调试信息由 `JsonLogger` 写到 `./logs`。这更像“离线索引构建产物”，不是运行中可演化的 memory store。
- 使用：`tutorials/tree-search/README.md` 和 `cookbook/pageindex_RAG_simple.ipynb` 的消费方式都是“先树搜索，再取命中节点文本/页码做回答”。
- 更新：没有增量写回；文档内容变了，基本就是整份文档重新 parse。
- 遗忘/淘汰：没有 retention policy、forgetting、dedup、merge、conflict resolution 之类的生命周期设计。

## 5. Injection层面
- 最核心的 injection 是把 `PageIndex_Tree` 直接注入到 tree search prompt，让模型返回 `node_id` 列表；示例在 `tutorials/tree-search/README.md`。
- 第二层 injection 是把外部偏好/专家知识 snippet 拼到同一个 tree search prompt。教程里写得很直接：相关 preference 可以来自数据库、规则库、关键词匹配、semantic similarity 或 LLM relevance search，但这些都不在本仓实现。
- 多文档 injection 也是两阶段：先在外围系统里挑 `doc_id`，再调用 PageIndex retrieval API。`tutorials/doc-search/metadata.md` 走 SQL，`semantics.md` 走向量检索，`description.md` 走 doc description 路由。
- 这个仓没有自己的 prompt packing、context budget、memory ranking、injection policy manager；注入编排基本停留在教程/样例层。

## 6. 抽象层面
- 它最值得关注的抽象不是“memory for agent”，而是“document as hierarchical external memory”。
- memory cell 不是 passage chunk，而是 section node；address 不是 embedding 向量，而是 `title + summary + page range + tree position`。
- `doc_description` 可以看成文档级路由摘要，`summary` 是节点级压缩摘要，`text` / page span 是最终 recall payload，这个分层是合理的。
- 但从 agent memory 视角看，它仍然只是静态语料记忆，不是 semantic memory + episodic memory 的组合系统。仓里没有“何时学到”“谁提供的”“置信度”“新鲜度”“是否过期”等抽象。

## 7. 值得借鉴 / 明显局限
- 值得借鉴：
  - 用显式层级树而不是平铺 chunk 作为 memory index，可解释性更强，页码引用也更自然。
  - 把 memory 压缩做成多层：文档级 `doc_description`、节点级 `summary`、原始级 `text`，适合做路由和逐步展开。
  - 索引构建里加入 `verify_toc` / `fix_incorrect_toc_with_retries` 这种校验闭环，比“一次 LLM 抽取就相信结果”更稳。
  - 偏好注入与索引本体解耦，不需要像 embedding 系统那样为每类偏好重建向量库。
- 明显局限：
  - 没有真实的长期记忆系统；如果按 agent memory 评估，它几乎不覆盖 session memory、user memory、episodic memory。
  - 跨文档 memory、偏好 memory、检索 API 都依赖外部系统或闭源服务，本仓只开源了索引构建的核心一段。
  - `cookbook/*.ipynb` 里依赖的 `PageIndexClient` 和若干 utils 能力不在本地源码中，开源仓与产品/发布包能力边界并不一致。
  - 没有 freshness、mutation、ACL、dedup、冲突解决、版本化等真实 memory system 常见能力。
