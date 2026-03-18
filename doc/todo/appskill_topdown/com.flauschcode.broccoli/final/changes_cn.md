# Broccoli — 改动摘要

**行数**: 29 → 7（-76%）

## 删除了什么（solver procedure）
- "Efficient approach:" 下的 4 步 duplicate detection 流程：
  - "Scan the list. Group recipes by title." — solver strategy
  - "open the FIRST, record all fields to scratchpad" — scratchpad 格式规定
  - "delete immediately while still viewing it. This avoids re-navigation." — turn 优化
- "After each deletion, verify the remaining count decreased" — 通用验证
- Section headers 精简

## 保留了什么
- FAB 添加 + 不要用 Edit 创建新 recipe — interaction pitfall
- 长标题搜索可能失败 → browse list — app quirk
- 只填有直接 mapping 的字段 — 数据输入规则
- Cards 显示 title + description → 不同 description = 不是 duplicate — app data model fact
- ALL fields match 才是 duplicate — 安全规则
- Delete 入口（3-dot menu）
- Category 横向滚动
