# OsmAnd — 改动摘要

**行数**: 24 → 12（-50%）

## 删除了什么
- Marker section 的 5 步 procedural 流程压缩为 2 行 fact
- GPX section 的 3 步流程压缩为 2 行 fact
- 去掉了 numbered list 格式（序号本身不是 app 知识）

## 保留了什么
- **CRITICAL**: Address tab for search — 默认搜索是 proximity-based，经常 geocode 到错误位置
  - Address tab 做 structured offline lookup
  - Coordinates search 作为 fallback
  - NEVER use "SHOW ON MAP" from general search
- Marker: map pin → scroll action buttons → "Marker" (flag icon)
- GPX: 只有 "Plan a route" 能保存 GPX（"Directions"/"Navigation" 不行）
- Waypoint 添加通过 Address tab
