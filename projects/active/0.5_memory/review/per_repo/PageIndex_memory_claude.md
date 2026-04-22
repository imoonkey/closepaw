# PageIndex Memory System Analysis

## 1. Product层面

### Memory分类
PageIndex **不是**传统意义上的 memory 系统，而是一个 **vectorless reasoning-based RAG** 框架。它将长文档转换为层次化树结构索引，然后用 LLM 推理进行检索。

唯一的"memory"类型:
- **文档树索引 (PageIndex Tree)**: 类似"智能目录"的层次结构

### 每类结构
树节点 (PageIndex node):
```json
{
  "title": "Section Title",
  "node_id": "0006",
  "start_index": 21,  // 起始页码
  "end_index": 22,     // 结束页码
  "summary": "...",     // 节点摘要
  "nodes": [...]        // 子节点
}
```

## 2. System层面

### 架构
极简设计: PDF → LLM 分析 → 树结构 JSON

核心组件:
- `page_index.py`: 从 PDF 提取页面文本，LLM 调用生成树结构
- `page_index_md.py`: markdown 文件支持 (用 `#` 层级确定节点)
- `utils.py`: OpenAI API 调用封装

### 存储/索引
- **无向量数据库**: 不做 embedding
- **无分块**: 保留文档自然分节
- 输出为纯 JSON 树结构文件
- 索引本身就是 LLM 生成的层次化摘要

### 写入方法
- `python3 run_pageindex.py --pdf_path /path/to/doc.pdf`
- 过程: 检查目录页 (前 N 页) → LLM 提取结构 → 验证标题在页面中的出现 → 递归构建子节点 → 输出 JSON

### 检索方法
- **树搜索 (tree search)**: LLM 从根节点开始，逐层推理选择最相关的子节点，直到定位到具体页面范围
- 非向量相似度匹配，而是 LLM reasoning-based navigation

### 写入时机
- 离线: 文档索引在查询前一次性生成
- 不支持增量更新

## 3. Lifecycle层面

### 淘汰/上限
- 不适用: 静态索引，一次生成
- 可配置参数: `--max-pages-per-node`(默认 10) / `--max-tokens-per-node`(默认 20000)

### 去重/合并
- 不适用

### 时间衰减
- 不适用

## 4. Injection层面

### Token预算
- 通过树结构的层次设计间接控制:
  - 每层只需注入当前层级的节点摘要
  - 不需要将整个文档放入 context
- `max_tokens_per_node` 控制单节点摘要的 token 预算

### 分级加载
- 天然的分级加载: 根节点 → 中间节点 → 叶节点 → 原始页面
- 每次推理只需看当前层级的 title + summary，不需要加载所有内容

### 作用域隔离
- 文档级隔离: 每个 PDF 生成独立的树索引

## 5. Abstraction层面

### 反思/提炼
- 树结构本身就是文档的多级提炼:
  - 根节点: 文档整体描述
  - 中间节点: 章节摘要
  - 叶节点: 最细粒度的节点摘要
- LLM 在构建树时进行信息提炼和摘要

### Working Memory <-> Long-term Memory
- **不涉及**: PageIndex 是纯检索系统，不维护对话状态
- 它是一个单一用途的工具: 长文档 → 树索引 → reasoning-based 检索

**核心价值**: 证明了 vectorless RAG 的可行性。在 FinanceBench 上达到 98.7% 准确率，证明结构化推理在专业文档上显著优于向量相似度匹配。对于 memory 系统设计的启发在于: **层次化组织 + LLM 推理** 可能比 flat embedding search 更适合结构化知识的检索。
