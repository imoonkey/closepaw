# Tasks.org — 改动摘要

**行数**: 31 → 15（-52%）

## 删除了什么（最 overbudget 的 skill）
- Counting Tasks section 整体：
  - "Maintain a set of unique names in scratchpad using compact format (`unique: A, B, C`)" — scratchpad 格式规定
  - "MUST open EVERY candidate task's detail view" — solver procedure
  - "Do NOT shortcut — verify all candidates even if it takes many turns" — 通用 discipline
- Information Tasks section：
  - "Do NOT answer '0' or 'none' without scrolling the full list" — 通用规则
- CRITICAL 中的 procedural 步骤精简：
  - 原来 4 步操作流程 → 压缩为 fact（completion field 在哪里、长什么样）
- "For all queries: scroll down repeatedly" — 通用 scroll 规则

## 保留了什么
- **CRITICAL**: Completion status hidden — list view 不显示 completion state，只在 detail view 底部 metadata 有 `Completion YYYY-MM-DD HH:MM`
- "Show completed" toggle — 必须先启用才能看到 completed tasks
- Date 相关的 3 个 app fact：
  - Day label 跨周歧义 → 必须看 detail view 的 full-format date
  - Due date = 右侧 standalone label（不是 title 下方的 chip）
  - Detail view 中 bare day name = start date（不是 due date）
- Priority radio button 顺序：None, Low, Medium, High
- Recurring/sub-tasks 会产生同名行
- 不要点 checkbox 做 information task
