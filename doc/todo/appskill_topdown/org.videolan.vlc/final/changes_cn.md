# VLC — 改动摘要

**行数**: 25 → 12（-52%）

## 删除了什么
- Multi-Select section 整体（3 行）— "long-press first, tap additional, use overflow" 是通用 Android 交互
- Adding Files section 的 procedural 步骤压缩
- "Long-press first item → tap additional items → overflow menu → 'Add to playlist'" — 通用 multi-select 流程

## 保留了什么
- **CRITICAL**: Library tabs（Audio/Video），不要用 Browse — multi-select 在 Browse 里不工作
- Audio vs Video playlist 创建路径区别
- Video grouping quirk（"Video group: …, N videos" → 必须 ungroup）
- Search 会 auto-play — 不要用
- Selection order = playlist track order — app 行为 fact
- Multiple playlists: 逐个操作，每次 scroll to top
