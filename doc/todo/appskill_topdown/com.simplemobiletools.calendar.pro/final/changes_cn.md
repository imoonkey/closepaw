# Simple Calendar Pro — 改动摘要

**行数**: 24 → 12（-50%）

## 删除了什么
- "compute exact date range FIRST, write it to scratchpad (e.g., `range: Oct 16-22`)" — scratchpad 格式规定
- "Do NOT skip days or guess from the month grid" — 通用 discipline
- "Use device date context to map relative labels" — core prompt 已有
- "Prefer editing date/time fields directly over incrementally scrolling" — 通用 preference
- Event Management section 大部分 — 通用验证

## 保留了什么
- **CRITICAL**: Month grid 没有 per-cell accessibility node — 核心 a11y 缺陷
  - 不能 click coordinates 选日期 → tap day-number text
  - < > 箭头换月份
- "Nh" = 24h format（"5h" = 05:00，不是 17:00）— app 术语
- NumberPicker: scroll/swipe, 不能 type() — platform quirk
- Date range query → navigate to EACH day individually — app limitation（grid 不暴露 event 数据）
- 验证日期 + 保存后 reopen 检查
