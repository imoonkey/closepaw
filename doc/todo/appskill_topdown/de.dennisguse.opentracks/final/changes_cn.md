# OpenTracks — 改动摘要

**行数**: 29 → 11（-62%）

## 删除了什么（framework Example A 的原型）
- Counting/Summing section 的 5 步 solver 流程：
  - "FIRST scroll entire list end-to-end" — 通用 scroll
  - "Write all tracks... use compact format: `unchecked: Name1...` / `checked: Name1=type...`" — scratchpad 格式规定
  - "Open Edit for EVERY track. You cannot skip any" — solver procedure
  - "Do NOT call complete_task until every track is marked checked" — solver hedging
  - "Answering with 0 before checking all tracks is almost certainly wrong" — eval 特定 warning
- "names are misleading by design" — 为 procedure 辩护的语句
- Scrolling section — 通用规则（合并到 Track Data section）

## 保留了什么
- **CRITICAL**: Track name 不代表 activity type — hidden data location
  - "Trail Biking" 可能是 "running"
  - 权威来源只在 tap track → 3-dot → Edit → Activity type
  - Exact match（"biking" ≠ "mountain biking"）
- Stats tab 显示 distance/moving time（不需要 Edit）
- Track list 可能超出可见区域
- Relative date label → 需要 map 到 absolute date
