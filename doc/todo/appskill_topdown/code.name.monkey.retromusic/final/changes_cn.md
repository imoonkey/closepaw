# Retro Music — 改动摘要

**行数**: 27 → 9（-67%）

## 删除了什么（大量 solver overfit）
- **CRITICAL — Playlist Efficiency** 整个 section：
  - "NEVER open song Details to check durations" — solver 优化
  - "Add 8-10 songs WITHOUT visiting" — hardcoded batch size
  - "If total EXCEEDS, remove the last-added" — 调整算法
  - "If duration stops increasing, song already in playlist" — eval 特定 pattern
- "Scroll the Songs list to see ALL available songs" — 通用 scroll 规则

## 保留了什么
- Playlist detail view 显示 total duration — hidden data location
- "Add to playlist" 按钮的陷阱（在 playlist 上点会把 playlist 加到别的 playlist）— interaction pitfall
- Multi-select fallback（不响应 → per-song 3-dot menu）— app 特定 recovery
- 歌曲顺序保持（按 goal 指定顺序添加）— app 行为 fact
- "Provided songs" = app library（Songs tab）— 术语映射
- Navigation tabs
