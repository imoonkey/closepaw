# Pro Expense — 改动摘要

**行数**: 26 → 6（-77%）

## 删除了什么
- Section headers（Adding Expenses, Reading Data, Category Selection, Viewing All, Comparing, Deleting）— 6 个 section 太多，内容压缩后不需要 header
- 部分重复描述

## 保留了什么（核心 app fact 全部保留）
- 4 field 必填 + field focus 静默覆盖 — silent failure mode
- Source file label 不是数据字段 — 交互陷阱
- Category 横向滚动（5 visible at a time）— hidden scroll
- Home "Recent" 不是完整列表 → Expense Logs
- Same-name expenses 可能不同 → 全字段比较
- 删除方式（swipe / long-press / open）
