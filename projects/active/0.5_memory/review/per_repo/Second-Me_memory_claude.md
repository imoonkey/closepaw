# Second-Me Memory System Analysis

## 1. Product层面

### Memory分类
Second-Me 是一个"AI 分身"系统 — 用用户数据训练个性化小模型。其 memory 体系围绕 **三层知识表示** 展开:
- **L0 (原始感知)**: 文档级别的 insight 和 summary — 理解每个文件讲了什么
- **L1 (结构化知识)**: 从所有 L0 聚合的 biography/profile + 主题聚类 — 理解"你是谁"
- **L2 (模型参数)**: 将 L0+L1 知识蒸馏到 LoRA 微调的小模型参数中 — 让模型"成为你"

另有基础存储层:
- **Memory (文件)**: 用户上传的原始文件 (文本/文档等)
- **Document**: 从 Memory 处理后的结构化文档对象
- **Chunk**: 文档切片 + embedding

### 每类结构
- **Memory** (ORM): id / name / size / type / path / meta_data / document_id / status
- **Document**: raw_content + insight (L0) + summary (L0) + chunks + embeddings
- **L1 Bio**: global_bio (总体描述) + status_bio (状态描述) + about_me + shades (主题聚类)
  - **Shade**: 围绕某个主题聚类的 notes 集合，类似人格的"侧面"
- **L2**: 训练数据集 (QA 对) + LoRA adapter 权重

## 2. System层面

### 架构
- **前端**: Next.js Web UI (lpm_frontend)
- **后端**: Flask API (lpm_kernel)
- **存储**: SQLite (元数据) + ChromaDB (向量检索) + 文件系统 (原始文件 + 模型权重)
- **训练**: PyTorch + Transformers (GPU) 或 MLX (Mac M系列)
- **知识图谱**: 集成 GraphRAG 用于 L1 索引构建

### 存储/索引
- 元数据: SQLite (`memories` / `documents` / `l1_bio` 等表)
- 向量: ChromaDB 持久化 (`documents` + `document_chunks` 两个 collection)
- Embedding: 可配置模型 (OpenAI/本地)，自动检测维度
- GraphRAG: 用于 L1 阶段的实体/关系/社区构建

### 写入方法
- 用户上传文件 → `StorageService.save_file()` → 保存到磁盘 + 创建 Memory 记录 + 创建 Document
- L0 处理: `InsightKernel.analyze()` 生成 insight + `SummaryKernel.analyze()` 生成 summary/keywords
- Embedding: `EmbeddingService` 对文档和 chunks 生成向量存入 ChromaDB
- L1 生成: `generate_l1_from_l0()` — 聚类 → 主题生成 → shade 构建 → biography 生成
- L2 训练: QA 数据生成 → LoRA 微调 (或 DPO 对齐)

### 检索方法
- ChromaDB 向量相似度搜索 (用于 RAG)
- L1 biography 作为 system prompt 直接注入
- 训练后的模型本身包含记忆 (参数化记忆)

### 写入时机
- 用户主动上传文件时
- 用户触发训练流程时 (L0→L1→L2 pipeline)

## 3. Lifecycle层面

### 淘汰/上限
- Memory 有 `status` 字段 (active/deleted)
- ChromaDB collection 维度变更时会自动重建
- 无显式的记忆淘汰策略 — 所有上传内容持久保留

### 去重/合并
- `StorageService.check_file_exists()`: 基于文件名 + 文件大小的简单去重
- L1 生成时的主题聚类本身是一种合并 (多文档 → 少量主题)

### 时间衰减
- 无显式时间衰减
- L1 的 `StatusBiography` 理论上反映当前状态，但更新依赖用户重新触发训练

## 4. Injection层面

### Token预算
- 未见显式 token budget 管理
- L2 模型参数化记忆完全绕过 token 限制

### 分级加载
**L0/L1/L2 天然分级**:
- 日常对话: L2 模型 + L1 biography (system prompt) — 零检索成本
- 需要细节时: RAG 从 ChromaDB 检索 L0 级 chunks
- 这是与其他系统最大的区别: **记忆被烤入模型参数**

### 作用域隔离
- 单用户设计: 整个系统服务一个用户的 "AI 分身"
- 无多用户/多 agent 隔离

## 5. Abstraction层面

### 反思/提炼
- **L0 → L1**: insight + summary → 聚类 → 主题 shade → biography — 这是核心的反思/提炼过程
  - `gen_topics_for_shades()`: 将所有文档 embedding 聚类为主题
  - 为每个主题生成 shade (主题侧面描述)
  - 综合所有 shade 生成 global_bio + status_bio
- **L1 → L2**: biography + QA 数据 → LoRA 微调 — 知识蒸馏到模型参数
- **DPO 对齐**: 可选的偏好对齐训练，进一步让模型行为符合用户风格

### Working Memory <-> Long-term Memory
- **Working Memory**: 对话上下文 + L1 biography (system prompt)
- **Long-term Memory**: L2 模型参数 (永久嵌入) + ChromaDB 向量索引 (可检索)
- 独特设计: 三者完全不同的存储形态
  - 向量存储 = 可检索的外显记忆
  - L1 biography = 浓缩的身份描述
  - L2 模型参数 = 内化的隐式记忆 (类似人类的"直觉"/"习惯")
- **核心创新**: 通过微调将记忆参数化，这是其他所有 memory 系统都不做的。代价是更新慢 (需要重新训练)，优势是推理时零检索延迟
